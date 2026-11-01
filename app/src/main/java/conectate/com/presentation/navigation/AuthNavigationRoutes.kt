package conectate.com.presentation.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import conectate.com.presentation.auth.EmailRecoveryDraftStore
import conectate.com.presentation.auth.StartupDestination
import conectate.com.presentation.auth.startupDestination
import conectate.com.core.auth.hasSavedGuestSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import conectate.com.presentation.auth.LoginScreen
import conectate.com.presentation.auth.NovvinAuthTheme
import conectate.com.presentation.auth.RegisterScreen
import conectate.com.presentation.auth.GuestScreen
import conectate.com.presentation.auth.GuestModeViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.presentation.auth.WebLoginScannerScreen
import conectate.com.presentation.legal.PrivacyPolicyScreen

internal fun androidx.navigation.NavGraphBuilder.addAuthRoutes(
    navController: NavHostController,
    isInitializing: Boolean,
    isLoggedIn: Boolean,
    hasPendingIntent: Boolean
) {
    composable(Screen.Splash.route) {
        val context = LocalContext.current
        conectate.com.presentation.auth.SplashScreen()

        LaunchedEffect(isInitializing, isLoggedIn, hasPendingIntent) {
            if (isInitializing) {
                android.util.Log.d("MainActivity", "Splash: Still initializing auth...")
                return@LaunchedEffect
            }

            val currentRoute = navController.currentDestination?.route
            android.util.Log.d(
                "MainActivity",
                "Splash check: initializing=$isInitializing, isLoggedIn=$isLoggedIn, pendingIntent=$hasPendingIntent"
            )

            if (hasPendingIntent) {
                android.util.Log.d("MainActivity", "Splash: Aborting automatic navigation due to Pending Intent")
            } else if (currentRoute != Screen.Splash.route) {
                android.util.Log.d("MainActivity", "Splash: Already navigated to $currentRoute. Aborting.")
            } else {
                try {
                    val persisted = withContext(Dispatchers.IO) {
                        conectate.com.core.observability.measureStartupPhase("startup recovery routing") {
                            val recovery = EmailRecoveryDraftStore(context).read() != null
                            recovery to (!isLoggedIn && !recovery && hasSavedGuestSession(context))
                        }
                    }
                    val targetRoute = when (startupDestination(isLoggedIn, persisted.first, persisted.second)) {
                        StartupDestination.ADD_ACCOUNT -> Screen.AddAccountLogin.route
                        StartupDestination.HOME -> Screen.Home.createRoute()
                        StartupDestination.GUEST -> Screen.Guest.route
                        StartupDestination.LOGIN -> Screen.Login.route
                    }
                    android.util.Log.d("MainActivity", "Splash: Navigating to ${if (isLoggedIn) "Home" else "Login"}")
                    withContext(Dispatchers.Main.immediate) {
                        // Recovery preferences are read off the UI thread. Preserve a link opened meanwhile.
                        if (navController.currentDestination?.route != Screen.Splash.route) return@withContext
                        if (targetRoute == Screen.AddAccountLogin.route) {
                            // Recovery may be restored while another account is active.
                            // Keep a valid destination beneath its Back button.
                            navController.navigate(Screen.Home.createRoute()) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        navController.navigate(targetRoute) {
                            if (targetRoute != Screen.AddAccountLogin.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                            launchSingleTop = true
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Splash: Navigation FAILED", e)
                }
            }
        }
    }

    composable(Screen.Login.route) {
        if (isLoggedIn) {
            LaunchedEffect(isLoggedIn) {
                // A link can replace this entry while the authentication update is queued.
                if (navController.currentDestination?.route != Screen.Login.route) return@LaunchedEffect
                if (navController.openPendingSharedContent()) return@LaunchedEffect
                withContext(Dispatchers.Main.immediate) {
                    navController.navigate(PendingVoiceRoomDestination.consume(navController) ?: conectate.com.presentation.surveys.PendingSurveyDestination.consume(navController) ?: Screen.Home.createRoute()) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        } else {
            NovvinAuthTheme { LoginScreen(navController = navController) }
        }
    }

    composable(Screen.Guest.route) {
        if (isLoggedIn) {
            LaunchedEffect(isLoggedIn) {
                // A link can replace this entry while the authentication update is queued.
                if (navController.currentDestination?.route != Screen.Guest.route) return@LaunchedEffect
                if (navController.openPendingSharedContent()) return@LaunchedEffect
                withContext(Dispatchers.Main.immediate) {
                    navController.navigate(PendingVoiceRoomDestination.consume(navController) ?: conectate.com.presentation.surveys.PendingSurveyDestination.consume(navController) ?: Screen.Home.createRoute()) {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        } else GuestScreen(navController)
    }

    composable(Screen.Register.route) { registerEntry ->
        val guestViewModel: GuestModeViewModel = hiltViewModel()
        if (isLoggedIn) {
            LaunchedEffect(isLoggedIn) {
                // A link can replace this entry while the authentication update is queued.
                if (navController.currentDestination?.route != Screen.Register.route) return@LaunchedEffect
                if (navController.openPendingSharedContent()) return@LaunchedEffect
                val pendingProfile = guestViewModel.consumePendingProfile()
                withContext(Dispatchers.Main.immediate) {
                    navController.navigate(
                        PendingVoiceRoomDestination.consume(navController) ?: conectate.com.presentation.surveys.PendingSurveyDestination.consume(navController) ?: pendingProfile?.let(Screen.UserProfile::createRoute) ?: Screen.Home.createRoute()
                    ) {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        } else {
            NovvinAuthTheme {
                RegisterScreen(
                    navController = navController,
                    registerBackStackEntry = registerEntry,
                )
            }
        }
    }

    composable(Screen.PrivacyPolicy.route) {
        PrivacyPolicyScreen(navController = navController)
    }

    composable(Screen.ChangePassword.route) {
        conectate.com.presentation.auth.ChangePasswordScreen(navController = navController)
    }

    composable(Screen.AccountRecoveryHelp.route) {
        NovvinAuthTheme { LoginScreen(navController = navController, isAddAccountMode = true, initialAssistedRecovery = true) }
    }

    composable(Screen.AddAccountLogin.route) {
        NovvinAuthTheme {
            LoginScreen(
                navController = navController,
                isAddAccountMode = true
            )
        }
    }

    composable(
        route = Screen.WebLoginScanner.route,
        arguments = listOf(
            navArgument("pairId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("nonce") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        WebLoginScannerScreen(
            navController = navController,
            initialPairId = backStackEntry.arguments?.getString("pairId"),
            initialNonce = backStackEntry.arguments?.getString("nonce")
        )
    }

    composable(
        route = Screen.Home.route,
        arguments = listOf(
            navArgument("startTab") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("sharedText") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("sharedUri") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val startTab = backStackEntry.arguments?.getString("startTab")
        val sharedText = backStackEntry.arguments?.getString("sharedText")
        val sharedUri = backStackEntry.arguments?.getString("sharedUri")
        RequireLoggedIn(isLoggedIn, navController) {
            conectate.com.presentation.home.HomeScreen(
                mainNavController = navController,
                homeBackStackEntry = backStackEntry,
                startTab = startTab,
                sharedText = sharedText,
                sharedUri = sharedUri
            )
        }
    }
}
