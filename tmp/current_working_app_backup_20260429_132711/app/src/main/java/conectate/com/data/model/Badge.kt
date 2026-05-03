package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Badge that can be awarded to users
 * Types:
 * - follower: Automatic based on follower count
 * - manual: Manually assigned by admins
 * - admin: Special admin badge
 */
@Serializable
data class Badge(
    val id: String,
    val name: String,
    val description: String?,
    val icon: String, // emoji like 🌟, ⭐, etc.
    val color: String, // hex color like #FFD700
    val type: String, // follower, manual, admin, verified
    val threshold: Int? = null, // for follower badges: 10000, 50000, etc.
    @SerialName("is_admin_badge") val isAdminBadge: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null
)

/**
 * Junction table entry representing a user's badge
 */
@Serializable
data class UserBadge(
    @SerialName("user_id") val userId: String,
    @SerialName("badge_id") val badgeId: String,
    @SerialName("granted_by") val grantedBy: String?,
    @SerialName("granted_at") val grantedAt: String,
    val badge: Badge? = null // joined data when fetching
)
