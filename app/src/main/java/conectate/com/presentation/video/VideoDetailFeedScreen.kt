package conectate.com.presentation.video

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

/** All video links share the same full-screen player and actions as the Videos feed. */
@Composable
fun VideoDetailFeedScreen(
    navController: NavController,
    videoId: String,
    focusCommentId: String? = null,
    initialPositionMs: Long = 0L,
    initialClipEndMs: Long = 0L,
) {
    VideoFeedScreen(
        navController = navController,
        initialVideoId = videoId,
        initialPositionMs = initialPositionMs,
        initialClipEndMs = initialClipEndMs,
        focusCommentId = focusCommentId,
        showBackButton = true,
        onNavigateToUpload = { sound ->
            savePendingVideoSound(navController, sound)
            navController.navigate(Screen.VideoUpload.route)
        },
        onNavigateToSearch = { navController.navigate(Screen.SearchUsers.route) },
        onNavigateToProfile = { navController.navigate(Screen.UserProfile.createRoute(it)) },
        onNavigateToChat = { navController.navigate(it) },
        onNavigateToVideoRoutes = {
            navController.navigate(Screen.VideoRoutes.route) { launchSingleTop = true }
        },
        onNavigateToLiveSetup = { navController.navigate(Screen.LiveSetup.route) },
        onNavigateToLiveDiscovery = { navController.navigate(Screen.LiveDiscovery.route) },
        onNavigateToLiveViewer = { navController.navigate(Screen.LiveViewer.createRoute(it)) },
    )
}
