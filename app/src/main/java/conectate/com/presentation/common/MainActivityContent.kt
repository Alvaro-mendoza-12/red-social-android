package conectate.com.presentation.common

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.rememberNavController
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.presentation.navigation.AppNavGraph
import conectate.com.presentation.navigation.IntentRouter
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.voicerooms.InvitationOverlayHost
import kotlinx.coroutines.CoroutineScope

@Composable
fun MainActivityContent(
    currentIntent: Intent?,
    onIntentConsumed: () -> Unit,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    voiceRoomRepository: VoiceRoomRepository,
    activityScope: CoroutineScope
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val navController = rememberNavController()

        LaunchedEffect(currentIntent) {
            currentIntent?.let { incomingIntent ->
                IntentRouter.handleIntentNavigation(
                    incomingIntent = incomingIntent,
                    navController = navController,
                    authRepository = authRepository,
                    activityScope = activityScope,
                    clearIntent = onIntentConsumed
                )
            }
        }

        val isInitializing by authRepository.isInitializing.collectAsStateWithLifecycle()
        val session by authRepository.getAuthStateFlow().collectAsStateWithLifecycle(initialValue = null)
        val isLoggedIn = session?.user?.id?.isNotBlank() == true
        var showForcedSuspensionDialog by remember { mutableStateOf(false) }
        var forcedSuspensionUntil by remember { mutableStateOf<String?>(null) }
        var forcedSuspensionReason by remember { mutableStateOf<String?>(null) }

        SessionSideEffects(
            isLoggedIn = isLoggedIn,
            authRepository = authRepository,
            notificationRepository = notificationRepository,
            onSuspensionDetected = { suspensionEndDate, reason ->
                forcedSuspensionUntil = suspensionEndDate
                forcedSuspensionReason = reason
                showForcedSuspensionDialog = true
            },
            onForceLogout = {
                navController.navigate(Screen.Login.route) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AppNavGraph(
                navController = navController,
                isInitializing = isInitializing,
                isLoggedIn = isLoggedIn,
                hasPendingIntent = IntentRouter.hasPendingNavigationIntent(currentIntent)
            )

            InvitationOverlayHost(
                isLoggedIn = isLoggedIn,
                navController = navController,
                authRepository = authRepository,
                voiceRoomRepository = voiceRoomRepository
            )

            ForcedSuspensionAlertDialog(
                visible = showForcedSuspensionDialog,
                suspensionEndDate = forcedSuspensionUntil,
                reason = forcedSuspensionReason,
                onAccept = {
                    showForcedSuspensionDialog = false
                    forcedSuspensionReason = null
                    navController.navigate(Screen.Login.route) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
