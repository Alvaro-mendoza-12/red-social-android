package conectate.com.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun androidx.navigation.NavGraphBuilder.addMainRoutes(
    navController: NavHostController,
    isLoggedIn: Boolean
) {
    composable(Screen.SearchUsers.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.search.SearchUsersScreen(navController = navController)
        }
    }

    composable(Screen.SocialQrScanner.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.SocialQrScannerScreen(navController = navController)
        }
    }

    composable(
        route = Screen.Chat.route,
        arguments = listOf(
            navArgument("userId") { type = NavType.StringType },
            navArgument("initialMessage") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("focusMessageId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val initialMessage = backStackEntry.arguments?.getString("initialMessage")
        val focusMessageId = backStackEntry.arguments?.getString("focusMessageId")
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.ChatScreen(
                navController = navController,
                initialMessage = initialMessage,
                focusMessageId = focusMessageId
            )
        }
    }

    composable(Screen.CreateGroup.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.CreateGroupScreen(navController = navController)
        }
    }

    composable(Screen.CreateCommunity.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.CreateCommunityScreen(navController = navController)
        }
    }

    composable(Screen.CreateVoiceRoom.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.CreateVoiceRoomScreen(navController = navController)
        }
    }

    composable(Screen.VideoUpload.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.videos.VideoUploadScreen(navController = navController)
        }
    }

    composable(
        route = Screen.FollowList.route,
        arguments = listOf(
            navArgument("userId") { type = NavType.StringType },
            navArgument("type") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val userId = backStackEntry.arguments?.getString("userId") ?: ""
        val type = backStackEntry.arguments?.getString("type") ?: "followers"
        conectate.com.presentation.profile.FollowListScreen(
            navController = navController,
            userId = userId,
            type = type
        )
    }

    composable(
        route = Screen.CommunityChat.route,
        arguments = listOf(
            navArgument("communityId") { type = NavType.StringType },
            navArgument("focusMessageId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val communityId = backStackEntry.arguments?.getString("communityId") ?: ""
        val focusMessageId = backStackEntry.arguments?.getString("focusMessageId")
        conectate.com.presentation.messages.CommunityChatScreen(
            navController = navController,
            communityId = communityId,
            focusMessageId = focusMessageId
        )
    }

    composable(
        route = Screen.GroupChat.route,
        arguments = listOf(
            navArgument("groupId") { type = NavType.StringType },
            navArgument("focusMessageId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
        val focusMessageId = backStackEntry.arguments?.getString("focusMessageId")
        conectate.com.presentation.messages.GroupChatScreen(
            navController = navController,
            groupId = groupId,
            focusMessageId = focusMessageId
        )
    }

    composable(Screen.GroupDetail.route) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
        conectate.com.presentation.messages.GroupDetailScreen(
            groupId = groupId,
            navController = navController
        )
    }

    composable(Screen.CommunityDetail.route) { backStackEntry ->
        val communityId = backStackEntry.arguments?.getString("communityId") ?: ""
        conectate.com.presentation.messages.CommunityDetailScreen(
            communityId = communityId,
            navController = navController
        )
    }

    composable(
        route = Screen.RoomDetail.route,
        arguments = listOf(
            navArgument("roomId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.voicerooms.VoiceRoomActiveScreen(
                navController = navController,
                roomId = roomId
            )
        }
    }

    composable(Screen.UserProfile.route) { backStackEntry ->
        val userId = backStackEntry.arguments?.getString("userId") ?: ""
        conectate.com.presentation.profile.ProfileScreen(
            navController = navController,
            userId = userId,
            onLogout = {
                navController.navigate(Screen.Login.route) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                }
            }
        )
    }

    composable(Screen.AdminPanel.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.admin.AdminPanelScreen(navController = navController)
        }
    }

    composable(Screen.Settings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.SettingsScreen(
                navController = navController,
                mainNavController = navController,
                authViewModel = hiltViewModel(),
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                }
            )
        }
    }

    composable(Screen.AccountSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.AccountSettingsScreen(navController = navController)
        }
    }

    composable(Screen.SecuritySettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.SecuritySettingsScreen(navController = navController)
        }
    }

    composable(Screen.PrivacySettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.PrivacySettingsScreen(navController = navController)
        }
    }

    composable(Screen.NotificationSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.NotificationSettingsScreen(navController = navController)
        }
    }

    composable(Screen.ChatSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.ChatSettingsScreen(navController = navController)
        }
    }

    composable(Screen.StorageSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.StorageSettingsScreen(navController = navController)
        }
    }

    composable(Screen.LanguageSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.LanguageSettingsScreen(navController = navController)
        }
    }

    composable(Screen.ThemeSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.ThemeSettingsScreen(navController = navController)
        }
    }

    composable(Screen.HelpSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.HelpSettingsScreen(navController = navController)
        }
    }

    composable(Screen.AboutSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.AboutSettingsScreen(navController = navController)
        }
    }

    composable(Screen.CustomizeApp.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.CustomizeAppScreen(navController = navController)
        }
    }

    composable(Screen.CallHistory.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.calls.CallHistoryScreen(navController = navController)
        }
    }

    composable(Screen.VoiceRoomSearch.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.voicerooms.VoiceRoomSearchScreen(navController = navController)
        }
    }

    composable(Screen.MyReports.route) {
        android.util.Log.d("MainActivity", "ENTERING MyReports Route")
        conectate.com.presentation.profile.MyReportsScreen(navController = navController)
    }

    composable(
        route = Screen.FullScreenImage.route,
        arguments = listOf(
            navArgument("imageUrl") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val imageUrl = backStackEntry.arguments?.getString("imageUrl") ?: ""
        conectate.com.presentation.media.FullScreenImageScreen(
            imageUrl = imageUrl,
            onBack = { navController.popBackStack() }
        )
    }

    composable(
        route = Screen.FullScreenVideo.route,
        arguments = listOf(
            navArgument("videoUrl") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val videoUrl = backStackEntry.arguments?.getString("videoUrl") ?: ""
        conectate.com.presentation.media.FullScreenVideoScreen(
            videoUrl = videoUrl,
            onBack = { navController.popBackStack() }
        )
    }

    composable(
        route = Screen.VideoPlayer.route,
        arguments = listOf(
            navArgument("videoId") { type = NavType.StringType },
            navArgument("focusCommentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val focusCommentId = backStackEntry.arguments?.getString("focusCommentId")
        conectate.com.presentation.videos.VideoPlayerScreen(
            navController = navController,
            focusCommentId = focusCommentId
        )
    }

    composable(Screen.Notifications.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.NotificationScreen(navController = navController)
        }
    }

    composable(Screen.Ranking.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.ranking.RankingScreen(navController = navController)
        }
    }

    composable(Screen.FriendRequests.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.FriendRequestsScreen(navController = navController)
        }
    }

    composable(Screen.MonetizationCenter.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.MonetizationCenterScreen(navController = navController)
        }
    }

    composable(Screen.CreatorProgram.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.CreatorProgramScreen(navController = navController)
        }
    }

    composable(
        route = Screen.PostDetail.route,
        arguments = listOf(
            navArgument("postId") { type = NavType.StringType },
            navArgument("focusCommentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val postId = backStackEntry.arguments?.getString("postId") ?: ""
        val focusCommentId = backStackEntry.arguments?.getString("focusCommentId")
        conectate.com.presentation.posts.PostDetailScreen(
            navController = navController,
            postId = postId,
            focusCommentId = focusCommentId
        )
    }

    composable(Screen.CreateMoment.route) {
        conectate.com.presentation.social.CreateMomentScreen(navController = navController)
    }

    composable(
        route = Screen.MomentViewer.route,
        arguments = listOf(
            navArgument("momentId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val momentId = backStackEntry.arguments?.getString("momentId") ?: ""
        conectate.com.presentation.social.MomentViewerScreen(
            navController = navController,
            momentId = momentId
        )
    }

    composable(
        route = Screen.MomentHighlights.route,
        arguments = listOf(
            navArgument("userId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val userId = backStackEntry.arguments?.getString("userId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.MomentHighlightsScreen(
                navController = navController,
                userId = userId
            )
        }
    }

    composable(
        route = Screen.LiveViewer.route,
        arguments = listOf(
            navArgument("streamId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val streamId = backStackEntry.arguments?.getString("streamId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveViewerScreen(
                navController = navController,
                liveId = streamId
            )
        }
    }

    composable(
        route = Screen.LiveHost.route,
        arguments = listOf(
            navArgument("liveId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val liveId = backStackEntry.arguments?.getString("liveId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveHostScreen(
                navController = navController,
                liveId = liveId
            )
        }
    }

    composable(
        route = Screen.Call.route,
        arguments = listOf(
            navArgument("callId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val callId = backStackEntry.arguments?.getString("callId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.calls.CallScreen(
                navController = navController,
                callId = callId
            )
        }
    }

    composable(
        route = Screen.VideoEditor.route,
        arguments = listOf(
            navArgument("videoUri") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val videoUriString = backStackEntry.arguments?.getString("videoUri") ?: ""
        val videoUri = Uri.parse(videoUriString)
        conectate.com.presentation.videos.VideoEditorScreen(
            navController = navController,
            videoUri = videoUri
        )
    }
}
