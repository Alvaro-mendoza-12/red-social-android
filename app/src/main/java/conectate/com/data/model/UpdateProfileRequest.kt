package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val display_name: String? = null,
    val bio: String? = null,
    val country: String? = null,
    val city: String? = null,
    val avatar_url: String? = null,
    val cover_url: String? = null,
    val last_name_change: String? = null,
    val balance: Double? = null
)

