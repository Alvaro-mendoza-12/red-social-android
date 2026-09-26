package conectate.com.presentation.common

/** Screens which can render local content without the global network-blocking overlay. */
internal fun isOfflineAccessibleRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return true
    return route.substringBefore('/').substringBefore('?') in setOf(
        "home",
        "chat",
        "group_chat",
        "community_chat",
        "offline_videos",
        "fullscreen_video",
        "fullscreen_image",
    )
}
