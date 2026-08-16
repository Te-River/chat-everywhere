package com.bitchat.android.internetp2p

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Minimal RFC 5389 STUN message codec (binding request/response) with RFC 5780
 * extension attribute support (CHANGE-REQUEST, RESPONSE-ORIGIN, OTHER-ADDRESS).
 *
 * Only the attributes the NAT traversal engine needs are decoded; unknown
 * attributes are skipped so responses from any conforming server parse cleanly.
 */
object StunMessage {

    // Message types
    const val TYPE_BINDING_REQUEST = 0x0001
    const val TYPE_BINDING_RESPONSE = 0x0101
    const val TYPE_BINDING_ERROR_RESPONSE = 0x0111

    // RFC 5389 magic cookie
    const val MAGIC_COOKIE: Int = 0x2112A442

    // Attribute types
    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_CHANGE_REQUEST = 0x0003
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_SOFTWARE = 0x8022
    const val ATTR_RESPONSE_ORIGIN = 0x802B
    const val ATTR_OTHER_ADDRESS = 0x802C

    // CHANGE-REQUEST flags (RFC 5780): ask the server to respond from a
    // different IP and/or port so NAT behavior can be inferred.
    const val CHANGE_REQUEST_IP: Int = 0x04
    const val CHANGE_REQUEST_PORT: Int = 0x02

    private const val HEADER_LENGTH = 20
    private val random = SecureRandom()

    /**
     * One parsed attribute. Raw bytes are kept so codecs can interpret them.
     */
    data class Attribute(val type: Int, val value: ByteArray)

    /**
     * A decoded STUN message plus the endpoints derived from its attributes.
     */
    data class DecodedMessage(
        val messageType: Int,
        val transactionId: ByteArray,
        val attributes: List<Attribute>,
        val mappedAddress: InetSocketAddress?,
        val xorMappedAddress: InetSocketAddress?,
        val responseOrigin: InetSocketAddress?,
        val otherAddress: InetSocketAddress?,
        val errorCode: Int?
    ) {
        /** Best available reflected endpoint (prefer XOR per RFC 5389). */
        val reflectedAddress: InetSocketAddress?
            get() = xorMappedAddress ?: mappedAddress
    }

    /**
     * Builds a binding request.
     *
     * @param transactionId 12-byte transaction id; generated when omitted.
     * @param changeRequest OR of [CHANGE_REQUEST_IP]/[CHANGE_REQUEST_PORT], or null.
     */
    fun encodeBindingRequest(
        transactionId: ByteArray = newTransactionId(),
        changeRequest: Int? = null
    ): ByteArray {
        val attributes = mutableListOf<Attribute>()
        if (changeRequest != null) {
            // 4-byte value, flags in the low 3 bits of the last octet.
            attributes.add(
                Attribute(
                    ATTR_CHANGE_REQUEST,
                    byteArrayOf(0, 0, 0, (changeRequest and 0x07).toByte())
                )
            )
        }
        return encode(TYPE_BINDING_REQUEST, transactionId, attributes)
    }

    /**
     * Encodes a STUN message: 20-byte header followed by padded attributes.
     */
    fun encode(
        messageType: Int,
        transactionId: ByteArray,
        attributes: List<Attribute>
    ): ByteArray {
        require(transactionId.size == 12) { "STUN transaction id must be 12 bytes" }
        val body = java.io.ByteArrayOutputStream()
        var length = 0
        for (attr in attributes) {
            val valueLength = attr.value.size
            body.write((attr.type ushr 8) and 0xFF)
            body.write(attr.type and 0xFF)
            body.write((valueLength ushr 8) and 0xFF)
            body.write(valueLength and 0xFF)
            body.write(attr.value)
            val padding = (4 - (valueLength % 4)) % 4
            repeat(padding) { body.write(0) }
            length += 4 + valueLength + padding
        }
        require(length <= 0xFFFF) { "STUN message too large" }

        val out = java.io.ByteArrayOutputStream()
        out.write((messageType ushr 8) and 0xFF)
        out.write(messageType and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write((MAGIC_COOKIE ushr 24) and 0xFF)
        out.write((MAGIC_COOKIE ushr 16) and 0xFF)
        out.write((MAGIC_COOKIE ushr 8) and 0xFF)
        out.write(MAGIC_COOKIE and 0xFF)
        out.write(transactionId)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /**
     * Decodes a STUN message, extracting the address attributes the NAT
     * traversal engine consumes. Returns null for malformed input.
     */
    fun decode(bytes: ByteArray): DecodedMessage? {
        if (bytes.size < HEADER_LENGTH) return null
        val messageType = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val cookie = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        if (cookie != MAGIC_COOKIE) return null
        val transactionId = bytes.copyOfRange(8, HEADER_LENGTH)
        if (HEADER_LENGTH + length > bytes.size) return null

        val attributes = mutableListOf<Attribute>()
        var offset = HEADER_LENGTH
        val end = HEADER_LENGTH + length
        while (offset + 4 <= end) {
            val type = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            val attrLength = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            val valueStart = offset + 4
            val valueEnd = minOf(valueStart + attrLength, bytes.size)
            attributes.add(Attribute(type, bytes.copyOfRange(valueStart, valueEnd)))
            val padding = (4 - (attrLength % 4)) % 4
            offset = valueStart + attrLength + padding
        }

        var mapped: InetSocketAddress? = null
        var xorMapped: InetSocketAddress? = null
        var responseOrigin: InetSocketAddress? = null
        var otherAddress: InetSocketAddress? = null
        var errorCode: Int? = null
        for (attr in attributes) {
            when (attr.type) {
                ATTR_MAPPED_ADDRESS -> mapped = parseAddressAttribute(attr.value, transactionId, xor = false)
                ATTR_XOR_MAPPED_ADDRESS -> xorMapped = parseAddressAttribute(attr.value, transactionId, xor = true)
                ATTR_RESPONSE_ORIGIN -> responseOrigin = parseAddressAttribute(attr.value, transactionId, xor = true)
                ATTR_OTHER_ADDRESS -> otherAddress = parseAddressAttribute(attr.value, transactionId, xor = true)
                ATTR_ERROR_CODE -> errorCode = parseErrorCode(attr.value)
            }
        }
        return DecodedMessage(
            messageType = messageType,
            transactionId = transactionId,
            attributes = attributes,
            mappedAddress = mapped,
            xorMappedAddress = xorMapped,
            responseOrigin = responseOrigin,
            otherAddress = otherAddress,
            errorCode = errorCode
        )
    }

    /** Random 12-byte transaction id per RFC 5389. */
    fun newTransactionId(): ByteArray {
        val id = ByteArray(12)
        random.nextBytes(id)
        return id
    }

    /**
     * Parses an address attribute. IPv4 (family 0x01) and IPv6 (family 0x02)
     * are supported; XOR decoding follows RFC 5389 section 15.2.
     */
    private fun parseAddressAttribute(
        value: ByteArray,
        transactionId: ByteArray,
        xor: Boolean
    ): InetSocketAddress? {
        if (value.size < 8) return null
        val family = value[1].toInt() and 0xFF
        val port = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        val decodedPort = if (xor) port xor (MAGIC_COOKIE ushr 16) else port

        val addressBytes: ByteArray = when (family) {
            0x01 -> {
                if (value.size < 8) return null
                val bytes = ByteArray(4)
                for (i in 0 until 4) {
                    bytes[i] = value[4 + i]
                }
                if (xor) {
                    for (i in 0 until 4) {
                        bytes[i] = (bytes[i].toInt() xor ((MAGIC_COOKIE ushr (24 - 8 * i)) and 0xFF)).toByte()
                    }
                }
                bytes
            }
            0x02 -> {
                if (value.size < 20) return null
                val bytes = ByteArray(16)
                for (i in 0 until 16) {
                    bytes[i] = value[4 + i]
                }
                if (xor) {
                    val key = ByteArray(16)
                    key[0] = (MAGIC_COOKIE ushr 24).toByte()
                    key[1] = (MAGIC_COOKIE ushr 16).toByte()
                    key[2] = (MAGIC_COOKIE ushr 8).toByte()
                    key[3] = MAGIC_COOKIE.toByte()
                    transactionId.copyInto(key, 4, 0, 12)
                    for (i in 0 until 16) {
                        bytes[i] = (bytes[i].toInt() xor key[i].toInt()).toByte()
                    }
                }
                bytes
            }
            else -> return null
        }

        return try {
            InetSocketAddress(InetAddress.getByAddress(addressBytes), decodedPort)
        } catch (e: Exception) {
            null
        }
    }

    /** Parses RFC 5389 ERROR-CODE: 2 reserved bytes + class(3 bits) + number. */
    private fun parseErrorCode(value: ByteArray): Int? {
        if (value.size < 4) return null
        val errorClass = (value[2].toInt() and 0x07)
        val number = value[3].toInt() and 0xFF
        return errorClass * 100 + number
    }
}
