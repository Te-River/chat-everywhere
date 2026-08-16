package com.bitchat.android.internetp2p

import android.util.Log
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Internet P2P transport: a [MeshTransport] whose "radio" is the direct
 * links established by [NatTraversalEngine] (IPv6 direct / UDP punch / TCP
 * simultaneous-open). Carries the standard BitchatPacket binary in the mesh
 * frame format ([4-byte length][payload]) so the protocol layer is
 * identical to BLE / Wi-Fi Aware and remains iOS compatible.
 *
 * Inbound frames are handed to [onInboundPacket], which the owner wires to
 * the shared MeshCore's `processIncoming` (see the holder wiring).
 */
class InternetMeshTransport(
    private val scope: CoroutineScope,
    private val stunServers: List<InetSocketAddress> = P2pConfig.resolveStunServers(),
    private val socketFactory: (() -> DatagramSocket)? = null,
    private val onInboundPacket: (
        packet: BitchatPacket,
        peerID: String?,
        relayAddress: String?,
        ingressLinkID: String?
    ) -> Unit
) : MeshTransport, TransportBridgeService.TransportLayer {

    companion object {
        private const val TAG = "InternetMeshTransport"
        private const val LINK_MONITOR_INTERVAL_MS = 10_000L
    }

    override val id: String = "INTERNET"

    private val engine = NatTraversalEngine(scope, stunServers, socketFactory = socketFactory)
    private val links = ConcurrentHashMap<String, P2pLink>()       // noiseKeyHex -> link
    private val linkToPeer = ConcurrentHashMap<P2pLink, String>()  // link -> noiseKeyHex
    private val ingressIds = ConcurrentHashMap<P2pLink, String>()  // link -> local ingress id
    private val pending = ConcurrentHashMap.newKeySet<String>()    // peerIDs currently connecting
    // mesh peer ID (16 hex, from packet.senderID) -> link key (noiseKeyHex).
    // The bridge addresses peers by their 16-hex mesh peer ID; the signaling
    // channel keys links by the stable noiseKeyHex, so both spellings must
    // resolve to the same link.
    private val peerAliases = ConcurrentHashMap<String, String>()
    private var monitorJob: Job? = null
    private var inboundJob: Job? = null

    /**
     * Starts the link monitor. Call once when the transport is brought up.
     */
    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(LINK_MONITOR_INTERVAL_MS)
                dropClosedLinks()
            }
        }
    }

    /**
     * Starts listening for inbound direct links (the QR / share-link flow).
     *
     * The importer punches toward the generator's mapped endpoint, but the
     * generator never learns the importer's candidate - so it must simply
     * wait for an inbound handshake and reply. This loop runs until the
     * transport is closed; each established inbound link is registered under
     * the peer nonce learned from the handshake (the mesh layer authenticates
     * the real identity via the Noise handshake over the link).
     */
    fun listenForInboundLinks() {
        if (inboundJob?.isActive == true) return
        inboundJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val inbound = engine.listenForInbound(onFrame = { frame ->
                        // Frames arrive after registration; resolve the link key
                        // from the link map to keep handleFrame's contract.
                        val key = links.entries.firstOrNull { it.value.endpointDescription == inboundLinkDescription }?.key
                            ?: return@listenForInbound
                        handleFrame(key, frame)
                    })
                    if (inbound == null) continue
                    val peerNonce = inbound.peerNonce.ifBlank { "inbound" }
                    val key = "inbound:$peerNonce"
                    links[key] = inbound.link
                    linkToPeer[inbound.link] = key
                    ingressIds[inbound.link] = "internet:$key"
                    inboundLinkDescription = inbound.link.endpointDescription
                    Log.i(TAG, "Inbound link registered as $key via ${inbound.link.endpointDescription}")
                } catch (e: Exception) {
                    Log.w(TAG, "Inbound listen failed: ${e.message}")
                }
                delay(500)
            }
        }
    }

    @Volatile
    private var inboundLinkDescription: String? = null

    /**
     * Attempts to establish a direct link to [peerID] using the remote
     * candidate [candidate] obtained through the Nostr signaling channel.
     * [peerID] is the stable noiseKeyHex identity key.
     */
    fun connectToPeer(peerID: String, candidate: PunchCandidate) {
        if (links.containsKey(peerID) || pending.contains(peerID)) return
        pending.add(peerID)
        scope.launch(Dispatchers.IO) {
            try {
                val link = engine.establish(candidate, onFrame = { frame ->
                    handleFrame(peerID, frame)
                })
                if (link != null) {
                    links[peerID] = link
                    linkToPeer[link] = peerID
                    ingressIds[link] = "internet:$peerID"
                    Log.i(TAG, "Link established with ${peerID.take(12)}… via ${link.endpointDescription}")
                } else {
                    Log.w(TAG, "No direct link possible for ${peerID.take(12)}…; falling back to Nostr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectToPeer(${peerID.take(12)}…) failed: ${e.message}")
            } finally {
                pending.remove(peerID)
            }
        }
    }

    /**
     * Gathers this device's local candidate for signaling (probes NAT and
     * binds the TCP listener). Safe to call on demand; the underlying engine
     * reuses its stable socket across calls.
     */
    suspend fun gatherLocalCandidate(): PunchCandidate? {
        return try {
            val profile = engine.probeAndGather()
            PunchCandidate.fromProfile(profile)
        } catch (e: Exception) {
            Log.e(TAG, "gatherLocalCandidate failed: ${e.message}")
            null
        }
    }

    /** Closes and forgets the link for [peerID] (noiseKeyHex or mesh peer ID). */
    fun disconnectPeer(peerID: String) {
        val linkKey = resolveLinkKey(peerID) ?: return
        links.remove(linkKey)?.let { link ->
            linkToPeer.remove(link)
            ingressIds.remove(link)
            link.close()
            Log.i(TAG, "Disconnected from ${linkKey.take(12)}…")
        }
        dropAliasesForLinkKey(linkKey)
    }

    /** True when a direct link to [peerID] (noiseKeyHex or mesh peer ID) is up. */
    fun isPeerConnected(peerID: String): Boolean {
        val linkKey = resolveLinkKey(peerID) ?: return false
        return links[linkKey]?.isClosed != true
    }

    /** Current direct-link peer IDs (noiseKeyHex keys). */
    fun connectedPeerIDs(): Set<String> = links.keys.toSet()

    /** Releases the engine and all links. */
    fun closeAll() {
        monitorJob?.cancel()
        monitorJob = null
        inboundJob?.cancel()
        inboundJob = null
        inboundLinkDescription = null
        links.values.forEach { it.close() }
        links.clear()
        linkToPeer.clear()
        ingressIds.clear()
        pending.clear()
        peerAliases.clear()
        engine.close()
    }

    // ------------------------------------------------------------------
    // MeshTransport
    // ------------------------------------------------------------------

    override fun broadcastPacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        var accepted = false
        for (link in links.values) {
            if (!link.isClosed && link.send(data)) accepted = true
        }
        return accepted
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val linkKey = resolveLinkKey(peerID) ?: return false
        val link = links[linkKey] ?: return false
        if (link.isClosed) {
            dropClosedLinks()
            return false
        }
        val data = packet.toBinaryData() ?: return false
        return link.send(data)
    }

    override fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket
    ): Boolean {
        val link = resolveIngress(ingressLinkID)
            ?: links.values.firstOrNull { it.endpointDescription == relayAddress }
            ?: return false
        val data = packet.toBinaryData() ?: return false
        return link.send(data)
    }

    override fun cancelTransfer(transferId: String): Boolean = false

    override fun getDeviceAddressForPeer(peerID: String): String? =
        resolveLinkKey(peerID)?.let { links[it]?.endpointDescription }

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        links.entries.associate { (peerID, link) ->
            peerID to (link.endpointDescription ?: "unknown")
        }

    override fun getTransportDebugInfo(): String {
        val summary = links.entries.joinToString("; ") { (peerID, link) ->
            "${peerID.take(12)}…=${link.endpointDescription}"
        }
        return "INTERNET links: ${if (summary.isEmpty()) "none" else summary}"
    }

    // ------------------------------------------------------------------
    // TransportBridgeService.TransportLayer
    // ------------------------------------------------------------------

    override fun send(packet: RoutedPacket) {
        broadcastPacket(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean =
        broadcastPacket(packet)

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        sendPacketToPeer(peerID, packet)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun handleFrame(peerID: String, frame: ByteArray) {
        val packet = BitchatPacket.fromBinaryData(frame) ?: run {
            Log.w(TAG, "Undecodable frame from ${peerID.take(12)}…")
            return
        }
        val senderHex = packet.senderID?.toHexString()?.take(16) ?: return
        // Learn the mesh peer ID (16 hex) for this link so the bridge can
        // address the peer by its mesh ID while the link stays keyed by
        // noiseKeyHex.
        if (senderHex != peerID) {
            peerAliases[senderHex] = peerID
        }
        val ingress = ingressIds[links[peerID]] ?: "internet:$peerID"
        onInboundPacket(packet, senderHex, peerID, ingress)
    }

    private fun dropClosedLinks() {
        val closedLinks = links.entries.filter { it.value.isClosed }
        for ((peerID, link) in closedLinks) {
            links.remove(peerID)
            linkToPeer.remove(link)
            ingressIds.remove(link)
            dropAliasesForLinkKey(peerID)
            Log.i(TAG, "Dropped closed link for ${peerID.take(12)}…")
        }
    }

    /** Resolves a peer spelling (noiseKeyHex or mesh peer ID) to the link key. */
    private fun resolveLinkKey(peerID: String): String? {
        if (links.containsKey(peerID)) return peerID
        return peerAliases[peerID]
    }

    private fun dropAliasesForLinkKey(linkKey: String) {
        peerAliases.entries.removeAll { it.value == linkKey }
    }

    private fun resolveIngress(ingressLinkID: String): P2pLink? {
        for ((link, id) in ingressIds) {
            if (id == ingressLinkID) return link
        }
        return null
    }
}
