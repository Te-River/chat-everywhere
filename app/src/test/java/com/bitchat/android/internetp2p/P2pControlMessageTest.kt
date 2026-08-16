package com.bitchat.android.internetp2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class P2pControlMessageTest {

    private val candidate = PunchCandidate(
        nonce = "0123456789abcdef0123456789abcdef",
        mappedHost = "203.0.113.10",
        mappedPort = 5000,
        ipv6Host = "2001:db8::1",
        tcpPort = 40001,
        natType = NatTypeDetector.NatType.FULL_CONE,
        hasIpv4Mapped = true
    )

    @Test
    fun `offer round-trips through json`() {
        val encoded = P2pControlMessage.encode(P2pControlMessage.Kind.OFFER, candidate)
        assertNotNull(encoded)
        val parsed = P2pControlMessage.parse(encoded)
        assertNotNull(parsed)
        assertEquals(P2pControlMessage.Kind.OFFER, parsed!!.kind)
        assertEquals(candidate, parsed.candidate)
    }

    @Test
    fun `answer round-trips through json`() {
        val encoded = P2pControlMessage.encode(P2pControlMessage.Kind.ANSWER, candidate)
        val parsed = P2pControlMessage.parse(encoded)
        assertNotNull(parsed)
        assertEquals(P2pControlMessage.Kind.ANSWER, parsed!!.kind)
        assertEquals(candidate.nonce, parsed.candidate.nonce)
    }

    @Test
    fun `candidate round-trips through json`() {
        val encoded = P2pControlMessage.encode(P2pControlMessage.Kind.CANDIDATE, candidate)
        val parsed = P2pControlMessage.parse(encoded)
        assertNotNull(parsed)
        assertEquals(P2pControlMessage.Kind.CANDIDATE, parsed!!.kind)
        assertEquals(candidate.natType, parsed.candidate.natType)
    }

    @Test
    fun `unrelated content is not a control message`() {
        assertNull(P2pControlMessage.parse("hello world"))
        assertNull(P2pControlMessage.parse("[FAVORITED]:npub1xyz"))
        assertNull(P2pControlMessage.parse("[P2P_OFFER]:"))
        assertNull(P2pControlMessage.parse("[P2P_OFFER]:not-json"))
    }

    @Test
    fun `candidate json round-trips through PunchCandidate alone`() {
        val json = PunchCandidate.toJson(candidate)
        val back = PunchCandidate.fromJson(json)
        assertNotNull(back)
        assertEquals(candidate, back)
        // Raw host/port fields must survive Gson without needing InetSocketAddress ctor.
        assertEquals("203.0.113.10", back!!.mappedHost)
        assertEquals(5000, back.mappedPort)
        assertEquals("2001:db8::1", back.ipv6Host)
    }
}
