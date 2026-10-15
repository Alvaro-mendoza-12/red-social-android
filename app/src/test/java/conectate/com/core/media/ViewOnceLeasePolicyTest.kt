package conectate.com.core.media

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ViewOnceLeasePolicyTest {
    private val now = 1_791_489_600_000L
    private val backend = "https://backend.test"
    private fun payload(update: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject {
        put("allowed", true); put("already_consumed", false)
        put("media_url", "$backend/storage/v1/object/public/chat-media/user/file?lease=${"a".repeat(43)}")
        put("lease_expires_at", java.time.Instant.ofEpochMilli(now + 300_000).toString())
        put("media_size_bytes", 2); put("content_type", "view_once_image"); update()
    }.toString()
    private fun rejected(raw: String) { assertTrue(runCatching { ViewOnceLeasePolicy.parse(raw, backend, now) }.isFailure) }
    @Test fun acceptsObjectAndSinglePostgrestRow() {
        assertEquals(2L, ViewOnceLeasePolicy.parse(payload(), backend, now).bytes)
        assertEquals("view_once_image", ViewOnceLeasePolicy.parse("[${payload()}]", backend, now).kind)
    }
    @Test fun rejectsMissingOrConsumedAuthorization() {
        listOf("null", "[]", "{}", "[${payload()},${payload()}]", payload { put("allowed", "true") }, payload { put("already_consumed", true) }).forEach(::rejected)
    }
    @Test fun rejectsUntrustedOrBareAndRepeatedLeaseUrls() {
        for (url in listOf("javascript:alert(1)", "https://foreign.test/storage/v1/object/public/a/b?lease=${"a".repeat(43)}", "$backend/storage/v1/object/public/a/b", "$backend/storage/v1/object/public/a/b?lease=${"a".repeat(43)}&token=x", "$backend/storage/v1/object/public/a/b?lease=${"a".repeat(43)}#x")) rejected(payload { put("media_url", url) })
    }
    @Test fun rejectsExpiredOrExcessiveLease() {
        for (expiry in listOf(now - 1, now, now + 360_001)) rejected(payload { put("lease_expires_at", java.time.Instant.ofEpochMilli(expiry).toString()) })
    }
    @Test fun rejectsEmptyOversizedFractionalOrUnexpectedMedia() {
        for (size in listOf(0L, -1L, ViewOnceLeasePolicy.MAX_BYTES + 1)) rejected(payload { put("media_size_bytes", size) })
        rejected(payload { put("media_size_bytes", 1.5) }); rejected(payload { put("content_type", "file") })
    }
}
