package conectate.com.presentation.navigation

import androidx.compose.runtime.Composable

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
    addRaffleRoutes(navController, isLoggedIn)
    composable(Screen.GamePlan.route,arguments = listOf(navArgument("planId") { type = NavType.StringType })) { entry ->
        RequireLoggedIn(isLoggedIn,navController) {
            val planId = entry.arguments?.getString("planId").orEmpty()
            if(conectate.com.presentation.purpose.purposeUuid(planId)) {
                conectate.com.presentation.purpose.PurposeExperienceDialog(
                    conectate.com.presentation.purpose.PurposeTarget("plan",planId),onDismiss = { navController.popBackStack() },
                    onSource = { source -> if(source.kind == "player") navController.navigate(Screen.UserProfile.createRoute(source.id)) { launchSingleTop = true }
                        else conectate.com.presentation.common.AppFeedbackBus.show("Ese contenido ya no está disponible.") },
                )
            } else LaunchedEffect(planId) { conectate.com.presentation.common.AppFeedbackBus.show("Esa propuesta ya no está disponible."); navController.popBackStack() }
        }
    }
    composable(Screen.Surveys.route, arguments = listOf(
        navArgument("id") { type = NavType.StringType; defaultValue = "" },
        navArgument("key") { type = NavType.StringType; defaultValue = "" },
        navArgument("mode") { type = NavType.StringType; defaultValue = "" }
    )) { entry ->
        val id = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() }
        val key = entry.arguments?.getString("key")?.takeIf { it.isNotBlank() }
        val mode = entry.arguments?.getString("mode")?.takeIf { it.isNotBlank() }
        if (!isLoggedIn) {
            LaunchedEffect(id, key, mode) {
                conectate.com.presentation.surveys.PendingSurveyDestination.remember(Screen.Surveys.createRoute(id, key, mode), entry.savedStateHandle)
                navController.navigate(Screen.Login.route) { launchSingleTop = true }
            }
        } else conectate.com.presentation.surveys.SurveysScreen(id, key, mode, onBack = { navController.popBackStack() })
    }
    composable(Screen.SearchUsers.route + "?query={query}", arguments = listOf(
        navArgument("query") { type = NavType.StringType; defaultValue = "" }
    )) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.search.SearchUsersScreen(navController = navController)
        }
    }

    composable(
        route = Screen.Chat.route,
        arguments = listOf(
            navArgument("userId") { type = NavType.StringType },
            navArgument("birthdayGreeting") { type = NavType.BoolType; defaultValue = false },
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
                birthdayGreeting = backStackEntry.arguments?.getBoolean("birthdayGreeting") ?: false,
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

    composable(Screen.OfflineVideos.route) {
        conectate.com.presentation.videos.OfflineVideosScreen(navController = navController)
    }

    composable(Screen.CreatePost.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.social.CreatePostScreen(navController = navController)
        }
    }

    // Social vive dentro del HomeScreen, pero recibe el NavController raíz para
    // poder abrir perfiles, detalles y chats. Por eso el feed recomendado debe
    // estar registrado en este mismo grafo raíz y no únicamente en el NavHost
    // anidado de las pestañas.
    composable(
        route = Screen.VideoDiscovery.route,
        arguments = listOf(navArgument("videoId") { type = NavType.StringType })
    ) { backStackEntry ->
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.video.VideoFeedScreen(
                navController = navController,
                initialVideoId = backStackEntry.arguments?.getString("videoId"),
                onNavigateToUpload = { sound ->
                    conectate.com.presentation.video.savePendingVideoSound(navController, sound)
                    navController.navigate(Screen.VideoUpload.route)
                },
                onNavigateToSearch = { navController.navigate(Screen.SearchUsers.route) },
                onNavigateToProfile = { userId ->
                    navController.navigate(Screen.UserProfile.createRoute(userId))
                },
                onNavigateToChat = { route -> navController.navigate(route) },
                onNavigateToLiveSetup = {
                    navController.navigate(Screen.LiveSetup.route) { launchSingleTop = true }
                },
                onNavigateToLiveDiscovery = {
                    navController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                },
                onNavigateToLiveViewer = { streamId ->
                    navController.navigate(Screen.LiveViewer.createRoute(streamId))
                }
            )
        }
    }

    composable(
        route = Screen.ProfileVideoFeed.route,
        arguments = listOf(
            navArgument("profileId") { type = NavType.StringType },
            navArgument("videoId") { type = NavType.StringType },
            navArgument("initialIndex") {
                type = NavType.IntType
                defaultValue = 0
            }
        )
    ) { backStackEntry ->
        val videoId = backStackEntry.arguments?.getString("videoId")
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.video.VideoFeedScreen(
                navController = navController,
                initialVideoId = videoId,
                onNavigateToUpload = { sound ->
                    conectate.com.presentation.video.savePendingVideoSound(navController, sound)
                    navController.navigate(Screen.VideoUpload.route)
                },
                onNavigateToSearch = { navController.navigate(Screen.SearchUsers.route) },
                onNavigateToProfile = { userId ->
                    navController.navigate(Screen.UserProfile.createRoute(userId))
                },
                onNavigateToChat = { route -> navController.navigate(route) },
                onNavigateToLiveSetup = {
                    navController.navigate(Screen.LiveSetup.route) { launchSingleTop = true }
                },
                onNavigateToLiveDiscovery = {
                    navController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                },
                onNavigateToLiveViewer = { streamId ->
                    navController.navigate(Screen.LiveViewer.createRoute(streamId))
                }
            )
        }
    }

    composable(Screen.Proximity.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.proximity.ProximityScreen(navController = navController)
        }
    }

    composable(Screen.LiveSetup.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveSetupScreen(navController = navController)
        }
    }

    composable(Screen.LiveDiscovery.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveDiscoveryScreen(navController = navController)
        }
    }

    composable(
        route = Screen.LiveHost.route,
        arguments = listOf(navArgument("liveId") { type = NavType.StringType })
    ) { backStackEntry ->
        val liveId = backStackEntry.arguments?.getString("liveId") ?: return@composable
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveHostScreen(
                navController = navController,
                liveId = liveId
            )
        }
    }

    composable(
        route = Screen.LiveViewer.route,
        arguments = listOf(navArgument("streamId") { type = NavType.StringType })
    ) { backStackEntry ->
        val streamId = backStackEntry.arguments?.getString("streamId") ?: return@composable
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveViewerScreen(
                navController = navController,
                liveId = streamId
            )
        }
    }

    composable(
        route = Screen.LiveReplay.route,
        arguments = listOf(navArgument("liveId") { type = NavType.StringType })
    ) { backStackEntry ->
        val liveId = backStackEntry.arguments?.getString("liveId") ?: return@composable
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.live.LiveReplayScreen(
                navController = navController,
                liveId = liveId
            )
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
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.FollowListScreen(
                navController = navController,
                userId = userId,
                type = type
            )
        }
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
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.CommunityChatScreen(
                navController = navController,
                communityId = communityId,
                focusMessageId = focusMessageId
            )
        }
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
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.GroupChatScreen(
                navController = navController,
                groupId = groupId,
                focusMessageId = focusMessageId
            )
        }
    }

    composable(
        route = Screen.GroupDetail.route,
        arguments = listOf(
            navArgument("groupId") { type = NavType.StringType },
            navArgument("invite") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
        val invite = backStackEntry.arguments?.getString("invite")
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.GroupDetailScreen(
                groupId = groupId,
                inviteToken = invite,
                navController = navController
            )
        }
    }

    composable(Screen.CommunityDetail.route) { backStackEntry ->
        val communityId = backStackEntry.arguments?.getString("communityId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.CommunityDetailScreen(
                communityId = communityId,
                navController = navController
            )
        }
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
        RequireLoggedIn(isLoggedIn, navController) {
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

    composable(Screen.SettingsCategory.route) { backStackEntry ->
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.SettingsCategoryScreen(
                navController = navController,
                category = backStackEntry.arguments?.getString("category").orEmpty(),
            )
        }
    }

    composable(Screen.CoinsAndPayments.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.wallet.CoinsAndPaymentsScreen(navController = navController)
        }
    }

    composable(Screen.CreatorEarnings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.wallet.CreatorEarningsScreen(navController = navController)
        }
    }

    composable(Screen.CreatorAnalytics.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.analytics.CreatorAnalyticsScreen(navController = navController)
        }
    }

    composable(Screen.Novvin.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.messages.NovvinChatScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }

    composable(Screen.EditProfile.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.EditProfileScreen(navController = navController)
        }
    }

    composable(Screen.AccountSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.EditProfileScreen(navController = navController)
        }
    }

    composable(Screen.ChatSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.ChatSettingsScreen(navController = navController)
        }
    }

    composable(Screen.CustomizeApp.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.CustomizeAppScreen(navController = navController)
        }
    }

    // Settings is reachable through both the root graph and the Home tabs.
    composable(Screen.LanguageSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.LanguageSettingsScreen(navController = navController)
        }
    }

    composable(Screen.HelpSettings.route) {
        conectate.com.presentation.legal.HelpCenterScreen(navController = navController)
    }

    composable(Screen.TermsAndConditions.route) {
        conectate.com.presentation.legal.TermsAndConditionsScreen(navController = navController)
    }

    composable(Screen.MyReports.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.MyReportsScreen(navController = navController)
        }
    }

    composable(Screen.AdminPanel.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.AdminPanelScreen(navController = navController)
        }
    }

    composable(
        route = Screen.NotificationAccountBridge.route,
        arguments = listOf(
            navArgument("destination") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("targetAccountId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("targetAccountLabel") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("currentAccountLabel") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("contextTitle") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("preview") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("targetUserId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("targetEmail") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val destination = backStackEntry.arguments?.getString("destination")
            ?.takeIf { it.isNotBlank() }
            ?: Screen.Home.createRoute(startTab = Screen.Messages.route)
        conectate.com.presentation.common.NotificationAccountBridgeScreen(
            navController = navController,
            bridgeEntry = backStackEntry,
            destinationRoute = destination,
            targetAccountId = backStackEntry.arguments?.getString("targetAccountId"),
            targetAccountLabel = backStackEntry.arguments?.getString("targetAccountLabel"),
            currentAccountLabel = backStackEntry.arguments?.getString("currentAccountLabel"),
            contextTitle = backStackEntry.arguments?.getString("contextTitle"),
            preview = backStackEntry.arguments?.getString("preview"),
            targetUserId = backStackEntry.arguments?.getString("targetUserId"),
            targetEmail = backStackEntry.arguments?.getString("targetEmail"),
        )
    }

    composable(
        route = Screen.FullScreenImage.route,
        arguments = listOf(
            navArgument("imageUrl") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val imageUrl = backStackEntry.arguments?.getString("imageUrl") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.media.FullScreenImageScreen(
                imageUrl = imageUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }

    composable(
        route = Screen.FullScreenVideo.route,
        arguments = listOf(
            navArgument("videoUrl") { type = NavType.StringType },
            navArgument("loop") { type = NavType.BoolType; defaultValue = true },
        )
    ) { backStackEntry ->
        val videoUrl = backStackEntry.arguments?.getString("videoUrl") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.media.FullScreenVideoScreen(
                videoUrl = videoUrl,
                loopVideo = backStackEntry.arguments?.getBoolean("loop") ?: true,
                onBack = { navController.popBackStack() }
            )
        }
    }

    composable(Screen.VideoRoutes.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.video.routes.VideoRoutesScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { videoId ->
                    navController.navigate(Screen.VideoRoutePlayer.createRoute(videoId)) { launchSingleTop = true }
                },
            )
        }
    }

    composable(
        route = Screen.VideoRoutePlayer.route,
        arguments = listOf(
            navArgument("videoId") { type = NavType.StringType },
            navArgument("singleVideoOnly") { type = NavType.BoolType; defaultValue = true },
        ),
    ) { entry ->
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.video.VideoDetailFeedScreen(
                navController = navController,
                videoId = entry.arguments?.getString("videoId") ?: return@RequireLoggedIn,
            )
        }
    }

    composable(
        route = Screen.VideoPlayer.route,
        arguments = listOf(
            navArgument("videoId") { type = NavType.StringType },
            navArgument("focusCommentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("resumeMs") {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument("endMs") {
                type = NavType.LongType
                defaultValue = 0L
            },
        )
    ) { backStackEntry ->
        val focusCommentId = backStackEntry.arguments?.getString("focusCommentId")
        val resumeMs = backStackEntry.arguments?.getLong("resumeMs") ?: 0L
        val endMs = backStackEntry.arguments?.getLong("endMs") ?: 0L
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.video.VideoDetailFeedScreen(
                navController = navController,
                videoId = backStackEntry.arguments?.getString("videoId") ?: return@RequireLoggedIn,
                focusCommentId = focusCommentId,
                initialPositionMs = resumeMs,
                initialClipEndMs = endMs,
            )
        }
    }

    composable(Screen.Notifications.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.NotificationScreen(navController = navController)
        }
    }

    composable(Screen.NotificationSettings.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.NotificationSettingsScreen(navController = navController)
        }
    }

    composable(
        route = Screen.Call.route,
        arguments = listOf(
            navArgument("callId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val callId = backStackEntry.arguments?.getString("callId") ?: return@composable
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.calls.CallScreen(
                navController = navController,
                callId = callId
            )
        }
    }

    composable(Screen.Ranking.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.ranking.RankingScreen(navController = navController)
        }
    }

    composable(Screen.CallHistory.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.calls.CallHistoryScreen(navController = navController)
        }
    }

    composable(Screen.FriendRequests.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.profile.FriendRequestsScreen(navController = navController)
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
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.posts.PostDetailScreen(
                navController = navController,
                postId = postId,
                focusCommentId = focusCommentId
            )
        }
    }

    composable(Screen.CreateMoment.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.social.CreateMomentScreen(navController = navController)
        }
    }

    composable(
        route = Screen.MomentViewer.route,
        arguments = listOf(
            navArgument("momentId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val momentId = backStackEntry.arguments?.getString("momentId") ?: ""
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.social.MomentViewerScreen(
                navController = navController,
                momentId = momentId
            )
        }
    }

    composable(Screen.ContentSafety.route) {
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.settings.ContentSafetyScreen(navController = navController)
        }
    }

    composable(
        route = Screen.MomentHighlightViewer.route,
        arguments = listOf(
            navArgument("highlightId") { type = NavType.StringType },
            navArgument("title") {
                type = NavType.StringType
                defaultValue = "Momentos destacados"
            }
        )
    ) { backStackEntry ->
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.social.MomentHighlightViewerScreen(
                navController = navController,
                highlightId = backStackEntry.arguments?.getString("highlightId").orEmpty(),
                title = backStackEntry.arguments?.getString("title").orEmpty()
            )
        }
    }

    composable(
        route = Screen.VideoEditor.route,
        arguments = listOf(
            navArgument("videoUri") { type = NavType.StringType },
            navArgument("soundUri") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    ) { backStackEntry ->
        val videoUriString = backStackEntry.arguments?.getString("videoUri") ?: ""
        val videoUri = Uri.parse(videoUriString)
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.videos.VideoEditorScreen(
                navController = navController,
                videoUri = videoUri,
                initialSoundUri = backStackEntry.arguments?.getString("soundUri")?.let(Uri::parse),
            )
        }
    }
}
