package com.bitchat.android.internetp2p

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.Base64

/**
 * Codec for the `bitchat-p2p://` peer-link URI shared by the QR-code and
 * share-link connection flows.
 *
 * Format: `bitchat-p2p://v1/<base64url>` where the payload is a JSON object
 * `{"npub": "<sender npub>", "candidate": {PunchCandidate json}}`.
 *
 * Carrying the sender's npub lets the receiver derive the stranger identity
 * key (`nostr_<pub16>`) without a favorite relationship, so both QR scans and
 * pasted links can establish a direct link between non-favorites.
 */
object P2pUriCodec {

    const val SCHEME = "bitchat-p2p://"
    private const val V1_PREFIX = SCHEME + "v1/"
    private val gson = Gson()

    /**
     * Encodes a peer-link URI for [npub] with [candidate].
     *
     * The generator's TCP listener port is also written at the top level
     * (`punchPort`) so the PORT CONVENTION is explicit in the QR/link: the
     * importer must dial this exact port, whatever the candidate payload says
     * internally. This is the "follow the scanned side" rule - the generator
     * is authoritative for where it listens.
     */
    fun encode(npub: String, candidate: PunchCandidate): String {
        val payload = JsonObject().apply {
            addProperty("npub", npub)
            addProperty("punchPort", candidate.tcpPort)
            add("candidate", gson.toJsonTree(candidate))
        }
        val raw = gson.toJson(payload)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return V1_PREFIX + b64
    }

    /**
     * Parses a peer-link URI. Returns the sender npub, the top-level port
     * convention and candidate, or null when the URI is malformed or not a
     * recognized bitchat-p2p link.
     */
    fun decode(uri: String): LinkPayload? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(V1_PREFIX)) return null
        val b64 = trimmed.removePrefix(V1_PREFIX)
        return try {
            val raw = String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
            val json = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val npub = json.get("npub")?.asString ?: return null
            val punchPort = json.get("punchPort")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val candidateJson = json.get("candidate")?.toString() ?: return null
            val candidate = PunchCandidate.fromJson(candidateJson) ?: return null
            LinkPayload(npub = npub, punchPort = punchPort, candidate = candidate)
        } catch (e: Exception) {
            null
        }
    }

    data class LinkPayload(
        val npub: String,
        val punchPort: Int = 0,
        val candidate: PunchCandidate
    )
}
