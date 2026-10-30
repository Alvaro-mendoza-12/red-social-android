package conectate.com.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream

class ResumableUploadProtocolTest {
    @Test fun `new session location cannot send credentials outside the upload endpoint`() {
        val endpoint = "https://uploads.example.test/storage/v1/upload/resumable".toHttpUrl()
        assertEquals("https://uploads.example.test/storage/v1/upload/resumable/session", resolveTusSessionUrl(endpoint, "/storage/v1/upload/resumable/session").toString())
        for (location in listOf("https://attacker.test/session", "http://uploads.example.test/storage/v1/upload/resumable/session", "/v1/auth/session", "https://user:pass@uploads.example.test/storage/v1/upload/resumable/session", "/storage/v1/upload/resumable/session?redirect=1")) {
            assertThrows(IllegalArgumentException::class.java) { resolveTusSessionUrl(endpoint, location) }
        }
    }

    @Test fun `same length replacement has a different resumable source identity`() = runTest {
        val original = uploadSourceSha256(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val replacement = uploadSourceSha256(ByteArrayInputStream(byteArrayOf(1, 2, 4)), 3)
        assertNotEquals(original, replacement)
        assertEquals(original, uploadSourceSha256(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
    }

    @Test fun `source truncated before retry does not acquire a resumable identity`() = runTest {
        try {
            uploadSourceSha256(ByteArrayInputStream(byteArrayOf(1, 2)), 3)
            org.junit.Assert.fail("Changed local file must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `only local large media opts into resumable transport`() {
        assertFalse(shouldAttemptResumableUpload(RESUMABLE_UPLOAD_THRESHOLD_BYTES - 1, true))
        assertFalse(shouldAttemptResumableUpload(RESUMABLE_UPLOAD_THRESHOLD_BYTES, false))
        assertTrue(shouldAttemptResumableUpload(RESUMABLE_UPLOAD_THRESHOLD_BYTES, true))
    }

    @Test
    fun `metadata is base64 encoded and offsets are strict`() {
        assertEquals(
            "bucketName dmlkZW9z,objectName dXNlci9jbGlwLm1wNA==",
            tusMetadataHeader(linkedMapOf("bucketName" to "videos", "objectName" to "user/clip.mp4")),
        )
        assertEquals(6291456L, resumableUploadOffset("6291456"))
        assertNull(resumableUploadOffset("-1"))
        assertNull(resumableUploadOffset("6.2"))
        assertNull(resumableUploadOffset(""))
    }

    @Test
    fun `chunks never exceed six mebibytes or remaining bytes`() {
        assertEquals(RESUMABLE_UPLOAD_CHUNK_BYTES, boundedResumableChunkSize(20L * 1024L * 1024L, 0L))
        assertEquals(2L * 1024L * 1024L, boundedResumableChunkSize(20L * 1024L * 1024L, 18L * 1024L * 1024L))
    }

    @Test
    fun `persisted session must be fresh and belong to the same endpoint`() {
        val endpoint = "https://media.novvin.example/storage/v1/upload/resumable".toHttpUrl()
        val session = "https://media.novvin.example/storage/v1/upload/resumable/550e8400-e29b-41d4-a716-446655440000".toHttpUrl()
        val now = 1_800_000_000_000L
        assertTrue(isReusableTusSession(endpoint, session, now - 1_000L, now))
        assertFalse(
            isReusableTusSession(
                endpoint,
                "https://attacker.example/storage/v1/upload/resumable/550e8400-e29b-41d4-a716-446655440000".toHttpUrl(),
                now - 1_000L,
                now,
            ),
        )
        assertFalse(
            isReusableTusSession(
                endpoint,
                session,
                now - RESUMABLE_UPLOAD_SESSION_MAX_AGE_MS - 1L,
                now,
            ),
        )
    }
}
