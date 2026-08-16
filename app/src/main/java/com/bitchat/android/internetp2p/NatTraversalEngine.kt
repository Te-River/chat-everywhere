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
    private val socketFactory: (() -> DatagramSocket)? = null,
    private val activeInterfaceNameProvider: (() -> String?)? = null
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
        val tcpPort: Int,
        val lanHost: String? = null
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
        // Bind the TCP listener on the wildcard so it accepts BOTH IPv4 and
        // IPv6 connections. Binding 0.0.0.0 (IPv4-only) silently kills the
        // IPv6 direct tier, which on cellular data (CGNAT, symmetric NAT) is
        // often the ONLY viable path. Prefer dual-stack "::" (accepts IPv4 via
        // v4-mapped) and fall back to 0.0.0.0 only if the platform forbids it.
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            try {
                listener.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            } catch (e: Exception) {
                Log.w(TAG, "Dual-stack bind failed; falling back to IPv4: ${e.message}")
                listener.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP listener bind failed: ${e.message}")
        }

        val p = LocalProfile(
            nonce = randomNonce(),
            mappedAddress = natProbe.mappedAddress,
            ipv6Global = ipv6,
            natType = natProbe.natType,
            tcpPort = try { listener.localPort } catch (_: Exception) { 0 },
            lanHost = findLanIpv4()
        )
        profile = p
        tcpListener = listener
        Log.i(
            TAG,
            "NAT probe: type=${p.natType} mapped=${p.mappedAddress} ipv6=${p.ipv6Global} tcpPort=${p.tcpPort}"
        )
        P2pEventLog.log(
            "本机 NAT 探测：类型=${p.natType} 公网端点=${p.mappedAddress ?: "无(STUN失败)"} " +
                "IPv6=${p.ipv6Global?.address?.hostAddress ?: "无"} 局域网=${p.lanHost ?: "无"} 端口=${p.tcpPort}"
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
        val peerLan = peer.lanEndpoint
        val peerIpv6 = peer.ipv6Global
        val peerMapped = peer.mappedAddress

        // Tier 0: LAN direct (both peers share a private network). Fastest and
        // most reliable - no NAT traversal needed at all. Prefer this over
        // internet paths so same-Wi-Fi peers connect instantly.
        if (peerLan != null && local.lanHost != null) {
            Log.i(TAG, "Tier 0: LAN direct to ${peerLan.address.hostAddress}")
            P2pEventLog.log("Tier 0：尝试局域网直连 ${peerLan.address.hostAddress}")
            val link = tryTcpConnect(
                target = peerLan,
                peerNonce = peer.nonce,
                onFrame = onFrame,
                accept = true
            )
            if (link != null) return link
            P2pEventLog.log("Tier 0 失败：局域网直连未建立，继续尝试互联网路径")
        } else {
            P2pEventLog.log(
                "Tier 0 跳过：无局域网地址（本机=${local.lanHost ?: "无"} 对方=${peerLan?.address?.hostAddress ?: "无"}）"
            )
        }

        // Tier 1: IPv6 direct TCP (both peers have global IPv6).
        if (peerIpv6 != null && local.ipv6Global != null) {
            Log.i(TAG, "Tier 1: IPv6 direct to ${peerIpv6.address.hostAddress}")
            P2pEventLog.log("Tier 1：尝试 IPv6 直连 ${peerIpv6.address.hostAddress}")
            val link = tryTcpConnect(
                target = InetSocketAddress(peerIpv6.address, peer.tcpPort),
                peerNonce = peer.nonce,
                onFrame = onFrame,
                accept = true
            )
            if (link != null) return link
            P2pEventLog.log("Tier 1 失败：IPv6 直连未建立")
        } else {
            P2pEventLog.log(
                "Tier 1 跳过：IPv6 直连不可用（本机=${local.ipv6Global?.address?.hostAddress ?: "无"} " +
                    "对方=${peerIpv6?.address?.hostAddress ?: "无"}）"
            )
        }

        // Tier 2: UDP hole punch.
        if (local.natType.udpPunchViable && peerMapped != null) {
            Log.i(TAG, "Tier 2: UDP punch to $peerMapped")
            P2pEventLog.log("Tier 2：尝试 UDP 打洞 → $peerMapped")
            val link = tryUdpPunch(socket, peer, onFrame)
            if (link != null) return link
            P2pEventLog.log("Tier 2 失败：UDP 打洞未成功")
        } else {
            P2pEventLog.log(
                "Tier 2 跳过：UDP 打洞不可行（NAT=${local.natType} " +
                    "对方公网端点=${peerMapped ?: "无"}）"
            )
        }

        // Tier 3: TCP simultaneous-open against the peer's mapped endpoint.
        if (peerMapped != null) {
            Log.i(TAG, "Tier 3: TCP simultaneous-open to $peerMapped")
            P2pEventLog.log("Tier 3：尝试 TCP 同时打开 → $peerMapped")
            val link = tryTcpConnect(
                target = peerMapped,
                peerNonce = peer.nonce,
                onFrame = onFrame,
                accept = true
            )
            if (link != null) return link
            P2pEventLog.log("Tier 3 失败：TCP 同时打开未成功")
        } else {
            P2pEventLog.log("Tier 3 跳过：对方无公网端点（无法打洞）")
        }

        Log.w(TAG, "All direct tiers failed for ${peer.nonce.take(8)}; caller falls back to Nostr")
        P2pEventLog.log("❌ 全部直连方案失败，将走 Nostr 兜底")
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
    // Inbound listen (QR / share-link flow)
    //
    // The QR/link flow is asymmetric: the importer punches toward the
    // generator's mapped endpoint, but the generator never called establish()
    // (it has no candidate for the importer). Hole punching only works when
    // BOTH sides send, so the generator must also wait for an inbound
    // handshake and reply. We cannot validate the importer's nonce against a
    // known candidate, so we accept any datagram/TCP frame carrying PUNCH_MAGIC
    // and establish the link to its source; the mesh layer still authenticates
    // the peer via the Noise handshake over the link.
    // ------------------------------------------------------------------

    /** A link established by listening, plus the peer's nonce learned from
     *  the inbound handshake (used as a temporary link key). */
    data class InboundLink(val link: P2pLink, val peerNonce: String)

    suspend fun listenForInbound(onFrame: (ByteArray) -> Unit): InboundLink? {
        val local = probeAndGather()
        val socket = udpSocket ?: return null
        val handshake = PUNCH_MAGIC + local.nonce.toByteArray(Charsets.UTF_8)
        val deadline = System.currentTimeMillis() + P2pConfig.ACCEPT_WAIT_MS

        // UDP: watch for any inbound handshake carrying the magic.
        val udpDeferred = CompletableDeferred<InboundLink?>()
        val udpJob = scope.launch(Dispatchers.IO) {
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
                if (startsWithMagic(data)) {
                    val source = InetSocketAddress(packet.address, packet.port)
                    // Echo our own handshake so the importer confirms the path.
                    try {
                        socket.send(DatagramPacket(handshake, handshake.size, source))
                    } catch (_: Exception) { }
                    val peerNonce = extractNonce(data)
                    udpDeferred.complete(InboundLink(UdpLink(socket, source, onFrame, scope), peerNonce))
                    return@launch
                }
            }
            if (!udpDeferred.isCompleted) udpDeferred.complete(null)
        }

        val udpLink = withTimeoutOrNull(P2pConfig.ACCEPT_WAIT_MS) { udpDeferred.await() }
        if (udpLink != null) {
            udpJob.cancel()
            Log.i(TAG, "Inbound UDP link established via ${udpLink.link.endpointDescription}")
            return udpLink
        }
        udpJob.cancel()

        // TCP: accept one connection whose first frame carries the magic.
        val listener = tcpListener
        if (listener == null) return null
        val accepted = try {
            withTimeoutOrNull(P2pConfig.ACCEPT_WAIT_MS) { listener.accept() }
        } catch (_: Exception) {
            null
        } ?: return null
        val synced = SyncedSocket(accepted, P2pConfig.TCP_HANDSHAKE_TIMEOUT_MS.toInt())
        val frame = try { synced.read() } catch (_: Exception) { null }
        if (frame == null || !startsWithMagic(frame)) {
            try { accepted.close() } catch (_: Exception) { }
            return null
        }
        // Echo our own handshake so the initiator (which waits for our nonce
        // after sending its own) can validate and complete the link - the TCP
        // mirror of the UDP branch's reply. Without this the initiator times
        // out and reports failure even though we accepted the connection.
        try {
            synced.write(PUNCH_MAGIC + local.nonce.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { }
        try { accepted.soTimeout = SyncedSocket.DEFAULT_READ_TIMEOUT_MS.toInt() } catch (_: Exception) { }
        val peerNonce = extractNonce(frame)
        val link = TcpLink(synced, onFrame, scope)
        Log.i(TAG, "Inbound TCP link established via ${link.endpointDescription}")
        return InboundLink(link, peerNonce)
    }

    private fun startsWithMagic(data: ByteArray): Boolean {
        if (data.size < PUNCH_MAGIC.size) return false
        for (i in PUNCH_MAGIC.indices) {
            if (data[i] != PUNCH_MAGIC[i]) return false
        }
        return true
    }

    /** Extracts the 16-byte hex nonce following the magic, or "" if absent. */
    private fun extractNonce(data: ByteArray): String {
        val start = PUNCH_MAGIC.size
        return if (data.size > start) {
            String(data, start, data.size - start, Charsets.UTF_8)
        } else {
            ""
        }
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
     * Finds a global-scope IPv6 address. Prefers the CURRENT ACTIVE network
     * interface (Wi-Fi or mobile data) when known, so a device with several
     * up interfaces (e.g. Wi-Fi + cellular) advertises the IPv6 that is
     * actually in use - important because some Wi-Fi networks block IPv6
     * egress, and on cellular CGNAT the IPv6 direct tier may be the only
     * viable path. Falls back to scanning every up interface when the active
     * one has no usable global IPv6.
     */
    private fun findGlobalIpv6(): InetSocketAddress? {
        val preferredName = try { activeInterfaceNameProvider?.invoke() } catch (_: Exception) { null }
        if (preferredName != null) {
            findGlobalIpv6OnInterface(preferredName)?.let { return it }
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                findGlobalIpv6OnInterface(iface.name)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds a private IPv4 (LAN) address on any up, non-loopback interface.
     * Peers on the same local network then prefer a direct LAN link (Tier 0)
     * over internet hole punching. Returns null when no LAN address exists
     * (e.g. mobile-data-only with no Wi-Fi).
     */
    private fun findLanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (isPrivateIpv4(host)) return host
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        return host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("172.") && host.substringAfter("172.", "").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true
    }

    private fun findGlobalIpv6OnInterface(interfaceName: String): InetSocketAddress? {
        return try {
            val iface = NetworkInterface.getByName(interfaceName) ?: return null
            if (!iface.isUp || iface.isLoopback) return null
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
            null
        } catch (e: Exception) {
            null
        }
    }
}
