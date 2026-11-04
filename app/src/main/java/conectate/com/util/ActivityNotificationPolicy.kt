package conectate.com.util

internal object ActivityNotificationPolicy {
    val socialTypes = setOf(
        "like", "like_post", "like_video", "like_moment", "follow", "friend_request", "friend_request_accepted",
        "new_moment", "new_post", "new_video", "comment", "comment_post", "reply_post", "comment_video", "reply_video",
        "mention", "mention_post", "mention_video", "mention_video_description", "share", "share_post", "share_video",
        "admin_warning_video", "challenge_invite", "voice_invite", "voice_access_request", "birthday", "gaming_plan",
    )

    fun normalizedType(type: String): String = type.trim().lowercase().let { if (it == "new_live") "live_started" else it }

    fun allowed(type: String, socialEnabled: Boolean, liveEnabled: Boolean): Boolean {
        val normalized = normalizedType(type)
        val live = normalized.startsWith("live_") || normalized in setOf("stream_reminder", "stream_soon", "voice_invite")
        if (live && !liveEnabled) return false
        return normalized !in socialTypes || socialEnabled
    }
}
