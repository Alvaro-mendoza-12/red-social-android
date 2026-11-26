package conectate.com.presentation.gaming

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import conectate.com.presentation.components.LocalProfileIdentityAccount
import conectate.com.presentation.navigation.RequireLoggedIn
import conectate.com.presentation.navigation.Screen

private const val inviteRoute = "board_invitation/{roomId}"
private val boardRoomId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

internal fun boardInvitationRoute(id: String?): String? = id?.takeIf(boardRoomId::matches)?.let { "board_invitation/${it.lowercase()}" }

internal fun NavGraphBuilder.addBoardInvitationRoute(navController: NavHostController, isLoggedIn: Boolean) {
    composable(inviteRoute, arguments = listOf(navArgument("roomId") { type = NavType.StringType })) { entry ->
        val roomId = entry.arguments?.getString("roomId")?.takeIf(boardRoomId::matches)
        RequireLoggedIn(isLoggedIn, navController) {
            val activeAccount = LocalProfileIdentityAccount.current.orEmpty()
            var owner by rememberSaveable { mutableStateOf(activeAccount) }
            LaunchedEffect(activeAccount, roomId) {
                if (roomId == null) navController.popBackStack()
                else if (activeAccount.isNotBlank() && owner.isBlank()) owner = activeAccount
                else if (activeAccount.isNotBlank() && owner != activeAccount) navController.popBackStack()
            }
            if (roomId != null && activeAccount.isNotBlank() && owner == activeAccount) {
                val viewModel: PlayMatchViewModel = hiltViewModel()
                var resumeRoom by rememberSaveable(roomId, owner) { mutableStateOf<String?>(null) }
                key(roomId, owner) {
                    BoardGamesScreen(
                        onBack = { navController.popBackStack() },
                        request = viewModel::boardRequest,
                        resumeRoomId = resumeRoom,
                        invitationRoomId = roomId,
                        onOpenOpponent = { currentRoom, userId, openChat ->
                            resumeRoom = currentRoom
                            val destination = if (openChat) Screen.Chat.createRoute(userId) else Screen.UserProfile.createRoute(userId)
                            navController.navigate(destination) { launchSingleTop = true }
                        },
                    )
                }
            }
        }
    }
}
