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
    private val links = ConcurrentHashMap<String, P2pLink>()       // peerID -> link
    private val linkToPeer = ConcurrentHashMap<P2pLink, String>()  // link -> peerID
    private val ingressIds = ConcurrentHashMap<P2pLink, String>()  // link -> local ingress id
    private val pending = ConcurrentHashMap.newKeySet<String>()    // peerIDs currently connecting
    private var monitorJob: Job? = null

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
     * Attempts to establish a direct link to [peerID] using the remote
     * candidate [candidate] obtained through the Nostr signaling channel.
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
                    Log.i(TAG, "Link established with $peerID via ${link.endpointDescription}")
                } else {
                    Log.w(TAG, "No direct link possible for $peerID; falling back to Nostr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectToPeer($peerID) failed: ${e.message}")
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

    /** Closes and forgets the link for [peerID], if any. */
    fun disconnectPeer(peerID: String) {
        links.remove(peerID)?.let { link ->
            linkToPeer.remove(link)
            ingressIds.remove(link)
            link.close()
            Log.i(TAG, "Disconnected from $peerID")
        }
    }

    /** True when a direct link to [peerID] is currently up. */
    fun isPeerConnected(peerID: String): Boolean =
        links[peerID]?.isClosed != true

    /** Current direct-link peer IDs. */
    fun connectedPeerIDs(): Set<String> = links.keys.toSet()

    /** Releases the engine and all links. */
    fun closeAll() {
        monitorJob?.cancel()
        monitorJob = null
        links.values.forEach { it.close() }
        links.clear()
        linkToPeer.clear()
        ingressIds.clear()
        pending.clear()
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
        val link = links[peerID] ?: return false
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
        links[peerID]?.endpointDescription

    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        links.entries.associate { (peerID, link) ->
            peerID to (link.endpointDescription ?: "unknown")
        }

    override fun getTransportDebugInfo(): String {
        val summary = links.entries.joinToString("; ") { (peerID, link) ->
            "$peerID=${link.endpointDescription}"
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
            Log.w(TAG, "Undecodable frame from $peerID")
            return
        }
        val senderHex = packet.senderID?.toHexString()?.take(16) ?: return
        val ingress = ingressIds[links[peerID]] ?: "internet:$peerID"
        onInboundPacket(packet, senderHex, peerID, ingress)
    }

    private fun dropClosedLinks() {
        val closedLinks = links.entries.filter { it.value.isClosed }
        for ((peerID, link) in closedLinks) {
            links.remove(peerID)
            linkToPeer.remove(link)
            ingressIds.remove(link)
            Log.i(TAG, "Dropped closed link for $peerID")
        }
    }

    private fun resolveIngress(ingressLinkID: String): P2pLink? {
        for ((link, id) in ingressIds) {
            if (id == ingressLinkID) return link
        }
        return null
    }
}
