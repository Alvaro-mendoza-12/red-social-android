package conectate.com.data.repository.message

import conectate.com.data.model.Message
import org.junit.Assert.*
import org.junit.Test

class UploadedMessageRetryTest {
    private val cached = Message(id = "same-message", chat_id = "chat", sender_id = "a", content = "caption",
        content_type = "video", media_url = "https://server.example/storage/file.mp4?version=1",
        thumbnail_url = "https://server.example/storage/thumbnail.jpg", media_size = 15_000,
        created_at = "2026-11-01T12:00:00Z", status = "sending")

    @Test fun aPendingSendCanReuseAnUploadAndAllItsMetadataWithoutReopeningItsSource() {
        val reused = uploadedMessageForRetry(cached, "video")!!
        assertSame(cached, reused)
        assertEquals(15_000L, reused.media_size)
        assertEquals(cached.thumbnail_url, reused.thumbnail_url)
        assertEquals(cached.created_at, reused.created_at)
        assertEquals(cached.media_url, reused.media_url)
    }

    @Test fun aDurableFileStillNeedsAnUpload() {
        assertNull(uploadedMessageForRetry(cached.copy(media_url = "file:///private/pending.mp4"), "video"))
        assertNull(uploadedMessageForRetry(cached.copy(media_url = "content://provider/video"), "video"))
    }

    @Test fun absentOrMalformedUploadUrlsCannotSuppressTheOriginalSource() {
        for (url in listOf(null, "", " ", "not a url", "https://", "//server/file.mp4"))
            assertNull(uploadedMessageForRetry(cached.copy(media_url = url), "video"))
        assertNull(uploadedMessageForRetry(null, "video"))
    }

    @Test fun deletedMediaAndDifferentContentKindsAreNotReused() {
        assertNull(uploadedMessageForRetry(cached.copy(deleted_for_all = true), "video"))
        assertNull(uploadedMessageForRetry(cached, "document"))
    }

    @Test fun mediaKindNormalizationDoesNotChangeTheConfirmedObjectUrl() {
        assertSame(cached, uploadedMessageForRetry(cached, " VIDEO "))
    }

    @Test fun credentialsInAnUploadUrlAreNotAcceptedForReuse() {
        assertNull(uploadedMessageForRetry(cached.copy(media_url = "https://user:password@server.example/file.mp4"), "video"))
    }

    @Test fun legacyHttpUploadsRemainAvailableForTheExistingEndpointRewriter() {
        val legacy = cached.copy(media_url = "http://legacy.example/storage/file.mp4")
        assertSame(legacy, uploadedMessageForRetry(legacy, "video"))
    }
}
