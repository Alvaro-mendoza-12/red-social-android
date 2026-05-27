package conectate.com.presentation.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import conectate.com.presentation.auth.LoginScreen
import conectate.com.presentation.auth.RegisterScreen
import conectate.com.presentation.legal.PrivacyPolicyScreen

internal fun androidx.navigation.NavGraphBuilder.addAuthRoutes(
    navController: NavHostController,
    isInitializing: Boolean,
    isLoggedIn: Boolean,
    hasPendingIntent: Boolean
) {
    composable(Screen.Splash.route) {
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
                    val targetRoute = if (isLoggedIn) Screen.Home.createRoute() else Screen.Login.route
                    android.util.Log.d("MainActivity", "Splash: Navigating to ${if (isLoggedIn) "Home" else "Login"}")
                    navController.navigate(targetRoute) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Splash: Navigation FAILED", e)
                }
            }
        }
    }

    composable(Screen.Login.route) {
        if (isLoggedIn) {
            LaunchedEffect(isLoggedIn) {
                navController.navigate(Screen.Home.createRoute()) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else {
            LoginScreen(navController = navController)
        }
    }

    composable(Screen.Register.route) {
        if (isLoggedIn) {
            LaunchedEffect(isLoggedIn) {
                navController.navigate(Screen.Home.createRoute()) {
                    popUpTo(Screen.Register.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else {
            RegisterScreen(navController = navController)
        }
    }

    composable(Screen.PrivacyPolicy.route) {
        PrivacyPolicyScreen(navController = navController)
    }

    composable(Screen.ChangePassword.route) {
        conectate.com.presentation.auth.ChangePasswordScreen(navController = navController)
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
                startTab = startTab,
                sharedText = sharedText,
                sharedUri = sharedUri
            )
        }
    }
}
