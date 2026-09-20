package com.bitchat.android.internetp2p

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-reader demultiplexer for ONE shared UDP socket.
 *
 * A [DatagramSocket] has a single receive queue, so two concurrent `receive()`
 * callers steal each other's datagrams — the root cause of one-way / lossy
 * hole-punched chat (e.g. the generator's inbound listener and an outbound
 * punch both calling `receive()` on the same socket, or two links racing).
 * This class is the ONLY thing that ever calls `receive()` on the socket. It
 * routes each datagram to either:
 *
 *  1. an in-flight handshake waiter (a punch or the inbound listener that is
 *     waiting for a `BP2P<nonce>` handshake frame), matched by the peer nonce
 *     it expects — or, for the inbound listener which cannot know the importer's
 *     nonce, by "any magic" (lowest priority so it never steals a targeted
 *     punch's handshake); or
 *  2. a registered [UdpLink] keyed by the datagram's source endpoint, which is
 *     carrying data-phase frames (`[4-byte length][payload]`).
 *
 * Sending also funnels through here ([send]) so writes are serialized against
 * the socket; the socket is never closed by a link, only by the engine via
 * [close].
 */
internal class UdpDemux(
    private val socket: DatagramSocket,
    private val scope: CoroutineScope,
    private val tag: String,
    /** Fixed reader poll timeout; the loop treats a timeout as "keep waiting".
     *  Set once here so no other code mutates `soTimeout` under a live reader. */
    private val pollTimeoutMs: Int = READER_POLL_MS
) {

    companion object {
        private const val READER_POLL_MS = 500
        private const val RX_BUFFER_BYTES = 60_000
    }

    /**
     * A one-shot waiter for an inbound `BP2P<nonce>` handshake.
     *
     * @param expectNonceBytesList When non-empty, only a handshake carrying one
     *   of these nonces matches (targeted punch; a list tolerates the
     *   OFFER/ANSWER nonce drift the TCP path already handles). When empty,
     *   ANY magic handshake matches (inbound listener); such waiters are
     *   matched last so they never steal a targeted punch's handshake.
     * @param echoBytes When non-null, the reader replies with these bytes from
     *   the shared socket on match so the peer (which waits for our nonce) can
     *   confirm the path.
     */
    class HandshakeWaiter(
        val expectNonceBytesList: List<ByteArray>,
        val echoBytes: ByteArray?
    ) {
        private val deferred = CompletableDeferred<Match?>()

        data class Match(val source: InetSocketAddress, val raw: ByteArray)

        fun matches(raw: ByteArray): Boolean =
            expectNonceBytesList.any { isHandshake(raw, it) }

        val isWildcard: Boolean get() = expectNonceBytesList.isEmpty()

        internal fun complete(source: InetSocketAddress, raw: ByteArray) {
            deferred.complete(Match(source, raw))
        }

        suspend fun await(timeoutMs: Long): Match? =
            withTimeoutOrNull(timeoutMs) { deferred.await() }

        fun cancel() {
            deferred.complete(null)
        }
    }

    private val links = ConcurrentHashMap<InetSocketAddress, UdpLink>()
    private val waiters = ConcurrentHashMap.newKeySet<HandshakeWaiter>()

    // Serializes socket.send() across every caller (links, punch sender loops,
    // handshake echoes). The old per-link sendLock did this for one link; now
    // that all sends funnel through here, one lock preserves that guarantee so
    // concurrent datagrams cannot interleave on the shared socket.
    private val sendLock = Any()

    @Volatile private var readerStarted = false
    @Volatile private var closed = false
    private var readerJob: Job? = null

    private val readerLock = Any()

    /** Starts the single reader loop if not already running. Idempotent. */
    fun ensureReader() {
        synchronized(readerLock) {
            if (readerStarted || closed) return
            readerStarted = true
        }
        try { socket.soTimeout = pollTimeoutMs } catch (_: Exception) { }
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(RX_BUFFER_BYTES)
        while (currentCoroutineContext().isActive && !closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!closed) Log.w(tag, "UDP reader stopped: ${e.message}")
                break
            }
            val raw = buffer.copyOf(packet.length)
            val source = InetSocketAddress(packet.address, packet.port)
            route(raw, source)
        }
    }

    private fun route(raw: ByteArray, source: InetSocketAddress) {
        if (startsWithMagic(raw)) {
            // Targeted waiters (know the peer nonce) win over the wildcard
            // inbound listener, so a real punch is never starved by it.
            val waiter = waiters.firstOrNull { !it.isWildcard && it.matches(raw) }
                ?: waiters.firstOrNull { it.isWildcard }
            if (waiter != null) {
                // One-shot: remove before completing so a satisfied waiter can
                // never swallow the next handshake (e.g. the persistent
                // inbound-listener wildcard matching a later peer's punch).
                waiters.remove(waiter)
                waiter.echoBytes?.let { send(source, it) }
                waiter.complete(source, raw)
            }
            // No waiter: a late/duplicate handshake. Harmless — drop it.
            return
        }
        links[source]?.onDatagram(raw)
    }

    /** Registers [link] to receive data-phase datagrams from [peer]. */
    fun register(peer: InetSocketAddress, link: UdpLink) {
        ensureReader()
        links[peer] = link
    }

    fun unregister(peer: InetSocketAddress) {
        links.remove(peer)
    }

    /** Registers a handshake waiter. */
    fun attach(w: HandshakeWaiter) {
        ensureReader()
        waiters.add(w)
    }

    fun detach(w: HandshakeWaiter) {
        waiters.remove(w)
    }

    /** Sends one raw datagram to [target]. Thread-safe; never throws. */
    fun send(target: InetSocketAddress, bytes: ByteArray): Boolean {
        if (closed) return false
        return try {
            synchronized(sendLock) {
                socket.send(DatagramPacket(bytes, bytes.size, target))
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "UDP send to $target failed: ${e.message}")
            false
        }
    }

    /** Closes the reader and the underlying socket. Engine-level teardown only. */
    fun close() {
        closed = true
        try { readerJob?.cancel() } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
        links.clear()
        waiters.clear()
    }
}

// ----------------------------------------------------------------------
// Handshake byte helpers shared by the engine and the demux.
// The BP2P magic prefixes every handshake datagram; the nonce (hex string)
// follows it. Data-phase frames never start with this magic.
// ----------------------------------------------------------------------

private val PUNCH_MAGIC = byteArrayOf(
    'B'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte()
)

internal fun startsWithMagic(data: ByteArray): Boolean {
    if (data.size < PUNCH_MAGIC.size) return false
    for (i in PUNCH_MAGIC.indices) {
        if (data[i] != PUNCH_MAGIC[i]) return false
    }
    return true
}

internal fun isHandshake(data: ByteArray, peerNonceBytes: ByteArray): Boolean {
    if (data.size != PUNCH_MAGIC.size + peerNonceBytes.size) return false
    if (!startsWithMagic(data)) return false
    for (i in peerNonceBytes.indices) {
        if (data[PUNCH_MAGIC.size + i] != peerNonceBytes[i]) return false
    }
    return true
}

/** Extracts the (hex) nonce that follows the magic, or "" if absent. */
internal fun extractNonceAfterMagic(data: ByteArray): String {
    val start = PUNCH_MAGIC.size
    return if (data.size > start) {
        String(data, start, data.size - start, Charsets.UTF_8)
    } else {
        ""
    }
}
