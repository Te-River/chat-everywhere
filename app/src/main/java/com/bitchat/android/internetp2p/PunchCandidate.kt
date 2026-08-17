package com.bitchat.android.internetp2p

import com.google.gson.Gson
import java.net.InetSocketAddress

/**
 * Everything one peer must know about the other to establish a direct
 * internet link. Exchanged peer-to-peer over the existing encrypted Nostr
 * DM signaling channel; nothing here requires a central server.
 *
 * Endpoints are stored as raw host/port fields (not [InetSocketAddress]) so
 * the candidate serializes cleanly to JSON for the signaling channel.
 *
 * @param nonce Random token included in handshake datagrams so a NAT port
 *   collision with an unrelated host cannot fake a connected link.
 * @param mappedHost STUN-reflected public IP (IPv4), or null when unknown.
 * @param mappedPort STUN-reflected public port.
 * @param ipv6Host Global-scope IPv6 address, when available (direct
 *   connection, no punching required).
 * @param lanHost Private IPv4 (LAN) address, when available - peers on the
 *   same local network prefer a direct LAN link over internet punching.
 * @param tcpPort Local TCP listener port used for simultaneous-open.
 * @param natType Local NAT classification from the RFC 5780 probe.
 * @param punchStartDelayMs When to start punching after link import, so both
 *   sides align their hole-punch timeline (carried in the QR/link).
 * @param punchIntervalMs Hole-punch probe frequency (carried in QR/link).
 * @param punchWindowMs Total hole-punch window (carried in QR/link).
 */
data class PunchCandidate(
    val nonce: String,
    val mappedHost: String?,
    val mappedPort: Int,
    val ipv6Host: String?,
    val lanHost: String?,
    val tcpPort: Int,
    val natType: NatTypeDetector.NatType,
    val hasIpv4Mapped: Boolean = false,
    val punchStartDelayMs: Long = 0L,
    val punchIntervalMs: Long = P2pConfig.PUNCH_PROBE_INTERVAL_MS,
    val punchWindowMs: Long = P2pConfig.PUNCH_TOTAL_TIMEOUT_MS,
    /** Local port of the IPv6-capable UDP socket, used for IPv6 punching. */
    val ipv6UdpPort: Int = 0
) {
    /** STUN-reflected public endpoint used for the UDP hole punch. */
    val mappedAddress: InetSocketAddress?
        get() = mappedHost
            ?.takeIf { it.isNotBlank() }
            ?.let { InetSocketAddress(it, mappedPort) }

    /** Global-scope IPv6 endpoint (port is the peer's TCP listener port). */
    val ipv6Global: InetSocketAddress?
        get() = ipv6Host
            ?.takeIf { it.isNotBlank() }
            ?.let { InetSocketAddress(it, 0) }

    /** Private IPv4 LAN endpoint (port is the peer's TCP listener port). */
    val lanEndpoint: InetSocketAddress?
        get() = lanHost
            ?.takeIf { it.isNotBlank() }
            ?.let { InetSocketAddress(it, tcpPort) }

    companion object {
        private val gson = Gson()

        fun toJson(candidate: PunchCandidate): String = gson.toJson(candidate)

        fun fromJson(json: String): PunchCandidate? = try {
            gson.fromJson(json, PunchCandidate::class.java)
        } catch (e: Exception) {
            null
        }

        /** Builds a candidate from the engine's local profile. */
        fun fromProfile(profile: NatTraversalEngine.LocalProfile): PunchCandidate {
            val mapped = profile.mappedAddress
            val ipv6 = profile.ipv6Global
            return PunchCandidate(
                nonce = profile.nonce,
                mappedHost = mapped?.address?.hostAddress,
                mappedPort = mapped?.port ?: 0,
                ipv6Host = ipv6?.address?.hostAddress,
                lanHost = profile.lanHost,
                tcpPort = profile.tcpPort,
                natType = profile.natType,
                hasIpv4Mapped = mapped != null,
                ipv6UdpPort = profile.ipv6UdpPort
            )
        }
    }
}
