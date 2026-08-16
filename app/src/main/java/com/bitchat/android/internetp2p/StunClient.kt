package com.bitchat.android.internetp2p

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal RFC 5389 STUN client: sends a binding request over the caller's
 * UDP socket and returns the reflected (mapped) endpoint.
 *
 * The socket is injected so the NAT traversal engine can reuse one bound
 * socket for both probing and the subsequent hole punch, which keeps the
 * NAT mapping stable (RFC 5780 requires the probe and the data path to come
 * from the same local endpoint).
 */
class StunClient(
    private val socket: DatagramSocket,
    private val timeoutMs: Long = P2pConfig.DEFAULT_STUN_TIMEOUT_MS,
    private val retries: Int = P2pConfig.DEFAULT_STUN_RETRIES
) {
    companion object {
        private const val TAG = "StunClient"
        private const val MAX_RESPONSE_BYTES = 2048
    }

    /**
     * Result of a single STUN binding exchange.
     *
     * @param message The decoded response, or null when the server did not
     * answer (timeout / unreachable) within the retry budget.
     * @param server The server that was queried.
     */
    data class ProbeResult(
        val message: StunMessage.DecodedMessage?,
        val server: InetSocketAddress
    ) {
        val reflectedAddress: InetSocketAddress?
            get() = message?.reflectedAddress
        val errorCode: Int?
            get() = message?.errorCode
    }

    /**
     * Performs one binding request to [server], optionally asking the server
     * to reflect from a different IP/port (RFC 5780 CHANGE-REQUEST).
     *
     * Runs on the IO dispatcher; the underlying socket must be bound already.
     * Cancellation propagates as a normal cancellation.
     */
    suspend fun probe(
        server: InetSocketAddress,
        changeRequest: Int? = null,
        transactionId: ByteArray = StunMessage.newTransactionId()
    ): ProbeResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        var attempt = 0
        while (attempt <= retries) {
            val request = StunMessage.encodeBindingRequest(transactionId, changeRequest)
            try {
                socket.send(DatagramPacket(request, request.size, server))
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.soTimeout = timeoutMs.toInt()
                socket.receive(response)

                val decoded = StunMessage.decode(buffer.copyOf(response.length))
                if (decoded == null) {
                    Log.w(TAG, "Undecodable STUN response from $server")
                    // A malformed datagram is not a retryable condition; keep the
                    // budget for genuine timeouts but do not loop forever.
                    return@withContext ProbeResult(null, server)
                }
                if (!decoded.transactionId.contentEquals(transactionId)) {
                    Log.w(TAG, "STUN transaction id mismatch from $server; ignoring")
                    // Another peer's traffic can land on a shared socket; ignore
                    // and keep waiting for our own response (same attempt budget).
                    continue
                }
                return@withContext ProbeResult(decoded, server)
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "STUN probe to $server failed: ${e.message}")
                lastError = e
                break
            }
            attempt++
        }
        Log.w(TAG, "STUN probe to $server failed after ${retries + 1} attempts: $lastError")
        ProbeResult(null, server)
    }
}
