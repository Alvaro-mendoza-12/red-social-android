package conectate.com.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

@Composable
internal inline fun RequireLoggedIn(
    isLoggedIn: Boolean,
    navController: NavHostController,
    crossinline content: @Composable () -> Unit
) {
    if (isLoggedIn) {
        content()
    } else {
        LaunchedEffect(Unit) {
            navController.navigate(Screen.Login.route) {
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
