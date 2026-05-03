package conectate.com.presentation.common

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import conectate.com.services.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


fun MainActivityContent(
    currentIntent: Intent,
    onIntentConsumed: () -> Unit,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    voiceRoomRepository: VoiceRoomRepository,
    callRepository: CallRepository,
    liveRepository: LiveRepository,
    activityScope: CoroutineScope
) {
    val appContext = LocalContext.current.applicationContext
    val connectivityViewModel: ConnectivityViewModel = hiltViewModel()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        var navController = rememberNavController()

        LaunchedEffect(currentIntent) {
            currentIntent.let { incomingIntent ->
                IntentRouter.handleIntentNavigation(
                    context = appContext,
                    incomingIntent = incomingIntent,
                    navController = navController,
                    authRepository = authRepository,
                    activityScope = activityScope,
                    onPresentCallInvite = { invite -> callRepository.presentIncomingInvite(invite) },
                    clearIntent = onIntentConsumed
                )
            }
        }

        val isInitializing by authRepository.isInitializing.collectAsStateWithLifecycle()
        val session by authRepository.getAuthStateFlow().collectAsStateWithLifecycle(initialValue = null)
        var isLoggedIn = (session?.user?.id?.isNotBlank() == true) || authRepository.isUserLoggedIn
        var showForcedSuspensionDialog by remember { mutableStateOf(false) }
        var forcedSuspensionUntil by remember { mutableStateOf<String?>(null) }
        var forcedSuspensionReason by remember { mutableStateOf<String?>(null) }
        val isConnected by connectivityViewModel.isConnected.collectAsStateWithLifecycle()
        val voiceSession by voiceRoomRepository.sessionState.collectAsStateWithLifecycle()
        val currentCall by callRepository.activeCall.collectAsStateWithLifecycle()
        val currentLiveSession by liveRepository.activeSession.collectAsStateWithLifecycle()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        var currentRoom = voiceSession.room ?: voiceSession.roomId?.let { activeRoomId ->
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
                    var call = currentCall ?: return
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
                } else if (showVoiceSessionBar) {
                    var room = currentRoom ?: return
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
                                runCatching {
                                    VoiceForegroundService.requestHangup(voiceRoomRepository.context)
                                }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                AppNavGraph(
                    navController = navController,
                    isInitializing = isInitializing,
                    isLoggedIn = isLoggedIn,
                    hasPendingIntent = IntentRouter.hasPendingNavigationIntent(appContext, currentIntent)
                )

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
                                    conectate.com.data.model.LiveRole.HOST -> Screen.LiveHost.route
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
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .imePadding()
                )
            }
        }
    }
}

private fun isOfflineAccessibleRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return true
    return route.startsWith("home") ||
        route.startsWith("chat/") ||
        route.startsWith("group_chat/") ||
        route.startsWith("community_chat/")
}







