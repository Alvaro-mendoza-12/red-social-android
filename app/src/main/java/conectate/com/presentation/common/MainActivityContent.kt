package conectate.com.presentation.common

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.CallRepository
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.repository.AccountSwitchUiState
import conectate.com.data.localfallback.AccountDeletedEvents
import conectate.com.data.model.VoiceRoom
import conectate.com.presentation.components.OfflineRetryState
import conectate.com.presentation.navigation.AppNavGraph
import conectate.com.presentation.navigation.IntentRouter
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.voicerooms.InvitationOverlayHost
import conectate.com.presentation.voicerooms.ActiveVoiceSessionBar
import conectate.com.presentation.calls.CallInvitationOverlayHost
import conectate.com.presentation.calls.ActiveCallSessionBar
import conectate.com.presentation.live.ActiveLiveSessionBubble
import conectate.com.presentation.messages.LocalUniversalForwardCoordinator
import conectate.com.presentation.messages.UniversalForwardCoordinator
import conectate.com.presentation.messages.UniversalShareHost
import conectate.com.util.IncomingSharePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun MainActivityContent(
    currentIntent: Intent?,
    onIntentConsumed: () -> Unit,
    incomingShare: IncomingSharePayload?,
    onIncomingShareConsumed: () -> Unit,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    voiceRoomRepository: VoiceRoomRepository,
    callRepository: CallRepository,
    liveRepository: LiveRepository,
    activityScope: CoroutineScope
) {
    val connectivityViewModel: ConnectivityViewModel = hiltViewModel()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val navController = rememberNavController()
        val universalForwardCoordinator = remember { UniversalForwardCoordinator() }

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
        val isLoggedIn = (session?.user?.id?.isNotBlank() == true) || authRepository.isUserLoggedIn
        val accountSwitchState by authRepository.accountSwitchUiState.collectAsStateWithLifecycle()
        var accountSwitchSnapshot by remember { mutableStateOf<AccountSwitchUiState?>(null) }
        var showAccountSwitchOverlay by remember { mutableStateOf(false) }
        var showForcedSuspensionDialog by remember { mutableStateOf(false) }
        var showAccountDeletedDialog by remember { mutableStateOf(false) }
        var forcedSuspensionUntil by remember { mutableStateOf<String?>(null) }
        var forcedSuspensionReason by remember { mutableStateOf<String?>(null) }
        val isConnected by connectivityViewModel.isConnected.collectAsStateWithLifecycle()
        val voiceSession by voiceRoomRepository.sessionState.collectAsStateWithLifecycle()
        val currentCall by callRepository.activeCall.collectAsStateWithLifecycle()
        val currentLiveSession by liveRepository.activeSession.collectAsStateWithLifecycle()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val currentRoom = voiceSession.room ?: voiceSession.roomId?.let { activeRoomId ->
            VoiceRoom(
                id = activeRoomId,
                name = voiceSession.roomName,
                participantCount = voiceSession.participantCount,
                readableId = voiceSession.readableId
            )
        }
        val showVoiceSessionBar = isLoggedIn &&
            voiceSession.hasActiveSession &&
            currentRoute?.startsWith("room_detail") != true
        val showCallSessionBar = isLoggedIn && currentCall != null && currentRoute?.startsWith("call/") != true
        val showLiveBubble = isLoggedIn &&
            currentLiveSession != null &&
            currentRoute?.startsWith("live_host/") != true &&
            currentRoute?.startsWith("live_viewer/") != true
        val shouldBlockOfflineRoute = isLoggedIn &&
            !isConnected &&
            !isOfflineAccessibleRoute(currentRoute)

        LaunchedEffect(accountSwitchState) {
            if (accountSwitchState.isSwitching) {
                accountSwitchSnapshot = accountSwitchState
                showAccountSwitchOverlay = true
            } else {
                showAccountSwitchOverlay = false
                kotlinx.coroutines.delay(220)
                accountSwitchSnapshot = null
            }
        }

        LaunchedEffect(Unit) {
            AccountDeletedEvents.events.collect {
                authRepository.forceLocalLogout()
                showAccountDeletedDialog = true
                navController.navigate(Screen.Login.route) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

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

        LaunchedEffect(isLoggedIn, currentRoute, currentRoom?.id) {
            if (
                isLoggedIn &&
                currentRoute?.startsWith("room_detail") != true &&
                currentRoom == null &&
                voiceSession.hasActiveSession
            ) {
                voiceRoomRepository.refreshRooms()
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showCallSessionBar) {
                    currentCall?.let { call ->
                    ActiveCallSessionBar(
                        call = call,
                        onReturnToCall = { callId ->
                            navController.navigate(Screen.Call.createRoute(callId)) {
                                launchSingleTop = true
                            }
                        },
                        onHangup = {
                            activityScope.launch {
                                callRepository.hangup()
                            }
                        }
                    )
                    }
                } else if (showVoiceSessionBar) {
                    currentRoom?.let { room ->
                    ActiveVoiceSessionBar(
                        room = room,
                        isMuted = voiceSession.isMuted,
                        connectionLabel = voiceSession.connectionState.label,
                        onReturnToRoom = { roomId ->
                            navController.navigate(Screen.RoomDetail.createRoute(roomId)) {
                                launchSingleTop = true
                            }
                        },
                        onToggleMute = {
                            voiceRoomRepository.toggleMute(!voiceSession.isMuted)
                        },
                        onHangup = {
                            activityScope.launch {
                                voiceRoomRepository.leaveRoom(room.id)
                            }
                        }
                    )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                CompositionLocalProvider(
                    LocalUniversalForwardCoordinator provides universalForwardCoordinator
                ) {
                    AppNavGraph(
                        navController = navController,
                        isInitializing = isInitializing,
                        isLoggedIn = isLoggedIn,
                        isSwitchingAccount = accountSwitchState.isSwitching,
                        hasPendingIntent = IntentRouter.hasPendingNavigationIntent(currentIntent)
                    )
                }

                if (shouldBlockOfflineRoute) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    ) {
                        OfflineRetryState(
                            modifier = Modifier.align(Alignment.Center),
                            title = "No tienes conexión a internet",
                            subtitle = "Esta parte de la app necesita red. Puedes continuar en Mensajes mientras vuelve la conexión.",
                            actionLabel = "Intentar de nuevo",
                            onRetry = { connectivityViewModel.retrySync("global_offline_retry") },
                            secondaryActionLabel = "Continuar en Mensajes",
                            onSecondaryAction = {
                                navController.navigate(Screen.Home.createRoute(startTab = Screen.Messages.route)) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }

                InvitationOverlayHost(
                    isLoggedIn = isLoggedIn,
                    navController = navController,
                    authRepository = authRepository,
                    voiceRoomRepository = voiceRoomRepository
                )

                CallInvitationOverlayHost(
                    isLoggedIn = isLoggedIn,
                    navController = navController,
                    authRepository = authRepository,
                    callRepository = callRepository
                )

                if (showLiveBubble) {
                    currentLiveSession?.let { liveSession ->
                        ActiveLiveSessionBubble(
                            session = liveSession,
                            onReturnToLive = {
                                val route = when (liveSession.role) {
                                    conectate.com.data.model.LiveRole.HOST -> Screen.LiveHost.createRoute(liveSession.stream.id)
                                    else -> Screen.LiveViewer.createRoute(liveSession.stream.id)
                                }
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            onClose = {
                                activityScope.launch { liveRepository.endLive() }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .navigationBarsPadding()
                        )
                    }
                }

                if (isLoggedIn) {
                    UniversalShareHost(
                        incomingShare = incomingShare,
                        forwardCoordinator = universalForwardCoordinator,
                        onIncomingShareConsumed = onIncomingShareConsumed
                    )
                }

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

                AppFeedbackHost(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                )

                if (showAccountDeletedDialog) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Cuenta eliminada") },
                        text = { Text("Tu cuenta ha sido eliminada. La sesión y los datos locales de esta cuenta se limpiaron del dispositivo.") },
                        confirmButton = {
                            TextButton(onClick = { showAccountDeletedDialog = false }) {
                                Text("Entendido")
                            }
                        }
                    )
                }

                AnimatedVisibility(
                    visible = showAccountSwitchOverlay,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    accountSwitchSnapshot?.let { snapshot ->
                        AccountSwitchOverlay(snapshot)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSwitchOverlay(state: AccountSwitchUiState) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Cambiando de cuenta",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            state.targetDisplayName?.takeIf { it.isNotBlank() }?.let { targetName ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = targetName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Preparando tus mensajes, perfil y preferencias...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun isOfflineAccessibleRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return true
    return route.startsWith("home") ||
        route.startsWith("chat/") ||
        route.startsWith("group_chat/") ||
        route.startsWith("community_chat/") ||
        route.startsWith("offline_videos") ||
        route.startsWith("fullscreen_video/")
}







