package com.bitchat.android.internetp2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * A UDP link established by hole punching. One datagram carries exactly one
 * frame: [4-byte length][payload], matching the mesh frame format so the
 * transport layer treats both media identically.
 *
 * A keepalive job periodically emits an empty frame (length 0) to hold the
 * NAT mapping open.
 */
class UdpLink(
    private val socket: DatagramSocket,
    private val peerEndpoint: InetSocketAddress,
    private val onFrame: (ByteArray) -> Unit,
    private val scope: CoroutineScope
) : P2pLink {

    companion object {
        private const val TAG = "UdpLink"
        private const val MAX_DATAGRAM_BYTES = 60_000 // safe under IPv4 UDP ceiling
    }

    private val sendLock = Any()
    @Volatile private var closed = false
    private var keepaliveJob: Job? = null
    private var readJob: Job? = null

    init {
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(P2pConfig.PUNCH_KEEPALIVE_MS)
                if (closed) break
                send(ByteArray(0))
            }
        }
    }

    override fun send(payload: ByteArray): Boolean {
        if (closed || payload.size > MAX_DATAGRAM_BYTES) return false
        val frame = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
        return try {
            synchronized(sendLock) {
                socket.send(DatagramPacket(frame, frame.size, peerEndpoint))
            }
            true
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
        try { socket.close() } catch (_: Exception) { }
    }

    override val isClosed: Boolean
        get() = closed

    override val endpointDescription: String?
        get() = "udp:${peerEndpoint.address?.hostAddress}:${peerEndpoint.port}"

    private fun readLoop() {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.soTimeout = P2pConfig.LINK_IDLE_TIMEOUT_MS.toInt()
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "UDP link idle; closing")
                close()
                return
            } catch (e: Exception) {
                if (!closed) Log.e(TAG, "UDP read failed: ${e.message}")
                close()
                return
            }
            val data = buffer.copyOf(packet.length)
            if (data.size < 4) continue
            val length = ByteBuffer.wrap(data).int
            if (length == 0) continue // keepalive frame
            if (4 + length > data.size) {
                Log.w(TAG, "Malformed UDP frame (len=$length, actual=${data.size - 4})")
                continue
            }
            onFrame(data.copyOfRange(4, 4 + length))
        }
    }
}
