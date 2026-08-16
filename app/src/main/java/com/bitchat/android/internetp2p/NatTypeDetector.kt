package com.bitchat.android.internetp2p

import java.net.InetSocketAddress

/**
 * RFC 5780 NAT behavior probe.
 *
 * Determines the local NAT's mapping/filtering behavior using STUN binding
 * exchanges, which decides which hole-punch strategy can work:
 *
 * - UDP_BLOCKED: no STUN response at all; UDP punching is pointless.
 * - OPEN_INTERNET: the reflected endpoint equals the local endpoint; no NAT.
 * - FULL_CONE / RESTRICTED_CONE / PORT_RESTRICTED_CONE: mapping is
 *   endpoint-independent, so a classic UDP punch can succeed.
 * - SYMMETRIC: the mapping depends on the destination; UDP punching cannot
 *   work without a relay (we deliberately have no relay - fall back to
 *   Nostr), though TCP simultaneous-open may still work.
 *
 * The probe function is injected so unit tests can emulate any NAT behavior
 * without real sockets. Production code wires it to a [StunClient] that
 * shares the bound socket used for the actual hole punch (RFC 5780 requires
 * the probe and the data path to share one local endpoint).
 */
class NatTypeDetector(
    private val probe: suspend (server: InetSocketAddress, changeRequest: Int?) -> StunClient.ProbeResult,
    private val localAddress: InetSocketAddress?,
    private val servers: List<InetSocketAddress>
) {

    enum class NatType {
        /** No STUN server answered; UDP is likely filtered/blocked. */
        UDP_BLOCKED,

        /** Reflected address == local address; no NAT in the path. */
        OPEN_INTERNET,

        /** Endpoint-independent mapping and filtering. */
        FULL_CONE,

        /** Endpoint-independent mapping, address-dependent filtering. */
        RESTRICTED_CONE,

        /** Endpoint-independent mapping, address-and-port-dependent filtering. */
        PORT_RESTRICTED_CONE,

        /** Address/port-dependent mapping; classic UDP punching cannot work. */
        SYMMETRIC,

        /** Could not determine (e.g., servers lack RFC 5780 support). */
        UNKNOWN;

        /** True when a classic UDP hole punch has a real chance. */
        val udpPunchViable: Boolean
            get() = this == FULL_CONE ||
                this == RESTRICTED_CONE ||
                this == PORT_RESTRICTED_CONE ||
                this == UNKNOWN

        /** True when the local interface already holds a public address. */
        val directConnectViable: Boolean
            get() = this == OPEN_INTERNET
    }

    data class ProbeResult(
        val natType: NatType,
        val mappedAddress: InetSocketAddress?,
        val localAddress: InetSocketAddress?,
        val servers: List<InetSocketAddress>
    ) {
        /** True when a classic UDP hole punch has a real chance. */
        val udpPunchViable: Boolean
            get() = natType.udpPunchViable

        /** True when the local interface already holds a public address. */
        val directConnectViable: Boolean
            get() = natType.directConnectViable
    }

    companion object {
        /**
         * Wraps a bound [StunClient] so the probe shares the punch socket.
         */
        fun forSocket(
            client: StunClient,
            localAddress: InetSocketAddress?,
            servers: List<InetSocketAddress>
        ): NatTypeDetector {
            return NatTypeDetector(
                probe = { server, changeRequest -> client.probe(server, changeRequest) },
                localAddress = localAddress,
                servers = servers
            )
        }
    }

    /**
     * Runs the RFC 5780 test sequence:
     *
     * Test I:  plain binding to the primary server. No response => UDP blocked.
     * Test II: binding that must come back from a *different* address (a
     *          second configured server, or CHANGE-REQUEST when the primary
     *          supports it). Response received => endpoint-independent
     *          filtering; comparing its mapped address with Test I also
     *          reveals the mapping behavior.
     * Test III:binding that must come back from the *same* address but a
     *          different port (CHANGE-REQUEST port-only). Distinguishes
     *          restricted cone from port-restricted cone.
     */
    suspend fun detect(): ProbeResult {
        val primary = servers.firstOrNull() ?: return ProbeResult(
            NatType.UDP_BLOCKED, null, localAddress, servers
        )

        // Test I
        val testI = probe(primary, null)
        val mappedI = testI.reflectedAddress
        if (mappedI == null) {
            // No response, or response without a mapped address.
            return ProbeResult(NatType.UDP_BLOCKED, null, localAddress, servers)
        }

        if (isSameEndpoint(mappedI, localAddress)) {
            return ProbeResult(NatType.OPEN_INTERNET, mappedI, localAddress, servers)
        }

        // Test II: different-address reflection.
        val secondServer = servers.getOrNull(1)
        val testII = if (secondServer != null) {
            probe(secondServer, null)
        } else {
            // Fall back to CHANGE-REQUEST on the primary; only honored by
            // servers that expose a second address.
            probe(primary, StunMessage.CHANGE_REQUEST_IP or StunMessage.CHANGE_REQUEST_PORT)
        }
        val mappedII = testII.reflectedAddress

        // Test III: same-address, different-port reflection.
        val testIII = probe(primary, StunMessage.CHANGE_REQUEST_PORT)
        val receivedIII = testIII.message != null

        return classify(mappedI, mappedII, receivedIII)
    }

    private fun classify(
        mappedI: InetSocketAddress,
        mappedII: InetSocketAddress?,
        receivedIII: Boolean
    ): ProbeResult {
        val natType = when {
            // Test II answered: we can receive from a different IP:port.
            mappedII != null -> {
                if (isSameEndpoint(mappedII, mappedI)) {
                    // Mapping is stable across destinations => endpoint-independent.
                    NatType.FULL_CONE
                } else {
                    // Mapping changed with the destination => symmetric.
                    NatType.SYMMETRIC
                }
            }
            // Test II silent but Test III answered: same IP, different port OK =>
            // address-dependent filtering, mapping still endpoint-independent.
            receivedIII -> NatType.RESTRICTED_CONE
            // Both silent: port-dependent filtering (or a very strict NAT).
            else -> NatType.PORT_RESTRICTED_CONE
        }
        return ProbeResult(natType, mappedI, localAddress, servers)
    }

    private fun isSameEndpoint(a: InetSocketAddress?, b: InetSocketAddress?): Boolean {
        if (a == null || b == null) return false
        val aBytes: ByteArray? = a.address?.address
        val bBytes: ByteArray? = b.address?.address
        if (aBytes == null || bBytes == null || !aBytes.contentEquals(bBytes)) return false
        return a.port == b.port
    }
}
