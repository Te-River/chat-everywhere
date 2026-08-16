package com.bitchat.android.internetp2p

import java.net.InetSocketAddress

/**
 * Configuration for the internet P2P channel.
 *
 * Decentralization rule: no server is mandatory. STUN is only a
 * best-effort public-endpoint reflector; if none of the configured servers
 * answers, the engine simply falls back to IPv6 direct / TCP / Nostr.
 * There is deliberately no TURN (relay) option: when hole punching fails the
 * message goes over Nostr relays, which the user already controls.
 */
object P2pConfig {

    /**
     * Default STUN endpoints. Order matters: the first reachable server is
     * used for the RFC 5780 behavior probe. The list is fully overridable
     * by the user (see the settings screen), so deployments can point at
     * their own reflector or a public one that is reachable from their
     * network.
     */
    val DEFAULT_STUN_SERVERS: List<String> = listOf(
        "stun.miwifi.com:3478",
        "stun.cloudflare.com:3478",
        "stun.l.google.com:19302"
    )

    const val DEFAULT_STUN_TIMEOUT_MS: Long = 3_000L
    const val DEFAULT_STUN_RETRIES: Int = 2

    /** UDP hole-punch keepalive interval to hold NAT mappings open. */
    const val PUNCH_KEEPALIVE_MS: Long = 25_000L

    /** Interval between handshake datagrams during a UDP punch attempt. */
    const val PUNCH_PROBE_INTERVAL_MS: Long = 250L

    /** Total budget for one UDP punch attempt. */
    const val PUNCH_TOTAL_TIMEOUT_MS: Long = 8_000L

    /** Connect timeout for direct / simultaneous-open TCP attempts. */
    const val TCP_CONNECT_TIMEOUT_MS: Long = 5_000L

    /** Timeout for the post-connect [BP2P][nonce] handshake frame exchange. */
    const val TCP_HANDSHAKE_TIMEOUT_MS: Long = 3_000L

    /** How long to keep the accept path open after a failed connect. */
    const val ACCEPT_WAIT_MS: Long = 8_000L

    /** Inactivity window after which a P2P link is considered dead. */
    const val LINK_IDLE_TIMEOUT_MS: Long = 120_000L

    /**
     * Parses "host:port" (IPv4 or bracketed IPv6) into an InetSocketAddress,
     * defaulting to the STUN port when the port is omitted. An explicitly
     * invalid port (e.g. "host:notaport") fails closed and returns null so a
     * misconfigured server list cannot silently probe the wrong endpoint.
     */
    fun parseServer(entry: String, defaultPort: Int = 3478): InetSocketAddress? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                // [v6]:port or [v6]
                val close = trimmed.indexOf(']')
                if (close < 0) return null
                val host = trimmed.substring(1, close)
                val rest = trimmed.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> rest.removePrefix(":").toIntOrNull() ?: return null
                    else -> return null
                }
                InetSocketAddress(host, port)
            } else {
                val colon = trimmed.lastIndexOf(':')
                if (colon > 0) {
                    val host = trimmed.substring(0, colon)
                    val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
                    InetSocketAddress(host, port)
                } else {
                    InetSocketAddress(trimmed, defaultPort)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Resolves the configured (or default) STUN server list. */
    fun resolveStunServers(configured: List<String>? = null): List<InetSocketAddress> {
        val entries = configured ?: DEFAULT_STUN_SERVERS
        return entries.mapNotNull { parseServer(it) }
    }
}
