package conectate.com.presentation.common

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
import dagger.Lazy
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
    notificationRepositoryProvider: Lazy<NotificationRepository>,
    voiceRoomRepositoryProvider: Lazy<VoiceRoomRepository>,
    callRepositoryProvider: Lazy<CallRepository>,
    liveRepositoryProvider: Lazy<LiveRepository>,
    activityScope: CoroutineScope
) {
    LaunchedEffect(Unit) {
        if (conectate.com.BuildConfig.DEBUG) {
            android.util.Log.d("StartupTiming", "MainActivityContent first composition completed")
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val navController = rememberNavController()
        val universalForwardCoordinator = remember { UniversalForwardCoordinator() }

        val isInitializing by authRepository.isInitializing.collectAsStateWithLifecycle()
        LaunchedEffect(currentIntent, isInitializing) {
            if (isInitializing) return@LaunchedEffect
            currentIntent?.let { incomingIntent ->
                if (!IntentRouter.hasPendingNavigationIntent(incomingIntent)) {
                    onIntentConsumed()
                    return@let
                }
                IntentRouter.handleIntentNavigation(
                    incomingIntent = incomingIntent,
                    navController = navController,
                    authRepository = authRepository,
                    notificationRepository = notificationRepositoryProvider.get(),
                    activityScope = activityScope,
                    clearIntent = onIntentConsumed
                )
            }
        }

        val session by authRepository.getAuthStateFlow().collectAsStateWithLifecycle(initialValue = null)
        // Drive the first route from the asynchronously restored session StateFlow. Calling
        // AuthService.isLoggedIn() here reads encrypted preferences synchronously during Compose.
        val isLoggedIn = session?.user?.id?.isNotBlank() == true
        if (!isInitializing && !isLoggedIn) SystemAnnouncementHost(null)
        if (isLoggedIn) StartupPrompts(session?.user?.id)
        val notificationRepository = authenticatedRepository(isLoggedIn, notificationRepositoryProvider, "notification repository")
        val voiceRoomRepository = authenticatedRepository(isLoggedIn, voiceRoomRepositoryProvider, "voice repository")
        val callRepository = authenticatedRepository(isLoggedIn, callRepositoryProvider, "call repository")
        val liveRepository = authenticatedRepository(isLoggedIn, liveRepositoryProvider, "live repository")
        // Connectivity and sync are only needed by authenticated routes. Constructing their
        // dependency graph on the welcome screen adds work before the first usable frame.
        val connectivityStarted = if (conectate.com.BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
        val connectivityViewModel = if (isLoggedIn) hiltViewModel<ConnectivityViewModel>() else null
        if (conectate.com.BuildConfig.DEBUG && isLoggedIn) {
            android.util.Log.d("StartupTiming", "connectivity model completed in ${android.os.SystemClock.elapsedRealtime() - connectivityStarted} ms")
        }
        val accountSwitchState by authRepository.accountSwitchUiState.collectAsStateWithLifecycle()
        var accountSwitchSnapshot by remember { mutableStateOf<AccountSwitchUiState?>(null) }
        var showAccountSwitchOverlay by remember { mutableStateOf(false) }
        var showForcedSuspensionDialog by remember { mutableStateOf(false) }
        var showAccountDeletedDialog by remember { mutableStateOf(false) }
        var forcedSuspensionUntil by remember { mutableStateOf<String?>(null) }
        var forcedSuspensionReason by remember { mutableStateOf<String?>(null) }
        val isConnected = connectivityViewModel?.isConnected
            ?.collectAsStateWithLifecycle()?.value ?: true
        val voiceSession = voiceRoomRepository?.sessionState?.collectAsStateWithLifecycle()?.value
        val currentCall = callRepository?.activeCall?.collectAsStateWithLifecycle()?.value
        val currentLiveSession = liveRepository?.activeSession?.collectAsStateWithLifecycle()?.value
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val currentRoom = voiceSession?.room ?: voiceSession?.roomId?.let { activeRoomId ->
            VoiceRoom(
                id = activeRoomId,
                name = voiceSession.roomName,
                participantCount = voiceSession.participantCount,
                readableId = voiceSession.readableId
            )
        }
        val showVoiceSessionBar = isLoggedIn &&
            voiceSession?.hasActiveSession == true &&
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

        if (notificationRepository != null) SessionSideEffects(
            isLoggedIn = isLoggedIn,
            activeUserId = session?.user?.id,
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
                voiceSession?.hasActiveSession == true
            ) {
                voiceRoomRepository?.refreshRooms()
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
                                callRepository?.hangup()
                            }
                        }
                    )
                    }
                } else if (showVoiceSessionBar) {
                    currentRoom?.let { room ->
                    ActiveVoiceSessionBar(
                        room = room,
                        isMuted = voiceSession?.isMuted == true,
                        connectionLabel = voiceSession?.connectionState?.label.orEmpty(),
                        onReturnToRoom = { roomId ->
                            navController.navigate(Screen.RoomDetail.createRoute(roomId)) {
                                launchSingleTop = true
                            }
                        },
                        onToggleMute = {
                            voiceRoomRepository?.toggleMute(voiceSession?.isMuted != true)
                        },
                        onHangup = {
                            activityScope.launch {
                                voiceRoomRepository?.leaveRoom(room.id)
                            }
                        }
                    )
                    }
                }
            }
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val statusBarTop = WindowInsets.statusBars
                .asPaddingValues()
                .calculateTopPadding()
            val adjustedPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = (innerPadding.calculateTopPadding() - statusBarTop).coerceAtLeast(0.dp),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding()
            )
            conectate.com.presentation.components.GlobalAvatarMomentProvider(
                accountId = if (accountSwitchState.isSwitching || !isLoggedIn) null else authRepository.getCurrentUserId(),
                onOpen = { moment -> navController.navigate(Screen.MomentViewer.createRoute(moment.id)) { launchSingleTop = true } },
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(adjustedPadding)
            ) {
                CompositionLocalProvider(
                    LocalUniversalForwardCoordinator provides universalForwardCoordinator,
                    conectate.com.presentation.components.LocalProfileIdentityAccount provides
                        if (accountSwitchState.isSwitching || !isLoggedIn) ""
                        else authRepository.getCurrentUserId().orEmpty(),
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
                            onRetry = { connectivityViewModel?.retrySync("global_offline_retry") },
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

                if (voiceRoomRepository != null) InvitationOverlayHost(
                    isLoggedIn = isLoggedIn,
                    navController = navController,
                    authRepository = authRepository,
                    voiceRoomRepository = voiceRoomRepository
                )

                if (callRepository != null) CallInvitationOverlayHost(
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
                                activityScope.launch { liveRepository?.endLive() }
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
                        onIncomingShareConsumed = onIncomingShareConsumed,
                        onOpenConversation = { destination ->
                            val route = when (destination.type) {
                                conectate.com.presentation.messages.ShareDestinationType.CHAT -> Screen.Chat.createRoute(destination.id)
                                conectate.com.presentation.messages.ShareDestinationType.GROUP -> Screen.GroupChat.createRoute(destination.id)
                                conectate.com.presentation.messages.ShareDestinationType.COMMUNITY -> Screen.CommunityChat.createRoute(destination.id)
                            }
                            navController.navigate(route) { launchSingleTop = true }
                        },
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
}

/** Prepare singleton dependencies without blocking the first authenticated composition. */
@Composable
private fun <T> authenticatedRepository(authenticated: Boolean, provider: Lazy<T>, phase: String): T? {
    val repository by androidx.compose.runtime.produceState<T?>(
        initialValue = null,
        authenticated,
        provider
    ) {
        value = if (authenticated) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            conectate.com.core.observability.measureStartupPhase(phase) { provider.get() }
        } else null
    }
    return if (authenticated) repository else null
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
