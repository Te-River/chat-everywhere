package com.bitchat.android.internetp2p

/**
 * A confirmed direct internet link between two peers. Implementations carry
 * framed payloads (the standard BitchatPacket binary bytes) and keep the
 * NAT mapping / socket alive until closed.
 */
interface P2pLink {
    /**
     * Sends one payload frame. Implementations add their own framing
     * ([4-byte length][payload], matching the mesh SyncedSocket format).
     * Returns true when the write was accepted by the socket.
     */
    fun send(payload: ByteArray): Boolean

    /** Closes the link and stops keepalive/read loops. Idempotent. */
    fun close()

    /** True once the link has been closed (peer disconnect, idle, or error). */
    val isClosed: Boolean

    /** Human-readable remote endpoint for debug/diagnostics. */
    val endpointDescription: String?
}
