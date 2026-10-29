package conectate.com.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.presentation.auth.GuestModeViewModel

@Composable
internal inline fun RequireLoggedIn(
    isLoggedIn: Boolean,
    navController: NavHostController,
    crossinline content: @Composable () -> Unit
) {
    if (isLoggedIn) {
        content()
    } else {
        val guestViewModel: GuestModeViewModel = hiltViewModel()
        LaunchedEffect(Unit) {
            val entry = navController.currentBackStackEntry
            if (entry?.destination?.route == Screen.RoomDetail.route) {
                entry.arguments?.getString("roomId")?.let { id ->
                    PendingVoiceRoomDestination.remember(id,
                        navController.getBackStackEntry(navController.graph.id).savedStateHandle)
                }
            }
            if (guestViewModel.hasSavedSession()) {
                val route = entry?.destination?.route
                val arguments = entry?.arguments
                when (route) {
                    Screen.UserProfile.route -> guestViewModel.rememberPendingPreview(
                        "profile", arguments?.getString("userId").orEmpty())
                    Screen.VideoPlayer.route, Screen.VideoDiscovery.route -> guestViewModel.rememberPendingPreview(
                        "video", arguments?.getString("videoId").orEmpty())
                    Screen.PostDetail.route -> guestViewModel.rememberPendingPreview(
                        "post", arguments?.getString("postId").orEmpty())
                    Screen.Home.route -> guestViewModel.rememberPendingPreview(
                        when (arguments?.getString("startTab")) {
                            Screen.Social.route -> "social"
                            Screen.Messages.route -> "messages"
                            else -> "videos"
                        })
                    Screen.Chat.route -> guestViewModel.rememberPendingPreview("enviar mensajes")
                    Screen.CreatePost.route -> guestViewModel.rememberPendingPreview("publicar")
                    Screen.CreateMoment.route -> guestViewModel.rememberPendingPreview("crear momentos")
                    Screen.VideoUpload.route -> guestViewModel.rememberPendingPreview("subir videos")
                    else -> guestViewModel.rememberPendingPreview("utilizar esta función")
                }
                navController.navigate(Screen.Guest.route) {
                    entry?.destination?.id?.let { popUpTo(it) { inclusive = true } }
                    launchSingleTop = true
                }
            } else navController.navigate(Screen.Login.route) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun AppNavGraph(
    navController: NavHostController,
    isInitializing: Boolean,
    isLoggedIn: Boolean,
    isSwitchingAccount: Boolean,
    hasPendingIntent: Boolean
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        addAuthRoutes(
            navController = navController,
            isInitializing = isInitializing,
            isLoggedIn = isLoggedIn,
            hasPendingIntent = hasPendingIntent
        )
        addMainRoutes(
            navController = navController,
            isLoggedIn = isLoggedIn || isSwitchingAccount
        )
    }
}
