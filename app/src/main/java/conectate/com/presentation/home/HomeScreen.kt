package conectate.com.presentation.home

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import conectate.com.presentation.videos.VideosScreen
import conectate.com.presentation.video.VideoFeedScreen

sealed class BottomNavItem(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Messages : BottomNavItem(Screen.Messages.route, "Mensajes", Icons.Default.Message)
    object Videos : BottomNavItem(Screen.Videos.route, "Videos", Icons.Default.VideoLibrary)
    object Social : BottomNavItem(Screen.Social.route, "Social", Icons.Default.Public)
    object Profile : BottomNavItem(Screen.Profile.route, "Perfil", Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mainNavController: NavController,
    authViewModel: AuthViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    startTab: String? = null,
    sharedText: String? = null,
    sharedUri: String? = null
) {
    val navController = rememberNavController()
    val currentUserAvatarUrl by homeViewModel.currentUserAvatarUrl.collectAsState()

    LaunchedEffect(Unit) {
        homeViewModel.refreshShellUserData()
    }

    val items = listOf(
        BottomNavItem.Messages,
        BottomNavItem.Videos,
        BottomNavItem.Social,
        BottomNavItem.Profile
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { item ->
                    val unreadBadge by homeViewModel.totalUnreadConversations.collectAsState()
                    
                    NavigationBarItem(
                        icon = { 
                            BadgedBox(
                                badge = {
                                    if (item is BottomNavItem.Messages && unreadBadge > 0) {
                                        Badge(
                                            containerColor = Color(0xFF2196F3), // Blue circle as requested
                                            contentColor = Color.White
                                        ) {
                                            Text(if (unreadBadge > 9) "9+" else unreadBadge.toString())
                                        }
                                    }
                                }
                            ) {
                                if (item is BottomNavItem.Profile) {
                                    CircularProfileImage(
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
                                    Icon(item.icon, contentDescription = item.title)
                                }
                            }
                        },
                        label = { Text(item.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startTab ?: Screen.Messages.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(Screen.Messages.route) {
                ConversationsScreen(
                    navController = navController,
                    onNavigateToChat = { userId ->
                        mainNavController.navigate(Screen.Chat.createRoute(userId))
                    },
                    onNavigateToSearch = {
                        mainNavController.navigate(Screen.SearchUsers.route)
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
                // VideosScreen(navController = navController) // Old Grid
                conectate.com.presentation.video.VideoFeedScreen(
                    navController = navController,
                    onNavigateToUpload = {
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
                    }
                )
            }

            composable(Screen.Social.route) {
                conectate.com.presentation.social.SocialScreen(
                    mainNavController,
                    sharedText = sharedText,
                    sharedUri = sharedUri
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

            composable(Screen.AccountSettings.route) {
                conectate.com.presentation.profile.EditProfileScreen(navController = navController)
            }

            composable(Screen.ChatSettings.route) {
                conectate.com.presentation.settings.ChatSettingsScreen(navController = navController)
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
                    androidx.navigation.navArgument("videoId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                conectate.com.presentation.videos.VideoPlayerScreen(navController = navController)
            }

            // ✅ FIX: Add PostDetail route to HomeScreen NavHost to ensure SocialScreen can navigate to it
            composable(
                route = Screen.PostDetail.route,
                arguments = listOf(
                    androidx.navigation.navArgument("postId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val postId = backStackEntry.arguments?.getString("postId") ?: ""
                conectate.com.presentation.posts.PostDetailScreen(navController = navController, postId = postId)
            }

            // ✅ FIX: Add Notifications route to HomeScreen NavHost (was missing, causing freeze)
            composable(Screen.Notifications.route) {
                conectate.com.presentation.profile.NotificationScreen(navController = navController)
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
