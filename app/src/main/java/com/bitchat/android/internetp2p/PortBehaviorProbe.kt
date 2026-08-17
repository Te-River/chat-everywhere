package com.bitchat.android.internetp2p

import java.net.InetSocketAddress

/**
 * Infers the local NAT's EXTERNAL-PORT ALLOCATION behavior by issuing several
 * consecutive binding requests to one STUN server and observing how the
 * reflected port changes. This decides which hole-punch strategy can work for
 * symmetric NATs:
 *
 *  - [PortAllocation.STABLE]:     reflected port unchanged across probes
 *    (endpoint-independent mapping; classic UDP punch or stable TSO ports).
 *  - [PortAllocation.SEQUENTIAL]: port moves by a constant small delta
 *    (NAT4E / incremental symmetric) -> port prediction (N+1) can work.
 *  - [PortAllocation.RANDOM]:     port jumps unpredictably (e.g. China Mobile
 *    NAT4 hashes the remote address) -> prediction useless; fall back to
 *    multi-port TCP Simultaneous Open (Birthday Attack) or Nostr.
 *  - [PortAllocation.UNKNOWN]:    probe failed / no reflected address.
 *
 * The probe function is injected so unit tests can emulate any allocation
 * pattern without real sockets; production wires it to a [StunClient] sharing
 * the bound punch socket.
 */
class PortBehaviorProbe(
    private val probe: suspend (server: InetSocketAddress, changeRequest: Int?) -> StunClient.ProbeResult,
    private val server: InetSocketAddress,
    private val samples: Int = DEFAULT_SAMPLES
) {

    enum class PortAllocation { STABLE, SEQUENTIAL, RANDOM, UNKNOWN }

    companion object {
        private const val TAG = "PortBehaviorProbe"
        const val DEFAULT_SAMPLES = 4

        /** Maximum |delta| still considered a predictable sequential step. */
        const val MAX_SEQUENTIAL_DELTA = 16

        fun forSocket(
            client: StunClient,
            server: InetSocketAddress,
            samples: Int = DEFAULT_SAMPLES
        ): PortBehaviorProbe {
            return PortBehaviorProbe(
                probe = { s, cr -> client.probe(s, cr) },
                server = server,
                samples = samples
            )
        }
    }

    /**
     * Runs [samples] consecutive binding requests and classifies the port
     * allocation pattern observed on the reflected addresses.
     */
    suspend fun classify(): PortAllocation {
        val ports = ArrayList<Int>(samples)
        for (i in 0 until samples) {
            val result = try {
                probe(server, null)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Port behavior probe failed: ${e.message}")
                return PortAllocation.UNKNOWN
            }
            val mapped = result.reflectedAddress ?: return PortAllocation.UNKNOWN
            ports.add(mapped.port)
        }

        // All samples identical => endpoint-independent mapping.
        if (ports.all { it == ports.first() }) return PortAllocation.STABLE

        // Constant small delta => predictable (NAT4E incremental symmetric).
        val deltas = ports.zipWithNext { a, b -> b - a }
        val firstDelta = deltas.first()
        if (firstDelta != 0 &&
            Math.abs(firstDelta) <= MAX_SEQUENTIAL_DELTA &&
            deltas.all { it == firstDelta }
        ) {
            return PortAllocation.SEQUENTIAL
        }

        return PortAllocation.RANDOM
    }
}
