package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class Report(
    val id: String? = null, // Database generates this
    val reporter_id: String? = null,
    val reporter_name: String? = null,
    val reported_user_id: String? = null,
    val reported_user_name: String? = null,
    val reason: String? = null,
    val description: String? = null,
    val evidence_url: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_id") val contentId: String? = null,
    val status: String? = "pendiente",
    val created_at: String? = null,
    val updated_at: String? = null
)
