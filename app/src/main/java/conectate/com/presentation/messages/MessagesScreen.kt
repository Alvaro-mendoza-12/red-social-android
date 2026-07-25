package conectate.com.presentation.messages

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

@Composable
@Suppress("UNUSED_PARAMETER")
fun MessagesScreen(
    navController: NavController,
    sharedText: String? = null,
    sharedUri: String? = null,
) {
    ConversationsScreen(
        navController = navController,
        sharedStickerUri = sharedUri,
        onNavigateToChat = { chatId ->
            navController.navigate(Screen.Chat.createRoute(chatId))
        },
        onNavigateToSearch = {
            navController.navigate(Screen.SearchUsers.route)
        },
        onNavigateToCallHistory = {
            navController.navigate(Screen.CallHistory.route) {
                launchSingleTop = true
            }
        },
        onNavigateToCreateGroup = {
            navController.navigate(Screen.CreateGroup.route)
        },
        onNavigateToCreateCommunity = {
            navController.navigate(Screen.CreateCommunity.route)
        },
        onNavigateToCreateVoiceRoom = {
            navController.navigate(Screen.CreateVoiceRoom.route)
        },
        onNavigateToNovvin = {
            navController.navigate(Screen.Novvin.route)
        },
        onNavigateToGroupChat = { groupId ->
            navController.navigate(Screen.GroupChat.createRoute(groupId))
        },
        onNavigateToCommunityChat = { communityId ->
            navController.navigate(Screen.CommunityChat.createRoute(communityId))
        },
        onNavigateToRoomDetail = { roomId ->
            navController.navigate(Screen.RoomDetail.createRoute(roomId))
        },
    )
}
