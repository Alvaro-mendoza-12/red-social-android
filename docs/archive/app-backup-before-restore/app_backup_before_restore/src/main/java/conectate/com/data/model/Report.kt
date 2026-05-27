package conectate.com.data.model

import kotlinx.serialization.Serializable
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
    val status: String? = "pendiente",
    val created_at: String? = null
)
