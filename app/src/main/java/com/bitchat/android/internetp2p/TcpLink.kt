package com.bitchat.android.internetp2p

import android.util.Log
import com.bitchat.android.wifiaware.SyncedSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A TCP link carrying the mesh frame format. Reuses [SyncedSocket] so the
 * framing ([4-byte length][payload]) is identical to the Wi-Fi Aware
 * transport and to [UdpLink]; the transport layer is oblivious to the medium.
 *
 * A keepalive job emits an empty frame periodically to keep the connection
 * and any NAT mapping alive; [SyncedSocket] tolerates empty frames.
 */
class TcpLink(
    private val syncedSocket: SyncedSocket,
    private val onFrame: (ByteArray) -> Unit,
    private val scope: CoroutineScope
) : P2pLink {

    companion object {
        private const val TAG = "TcpLink"
    }

    @Volatile private var closed = false
    private var keepaliveJob: Job? = null
    private var readJob: Job? = null

    init {
        readJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed) {
                val frame = try {
                    syncedSocket.read()
                } catch (e: Exception) {
                    Log.e(TAG, "TCP read failed: ${e.message}")
                    null
                } ?: run {
                    Log.w(TAG, "TCP link closed by peer")
                    close()
                    return@launch
                }
                if (frame.isEmpty()) continue // keepalive frame
                onFrame(frame)
            }
        }
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(P2pConfig.PUNCH_KEEPALIVE_MS)
                if (closed) break
                try { syncedSocket.write(ByteArray(0)) } catch (_: Exception) { }
            }
        }
    }

    override fun send(payload: ByteArray): Boolean {
        if (closed) return false
        return try {
            syncedSocket.write(payload)
            true
        } catch (e: Exception) {
            Log.w(TAG, "TCP send failed: ${e.message}")
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        keepaliveJob?.cancel()
        readJob?.cancel()
        try { syncedSocket.close() } catch (_: Exception) { }
    }

    override val isClosed: Boolean
        get() = closed || syncedSocket.isClosed()

    override val endpointDescription: String?
        get() = remoteAddress

    val remoteAddress: String?
        get() = try { syncedSocket.inetAddress.hostAddress } catch (_: Exception) { null }
}
