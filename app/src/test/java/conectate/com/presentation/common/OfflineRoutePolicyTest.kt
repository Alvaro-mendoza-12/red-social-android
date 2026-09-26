package conectate.com.presentation.common

import org.junit.Assert.*
import org.junit.Test

class OfflineRoutePolicyTest {
    @Test fun `an enlarged saved chat avatar is not hidden by the network overlay`() {
        assertTrue(isOfflineAccessibleRoute("fullscreen_image/{imageUrl}"))
        assertTrue(isOfflineAccessibleRoute("fullscreen_image/https%3A%2F%2Fexample.invalid%2Favatar.webp"))
    }

    @Test fun `existing offline destinations and startup remain accessible`() {
        listOf(null, "", "home?startTab=messages", "chat/{chatId}", "group_chat/{groupId}",
            "community_chat/{communityId}", "offline_videos", "fullscreen_video/{videoUrl}",
        ).forEach { assertTrue(it, isOfflineAccessibleRoute(it)) }
    }

    @Test fun `network-only screens and unrelated prefix matches remain blocked`() {
        listOf("live_host/{id}", "live_viewer/{id}", "room_detail/{id}", "search", "home_not_cached",
            "fullscreen_image_upload", "offline_videos_admin",
        ).forEach { assertFalse(it, isOfflineAccessibleRoute(it)) }
    }
}
