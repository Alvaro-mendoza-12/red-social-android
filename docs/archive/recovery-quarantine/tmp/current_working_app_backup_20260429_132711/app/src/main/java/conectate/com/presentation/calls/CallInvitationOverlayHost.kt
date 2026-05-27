package conectate.com.presentation.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.navigation.Screen
import kotlinx.coroutines.launch


fun CallInvitationOverlayHost(
    isLoggedIn: Boolean,
    navController: NavHostController,
    authRepository: AuthRepository,
    callRepository: CallRepository
) {
    if (!isLoggedIn) return

    val scope = rememberCoroutineScope()
    var invite = callRepository.incomingInvite.collectAsStateWithLifecycle().value
    val currentUserId = authRepository.getCurrentUserId()

    LaunchedEffect(currentUserId) {
        currentUserId?.let { uid ->
            callRepository.ensureSubscribedToCallUserChannel(uid)
        }
    }

    CallInvitationOverlay(
        invite = invite,
        onAccept = { incoming ->
            scope.launch {
                val result = callRepository.acceptIncomingCall(incoming)
                if (result.isSuccess) {
                    navController.navigate(Screen.Call.createRoute(incoming.callId)) {
                        launchSingleTop = true
                    }
                }
            }
        },
        onDecline = { incoming ->
            scope.launch {
                callRepository.declineIncomingCall(incoming)
            }
        }
    )
}








