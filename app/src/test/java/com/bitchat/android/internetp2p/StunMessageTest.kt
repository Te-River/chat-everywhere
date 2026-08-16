package com.bitchat.android.internetp2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class StunMessageTest {

    private fun bindingRequest(type: Int, transactionId: ByteArray, attrs: List<StunMessage.Attribute>): ByteArray {
        return StunMessage.encode(type, transactionId, attrs)
    }

    /**
     * A binding request with a CHANGE-REQUEST attribute must carry the
     * request type, a 12-byte transaction id, and the RFC 5780 flags.
     */
    @Test
    fun `binding request encodes header and change-request`() {
        val txn = StunMessage.newTransactionId()
        val bytes = StunMessage.encodeBindingRequest(
            transactionId = txn,
            changeRequest = StunMessage.CHANGE_REQUEST_IP or StunMessage.CHANGE_REQUEST_PORT
        )

        assertEquals(0x0001, ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF))
        val cookie = ByteBuffer.wrap(bytes, 4, 4).int
        assertEquals(StunMessage.MAGIC_COOKIE, cookie)
        assertArrayEquals(txn, bytes.copyOfRange(8, 20))
        // CHANGE-REQUEST attribute present: type 0x0003 at offset 20.
        assertEquals(StunMessage.ATTR_CHANGE_REQUEST, ((bytes[20].toInt() and 0xFF) shl 8) or (bytes[21].toInt() and 0xFF))
        // Attribute value is 4 bytes starting at offset 24; flags live in byte 27.
        assertEquals(0x06, bytes[27].toInt() and 0x07)
    }

    /**
     * Builds a full STUN response message with one address attribute:
     * 20-byte header + 4-byte attribute header + N-byte value.
     */
    private fun responseWithAttr(
        type: Int,
        attrType: Int,
        value: ByteArray,
        txn: ByteArray = StunMessage.newTransactionId()
    ): ByteArray {
        val header = ByteBuffer.allocate(20).apply {
            putShort(type.toShort())
            putShort((4 + value.size).toShort()) // STUN length = attr header + value
            putInt(StunMessage.MAGIC_COOKIE)
            put(txn)
        }.array()
        val attrHeader = ByteBuffer.allocate(4).apply {
            putShort(attrType.toShort())
            putShort(value.size.toShort())
        }.array()
        return header + attrHeader + value
    }

    /**
     * RFC 5389 XOR-MAPPED-ADDRESS round trip: encode a synthetic server
     * response with a known reflected IPv4 endpoint and decode it back.
     */
    @Test
    fun `decodes xor-mapped-address for ipv4`() {
        val txn = StunMessage.newTransactionId()
        val realIp = byteArrayOf(1, 2, 3, 4)
        val realPort = 1234

        // XOR-MAPPED-ADDRESS value: 1 reserved + 1 family + 2 port + 4 address.
        val value = ByteBuffer.allocate(8).apply {
            put(0)                 // reserved
            put(0x01)              // family IPv4
            putShort((realPort xor (StunMessage.MAGIC_COOKIE ushr 16)).toShort())
            // XOR IPv4 with the cookie (32-bit big endian).
            val cookieBe = ByteBuffer.allocate(4).putInt(StunMessage.MAGIC_COOKIE).array()
            for (i in 0 until 4) {
                put((realIp[i].toInt() xor (cookieBe[i].toInt() and 0xFF)).toByte())
            }
        }.array()

        val response = responseWithAttr(
            type = StunMessage.TYPE_BINDING_RESPONSE,
            attrType = StunMessage.ATTR_XOR_MAPPED_ADDRESS,
            value = value,
            txn = txn
        )

        val decoded = StunMessage.decode(response)
        assertNotNull(decoded)
        assertEquals(StunMessage.TYPE_BINDING_RESPONSE, decoded!!.messageType)
        assertArrayEquals(txn, decoded.transactionId)

        val reflected = decoded.reflectedAddress
        assertNotNull(reflected)
        assertEquals(realPort, reflected!!.port)
        assertArrayEquals(realIp, reflected.address.address)
    }

    /**
     * IPv6 XOR-MAPPED-ADDRESS decodes correctly (XOR key = cookie + txn id).
     */
    @Test
    fun `decodes xor-mapped-address for ipv6`() {
        val txn = StunMessage.newTransactionId()
        val realIp = ByteArray(16) { (it + 1).toByte() }
        val realPort = 5555

        val value = ByteBuffer.allocate(20).apply {
            put(0)
            put(0x02) // family IPv6
            putShort((realPort xor (StunMessage.MAGIC_COOKIE ushr 16)).toShort())
            val cookieBe = ByteBuffer.allocate(4).putInt(StunMessage.MAGIC_COOKIE).array()
            val key = ByteArray(16)
            cookieBe.copyInto(key, 0, 0, 4)
            txn.copyInto(key, 4, 0, 12)
            for (i in 0 until 16) {
                put((realIp[i].toInt() xor key[i].toInt()).toByte())
            }
        }.array()

        val response = responseWithAttr(
            type = StunMessage.TYPE_BINDING_RESPONSE,
            attrType = StunMessage.ATTR_XOR_MAPPED_ADDRESS,
            value = value,
            txn = txn
        )

        val decoded = StunMessage.decode(response)
        val reflected = decoded?.reflectedAddress
        assertNotNull(reflected)
        assertEquals(realPort, reflected!!.port)
        assertArrayEquals(realIp, reflected.address.address)
    }

    /**
     * An error response exposes the ERROR-CODE attribute.
     */
    @Test
    fun `decodes error code`() {
        val txn = StunMessage.newTransactionId()
        val value = ByteBuffer.allocate(4).apply {
            put(0)
            put(0)
            put(0x03.toByte()) // class 3 -> 300
            put(0x01)          // number 1 -> 301
        }.array()

        val response = responseWithAttr(
            type = StunMessage.TYPE_BINDING_ERROR_RESPONSE,
            attrType = StunMessage.ATTR_ERROR_CODE,
            value = value,
            txn = txn
        )

        val decoded = StunMessage.decode(response)
        assertNotNull(decoded)
        assertEquals(301, decoded!!.errorCode)
    }

    /**
     * Malformed input (short header, wrong cookie, truncated attributes)
     * must decode to null rather than throw.
     */
    @Test
    fun `malformed input returns null`() {
        assertNull(StunMessage.decode(ByteArray(10)))
        assertNull(StunMessage.decode(ByteArray(20))) // zero-length body, but header cookie = 0
        val badCookie = ByteBuffer.allocate(20).apply {
            putShort(0x0101.toShort())
            putShort(0)
            putInt(0xDEADBEEF.toInt())
            put(StunMessage.newTransactionId())
        }.array()
        assertNull(StunMessage.decode(badCookie))
    }

    /**
     * Transaction ids are random and unique per call.
     */
    @Test
    fun `transaction ids are unique`() {
        val a = StunMessage.newTransactionId()
        val b = StunMessage.newTransactionId()
        assertTrue(a.size == 12)
        assertTrue(!a.contentEquals(b))
    }

    /**
     * P2pConfig parses host:port, bare host, and bracketed IPv6.
     */
    @Test
    fun `parseServer handles common forms`() {
        assertEquals(3478, P2pConfig.parseServer("stun.example.com")!!.port)
        assertEquals(1234, P2pConfig.parseServer("stun.example.com:1234")!!.port)
        val v6 = P2pConfig.parseServer("[2001:db8::1]:1234")!!
        // hostAddress may be either compressed or fully-expanded across JDKs.
        val normalized = InetAddress.getByName(v6.address.hostAddress).hostAddress
        assertEquals("2001:db8:0:0:0:0:0:1", normalized)
        assertNull(P2pConfig.parseServer(""))
        assertNull(P2pConfig.parseServer("host:notaport"))
    }
}
