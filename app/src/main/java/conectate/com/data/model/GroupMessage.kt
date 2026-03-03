package conectate.com.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class GroupMessage(
    val id: String = UUID.randomUUID().toString(),
    val group_id: String = "",
    val sender_id: String = "",
    val content: String = "",
    val content_type: String = "text",
    val media_url: String? = null,
    val poll_id: String? = null,
    val created_at: String = "",
    val status: String = "sent",
    val delivered_at: String? = null,
    val read_at: String? = null,
    val sender_name: String? = null,
    val sender_image: String? = null,
    val reply_to_id: String? = null,
    val is_edited: Boolean = false,
    val deleted_for_all: Boolean = false,
    val deleted_by: List<String> = emptyList(),
    val reactions: Map<String, List<String>> = emptyMap(),
    val is_forwarded: Boolean = false,
    val forwarded_from_message_id: String? = null,
    val forwarded_from_user_id: String? = null,
    @kotlinx.serialization.SerialName("reply_to") val reply_to: GroupMessage? = null
) {
    val timestampMillis: Long
        get() {
            return try {
                if (created_at.isBlank()) System.currentTimeMillis()
                else {
                    val cleaned = created_at.trim()
                        .replace(" ", "T")
                        .let { if (!it.contains("Z") && !it.contains("+") && it.length > 10) "${it}Z" else it }
                    
                    try {
                        java.time.Instant.parse(cleaned).toEpochMilli()
                    } catch (e1: Exception) {
                        try {
                            java.time.OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
                        } catch (e2: Exception) {
                            try {
                                java.time.LocalDateTime.parse(cleaned.substringBefore("Z")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                            } catch (e3: Exception) {
                                try { cleaned.toLong() } catch (e4: Exception) { System.currentTimeMillis() }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }

    val sortKey: String
        get() = created_at.trim().replace(" ", "T")
}
