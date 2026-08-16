package com.bitchat.android.internetp2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * RFC 5780 NAT classification tests. The probe function is injected, so each
 * test emulates a different NAT behavior without real sockets.
 */
class NatTypeDetectorTest {

    private val serverA = InetSocketAddress(InetAddress.getByName("198.51.100.1"), 3478)
    private val serverB = InetSocketAddress(InetAddress.getByName("198.51.100.2"), 3478)
    private val local = InetSocketAddress(InetAddress.getByName("192.168.1.50"), 40000)

    private fun mapped(ip: String, port: Int): InetSocketAddress =
        InetSocketAddress(InetAddress.getByName(ip), port)

    private fun response(addr: InetSocketAddress?): StunClient.ProbeResult =
        StunClient.ProbeResult(
            message = if (addr != null) {
                StunMessage.DecodedMessage(
                    messageType = StunMessage.TYPE_BINDING_RESPONSE,
                    transactionId = StunMessage.newTransactionId(),
                    attributes = emptyList(),
                    mappedAddress = addr,
                    xorMappedAddress = addr,
                    responseOrigin = null,
                    otherAddress = null,
                    errorCode = null
                )
            } else {
                null
            },
            server = serverA
        )

    /** Helper: build a detector whose probe returns per-server canned results. */
    private fun detector(
        testI: StunClient.ProbeResult,
        testII: StunClient.ProbeResult? = null,
        testIII: StunClient.ProbeResult? = null,
        servers: List<InetSocketAddress> = listOf(serverA, serverB)
    ): NatTypeDetector {
        return NatTypeDetector(
            probe = { server, changeRequest ->
                when {
                    // Test II uses the second server (plain) when present.
                    server == serverB && changeRequest == null -> testII ?: StunClient.ProbeResult(null, server)
                    // Test III is the port-only CHANGE-REQUEST on the primary.
                    server == serverA && changeRequest == StunMessage.CHANGE_REQUEST_PORT ->
                        testIII ?: StunClient.ProbeResult(null, server)
                    // Test I is the plain request on the primary.
                    server == serverA && changeRequest == null -> testI
                    else -> StunClient.ProbeResult(null, server)
                }
            },
            localAddress = local,
            servers = servers
        )
    }

    @Test
    fun `no response means udp blocked`() {
        val result = kotlinx.coroutines.runBlocking { detector(testI = response(null)).detect() }
        assertEquals(NatTypeDetector.NatType.UDP_BLOCKED, result.natType)
        assertNull(result.mappedAddress)
        assertFalse(result.udpPunchViable)
        assertFalse(result.directConnectViable)
    }

    @Test
    fun `reflected equals local means open internet`() {
        val result = kotlinx.coroutines.runBlocking { detector(testI = response(local)).detect() }
        assertEquals(NatTypeDetector.NatType.OPEN_INTERNET, result.natType)
        assertTrue(result.directConnectViable)
        assertFalse(result.udpPunchViable)
    }

    @Test
    fun `stable mapping across destinations is full cone`() {
        val result = kotlinx.coroutines.runBlocking { detector(
            testI = response(mapped("203.0.113.10", 5000)),
            testII = response(mapped("203.0.113.10", 5000))
        ).detect() }
        assertEquals(NatTypeDetector.NatType.FULL_CONE, result.natType)
        assertTrue(result.udpPunchViable)
    }

    @Test
    fun `mapping changes per destination is symmetric`() {
        val result = kotlinx.coroutines.runBlocking { detector(
            testI = response(mapped("203.0.113.10", 5000)),
            testII = response(mapped("203.0.113.10", 5010))
        ).detect() }
        assertEquals(NatTypeDetector.NatType.SYMMETRIC, result.natType)
        assertFalse(result.udpPunchViable)
    }

    @Test
    fun `different ip blocked but same ip different port ok is restricted cone`() {
        val result = kotlinx.coroutines.runBlocking { detector(
            testI = response(mapped("203.0.113.10", 5000)),
            testII = null,
            testIII = response(mapped("203.0.113.10", 5000))
        ).detect() }
        assertEquals(NatTypeDetector.NatType.RESTRICTED_CONE, result.natType)
        assertTrue(result.udpPunchViable)
    }

    @Test
    fun `both alt probes silent is port restricted cone`() {
        val result = kotlinx.coroutines.runBlocking { detector(
            testI = response(mapped("203.0.113.10", 5000)),
            testII = null,
            testIII = null
        ).detect() }
        assertEquals(NatTypeDetector.NatType.PORT_RESTRICTED_CONE, result.natType)
        assertTrue(result.udpPunchViable)
    }

    @Test
    fun `single server falls back to change-request probing`() {
        // With only one server, Test II uses CHANGE-REQUEST on the primary.
        val detector = NatTypeDetector(
            probe = { _, changeRequest ->
                if (changeRequest == null) {
                    response(mapped("203.0.113.10", 5000))
                } else {
                    // Honoring the change request: different port reflected.
                    StunClient.ProbeResult(
                        StunMessage.DecodedMessage(
                            messageType = StunMessage.TYPE_BINDING_RESPONSE,
                            transactionId = StunMessage.newTransactionId(),
                            attributes = emptyList(),
                            mappedAddress = mapped("203.0.113.10", 5010),
                            xorMappedAddress = mapped("203.0.113.10", 5010),
                            responseOrigin = null,
                            otherAddress = null,
                            errorCode = null
                        ),
                        serverA
                    )
                }
            },
            localAddress = local,
            servers = listOf(serverA)
        )
        val result = kotlinx.coroutines.runBlocking { detector.detect() }
        // Different mapped port for the second destination => symmetric.
        assertEquals(NatTypeDetector.NatType.SYMMETRIC, result.natType)
    }

    @Test
    fun `empty server list is treated as udp blocked`() {
        val detector = NatTypeDetector(
            probe = { _, _ -> StunClient.ProbeResult(null, serverA) },
            localAddress = local,
            servers = emptyList()
        )
        val result = kotlinx.coroutines.runBlocking { detector.detect() }
        assertEquals(NatTypeDetector.NatType.UDP_BLOCKED, result.natType)
    }
}
