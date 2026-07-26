package conectate.com.data.model

data class PostReactionUser(
    val userId: String,
    val userName: String,
    val userAvatarUrl: String? = null,
    val isVerified: Boolean = false,
    val reactionType: String,
    val reactedAt: String? = null
)
