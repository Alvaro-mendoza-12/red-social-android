package conectate.com.presentation.video

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackRecoveryPolicyTest {
    @Test
    fun `network failure retries three times with bounded backoff`() {
        val first = decideVideoPlaybackRecovery(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            httpStatusCode = null,
            previousAttempts = 0,
        ) as VideoPlaybackRecoveryDecision.Retry
        val second = decideVideoPlaybackRecovery(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            httpStatusCode = null,
            previousAttempts = 1,
        ) as VideoPlaybackRecoveryDecision.Retry
        val third = decideVideoPlaybackRecovery(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            httpStatusCode = null,
            previousAttempts = 2,
        ) as VideoPlaybackRecoveryDecision.Retry

        assertEquals(1, first.attempt)
        assertEquals(350L, first.delayMs)
        assertFalse(first.refreshEndpoint)
        assertFalse(first.evictCache)
        assertEquals(2, second.attempt)
        assertEquals(1_000L, second.delayMs)
        assertTrue(second.refreshEndpoint)
        assertFalse(second.evictCache)
        assertEquals(3, third.attempt)
        assertEquals(2_000L, third.delayMs)
        assertTrue(third.refreshEndpoint)
    }

    @Test
    fun `retry budget cannot loop forever`() {
        val result = decideVideoPlaybackRecovery(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            httpStatusCode = null,
            previousAttempts = MAX_AUTOMATIC_VIDEO_RETRIES,
        )

        assertEquals(VideoPlaybackRecoveryDecision.ShowError, result)
    }

    @Test
    fun `temporary and stale media http statuses retry`() {
        assertTrue(
            isRetryableVideoPlaybackFailure(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                503,
            )
        )
        assertTrue(
            isRetryableVideoPlaybackFailure(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                404,
            )
        )
        val staleMedia = decideVideoPlaybackRecovery(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatusCode = 404,
            previousAttempts = 0,
        ) as VideoPlaybackRecoveryDecision.Retry
        assertTrue(staleMedia.refreshEndpoint)

        assertTrue(
            isRetryableVideoPlaybackFailure(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                530,
            )
        )
    }

    @Test
    fun `decoder and unsupported format failures are not retried`() {
        assertFalse(
            isRetryableVideoPlaybackFailure(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                httpStatusCode = null,
            )
        )
    }

    @Test
    fun `invalid range discards stale bytes but network and origin failures preserve them`() {
        val range = decideVideoPlaybackRecovery(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 416, 0)
            as VideoPlaybackRecoveryDecision.Retry
        assertTrue(range.evictCache)
        for (status in listOf(403, 404, 503, 530)) {
            val retry = decideVideoPlaybackRecovery(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, 0)
                as VideoPlaybackRecoveryDecision.Retry
            assertFalse(retry.evictCache)
        }
    }
}
