package com.bitchat.android.internetp2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PortBehaviorProbeTest {

    private val server = java.net.InetSocketAddress("203.0.113.10", 3478)

    /** Builds a probe whose binding responses return the given port sequence. */
    private fun probeReturning(ports: List<Int>): PortBehaviorProbe {
        var index = 0
        return PortBehaviorProbe(
            probe = { _, _ ->
                val port = ports[index.coerceAtMost(ports.lastIndex)]
                index++
                StunClient.ProbeResult(
                    message = StunMessage.DecodedMessage(
                        messageType = 0x0101,
                        transactionId = StunMessage.newTransactionId(),
                        attributes = emptyList(),
                        mappedAddress = null,
                        xorMappedAddress = java.net.InetSocketAddress("203.0.113.10", port),
                        responseOrigin = null,
                        otherAddress = null,
                        errorCode = null
                    ),
                    server = server
                )
            },
            server = server
        )
    }

    /** Same port every probe => stable mapping (cone NAT). */
    @Test
    fun `same port across probes is STABLE`() {
        val probe = probeReturning(listOf(5000, 5000, 5000, 5000))
        assertEquals(PortBehaviorProbe.PortAllocation.STABLE, kotlinx.coroutines.runBlocking { probe.classify() })
    }

    /** Constant small delta => incremental symmetric NAT (predictable, N+1). */
    @Test
    fun `constant small delta is SEQUENTIAL`() {
        val probe = probeReturning(listOf(5000, 5001, 5002, 5003))
        assertEquals(PortBehaviorProbe.PortAllocation.SEQUENTIAL, kotlinx.coroutines.runBlocking { probe.classify() })
    }

    /** Constant large delta (>= MAX_SEQUENTIAL_DELTA) is NOT predictable. */
    @Test
    fun `large constant delta is RANDOM`() {
        val probe = probeReturning(listOf(5000, 5500, 6000, 6500))
        assertEquals(PortBehaviorProbe.PortAllocation.RANDOM, kotlinx.coroutines.runBlocking { probe.classify() })
    }

    /** Random jumps => unpredictable symmetric NAT (China Mobile NAT4). */
    @Test
    fun `random jumps are RANDOM`() {
        val probe = probeReturning(listOf(5000, 37201, 20991, 61303))
        assertEquals(PortBehaviorProbe.PortAllocation.RANDOM, kotlinx.coroutines.runBlocking { probe.classify() })
    }

    /** A dying STUN server yields UNKNOWN, not a wrong classification. */
    @Test
    fun `no reflected address is UNKNOWN`() {
        var calls = 0
        val probe = PortBehaviorProbe(
            probe = { _, _ ->
                calls++
                StunClient.ProbeResult(message = null, server = server)
            },
            server = server
        )
        assertEquals(PortBehaviorProbe.PortAllocation.UNKNOWN, kotlinx.coroutines.runBlocking { probe.classify() })
    }

    /**
     * Port-prediction sweep: for a SEQUENTIAL NAT the UDP punch must dial the
     * advertised port PLUS a +/- window (RFC 5128 N+1). This test pins the
     * config so a future refactor cannot silently shrink the sweep.
     */
    @Test
    fun `port prediction window covers the advertised port plus neighbors`() {
        assertEquals(8, P2pConfig.PORT_PREDICTION_WINDOW)
        // 1 (base) + 2 * window (offsets) candidate endpoints are dialed.
        val expectedTargets = 1 + 2 * P2pConfig.PORT_PREDICTION_WINDOW
        assertEquals(17, expectedTargets)
    }

    /**
     * TSO Birthday Attack sizing: RANDOM symmetric NATs get a wider sweep
     * than stable ones (4 vs 8 shared ports).
     */
    @Test
    fun `tso port counts are sane`() {
        assertEquals(4, P2pConfig.TSO_PORT_COUNT)
        assertEquals(8, P2pConfig.TSO_PORT_COUNT_RANDOM)
        assertEquals(50_000, P2pConfig.TSO_PORT_BASE)
    }

    /** The new ipv6UdpPort rides the candidate JSON chain end-to-end. */
    @Test
    fun `ipv6UdpPort survives candidate json round trip`() {
        val candidate = PunchCandidate(
            nonce = "0123456789abcdef0123456789abcdef",
            mappedHost = "203.0.113.10",
            mappedPort = 5000,
            ipv6Host = "2001:db8::1",
            lanHost = "192.168.1.50",
            tcpPort = 40001,
            natType = NatTypeDetector.NatType.SYMMETRIC,
            ipv6UdpPort = 4242
        )
        val json = PunchCandidate.toJson(candidate)
        val back = PunchCandidate.fromJson(json)
        assertNotNull(back)
        assertEquals(4242, back!!.ipv6UdpPort)
    }
}
