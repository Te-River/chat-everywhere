package com.bitchat.android.internetp2p

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.bitchat.android.wifiaware.SyncedSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Four-tier internet P2P establishment engine.
 *
 * Tier order (most direct first):
 *  1. IPv6 direct TCP  - both peers have a global-scope IPv6 address; no NAT
 *     traversal needed at all (the biggest win on China's mobile networks).
 *  2. UDP hole punch    - classic RFC 5780-informed punch over a stable UDP
 *     endpoint; viable for endpoint-independent NAT mappings.
 *  3. TCP simultaneous-open - connect + accept race against the peer's mapped
 *     address; the last resort before falling back.
 *  4. Nostr fallback    - returning null lets the router send over Nostr.
 *
 * Decentralization: STUN is only a reflector, never a relay; no TURN exists
 * anywhere in this design. The engine's UDP socket is the SAME socket used
 * for the STUN probe (RFC 5780 requires probe and data path to share one
 * local endpoint so the mapping stays stable).
 */
class NatTraversalEngine(
    private val scope: CoroutineScope,
    private val stunServers: List<InetSocketAddress> = P2pConfig.resolveStunServers(),
    private val stunTimeoutMs: Long = P2pConfig.DEFAULT_STUN_TIMEOUT_MS,
    private val socketFactory: (() -> DatagramSocket)? = null
) {

    companion object {
        private const val TAG = "NatTraversalEngine"
        private val PUNCH_MAGIC = byteArrayOf('B'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte())
        private val random = SecureRandom()
    }

    /**
     * Local identity/endpoints for this engine instance.
     */
    data class LocalProfile(
        val nonce: String,
        val mappedAddress: InetSocketAddress?,
        val ipv6Global: InetSocketAddress?,
        val natType: NatTypeDetector.NatType,
        val tcpPort: Int
    )

    @Volatile private var profile: LocalProfile? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var tcpListener: ServerSocket? = null

    private val punchMutex = Mutex()

    /**
     * Probes local NAT behavior and gathers the local candidate endpoints.
     * Idempotent; subsequent calls reuse the same socket so the mapping and
     * the candidate stay valid for multiple peers.
     */
    suspend fun probeAndGather(): LocalProfile = punchMutex.withLock {
        profile?.let { return it }

        val socket = socketFactory?.invoke() ?: DatagramSocket()
        socket.soTimeout = stunTimeoutMs.toInt()
        udpSocket = socket

        val localEndpoint = socket.localSocketAddress as? InetSocketAddress
        val stun = StunClient(socket, stunTimeoutMs)
        val detector = NatTypeDetector.forSocket(stun, localEndpoint, stunServers)
        val natProbe = detector.detect()

        val ipv6 = findGlobalIpv6()
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            val wildcard = InetAddress.getByName("0.0.0.0")
            listener.bind(InetSocketAddress(wildcard, 0))
        } catch (e: Exception) {
            Log.e(TAG, "TCP listener bind failed: ${e.message}")
        }

        val p = LocalProfile(
            nonce = randomNonce(),
            mappedAddress = natProbe.mappedAddress,
            ipv6Global = ipv6,
            natType = natProbe.natType,
            tcpPort = try { listener.localPort } catch (_: Exception) { 0 }
        )
        profile = p
        tcpListener = listener
        Log.i(
            TAG,
            "NAT probe: type=${p.natType} mapped=${p.mappedAddress} ipv6=${p.ipv6Global} tcpPort=${p.tcpPort}"
        )
        p
    }

    /**
     * Establishes a direct link to [peer], running the tiers in order.
     * Returns null when every tier failed; the caller then falls back to Nostr.
     */
    suspend fun establish(peer: PunchCandidate, onFrame: (ByteArray) -> Unit): P2pLink? {
        val local = probeAndGather()
        val socket = udpSocket ?: return null
        val peerIpv6 = peer.ipv6Global
        val peerMapped = peer.mappedAddress

        // Tier 1: IPv6 direct TCP (both peers have global IPv6).
        if (peerIpv6 != null && local.ipv6Global != null) {
            Log.i(TAG, "Tier 1: IPv6 direct to ${peerIpv6.address.hostAddress}")
            val link = tryTcpConnect(
                target = InetSocketAddress(peerIpv6.address, peer.tcpPort),
                peerNonce = peer.nonce,
                onFrame = onFrame,
                accept = true
            )
            if (link != null) return link
        }

        // Tier 2: UDP hole punch.
        if (local.natType.udpPunchViable && peerMapped != null) {
            Log.i(TAG, "Tier 2: UDP punch to $peerMapped")
            val link = tryUdpPunch(socket, peer, onFrame)
            if (link != null) return link
        }

        // Tier 3: TCP simultaneous-open against the peer's mapped endpoint.
        if (peerMapped != null) {
            Log.i(TAG, "Tier 3: TCP simultaneous-open to $peerMapped")
            val link = tryTcpConnect(
                target = peerMapped,
                peerNonce = peer.nonce,
                onFrame = onFrame,
                accept = true
            )
            if (link != null) return link
        }

        Log.w(TAG, "All direct tiers failed for ${peer.nonce.take(8)}; caller falls back to Nostr")
        return null
    }

    /** Releases all local resources. */
    fun close() {
        try { udpSocket?.close() } catch (_: Exception) { }
        try { tcpListener?.close() } catch (_: Exception) { }
        udpSocket = null
        tcpListener = null
        profile = null
    }

    // ------------------------------------------------------------------
    // Tier 2: UDP hole punch
    // ------------------------------------------------------------------

    private suspend fun tryUdpPunch(
        socket: DatagramSocket,
        peer: PunchCandidate,
        onFrame: (ByteArray) -> Unit
    ): UdpLink? {
        val local = profile ?: return null
        val target = peer.mappedAddress ?: return null
        val handshake = PUNCH_MAGIC + local.nonce.toByteArray(Charsets.UTF_8)
        val peerNonceBytes = peer.nonce.toByteArray(Charsets.UTF_8)

        val established = CompletableDeferred<InetSocketAddress?>()
        val deadline = System.currentTimeMillis() + P2pConfig.PUNCH_TOTAL_TIMEOUT_MS

        // Sender: keep poking the peer's mapped endpoint.
        val sender = scope.launch(Dispatchers.IO) {
            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    socket.send(DatagramPacket(handshake, handshake.size, target))
                } catch (_: Exception) { }
                delay(P2pConfig.PUNCH_PROBE_INTERVAL_MS)
            }
        }

        // Receiver: watch for the peer's handshake (our nonce echoed back or
        // the peer's own nonce - either proves a bidirectional path).
        val receiver = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.soTimeout = 500
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val data = buf.copyOf(packet.length)
                if (isHandshake(data, peerNonceBytes)) {
                    val source = InetSocketAddress(packet.address, packet.port)
                    // Echo our own handshake back so the peer confirms too.
                    try {
                        socket.send(DatagramPacket(handshake, handshake.size, source))
                    } catch (_: Exception) { }
                    established.complete(source)
                    return@launch
                }
            }
            if (!established.isCompleted) established.complete(null)
        }

        val peerEndpoint = established.await()
        sender.cancelAndJoin()
        receiver.cancelAndJoin()
        if (peerEndpoint == null) return null

        Log.i(TAG, "UDP punch succeeded via $peerEndpoint")
        return UdpLink(socket, peerEndpoint, onFrame, scope)
    }

    // ------------------------------------------------------------------
    // Tier 1 / 3: TCP connect + accept race (covers IPv6 direct and
    // simultaneous-open with one code path).
    //
    // Security: a raw TCP connection alone is NOT trusted. After the socket
    // is established (via connect or accept) both sides exchange a
    // [BP2P][nonce] handshake frame; the nonce was delivered out-of-band
    // over the encrypted Nostr DM, so only the real peer can produce it.
    // Connections that fail the handshake are closed immediately. This
    // prevents random internet hosts that can reach our listener port from
    // establishing usable links (resource-exhaustion / DoS surface).
    // ------------------------------------------------------------------

    private suspend fun tryTcpConnect(
        target: InetSocketAddress,
        peerNonce: String,
        onFrame: (ByteArray) -> Unit,
        accept: Boolean
    ): TcpLink? {
        val local = profile ?: return null
        val listener = tcpListener
        val handshake = PUNCH_MAGIC + local.nonce.toByteArray(Charsets.UTF_8)
        val peerNonceBytes = peerNonce.toByteArray(Charsets.UTF_8)

        val established = CompletableDeferred<TcpLink?>()

        // Accept path: keep accepting until a handshake-validated socket wins.
        val acceptJob: Job? = if (accept && listener != null) {
            scope.launch(Dispatchers.IO) {
                while (isActive && !established.isCompleted) {
                    val accepted = try {
                        listener.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val link = verifyTcpHandshake(accepted, handshake, peerNonceBytes, onFrame)
                    if (link != null) {
                        established.complete(link)
                        return@launch
                    }
                    // Handshake failed: this socket is not our peer. Drop it and
                    // keep listening; do NOT leak the fd.
                    try { accepted.close() } catch (_: Exception) { }
                }
            }
        } else null

        // Connect path: connect, then handshake-validate before use.
        val connectJob = scope.launch(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(target, P2pConfig.TCP_CONNECT_TIMEOUT_MS.toInt())
                val link = verifyTcpHandshake(socket, handshake, peerNonceBytes, onFrame)
                if (link != null) {
                    established.complete(link)
                } else {
                    try { socket.close() } catch (_: Exception) { }
                    // Do NOT complete(null): the accept path may still win when
                    // the peer's own connect arrives at our listener.
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP connect to $target failed: ${e.message}")
                try { socket.close() } catch (_: Exception) { }
                // Same as above: give the accept path a chance.
            }
        }

        val result = withTimeoutOrNull(
            P2pConfig.TCP_CONNECT_TIMEOUT_MS + P2pConfig.ACCEPT_WAIT_MS
        ) {
            established.await()
        }
        acceptJob?.cancel()
        connectJob.cancel()
        if (result != null) {
            Log.i(TAG, "TCP established with $target")
        }
        return result
    }

    /**
     * Validates a freshly established TCP socket with the [BP2P][nonce]
     * handshake. Writes our handshake frame, reads one frame, and requires it
     * to carry the peer's nonce. Returns a ready [TcpLink] on success or null
     * (with the socket closed) on failure.
     */
    private fun verifyTcpHandshake(
        socket: Socket,
        handshake: ByteArray,
        peerNonceBytes: ByteArray,
        onFrame: (ByteArray) -> Unit
    ): TcpLink? {
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = P2pConfig.TCP_HANDSHAKE_TIMEOUT_MS.toInt()
            val synced = SyncedSocket(socket, P2pConfig.TCP_HANDSHAKE_TIMEOUT_MS.toInt())
            synced.write(handshake)
            val frame = synced.read()
            if (frame == null || !isHandshake(frame, peerNonceBytes)) {
                Log.w(TAG, "TCP handshake validation failed")
                try { socket.close() } catch (_: Exception) { }
                null
            } else {
                // Handshake done; restore the normal data-phase read timeout.
                try { socket.soTimeout = SyncedSocket.DEFAULT_READ_TIMEOUT_MS.toInt() } catch (_: Exception) { }
                TcpLink(synced, onFrame, scope)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP handshake error: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
            null
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isHandshake(data: ByteArray, peerNonceBytes: ByteArray): Boolean {
        if (data.size != PUNCH_MAGIC.size + peerNonceBytes.size) return false
        for (i in PUNCH_MAGIC.indices) {
            if (data[i] != PUNCH_MAGIC[i]) return false
        }
        for (i in peerNonceBytes.indices) {
            if (data[PUNCH_MAGIC.size + i] != peerNonceBytes[i]) return false
        }
        return true
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds a global-scope IPv6 address on any up, non-loopback interface.
     * Returns null when the device has no usable global IPv6 (e.g., v4-only
     * CGNAT with no v6 prefix).
     */
    private fun findGlobalIpv6(): InetSocketAddress? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet6Address) {
                        val v6 = addr as Inet6Address
                        if (v6.isLinkLocalAddress || v6.isLoopbackAddress || v6.isSiteLocalAddress) {
                            continue
                        }
                        if (v6.isIPv4CompatibleAddress) continue
                        if (v6.address[0].toInt() and 0xFF == 0xFE) continue // link-local FE80::/10 family
                        return InetSocketAddress(v6, 0)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
