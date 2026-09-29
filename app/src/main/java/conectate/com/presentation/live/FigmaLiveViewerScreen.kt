package conectate.com.presentation.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.wallet.GooglePlayRechargeDialog
import java.util.concurrent.atomic.AtomicBoolean

internal class FigmaLiveViewerExitGate {
    private val started = AtomicBoolean(false)

    fun runOnce(cleanup: () -> Unit): Boolean {
        if (!started.compareAndSet(false, true)) return false
        cleanup()
        return true
    }
}

@Composable
fun LiveViewerScreen(
    navController: NavController,
    liveId: String,
    viewModel: LiveSessionViewModel = hiltViewModel()
) {
    val viewerCount by viewModel.viewerCount.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val localGuestTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()
    val liveKitRemoteVideos by viewModel.liveKitRemoteVideos.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val isFollowingHost by viewModel.isFollowingHost.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val liveViewers by viewModel.liveViewers.collectAsStateWithLifecycle()
    val reactionCount by viewModel.reactionCount.collectAsStateWithLifecycle()
    val activeGuests by viewModel.activeGuests.collectAsStateWithLifecycle()
    val guestState by viewModel.guestAccessState.collectAsStateWithLifecycle()
    val guestBroadcasting by viewModel.isGuestBroadcasting.collectAsStateWithLifecycle()
    val guestMic by viewModel.isGuestMicEnabled.collectAsStateWithLifecycle()
    val guestVideo by viewModel.isGuestVideoEnabled.collectAsStateWithLifecycle()
    val giftEvents by viewModel.giftEvents.collectAsStateWithLifecycle()
    val battle by viewModel.battleState.collectAsStateWithLifecycle()
    var rechargeMinimumCoins by remember(liveId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(battle.battleId, battle.status, battle.opponentLiveId) {
        if (
            battle.status == conectate.com.data.model.LiveBattleStatus.ACTIVE &&
            battle.battleId.isNotBlank() && battle.opponentLiveId.isNotBlank()
        ) {
            viewModel.connectLiveKit(
                liveId = liveId,
                role = "viewer",
                publishAudio = false,
                publishCamera = false,
            ).onFailure {
                AppFeedbackBus.showError("No se pudo conectar la vista compartida de batalla.")
            }
        }
    }

    val context = LocalContext.current
    val stream = session?.stream
    val currentUserId = remember { viewModel.getCurrentUserId()?.trim().orEmpty() }
    val guestSocketIds = remember(activeGuests) { activeGuests.mapTo(mutableSetOf()) { it.socketId } }
    val hostTrack = remoteTracks.entries.firstOrNull { it.key !in guestSocketIds }?.value
    val guestTracks = remember(remoteTracks, guestSocketIds, activeGuests, currentUserId, localGuestTrack) {
        buildMap {
            remoteTracks
                .filterKeys { it in guestSocketIds }
                .forEach { (socketId, track) -> put(socketId, track) }
            val ownSocketId = activeGuests
                .firstOrNull { it.userId.equals(currentUserId, ignoreCase = true) }
                ?.socketId
            if (!ownSocketId.isNullOrBlank()) {
                localGuestTrack?.let { put(ownSocketId, it) }
            }
        }
    }
    var wasConnectedToLive by remember(liveId) { mutableStateOf(false) }
    var manualLeaveRequested by remember(liveId) { mutableStateOf(false) }
    var showModerationDialog by remember(liveId) { mutableStateOf(false) }
    var pendingGuestStart by remember(liveId) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    val exitGate = remember(liveId) { FigmaLiveViewerExitGate() }

    val guestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val requested = pendingGuestStart
        pendingGuestStart = null
        if (requested == null) return@rememberLauncherForActivityResult
        if (result.values.all { it }) {
            val started = viewModel.startGuestBroadcast(
                videoEnabled = requested.second,
                micEnabled = requested.first
            )
            if (!started) AppFeedbackBus.showError("No se pudo preparar tu participación.")
        } else {
            AppFeedbackBus.showError("Activa los permisos elegidos para participar.")
        }
    }

    fun startGuest(micEnabled: Boolean, videoEnabled: Boolean) {
        val needed = buildList {
            if (
                micEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (
                videoEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            val started = viewModel.startGuestBroadcast(videoEnabled, micEnabled)
            if (!started) AppFeedbackBus.showError("No se pudo preparar tu participación.")
        } else {
            pendingGuestStart = micEnabled to videoEnabled
            guestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun leaveViewer() {
        manualLeaveRequested = true
        exitGate.runOnce(viewModel::leaveLive)
        navController.popBackStack()
    }

    fun shareLive() {
        val text = buildString {
            append("Mira este directo en Novvin")
            val hostName = stream?.hostName.orEmpty()
            if (hostName.isNotBlank()) append(": " + hostName)
        }
        val shareIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(shareIntent, "Compartir directo"))
    }

    DisposableEffect(liveId) {
        onDispose {
            exitGate.runOnce(viewModel::leaveLive)
        }
    }

    LaunchedEffect(liveId) {
        try {
            viewModel.joinAsViewer(liveId)
        } catch (error: Exception) {
            android.util.Log.e("LiveViewerScreen", "Error joining live", error)
            AppFeedbackBus.showError("Error al unirse al directo: " + error.message)
            navController.popBackStack()
        }
    }

    LaunchedEffect(session?.stream?.id) {
        if (session?.stream?.id == liveId) {
            wasConnectedToLive = true
            viewModel.startCollaborationPolling(liveId)
        }
    }

    LaunchedEffect(session, wasConnectedToLive, manualLeaveRequested) {
        if (wasConnectedToLive && session == null && !manualLeaveRequested) {
            AppFeedbackBus.show("Directo finalizado")
            navController.popBackStack()
        }
    }

    LaunchedEffect(stream?.hostUserId) {
        val hostId = stream?.hostUserId?.trim().orEmpty()
        if (hostId.isNotBlank()) viewModel.refreshFollowing(hostId)
    }

    LaunchedEffect(uiError) {
        val error = uiError ?: return@LaunchedEffect
        AppFeedbackBus.showError(error)
        if (session == null) navController.popBackStack()
        viewModel.clearError()
    }

    BackHandler { leaveViewer() }

    if (showModerationDialog && moderation.canModerate) {
        LiveModerationDialog(
            moderation = moderation,
            viewers = liveViewers,
            onDismiss = { showModerationDialog = false },
            onSaveSettings = { chatPaused, _ -> viewModel.updateModerationPause(chatPaused) },
            onSetModerator = viewModel::setModerator,
            onKick = { viewModel.moderateViewer(it, false) },
            onBan = { viewModel.moderateViewer(it, true) }
        )
    }

    rechargeMinimumCoins?.let { minimumCoins ->
        GooglePlayRechargeDialog(
            accountId = viewModel.getCurrentUserId().orEmpty(),
            minimumCoins = minimumCoins,
            onDismiss = {
                rechargeMinimumCoins = null
                viewModel.refreshGifts()
            },
        )
    }

    NovvinLiveViewerExperience(
        stream = stream,
        viewerCount = viewerCount,
        reactionCount = reactionCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        viewers = liveViewers,
        hostTrack = hostTrack,
        remoteTracks = guestTracks,
        liveKitVideos = liveKitRemoteVideos,
        onAttachLiveKit = viewModel::attachLiveKitRenderer,
        onDetachLiveKit = viewModel::detachLiveKitRenderer,
        activeGuests = activeGuests,
        sharedEglContext = eglBaseContext,
        source = currentSource,
        paused = isPaused,
        videoEnabled = isVideoEnabled,
        comments = comments,
        reactions = reactions,
        giftsReceived = giftEvents,
        battle = battle,
        following = isFollowingHost,
        guestState = guestState,
        guestBroadcasting = guestBroadcasting,
        guestMic = guestMic,
        guestVideo = guestVideo,
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        canModerate = moderation.canModerate,
        onClose = ::leaveViewer,
        onFollow = {
            val hostId = stream?.hostUserId?.trim().orEmpty()
            if (hostId.isNotBlank()) viewModel.toggleFollow(hostId)
        },
        onShare = ::shareLive,
        onComment = viewModel::sendComment,
        onReaction = { viewModel.sendReaction() },
        onGiftSent = { receipt ->
            viewModel.announceCommittedGift(receipt)
            AppFeedbackBus.show("Regalo enviado")
        },
        onRecharge = { rechargeMinimumCoins = it },
        onRequestGuest = viewModel::requestGuestAccess,
        onCancelGuest = viewModel::cancelGuestAccessRequest,
        onStartGuest = ::startGuest,
        onGuestMic = viewModel::setGuestMicEnabled,
        onGuestVideo = { viewModel.setGuestVideoEnabled(it) },
        onLeaveGuest = viewModel::leaveGuestSeat,
        onBattleResponse = viewModel::respondToBattle,
        onModerate = { showModerationDialog = true }
    )
}
