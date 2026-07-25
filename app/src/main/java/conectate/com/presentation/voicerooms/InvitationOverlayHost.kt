package conectate.com.presentation.voicerooms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.presentation.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun InvitationOverlayHost(
    isLoggedIn: Boolean,
    navController: NavHostController,
    authRepository: AuthRepository,
    voiceRoomRepository: VoiceRoomRepository
) {
    if (!isLoggedIn) return

    val invitation = voiceRoomRepository.incomingInvitation.collectAsStateWithLifecycle().value
    val currentUserId = authRepository.getCurrentUserId()

    LaunchedEffect(currentUserId) {
        currentUserId?.let { userId ->
            android.util.Log.d("MainActivity", "=== SUBSCRIBING TO INVITATIONS IN MAIN ===")
            android.util.Log.d("MainActivity", "User ID: $userId")
            runCatching {
                withContext(Dispatchers.IO) {
                    voiceRoomRepository.subscribeToInvitations(userId)
                }
            }.onFailure { error ->
                android.util.Log.e("MainActivity", "Invitation subscription failed", error)
            }
            android.util.Log.d("MainActivity", "Subscription call completed")
        }
    }

    InvitationOverlay(
        invitation = invitation,
        onAccept = { roomId ->
            voiceRoomRepository.dismissInvitation()
            navController.navigate(Screen.RoomDetail.createRoute(roomId))
        },
        onDecline = {
            voiceRoomRepository.dismissInvitation()
        }
    )
}
