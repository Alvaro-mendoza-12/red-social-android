package conectate.com.presentation.home

import conectate.com.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.messages.ConversationsScreen
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.profile.ProfileScreen
import conectate.com.presentation.video.VideoFeedScreen
import conectate.com.presentation.gaming.PlayMatchColors

sealed class BottomNavItem(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Messages : BottomNavItem(Screen.Messages.route, "Mensajes", Icons.AutoMirrored.Filled.Message)
    object Videos : BottomNavItem(Screen.Videos.route, "Videos", Icons.Default.VideoLibrary)
    object Social : BottomNavItem(Screen.Social.route, "Social", Icons.Default.Public)
    object Games : BottomNavItem(Screen.Games.route, "Juegos", Icons.Default.SportsEsports)
    object Profile : BottomNavItem(Screen.Profile.route, "Perfil", Icons.Default.Person)
}

internal fun novvinBottomNavItems(): List<BottomNavItem> = listOf(
    BottomNavItem.Messages,
    BottomNavItem.Videos,
    BottomNavItem.Social,
    BottomNavItem.Games,
    BottomNavItem.Profile,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mainNavController: NavController,
    homeBackStackEntry: androidx.navigation.NavBackStackEntry,
    authViewModel: AuthViewModel = conectate.com.core.observability.startupViewModel("home auth model"),
    homeViewModel: HomeViewModel = conectate.com.core.observability.startupViewModel("home model"),
    startTab: String? = null,
    sharedText: String? = null,
    sharedUri: String? = null,
    socialPage: Int = 0,
) {
    val navController = rememberNavController()
    val currentUserAvatarUrl by homeViewModel.currentUserAvatarUrl.collectAsStateWithLifecycle()
    // A tap on Videos is an explicit request for a new recommendation session.
    // Keep this ephemeral so recreating the shell after leaving the app also
    // starts cleanly instead of restoring the previous feed order.
    var videoFeedRefreshToken by remember { mutableLongStateOf(0L) }

    val items = remember { novvinBottomNavItems() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isVideoFeed = currentDestination?.hierarchy?.any {
        it.route == Screen.Videos.route || it.route == Screen.VideoDiscovery.route ||
            it.route == Screen.ProfileVideoFeed.route
    } == true
    val showBottomNavigation = currentDestination == null || items.any { it.route == currentDestination.route }
    val homeEntry = homeBackStackEntry
    val pendingTab by homeEntry.savedStateHandle.getStateFlow<String?>("pending_home_tab", null).collectAsStateWithLifecycle()
    LaunchedEffect(pendingTab) {
        val target = pendingTab?.takeIf { tab -> items.any { it.route == tab } } ?: return@LaunchedEffect
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        homeEntry.savedStateHandle["pending_home_tab"] = null
    }
    LaunchedEffect(startTab) {
        val target = startTab?.takeIf { tab -> items.any { it.route == tab } } ?: return@LaunchedEffect
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val isPlayMatch = currentDestination?.hierarchy?.any { destination ->
        destination.route == Screen.Games.route || destination.route?.startsWith("games_") == true
    } == true
    val unreadBadge by homeViewModel.totalUnreadConversations.collectAsStateWithLifecycle()
    val newFollowingVideos by homeViewModel.newFollowingVideos.collectAsStateWithLifecycle()
    val newSocialContent by homeViewModel.newSocialContent.collectAsStateWithLifecycle()
    val newGames by homeViewModel.newGames.collectAsStateWithLifecycle()
    val unreadProfileNotifications by homeViewModel.unreadProfileNotifications.collectAsStateWithLifecycle()

    val socialSectionVisible = currentDestination?.hierarchy?.any { it.route == BottomNavItem.Social.route } == true
    DisposableEffect(socialSectionVisible, homeViewModel, conectate.com.presentation.components.LocalProfileIdentityAccount.current) {
        homeViewModel.setSocialSectionVisible(socialSectionVisible)
        onDispose { if (socialSectionVisible) homeViewModel.setSocialSectionVisible(false) }
    }

    LifecycleStartEffect(homeViewModel) {
        homeViewModel.setForeground(true)
        onStopOrDispose { homeViewModel.setForeground(false) }
    }

    Scaffold(
        modifier = Modifier.testTag("main-home"),
        bottomBar = {
            if (showBottomNavigation) NavigationBar(
                containerColor = if (isPlayMatch) PlayMatchColors.Surface else MaterialTheme.colorScheme.surface,
                // Tonal elevation mixed the red accent with the dark surface and produced brown.
                tonalElevation = 0.dp,
                // Material3 conserva el inset inferior del sistema. No se fija un máximo:
                // en navegación de tres botones la barra puede crecer sin recortar controles.
                modifier = Modifier.heightIn(min = 64.dp),
            ) {
                items.forEach { item ->
                    val localizedTitle = stringResource(when (item) {
                        BottomNavItem.Messages -> R.string.audit_nav_messages
                        BottomNavItem.Videos -> R.string.audit_nav_videos
                        BottomNavItem.Social -> R.string.audit_nav_social
                        BottomNavItem.Games -> R.string.audit_nav_games
                        BottomNavItem.Profile -> R.string.audit_nav_profile
                    })
                    val hasNews = when (item) {
                        BottomNavItem.Messages -> unreadBadge > 0
                        BottomNavItem.Videos -> newFollowingVideos
                        BottomNavItem.Social -> newSocialContent
                        BottomNavItem.Games -> newGames
                        BottomNavItem.Profile -> unreadProfileNotifications > 0
                    }
                    val newsDescription = stringResource(if (hasNews) R.string.audit_news else R.string.audit_no_news)
                    NavigationBarItem(
                        modifier = Modifier.testTag("main-tab-${item.route}").semantics {
                            stateDescription = newsDescription
                        },
                        icon = { 
                            BadgedBox(
                                badge = {
                                    if (item is BottomNavItem.Messages && unreadBadge > 0) {
                                        Badge(
                                            containerColor = Color(0xFF1565C0), // White numerals retain readable contrast.
                                            contentColor = Color.White,
                                            modifier = Modifier.offset(x = 6.dp, y = 0.dp)
                                        ) {
                                            Text(
                                                text = if (unreadBadge > 9) "9+" else unreadBadge.toString(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else if (hasNews) {
                                        Badge(containerColor = Color(0xFFE53935))
                                    }
                                }
                            ) {
                                if (item is BottomNavItem.Profile) {
                                    CircularProfileImage(
                                        ownerId = conectate.com.presentation.components.LocalProfileIdentityAccount.current,
                                        openMomentOnClick = false,
                                        showMomentRing = false,
                                        imageUrl = currentUserAvatarUrl,
                                        size = 24.dp,
                                        showBorder = true,
                                        borderColor = if (currentDestination?.hierarchy?.any { it.route == item.route } == true) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        },
                                        borderWidth = 1.5.dp
                                    )
                                } else {
                                    Icon(item.icon, contentDescription = localizedTitle)
                                }
                            }
                        },
                        label = { Text(localizedTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = currentDestination?.hierarchy?.any { destination ->
                            destination.route == item.route || (
                                item is BottomNavItem.Games && destination.route?.startsWith("games_") == true
                            )
                        } == true,
                        colors = if (isPlayMatch) NavigationBarItemDefaults.colors(
                            selectedIconColor = PlayMatchColors.Accent,
                            selectedTextColor = PlayMatchColors.Accent,
                            indicatorColor = PlayMatchColors.Card,
                            unselectedIconColor = PlayMatchColors.SecondaryText,
                            unselectedTextColor = PlayMatchColors.SecondaryText,
                        ) else NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        ),
                        onClick = {
                            if (item is BottomNavItem.Videos) {
                                videoFeedRefreshToken += 1L
                            }
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startTab ?: Screen.Messages.route,
            modifier = Modifier.padding(
                bottom = if (isVideoFeed) 0.dp else padding.calculateBottomPadding()
            )
        ) {
            composable(Screen.Messages.route) {
                ConversationsScreen(
                    // Inbox actions open destinations in the application's root graph.
                    navController = mainNavController,
                    sharedStickerUri = sharedUri.takeIf { startTab == Screen.Messages.route },
                    onNavigateToChat = { userId ->
                        mainNavController.navigate(Screen.Chat.createRoute(userId))
                    },
                    onNavigateToSearch = {
                        mainNavController.navigate(Screen.SearchUsers.route)
                    },
                    onNavigateToCallHistory = {
                        mainNavController.navigate(Screen.CallHistory.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToCreateGroup = {
                        mainNavController.navigate(Screen.CreateGroup.route)
                    },
                    onNavigateToCreateCommunity = {
                        mainNavController.navigate(Screen.CreateCommunity.route)
                    },
                    onNavigateToCreateVoiceRoom = {
                        mainNavController.navigate(Screen.CreateVoiceRoom.route)
                    },
                    onNavigateToNovvin = {
                        mainNavController.navigate(Screen.Novvin.route)
                    },
                    onNavigateToGroupChat = { groupId ->
                        mainNavController.navigate(Screen.GroupChat.createRoute(groupId))
                    },
                    onNavigateToCommunityChat = { communityId ->
                        mainNavController.navigate(Screen.CommunityChat.createRoute(communityId))
                    },
                    onNavigateToRoomDetail = { roomId ->
                        mainNavController.navigate(Screen.RoomDetail.createRoute(roomId))
                    }
                )
            }
            
            composable(Screen.Videos.route) {
                conectate.com.presentation.video.VideoFeedScreen(
                    navController = navController,
                    feedRefreshToken = videoFeedRefreshToken,
                    onFollowingFeedVisible = { videos -> homeViewModel.markFeedSeen("videos", videos.map { it.created_at }) },
                    onNavigateToUpload = { sound ->
                        conectate.com.presentation.video.savePendingVideoSound(mainNavController, sound)
                        mainNavController.navigate(Screen.VideoUpload.route)
                    },
                    onNavigateToSearch = {
                        mainNavController.navigate(Screen.SearchUsers.route)
                    },
                    onNavigateToProfile = { userId ->
                        mainNavController.navigate(Screen.UserProfile.createRoute(userId))
                    },
                    onNavigateToChat = { route ->
                        mainNavController.navigate(route)
                    },
                    // Los directos (LiveSetup/LiveDiscovery/LiveViewer) viven en el NavHost raíz, no
                    // en el grafo anidado de pestañas. Se navega con mainNavController para evitar el
                    // crash "destination cannot be found" al crear/ver directos desde la pestaña Videos.
                    onNavigateToLiveSetup = {
                        mainNavController.navigate(Screen.LiveSetup.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveDiscovery = {
                        mainNavController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveViewer = { streamId ->
                        mainNavController.navigate(Screen.LiveViewer.createRoute(streamId))
                    }
                )
            }

            composable(Screen.Social.route) {
                conectate.com.presentation.social.SocialScreen(
                    mainNavController,
                    sharedText = sharedText,
                    sharedUri = sharedUri,
                    initialPage = socialPage,
                    onFollowingFeedVisible = { posts -> homeViewModel.markFeedSeen("social", posts.map { it.createdAt }) },
                    onNavigateToHomeTab = { tab ->
                        navController.navigate(tab) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            
            composable(Screen.Profile.route) {
                ProfileScreen(
                    navController = navController,
                    mainNavController = mainNavController,
                    onLogout = {
                        mainNavController.navigate(Screen.Login.route) {
                            popUpTo(mainNavController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                )
            }
            
            composable(Screen.EditProfile.route) {
                conectate.com.presentation.profile.EditProfileScreen(navController = navController)
            }
            
            composable(Screen.Settings.route) {
                conectate.com.presentation.profile.SettingsScreen(
                    navController = navController,
                    mainNavController = mainNavController,
                    authViewModel = authViewModel,
                    onLogout = {
                        mainNavController.navigate(Screen.Login.route) {
                            popUpTo(mainNavController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.SettingsCategory.route) { backStackEntry ->
                conectate.com.presentation.settings.SettingsCategoryScreen(
                    navController = navController,
                    mainNavController = mainNavController,
                    category = backStackEntry.arguments?.getString("category").orEmpty(),
                )
            }

            composable(Screen.OfflineVideos.route) {
                conectate.com.presentation.videos.OfflineVideosScreen(navController = navController)
            }

            composable(Screen.Games.route) {
                conectate.com.presentation.gaming.GamesHomeScreen(
                    onOpenProfile = { navController.navigate(Screen.GamingProfile.route) { launchSingleTop = true } },
                    onOpenAddGame = { navController.navigate(Screen.AddGame.route) { launchSingleTop = true } },
                    onOpenPlayer = { playerId -> navController.navigate(Screen.PlayerDetail.createRoute(playerId)) },
                    onMatchFound = { sessionId -> navController.navigate(Screen.MatchFound.createRoute(sessionId)) { launchSingleTop = true } },
                    onOpenChat = { userId -> mainNavController.navigate(Screen.Chat.createRoute(userId)) },
                    onInboxVisible = homeViewModel::markGamesSeen,
                )
            }

            composable(Screen.GamingProfile.route) {
                conectate.com.presentation.gaming.GamingProfileScreen(
                    onBack = { navController.popBackStack() },
                    onAddGame = { navController.navigate(Screen.AddGame.route) { launchSingleTop = true } },
                )
            }

            composable(Screen.AddGame.route) {
                conectate.com.presentation.gaming.AddGameScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.PlayerDetail.route,
                arguments = listOf(androidx.navigation.navArgument("playerId") { type = androidx.navigation.NavType.StringType }),
            ) { entry ->
                conectate.com.presentation.gaming.PlayerDetailScreen(
                    playerId = entry.arguments?.getString("playerId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onMessage = { userId -> mainNavController.navigate(Screen.Chat.createRoute(userId)) },
                )
            }

            composable(
                route = Screen.MatchFound.route,
                arguments = listOf(androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.StringType }),
            ) { entry ->
                conectate.com.presentation.gaming.MatchFoundScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onMessage = { userId -> mainNavController.navigate(Screen.Chat.createRoute(userId)) },
                )
            }

            composable(Screen.CoinsAndPayments.route) {
                conectate.com.presentation.wallet.CoinsAndPaymentsScreen(
                    navController = navController,
                    mainNavController = mainNavController
                )
            }

            composable(
                route = Screen.VideoDiscovery.route,
                arguments = listOf(
                    androidx.navigation.navArgument("videoId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                conectate.com.presentation.video.VideoFeedScreen(
                    navController = navController,
                    initialVideoId = backStackEntry.arguments?.getString("videoId"),
                    onNavigateToUpload = { sound ->
                        conectate.com.presentation.video.savePendingVideoSound(mainNavController, sound)
                        mainNavController.navigate(Screen.VideoUpload.route)
                    },
                    onNavigateToSearch = { mainNavController.navigate(Screen.SearchUsers.route) },
                    onNavigateToProfile = { userId ->
                        mainNavController.navigate(Screen.UserProfile.createRoute(userId))
                    },
                    onNavigateToChat = { route -> mainNavController.navigate(route) },
                    onNavigateToLiveSetup = {
                        mainNavController.navigate(Screen.LiveSetup.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveDiscovery = {
                        mainNavController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveViewer = { streamId ->
                        mainNavController.navigate(Screen.LiveViewer.createRoute(streamId))
                    }
                )
            }

            composable(
                route = Screen.ProfileVideoFeed.route,
                arguments = listOf(
                    androidx.navigation.navArgument("profileId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("videoId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("initialIndex") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { backStackEntry ->
                conectate.com.presentation.video.VideoFeedScreen(
                    navController = navController,
                    initialVideoId = backStackEntry.arguments?.getString("videoId"),
                    onNavigateToUpload = { sound ->
                        conectate.com.presentation.video.savePendingVideoSound(mainNavController, sound)
                        mainNavController.navigate(Screen.VideoUpload.route)
                    },
                    onNavigateToSearch = { mainNavController.navigate(Screen.SearchUsers.route) },
                    onNavigateToProfile = { userId ->
                        mainNavController.navigate(Screen.UserProfile.createRoute(userId))
                    },
                    onNavigateToChat = { route -> mainNavController.navigate(route) },
                    onNavigateToLiveSetup = {
                        mainNavController.navigate(Screen.LiveSetup.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveDiscovery = {
                        mainNavController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                    },
                    onNavigateToLiveViewer = { streamId ->
                        mainNavController.navigate(Screen.LiveViewer.createRoute(streamId))
                    }
                )
            }

            composable(Screen.CreatorEarnings.route) {
                conectate.com.presentation.wallet.CreatorEarningsScreen(navController = navController)
            }

            composable(Screen.CreatorAnalytics.route) {
                conectate.com.presentation.analytics.CreatorAnalyticsScreen(navController = navController)
            }

            composable(Screen.AccountSettings.route) {
                conectate.com.presentation.profile.EditProfileScreen(navController = navController)
            }

            composable(Screen.ChatSettings.route) {
                conectate.com.presentation.settings.ChatSettingsScreen(navController = navController)
            }

            composable(Screen.CustomizeApp.route) {
                conectate.com.presentation.settings.CustomizeAppScreen(navController = navController)
            }

            composable(Screen.LanguageSettings.route) {
                conectate.com.presentation.settings.LanguageSettingsScreen(navController = navController)
            }

            composable(Screen.HelpSettings.route) {
                conectate.com.presentation.legal.HelpCenterScreen(navController = navController)
            }

            composable(Screen.PrivacyPolicy.route) {
                conectate.com.presentation.legal.PrivacyPolicyScreen(navController = navController)
            }

            composable(Screen.TermsAndConditions.route) {
                conectate.com.presentation.legal.TermsAndConditionsScreen(navController = navController)
            }

            composable(Screen.AdminPanel.route) {
                conectate.com.presentation.profile.AdminPanelScreen(navController = navController)
            }

            // ✅ FIX: Add FollowList route to HomeScreen NavHost to prevent crash
            composable(
                route = Screen.FollowList.route,
                arguments = listOf(
                    androidx.navigation.navArgument("userId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("type") { type = androidx.navigation.NavType.StringType }
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
            
            // ✅ FIX: Add UserProfile route to HomeScreen NavHost for viewing other users
            composable(
                route = Screen.UserProfile.route,
                arguments = listOf(
                    androidx.navigation.navArgument("userId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                conectate.com.presentation.profile.ProfileScreen(
                    navController = navController,
                    mainNavController = mainNavController,
                    userId = userId,
                    onLogout = {
                        mainNavController.navigate(Screen.Login.route) {
                            popUpTo(mainNavController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Screen.VideoPlayer.route,
                arguments = listOf(
                    androidx.navigation.navArgument("videoId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("focusCommentId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    androidx.navigation.navArgument("resumeMs") {
                        type = androidx.navigation.NavType.LongType
                        defaultValue = 0L
                    },
                    androidx.navigation.navArgument("endMs") {
                        type = androidx.navigation.NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { backStackEntry ->
                conectate.com.presentation.video.VideoDetailFeedScreen(
                    navController = navController,
                    videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable,
                    focusCommentId = backStackEntry.arguments?.getString("focusCommentId"),
                    initialPositionMs = backStackEntry.arguments?.getLong("resumeMs") ?: 0L,
                    initialClipEndMs = backStackEntry.arguments?.getLong("endMs") ?: 0L,
                )
            }

            // ✅ FIX: Add PostDetail route to HomeScreen NavHost to ensure SocialScreen can navigate to it
            composable(
                route = Screen.PostDetail.route,
                arguments = listOf(
                    androidx.navigation.navArgument("postId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("focusCommentId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val postId = backStackEntry.arguments?.getString("postId") ?: ""
                conectate.com.presentation.posts.PostDetailScreen(
                    navController = navController,
                    postId = postId,
                    focusCommentId = backStackEntry.arguments?.getString("focusCommentId")
                )
            }

            composable(
                route = Screen.MomentViewer.route,
                arguments = listOf(
                    androidx.navigation.navArgument("momentId") {
                        type = androidx.navigation.NavType.StringType
                    }
                )
            ) { backStackEntry ->
                conectate.com.presentation.social.MomentViewerScreen(
                    navController = navController,
                    momentId = backStackEntry.arguments?.getString("momentId").orEmpty()
                )
            }

            composable(
                route = Screen.MomentHighlightViewer.route,
                arguments = listOf(
                    androidx.navigation.navArgument("highlightId") {
                        type = androidx.navigation.NavType.StringType
                    },
                    androidx.navigation.navArgument("title") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "Momentos destacados"
                    }
                )
            ) { backStackEntry ->
                conectate.com.presentation.social.MomentHighlightViewerScreen(
                    navController = navController,
                    highlightId = backStackEntry.arguments?.getString("highlightId").orEmpty(),
                    title = backStackEntry.arguments?.getString("title").orEmpty()
                )
            }

            // ✅ FIX: Add Notifications route to HomeScreen NavHost (was missing, causing freeze)
            composable(Screen.Notifications.route) {
                conectate.com.presentation.profile.NotificationScreen(navController = navController)
            }

            composable(Screen.NotificationSettings.route) {
                conectate.com.presentation.profile.NotificationSettingsScreen(navController = navController)
            }

            composable(Screen.ContentSafety.route) {
                conectate.com.presentation.settings.ContentSafetyScreen(navController = navController)
            }

            // ✅ FIX: Add Ranking route to HomeScreen NavHost
            composable(Screen.Ranking.route) {
                conectate.com.presentation.ranking.RankingScreen(navController = navController)
            }

            // ✅ FIX: Add FriendRequests route to HomeScreen NavHost
            composable(Screen.FriendRequests.route) {
                conectate.com.presentation.profile.FriendRequestsScreen(navController = navController)
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}
