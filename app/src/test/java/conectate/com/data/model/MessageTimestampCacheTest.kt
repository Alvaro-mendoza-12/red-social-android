package conectate.com.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MessageTimestampCacheTest {
    @Test fun `reading timestamp does not change wire JSON or message equality`() {
        val message = Message(id = "m", created_at = "2026-10-24T00:00:00.123456Z", content = "Hola 👩🏽‍💻")
        val before = Json.encodeToString(message)
        repeat(20) { assertEquals(1792800000123L, message.timestampMillis) }
        assertEquals(before, Json.encodeToString(message))
        assertFalse(before.contains("cachedTimestamp"))
        assertEquals(message, message.copy())
    }

    @Test fun `copy with another creation date cannot reuse the original cached timestamp`() {
        val message = Message(created_at = "2026-10-24T00:00:00Z")
        val original = message.timestampMillis
        val copy = message.copy(created_at = "2026-10-24T00:00:01Z")
        assertEquals(original + 1_000, copy.timestampMillis)
        assertEquals(original, message.timestampMillis)
    }

    @Test fun `legacy missing and malformed dates remain deterministic and safe`() {
        val missing = Message(id = "legacy", created_at = null)
        val malformed = Message(id = "legacy", created_at = "not-a-timestamp")
        repeat(20) {
            assertEquals(missing.timestampMillis, missing.copy().timestampMillis)
            assertEquals(0L, malformed.timestampMillis)
        }
    }

    @Test fun `UTC positive and negative offsets describe the same instant`() {
        val expected = Message(created_at = "2026-10-24T00:23:40.123456Z").timestampMillis
        listOf(
            "2026-10-23T19:23:40.123456-05:00",
            "2026-10-24T05:53:40.123456+05:30",
            "2026-10-24T00:23:40.123456+00:00",
        ).forEach { timestamp -> assertEquals(timestamp, expected, Message(created_at = timestamp).timestampMillis) }
    }

    @Test fun `Postgres hour offsets compact offsets and legacy UTC dates remain readable`() {
        val expected = Message(created_at = "2026-10-24T00:23:40Z").timestampMillis
        listOf(
            "2026-10-23 19:23:40-05", "2026-10-24 00:23:40+00",
            "2026-10-23T19:23:40-0500", "2026-10-24T05:53:40+0530",
            "2026-10-24 00:23:40", "1792801420000",
        ).forEach { timestamp -> assertEquals(timestamp, expected, Message(created_at = timestamp).timestampMillis) }
    }
}
