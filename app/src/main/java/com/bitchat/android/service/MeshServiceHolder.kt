package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.internetp2p.InternetMeshTransport
import com.bitchat.android.internetp2p.InternetP2pSignaling
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    @Volatile
    var sharedGossipSyncManager: GossipSyncManager? = null
        private set

    private val activeGossipOwners = mutableSetOf<String>()

    @Synchronized
    fun setGossipManager(
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket
    ) {
        val previous = sharedGossipSyncManager
        if (previous !== mgr) {
            try { previous?.stop() } catch (_: Exception) { }
        }
        sharedGossipSyncManager = mgr
        mgr.delegate = TransportGossipDelegate(signer)
        if (activeGossipOwners.isNotEmpty()) {
            mgr.start()
        }
    }

    @Synchronized
    fun startSharedGossip(owner: String) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) {
            sharedGossipSyncManager?.start()
        }
    }

    @Synchronized
    fun stopSharedGossip(owner: String) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) {
            sharedGossipSyncManager?.stop()
        }
    }

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            return signer(packet)
        }
    }

    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    @Volatile
    var unifiedMeshService: UnifiedMeshService? = null
        private set

    @Volatile
    var internetMeshTransport: InternetMeshTransport? = null
        private set

    /** App context captured when the holder first creates a transport, used to
     *  lazily bring up the INTERNET transport on user search. */
    @Volatile
    private var appContext: Context? = null

    private val internetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun getOrCreate(context: Context): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) {
                    android.util.Log.d(TAG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    android.util.Log.w(TAG, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (e: Exception) {
                        android.util.Log.w(TAG, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = BluetoothMeshService(context.applicationContext)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = BluetoothMeshService(context.applicationContext)
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = BluetoothMeshService(context.applicationContext)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        return created
    }

    @Synchronized
    fun getUnifiedOrCreate(context: Context): UnifiedMeshService {
        val bluetooth = getOrCreate(context)
        val existing = unifiedMeshService
        if (existing != null) {
            existing.refreshDelegates()
            return existing
        }
        val created = UnifiedMeshService(context.applicationContext, bluetooth)
        unifiedMeshService = created
        android.util.Log.i(TAG, "Created new UnifiedMeshService")
        return created
    }

    /**
     * Creates (or reuses) the internet P2P transport and registers it with
     * the cross-transport bridge. Inbound packets from INTERNET links are
     * injected into the Bluetooth mesh processing pipeline, so they are
     * validated, decrypted, routed and bridged exactly like radio packets.
     */
    @Synchronized
    fun getInternetTransportOrCreate(context: Context): InternetMeshTransport {
        appContext = context.applicationContext
        val existing = internetMeshTransport
        if (existing != null) return existing
        val created = InternetMeshTransport(
            scope = internetScope,
            context = context.applicationContext,
            onInboundPacket = { packet, peerID, relayAddress, ingressLinkID ->
                meshService?.processInboundFromInternet(
                    packet = packet,
                    peerID = peerID,
                    relayAddress = relayAddress,
                    ingressLinkID = ingressLinkID
                )
            }
        )
        internetMeshTransport = created
        try {
            TransportBridgeService.register("INTERNET", created)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to register INTERNET transport: ${e.message}")
        }
        try {
            InternetP2pSignaling.attach(created, context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to attach P2P signaling: ${e.message}")
        }
        android.util.Log.i(TAG, "Created new InternetMeshTransport")
        return created
    }

    /**
     * Outcome of a user-initiated search pass, reported back to the UI so it
     * can surface per-transport results (Toast) while staying faithful to the
     * "skip what is disabled" rule.
     */
    data class SearchResult(
        val bleTriggered: Boolean,
        val p2pEnabled: Boolean,
        val p2pOffers: Int
    ) {
        val p2pTriggered: Boolean get() = p2pOffers > 0
    }

    /**
     * User-facing search entry point: triggers BLE discovery and the internet
     * P2P probe together. Each transport is guarded by its own enable switch:
     * - BLE scanning runs only when the BLE transport is enabled.
     * - P2P probing runs only when the internet P2P feature is enabled.
     * When P2P is enabled but the transport has not been created yet (e.g. the
     * foreground service has not started), it is brought up lazily so a search
     * never reports "P2P off" merely because of missing wiring.
     * Safe to call from the UI on any thread; all heavy work is dispatched
     * onto the transport scopes.
     */
    fun searchNow(): SearchResult {
        // BLE discovery (guarded inside BluetoothMeshService.restartScanning).
        var bleTriggered = false
        try {
            bleTriggered = meshService?.restartScanning() == true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "BLE search failed: ${e.message}")
        }
        // Internet P2P probe (guarded by the P2P feature switch).
        var p2pEnabled = false
        var p2pOffers = 0
        try {
            com.bitchat.android.internetp2p.P2pPreferenceManager.init(appContext ?: return SearchResult(bleTriggered, false, 0))
            if (com.bitchat.android.internetp2p.P2pPreferenceManager.isEnabled()) {
                // Lazily create the transport if the feature is on but the
                // foreground service has not wired it up yet.
                if (internetMeshTransport == null) {
                    appContext?.let { getInternetTransportOrCreate(it) }?.start()
                }
                if (internetMeshTransport != null) {
                    p2pEnabled = true
                    p2pOffers = com.bitchat.android.internetp2p.InternetP2pSignaling.searchAll()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "P2P search failed: ${e.message}")
        }
        return SearchResult(
            bleTriggered = bleTriggered,
            p2pEnabled = p2pEnabled,
            p2pOffers = p2pOffers
        )
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        android.util.Log.d(TAG, "Attaching BluetoothMeshService to holder")
        meshService = service
        unifiedMeshService = null
    }

    @Synchronized
    fun clear() {
        android.util.Log.d(TAG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.clear() } catch (_: Exception) { }
        try { sharedGossipSyncManager?.stop() } catch (_: Exception) { }
        sharedGossipSyncManager = null
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
        try { internetMeshTransport?.closeAll() } catch (_: Exception) { }
        try { TransportBridgeService.unregister("INTERNET") } catch (_: Exception) { }
        try { InternetP2pSignaling.detach() } catch (_: Exception) { }
        internetMeshTransport = null
        internetScope.cancel()
    }
}
