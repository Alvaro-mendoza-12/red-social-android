package conectate.com.presentation.navigation

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import conectate.com.presentation.raffles.RafflesScreen

object RaffleNavigation {
    const val route = "raffles/{kind}/{roomId}?id={id}"
    fun createRoute(kind: String, roomId: String, id: String? = null) =
        "raffles/${Uri.encode(kind)}/${Uri.encode(roomId)}?id=${Uri.encode(id.orEmpty())}"
}

internal fun NavGraphBuilder.addRaffleRoutes(navController: NavHostController, isLoggedIn: Boolean) {
    composable(RaffleNavigation.route, arguments = listOf(
        navArgument("kind") { type = NavType.StringType },
        navArgument("roomId") { type = NavType.StringType },
        navArgument("id") { type = NavType.StringType; defaultValue = "" },
    )) {
        RequireLoggedIn(isLoggedIn, navController) { RafflesScreen(onBack = { navController.popBackStack() }) }
    }
}
