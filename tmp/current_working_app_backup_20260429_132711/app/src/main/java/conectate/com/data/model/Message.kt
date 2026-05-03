package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.abs

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val chat_id: String = "",
    val sender_id: String = "",
    val content: String = "",
    @SerialName("content_type") val content_type: String = "text",
    @SerialName("media_url") val media_url: String? = null,
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

    val timestampMillis: Long
        get() {
            return try {
                if (created_at == null) {
                    // STABLE FALLBACK: Use a deterministic time based on ID to avoid jumps
                    val hash = id.hashCode().toLong()
                    0L + abs(hash % 1000000) 
                } else {
                    // Robust parsing for various Postgres/ISO formats
                    val cleaned = created_at.trim()
                        .replace(" ", "T") // Normalize space to T
                        .let { if (!it.contains("Z") && !it.contains("+") && it.length > 10) "${it}Z" else it } 
                    
                    try {
                        java.time.Instant.parse(cleaned).toEpochMilli()
                    } catch (e1: Exception) {
                        try {
                            java.time.OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
                        } catch (e2: Exception) {
                            try {
                                val formatter = java.time.format.DateTimeFormatterBuilder()
                                    .append(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                    .appendLiteral('T')
                                    .append(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
                                    .optionalStart()
                                    .appendOffsetId()
                                    .toFormatter()
                                java.time.LocalDateTime.parse(cleaned.substringBefore("Z"), formatter)
                                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                            } catch (e3: Exception) {
                                try {
                                    cleaned.toLong()
                                } catch (e4: Exception) {
                                    0L // Epoch 0 is better than "now" for consistency
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                0L
            }
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


