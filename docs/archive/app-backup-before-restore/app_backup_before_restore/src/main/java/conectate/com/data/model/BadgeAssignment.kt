package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BadgeAssignment(
    val id: String,
    val user_id: String,
    val badge_id: String,
    val granted_at: String? = null,
    val granted_by: String? = null,
    val user: User? = null,
    val badge: Badge? = null
)
