package com.bitchat.android.service

import android.util.Log
import com.bitchat.android.internetp2p.InternetP2pSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuous peer search session, driven by the header search button.
 *
 * Unlike a one-shot probe, the search keeps running until one of:
 *  - the user taps the button again (manual stop), or
 *  - no new peer is discovered for [SEARCH_IDLE_TIMEOUT_MS] (auto stop).
 *
 * Each tick re-triggers a fresh BLE discovery pass (guarded by the BLE
 * transport switch) and a P2P OFFER sweep (guarded by the P2P switch), then
 * compares the discovered-peer counts against the session baseline. Any
 * growth refreshes the "last discovery" timestamp.
 *
 * The UI observes [isSearching] to keep the spinner up and [stopReason] to
 * tell the user whether the session ended manually or by timeout.
 */
object PeerSearchController {

    private const val TAG = "PeerSearchController"

    /** Interval between search ticks. */
    const val SEARCH_TICK_MS: Long = 10_000L

    /** Auto-stop when no new peer appears within this window. */
    const val SEARCH_IDLE_TIMEOUT_MS: Long = 3 * 60_000L

    enum class StopReason { MANUAL, TIMEOUT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _stopReason = MutableStateFlow<StopReason?>(null)
    val stopReason: StateFlow<StopReason?> = _stopReason.asStateFlow()

    @Volatile
    private var searchJob: Job? = null

    @Volatile
    private var sessionBaselinePeers: Int = 0

    @Volatile
    private var lastDiscoveryAtMs: Long = 0L

    /** Whether a search session is currently running. */
    fun isSearchingNow(): Boolean = _isSearching.value

    /** Starts (or restarts) a continuous search session. */
    @Synchronized
    fun startSearch() {
        if (searchJob?.isActive == true) {
            Log.i(TAG, "Search already running; ignoring duplicate start")
            return
        }
        _stopReason.value = null
        _isSearching.value = true
        sessionBaselinePeers = currentDiscoveredPeerCount()
        lastDiscoveryAtMs = System.currentTimeMillis()
        Log.i(TAG, "Search started (baseline peers=$sessionBaselinePeers)")
        searchJob = scope.launch {
            while (isActive) {
                runSearchTick()
                val idle = System.currentTimeMillis() - lastDiscoveryAtMs
                if (idle >= SEARCH_IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "Search auto-stopped: no new peer for ${idle}ms")
                    _isSearching.value = false
                    _stopReason.value = StopReason.TIMEOUT
                    break
                }
                delay(SEARCH_TICK_MS)
            }
            searchJob = null
        }
    }

    /** Stops the current search session manually (user tapped again). */
    @Synchronized
    fun stopSearch() {
        val job = searchJob
        searchJob = null
        job?.cancel()
        if (_isSearching.value) {
            _isSearching.value = false
            _stopReason.value = StopReason.MANUAL
            Log.i(TAG, "Search stopped by user")
        }
    }

    // ------------------------------------------------------------------

    /** One pass: fire both transports, then refresh the discovery timestamp. */
    private fun runSearchTick() {
        // BLE discovery (guarded inside BluetoothMeshService.restartScanning).
        try {
            MeshServiceHolder.meshService?.restartScanning()
        } catch (e: Exception) {
            Log.w(TAG, "BLE tick failed: ${e.message}")
        }
        // P2P OFFER sweep (guarded inside InternetP2pSignaling.searchAll).
        try {
            if (MeshServiceHolder.internetMeshTransport != null) {
                InternetP2pSignaling.searchAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P tick failed: ${e.message}")
        }
        // Any growth over the session baseline counts as a discovery.
        val nowPeers = currentDiscoveredPeerCount()
        if (nowPeers > sessionBaselinePeers) {
            sessionBaselinePeers = nowPeers
            lastDiscoveryAtMs = System.currentTimeMillis()
            Log.i(TAG, "Discovery growth: ${nowPeers} peer(s) seen")
        }
    }

    /** Combined discovered-peer count across BLE mesh and P2P links. */
    private fun currentDiscoveredPeerCount(): Int {
        var count = 0
        try {
            count += MeshServiceHolder.meshService?.getActivePeerCount() ?: 0
        } catch (_: Exception) { }
        try {
            count += MeshServiceHolder.internetMeshTransport?.connectedPeerIDs()?.size ?: 0
        } catch (_: Exception) { }
        return count
    }
}
