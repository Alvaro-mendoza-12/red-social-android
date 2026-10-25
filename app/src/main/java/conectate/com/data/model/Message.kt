package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.abs

/** A single timestamp policy for timeline ordering and the visible message clock. */
internal fun parseMessageInstant(raw: String): java.time.Instant? {
    val normalized = raw.trim().replace(" ", "T")
    val offsetStart = maxOf(normalized.lastIndexOf('+'), normalized.lastIndexOf('-'))
    val hasOffset = normalized.indexOf('T').let { it >= 0 && offsetStart > it }
    // PostgreSQL can emit +00/-05; ISO also accepts compact +0000/-0500.
    // Never append Z to an existing negative offset.
    val cleaned = when {
        hasOffset && normalized.length - offsetStart == 3 -> "$normalized:00"
        hasOffset && normalized.length - offsetStart == 5 &&
            normalized.substring(offsetStart + 1).all(Char::isDigit) ->
            normalized.dropLast(2) + ":" + normalized.takeLast(2)
        else -> normalized
    }
    return runCatching { java.time.Instant.parse(cleaned) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(cleaned).toInstant() }.getOrNull()
        // Legacy zone-less database dates were stored in UTC.
        ?: runCatching { LocalDateTime.parse(cleaned).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
        ?: cleaned.toLongOrNull()?.let(java.time.Instant::ofEpochMilli)
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val chat_id: String = "",
    val sender_id: String = "",
    val content: String = "",
    @SerialName("content_type") val content_type: String = "text",
    @SerialName("media_url") val media_url: String? = null,
    @SerialName("thumbnail_url") val thumbnail_url: String? = null,
    @SerialName("poll_id") val poll_id: String? = null,
    @SerialName("media_size") val media_size: Long = 0,
    val status: String = "sent",
    @SerialName("read_at") val read_at: String? = null,
    @SerialName("reply_to_id") val reply_to_id: String? = null,
    @SerialName("is_edited") val is_edited: Boolean = false,
    @SerialName("deleted_for_all") val deleted_for_all: Boolean = false,
    @SerialName("deleted_by") val deleted_by: List<String> = emptyList(),
    @SerialName("reactions") val reactions: Map<String, List<String>> = emptyMap(),
    @SerialName("is_forwarded") val is_forwarded: Boolean = false,
    @SerialName("forwarded_from_message_id") val forwarded_from_message_id: String? = null,
    @SerialName("forwarded_from_user_id") val forwarded_from_user_id: String? = null,
    @SerialName("sender_name") val sender_name: String? = null,
    @SerialName("reply_to") val reply_to: Message? = null,
    @SerialName("created_at") val created_at: String? = null, // Make optional to handle missing/null
    @SerialName("delivered_at") val delivered_at: String? = null,
    @SerialName("updated_at") val updated_at: String? = null
) {
    // Helper for safe date access
    val safeCreatedAt: String
        get() = (created_at ?: "").replace(" ", "T")

    // Timeline sorting and media grouping read this value repeatedly. Message
    // fields are immutable, so a copy gets its own cache and wire JSON is unchanged.
    @kotlinx.serialization.Transient
    @Volatile
    private var cachedTimestampMillis: Long? = null

    val timestampMillis: Long
        get() {
            cachedTimestampMillis?.let { return it }
            val parsed = try {
                if (created_at == null) {
                    // STABLE FALLBACK: Use a deterministic time based on ID to avoid jumps
                    val hash = id.hashCode().toLong()
                    0L + abs(hash % 1000000) 
                } else {
                    parseMessageInstant(created_at)?.toEpochMilli() ?: 0L
                }
            } catch (e: Exception) {
                0L
            }
            cachedTimestampMillis = parsed
            return parsed
        }

    /**
     * Stable sort key using the full ISO string to preserve microsecond precision.
     * ISO strings are lexicographically sortable.
     */
    val sortKey: String
        get() = safeCreatedAt.replace(" ", "T")

    val isText: Boolean
        get() = content_type == "text"
        
    val isImage: Boolean
        get() = content_type == "image"
        
    val isVideo: Boolean
        get() = content_type == "video"
        
    val isAudio: Boolean
        get() = content_type == "audio"
        
    val isDocument: Boolean
        get() = content_type == "document"
}


