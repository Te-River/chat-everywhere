package com.bitchat.android.internetp2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression contract for the constants introduced by the direct-link fixes:
 * UDP→TCP upgrade budget and the per-sender OFFER burst throttle. These are
 * timing constants that shape real-device behavior, so pinning them here
 * guards against accidental drift.
 */
class P2pConfigTest {

    @Test
    fun `TCP upgrade budget is bounded and short`() {
        // Must be far below the full connect+accept window so a failed upgrade
        // does not noticeably delay the already-working UDP link.
        assertTrue(P2pConfig.TCP_UPGRADE_TIMEOUT_MS > 0)
        assertTrue(
            "upgrade budget should be shorter than connect+accept window",
            P2pConfig.TCP_UPGRADE_TIMEOUT_MS <
                P2pConfig.TCP_CONNECT_TIMEOUT_MS + P2pConfig.ACCEPT_WAIT_MS
        )
    }

    @Test
    fun `OFFER burst window coalesces repeated offers`() {
        // One connect/answer burst per sender per window: long enough to
        // swallow relay redelivery + candidate drift storms, short enough not
        // to delay a legitimate fresh attempt.
        assertTrue(P2pConfig.P2P_OFFER_BURST_WINDOW_MS >= 5_000)
        assertTrue(P2pConfig.P2P_OFFER_BURST_WINDOW_MS <= 15_000)
        assertEquals(8_000L, P2pConfig.P2P_OFFER_BURST_WINDOW_MS)
    }

    @Test
    fun `handshake timeout keeps failed links fast`() {
        assertTrue(P2pConfig.TCP_HANDSHAKE_TIMEOUT_MS > 0)
        assertTrue(P2pConfig.TCP_HANDSHAKE_TIMEOUT_MS <= P2pConfig.TCP_CONNECT_TIMEOUT_MS)
    }
}
