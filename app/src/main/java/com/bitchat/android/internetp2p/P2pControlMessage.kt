package com.bitchat.android.internetp2p

/**
 * Signaling control message for the internet P2P channel, carried inside the
 * existing end-to-end encrypted Nostr DM stream (same pattern as
 * [com.bitchat.android.favorites.FavoriteControlMessage]).
 *
 * Wire format: `[P2P_OFFER]:<candidate-json>` (likewise ANSWER / CANDIDATE).
 * The candidate JSON is produced by [PunchCandidate.toJson].
 *
 * Flow:
 *  - OFFER: I want a direct link; here is my candidate.
 *  - ANSWER: I got your offer; here is my candidate (then both sides punch).
 *  - CANDIDATE: incremental candidate update for an in-progress punch.
 */
data class P2pControlMessage(
    val kind: Kind,
    val candidate: PunchCandidate
) {
    enum class Kind { OFFER, ANSWER, CANDIDATE }

    companion object {
        private const val PREFIX_OFFER = "[P2P_OFFER]"
        private const val PREFIX_ANSWER = "[P2P_ANSWER]"
        private const val PREFIX_CANDIDATE = "[P2P_CANDIDATE]"

        fun encode(kind: Kind, candidate: PunchCandidate): String {
            val prefix = when (kind) {
                Kind.OFFER -> PREFIX_OFFER
                Kind.ANSWER -> PREFIX_ANSWER
                Kind.CANDIDATE -> PREFIX_CANDIDATE
            }
            return "$prefix:${PunchCandidate.toJson(candidate)}"
        }

        fun parse(content: String): P2pControlMessage? {
            val trimmed = content.trim()
            val (prefix, kind) = when {
                trimmed.startsWith(PREFIX_OFFER) -> PREFIX_OFFER to Kind.OFFER
                trimmed.startsWith(PREFIX_ANSWER) -> PREFIX_ANSWER to Kind.ANSWER
                trimmed.startsWith(PREFIX_CANDIDATE) -> PREFIX_CANDIDATE to Kind.CANDIDATE
                else -> return null
            }
            val json = trimmed.substringAfter("$prefix:", "").trim()
            if (json.isEmpty()) return null
            val candidate = PunchCandidate.fromJson(json) ?: return null
            return P2pControlMessage(kind, candidate)
        }
    }
}
