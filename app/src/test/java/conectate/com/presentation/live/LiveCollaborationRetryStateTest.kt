package conectate.com.presentation.live

import org.junit.Assert.*
import org.junit.Test

class LiveCollaborationRetryStateTest {
    @Test fun `initial disconnected state never offers retry`() {
        assertFalse(LiveCollaborationRetryState().shouldOfferAfterDisconnect())
    }

    @Test fun `connecting or cancelled attempt never offers terminal recovery`() {
        val state = LiveCollaborationRetryState().beginAttempt()
        assertTrue(state.attempting)
        assertFalse(state.shouldOfferAfterDisconnect())
    }

    @Test fun `completed success offers recovery for one terminal event only`() {
        val state = LiveCollaborationRetryState().beginAttempt().completeAttempt(success = true)
        assertTrue(state.shouldOfferAfterDisconnect())
        assertFalse(state.consumeDisconnect().shouldOfferAfterDisconnect())
    }

    @Test fun `completed failure does not also trigger a duplicate disconnect alert`() {
        val state = LiveCollaborationRetryState().beginAttempt().completeAttempt(success = false)
        assertFalse(state.attempting)
        assertTrue(state.terminalHandled)
        assertFalse(state.shouldOfferAfterDisconnect())
    }

    @Test fun `manual retry resets terminal state and can succeed`() {
        val attempt = LiveCollaborationRetryState().completeAttempt(success = false).beginAttempt()
        assertFalse(attempt.terminalHandled)
        assertFalse(attempt.shouldOfferAfterDisconnect())
        assertTrue(attempt.completeAttempt(success = true).shouldOfferAfterDisconnect())
    }

    @Test fun `retry dialog is hidden while leaving connecting or no plan`() {
        fun visible(requested: Boolean = true, hasPlan: Boolean = true, leaving: Boolean = false,
                    attempting: Boolean = false, busy: Boolean = false) =
            canShowLiveCollaborationRetry(requested, hasPlan, leaving, attempting, busy)
        assertTrue(visible())
        assertFalse(visible(requested = false))
        assertFalse(visible(hasPlan = false))
        assertFalse(visible(leaving = true))
        assertFalse(visible(attempting = true))
        assertFalse(visible(busy = true))
    }
}
