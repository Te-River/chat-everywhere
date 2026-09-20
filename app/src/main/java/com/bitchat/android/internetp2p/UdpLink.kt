package com.bitchat.android.internetp2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A UDP link established by hole punching. One datagram carries exactly one
 * frame: [4-byte length][payload], matching the mesh frame format so the
 * transport layer treats both media identically.
 *
 * IMPORTANT — socket ownership:
 * A UDP link does NOT own the underlying [java.net.DatagramSocket]. Hole
 * punching requires the data phase to reuse the exact local endpoint that the
 * peer's NAT pinned during the handshake, so every UDP link on a given local
 * socket SHARES that one socket with the engine (and with every other link and
 * every in-flight punch on it). Therefore:
 *
 *  - This link never calls `socket.close()`. Closing the shared socket would
 *    permanently break NAT traversal for the whole process (the engine caches
 *    its profile/socket, so a later punch would send/receive on a dead socket
 *    and silently fail). Instead [close] only unregisters this link from the
 *    engine's single socket reader via [onClosed].
 *  - This link runs NO receive loop of its own. A [java.net.DatagramSocket]
 *    has a single receive queue, so two concurrent `receive()` callers steal
 *    each other's datagrams (the classic one-way / lossy chat bug). The engine
 *    runs ONE demultiplexing reader per socket and pushes inbound datagrams
 *    here via [onDatagram]; this link decodes them on its own coroutine.
 *
 * A keepalive job periodically emits an empty frame (length 0) to hold the NAT
 * mapping open, and closes the link when nothing has been received for
 * [P2pConfig.LINK_IDLE_TIMEOUT_MS] (the peer's keepalives refresh that timer,
 * so a live peer never idles out while a vanished one does).
 */
class UdpLink(
    private val peerEndpoint: InetSocketAddress,
    private val onFrame: (ByteArray) -> Unit,
    private val scope: CoroutineScope,
    /** Sends one already-framed datagram to [target] over the shared socket. */
    private val sendDatagram: (target: InetSocketAddress, bytes: ByteArray) -> Boolean,
    /** Unregisters this link from the engine's socket reader. Must NOT close
     *  the shared socket. */
    private val onClosed: () -> Unit
) : P2pLink {

    companion object {
        private const val TAG = "UdpLink"
        private const val MAX_DATAGRAM_BYTES = 60_000 // safe under IPv4 UDP ceiling
    }

    @Volatile private var closed = false
    private var keepaliveJob: Job? = null
    private var readJob: Job? = null

    // Inbound datagrams are handed to us by the engine's single socket reader
    // (on the reader thread) and decoded here so the shared reader is never
    // blocked by mesh processing. UNLIMITED keeps trySend non-blocking.
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val lastRxAt = AtomicLong(System.currentTimeMillis())

    init {
        readJob = scope.launch(Dispatchers.IO) { decodeLoop() }
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(P2pConfig.PUNCH_KEEPALIVE_MS)
                if (closed) break
                // Idle-out when the peer has sent nothing (not even keepalives)
                // for the full idle window — mirrors the old receive-timeout
                // behavior without this link owning the socket.
                if (System.currentTimeMillis() - lastRxAt.get() > P2pConfig.LINK_IDLE_TIMEOUT_MS) {
                    Log.w(TAG, "UDP link idle; closing")
                    close()
                    break
                }
                send(ByteArray(0))
            }
        }
    }

    /**
     * Entry point for the engine's single socket reader: one raw datagram
     * ([4-byte length][payload]) received from [peerEndpoint]. Non-blocking.
     */
    fun onDatagram(raw: ByteArray) {
        if (closed) return
        lastRxAt.set(System.currentTimeMillis())
        inbox.trySend(raw)
    }

    private suspend fun decodeLoop() {
        for (raw in inbox) {
            if (closed) break
            if (raw.size < 4) continue
            val length = ByteBuffer.wrap(raw).int
            if (length == 0) continue // keepalive frame
            if (length < 0 || 4 + length > raw.size) {
                Log.w(TAG, "Malformed UDP frame (len=$length, actual=${raw.size - 4})")
                continue
            }
            onFrame(raw.copyOfRange(4, 4 + length))
        }
    }

    override fun send(payload: ByteArray): Boolean {
        if (closed || payload.size > MAX_DATAGRAM_BYTES) return false
        val frame = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
        return try {
            sendDatagram(peerEndpoint, frame)
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed: ${e.message}")
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        keepaliveJob?.cancel()
        readJob?.cancel()
        inbox.close()
        // Unregister from the engine reader; the shared socket stays open for
        // future punches and other links.
        try { onClosed() } catch (_: Exception) { }
    }

    override val isClosed: Boolean
        get() = closed

    override val endpointDescription: String?
        get() = "udp:${peerEndpoint.address?.hostAddress}:${peerEndpoint.port}"
}
