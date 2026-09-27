package conectate.com.presentation.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import conectate.com.data.model.LiveBattleCandidate
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.LiveGuestAccessState
import conectate.com.data.model.LiveGuestParticipant
import conectate.com.data.model.LiveGuestRequest
import conectate.com.data.model.LiveGuestRequestStatus
import conectate.com.data.model.LiveMusicPlaybackState
import conectate.com.data.model.LiveReaction
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveStream
import conectate.com.R
import conectate.com.presentation.wallet.GiftCelebrationOverlay
import conectate.com.data.model.GiftReceipt
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import java.time.Instant
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import io.livekit.android.renderer.TextureViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovvinLiveViewerExperience(
    stream: LiveStream?,
    viewerCount: Int,
    hostTrack: VideoTrack?,
    remoteTracks: Map<String, VideoTrack>,
    liveKitVideos: List<LiveKitRoomBridge.RemoteVideo>,
    onAttachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    activeGuests: List<LiveGuestParticipant>,
    sharedEglContext: EglBase.Context?,
    source: LiveSource,
    paused: Boolean,
    videoEnabled: Boolean,
    comments: List<LiveComment>,
    reactions: List<LiveReaction>,
    giftsReceived: List<LiveGiftEvent>,
    battle: LiveBattleState,
    following: Boolean?,
    guestState: LiveGuestAccessState,
    guestBroadcasting: Boolean,
    guestMic: Boolean,
    guestVideo: Boolean,
    commentingEnabled: Boolean,
    canModerate: Boolean,
    onClose: () -> Unit,
    onFollow: () -> Unit,
    onShare: () -> Unit,
    onComment: (String) -> Unit,
    onReaction: () -> Unit,
    onGiftSent: (GiftReceipt) -> Unit,
    onRecharge: (Int) -> Unit,
    onRequestGuest: () -> Unit,
    onCancelGuest: () -> Unit,
    onStartGuest: (Boolean, Boolean) -> Unit,
    onGuestMic: (Boolean) -> Unit,
    onGuestVideo: (Boolean) -> Unit,
    onLeaveGuest: () -> Unit,
    onBattleResponse: (Boolean) -> Unit,
    onModerate: () -> Unit
) {
    var message by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf<ViewerPanel?>(null) }
    var dismissedBattleResult by remember { mutableStateOf<Long?>(null) }
    var visibleGift by remember { mutableStateOf<LiveGiftEvent?>(null) }
    LaunchedEffect(giftsReceived.lastOrNull()?.createdAtMs) {
        val latest = giftsReceived.lastOrNull() ?: return@LaunchedEffect
        visibleGift = latest
        delay(3_200L)
        if (visibleGift?.createdAtMs == latest.createdAtMs) visibleGift = null
    }
    LaunchedEffect(guestState.status, guestBroadcasting) {
        if (guestState.status == LiveGuestRequestStatus.ACCEPTED && !guestBroadcasting) {
            panel = ViewerPanel.GUEST_SETUP
        }
    }

    LiveExperienceFrame(
        primaryTrack = if (paused || !videoEnabled) null else hostTrack,
        primaryName = stream?.hostName?.ifBlank { "Streamer" } ?: "Streamer",
        primaryAvatar = stream?.hostAvatarUrl,
        primaryMirror = false,
        remoteTracks = remoteTracks,
        liveKitVideos = liveKitVideos,
        onAttachLiveKit = onAttachLiveKit,
        onDetachLiveKit = onDetachLiveKit,
        guests = battleAwareGuests(activeGuests, battle),
        egl = sharedEglContext,
        scaling = if (source == LiveSource.SCREEN) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
        comments = comments,
        reactions = reactions,
        battle = battle,
        top = { metrics ->
            ViewerTopBar(stream, viewerCount, following, metrics, onFollow, onClose)
        },
        event = {
            visibleGift?.let { GiftToast(it) }
        },
        center = {
            when {
                paused -> LiveStateBadge("DIRECTO EN PAUSA")
                !videoEnabled -> LiveStateBadge(if (source == LiveSource.SCREEN) "PANTALLA DESACTIVADA" else "CÁMARA DESACTIVADA")
                hostTrack == null -> ConnectingState(stream, "Conectando directo…")
            }
        },
        actions = { metrics ->
            ViewerActions(
                metrics = metrics,
                guestState = guestState,
                guestBroadcasting = guestBroadcasting,
                giftsEnabled = stream?.giftsEnabled != false,
                onGift = { panel = ViewerPanel.GIFTS },
                onGuest = {
                    panel = if (guestState.status == LiveGuestRequestStatus.ACCEPTED || guestBroadcasting) {
                        ViewerPanel.GUEST_SETUP
                    } else {
                        ViewerPanel.GUEST_REQUEST
                    }
                },
                onShare = onShare,
                onReaction = onReaction,
                onMore = { panel = ViewerPanel.MORE }
            )
        },
        floatingOverlay = {
            GiftCelebrationOverlay(events = giftsReceived, sessionKey = stream?.id)
        },
        bottom = { metrics ->
            CommentComposer(
                value = message,
                enabled = commentingEnabled,
                onValueChange = { message = it.take(220) },
                onSend = {
                    val clean = message.trim()
                    if (clean.isNotEmpty()) {
                        onComment(clean)
                        message = ""
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = metrics.horizontalPadding, vertical = 8.dp)
            )
        }
    )

    when (panel) {
        ViewerPanel.GIFTS -> if (stream != null && stream.giftsEnabled) {
            LiveGiftSheet(
                stream = stream,
                battle = battle,
                onDismiss = { panel = null },
                onRecharge = onRecharge,
                onGiftSent = onGiftSent,
            )
        }
        ViewerPanel.GUEST_REQUEST -> GuestRequestSheet(
            state = guestState,
            onDismiss = { panel = null },
            onRequest = onRequestGuest,
            onCancel = onCancelGuest
        )
        ViewerPanel.GUEST_SETUP -> GuestSetupSheet(
            broadcasting = guestBroadcasting,
            micEnabled = guestMic,
            videoEnabled = guestVideo,
            onDismiss = { panel = null },
            onStart = onStartGuest,
            onMic = onGuestMic,
            onVideo = onGuestVideo,
            onLeave = {
                onLeaveGuest()
                panel = null
            }
        )
        ViewerPanel.MORE -> ViewerMoreSheet(
            canModerate = canModerate,
            onModerate = { panel = null; onModerate() },
            onShare = { panel = null; onShare() },
            onExit = { panel = null; onClose() },
            onDismiss = { panel = null }
        )
        null -> Unit
    }

    if (battle.status == LiveBattleStatus.INVITED) {
        FigmaBattleInvitationDialog(
            battle = battle,
            onAccept = { onBattleResponse(true) },
            onReject = { onBattleResponse(false) }
        )
    }
    val viewerBattleKey = battle.startedAtMs.takeIf { it > 0L } ?: battle.durationSeconds.toLong()
    if (battle.status == LiveBattleStatus.FINISHED && dismissedBattleResult != viewerBattleKey) {
        FigmaBattleResultScreen(
            battle = battle,
            hostName = stream?.hostName.orEmpty(),
            onDismiss = { dismissedBattleResult = viewerBattleKey }
        )
    }
}

private enum class ViewerPanel { GIFTS, GUEST_REQUEST, GUEST_SETUP, MORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovvinLiveHostExperience(
    stream: LiveStream?,
    viewerCount: Int,
    localTrack: VideoTrack?,
    remoteTracks: Map<String, VideoTrack>,
    liveKitVideos: List<LiveKitRoomBridge.RemoteVideo>,
    onAttachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    activeGuests: List<LiveGuestParticipant>,
    requests: List<LiveGuestRequest>,
    egl: EglBase.Context?,
    source: LiveSource,
    paused: Boolean,
    videoEnabled: Boolean,
    isFrontCamera: Boolean,
    micEnabled: Boolean,
    musicState: LiveMusicPlaybackState,
    comments: List<LiveComment>,
    reactions: List<LiveReaction>,
    giftsReceived: List<LiveGiftEvent>,
    battle: LiveBattleState,
    battleCandidates: List<LiveBattleCandidate>,
    commentingEnabled: Boolean,
    onComment: (String) -> Unit,
    onRespondGuest: (String, Boolean) -> Unit,
    onGuestRequestsAllowed: (Boolean) -> Unit,
    onMuteGuest: (String) -> Unit,
    onRemoveGuest: (String) -> Unit,
    onPauseResume: () -> Unit,
    onMic: (Boolean) -> Unit,
    onPickMusic: () -> Unit,
    onMusicEnabled: (Boolean) -> Unit,
    onMusicVolume: (Float) -> Unit,
    onRemoveMusic: () -> Unit,
    onSettings: () -> Unit,
    onModeration: () -> Unit,
    onMoment: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCameraToggle: () -> Unit,
    onEffects: () -> Unit,
    onFloatingChat: () -> Unit,
    onBattle: (String, Int) -> Unit,
    onCrossLiveBattle: (String, String) -> Unit,
    onRefreshBattleCandidates: () -> Unit,
    onBattleResponse: (Boolean) -> Unit,
    onEnd: () -> Unit,
    floatingOverlay: @Composable BoxScope.() -> Unit = {}
) {
    var message by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf<HostPanel?>(null) }
    var dismissedBattleResult by remember { mutableStateOf<Long?>(null) }
    var visibleGift by remember { mutableStateOf<LiveGiftEvent?>(null) }
    LaunchedEffect(giftsReceived.lastOrNull()?.createdAtMs) {
        val latest = giftsReceived.lastOrNull() ?: return@LaunchedEffect
        visibleGift = latest
        delay(3_200L)
        if (visibleGift?.createdAtMs == latest.createdAtMs) visibleGift = null
    }
    LiveExperienceFrame(
        primaryTrack = if (!paused && videoEnabled) localTrack else null,
        primaryName = stream?.hostName?.ifBlank { "Tú" } ?: "Tú",
        primaryAvatar = stream?.hostAvatarUrl,
        primaryMirror = source == LiveSource.CAMERA && isFrontCamera,
        remoteTracks = remoteTracks,
        liveKitVideos = liveKitVideos,
        onAttachLiveKit = onAttachLiveKit,
        onDetachLiveKit = onDetachLiveKit,
        guests = battleAwareGuests(activeGuests, battle),
        egl = egl,
        scaling = if (source == LiveSource.SCREEN) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
        comments = comments,
        reactions = reactions,
        battle = battle,
        top = { metrics ->
            HostTopBar(
                viewerCount = viewerCount,
                stream = stream,
                metrics = metrics,
                requestCount = requests.size,
                onRequests = { panel = HostPanel.REQUESTS },
                onEnd = onEnd
            )
        },
        event = {
            if (requests.isNotEmpty()) {
                GuestRequestBanner(requests.first(), requests.size) { panel = HostPanel.REQUESTS }
            } else {
                visibleGift?.let { GiftToast(it) }
            }
        },
        center = {
            when {
                paused -> LiveStateBadge("DIRECTO EN PAUSA")
                !videoEnabled -> LiveStateBadge(if (source == LiveSource.SCREEN) "PANTALLA DESACTIVADA" else "CÁMARA DESACTIVADA")
                localTrack == null -> ConnectingState(stream, "Preparando transmisión…")
            }
        },
        actions = {},
        bottom = { metrics ->
            HostControlBar(
                metrics = metrics,
                paused = paused,
                micEnabled = micEnabled,
                source = source,
                videoEnabled = videoEnabled,
                battleActive = battle.status == LiveBattleStatus.ACTIVE,
                onPauseResume = onPauseResume,
                onMic = { onMic(!micEnabled) },
                onCameraToggle = onCameraToggle,
                onSwitchCamera = onSwitchCamera,
                onBattle = {
                    onRefreshBattleCandidates()
                    panel = HostPanel.BATTLE
                },
                onGifts = { panel = HostPanel.GIFTS },
                onMore = { panel = HostPanel.TOOLS }
            )
        },
        hostComposer = {
            MiniComposer(
                value = message,
                enabled = commentingEnabled,
                onValueChange = { message = it.take(220) },
                onSend = {
                    val clean = message.trim()
                    if (clean.isNotEmpty()) {
                        onComment(clean)
                        message = ""
                    }
                }
            )
        },
        floatingOverlay = {
            floatingOverlay()
            GiftCelebrationOverlay(events = giftsReceived, sessionKey = stream?.id)
        }
    )

    when (panel) {
        HostPanel.REQUESTS -> GuestRequestsSheet(
            requests = requests,
            activeGuests = activeGuests,
            requestsAllowed = stream?.allowGuestRequests == true,
            onRequestsAllowed = onGuestRequestsAllowed,
            onMuteGuest = onMuteGuest,
            onRemoveGuest = onRemoveGuest,
            onDismiss = { panel = null },
            onRespond = onRespondGuest
        )
        HostPanel.GIFTS -> FigmaReceivedGiftsScreen(giftsReceived) { panel = null }
        HostPanel.NOTIFICATIONS -> FigmaLiveNotificationsScreen(
            comments = comments,
            gifts = giftsReceived,
            requests = requests,
            guests = activeGuests,
            onReviewRequests = { panel = HostPanel.REQUESTS },
            onReviewGifts = { panel = HostPanel.GIFTS },
            onBack = { panel = null }
        )
        HostPanel.BATTLE -> HostBattleSheet(
            guests = activeGuests,
            candidates = battleCandidates,
            enabled = stream?.battlesEnabled != false,
            onDismiss = { panel = null },
            onStartGuest = { socketId, seconds ->
                onBattle(socketId, seconds)
                panel = null
            },
            onStartCrossLive = { candidate ->
                onCrossLiveBattle(candidate.liveId, candidate.userId)
                panel = null
            },
        )
        HostPanel.TOOLS -> HostToolsSheet(
            paused = paused,
            source = source,
            videoEnabled = videoEnabled,
            onPauseResume = { panel = null; onPauseResume() },
            onSettings = { panel = null; onSettings() },
            onModeration = { panel = null; onModeration() },
            onMoment = { panel = null; onMoment() },
            onSwitchCamera = { panel = null; onSwitchCamera() },
            onCameraToggle = { panel = null; onCameraToggle() },
            onEffects = { panel = null; onEffects() },
            onMusic = { panel = HostPanel.MUSIC },
            onFloating = { panel = null; onFloatingChat() },
            onNotifications = { panel = HostPanel.NOTIFICATIONS },
            onEnd = { panel = null; onEnd() },
            onDismiss = { panel = null }
        )
        HostPanel.MUSIC -> HostMusicSheet(
            state = musicState,
            onPick = onPickMusic,
            onEnabled = onMusicEnabled,
            onVolume = onMusicVolume,
            onRemove = onRemoveMusic,
            onDismiss = { panel = null },
        )
        null -> Unit
    }

    val hostBattleKey = battle.startedAtMs.takeIf { it > 0L } ?: battle.durationSeconds.toLong()
    if (battle.status == LiveBattleStatus.INVITED && battle.invitationIncoming) {
        FigmaBattleInvitationDialog(
            battle = battle,
            onAccept = { onBattleResponse(true) },
            onReject = { onBattleResponse(false) },
        )
    }
    if (battle.status == LiveBattleStatus.FINISHED && dismissedBattleResult != hostBattleKey) {
        FigmaBattleResultScreen(
            battle = battle,
            hostName = stream?.hostName.orEmpty(),
            onDismiss = { dismissedBattleResult = hostBattleKey }
        )
    }
}

@Composable
internal fun NovvinLiveSummary(
    title: String,
    viewerCount: Int,
    commentCount: Int,
    reactionCount: Int,
    giftDiamonds: Int,
    topDonors: List<Pair<String, Int>>,
    startedAt: String?,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(LivePalette.Background, LivePalette.DeepSurface)
                )
            )
    ) {
        val compact = maxHeight < 700.dp || maxWidth < 360.dp
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveRoundIconButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Volver",
                    onClose,
                    size = 34.dp,
                    containerColor = LivePalette.Surface
                )
                Text(
                    "Resumen del directo",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Spacer(Modifier.size(34.dp))
            }
            Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF123B2C))
                    .border(1.dp, Color(0xFF22C55E).copy(alpha = .5f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.Check, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(15.dp))
                Text("DIRECTO COMPLETADO", color = Color(0xFF86EFAC), fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(11.dp))
            Text("¡Buen directo!", color = Color.White, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Black)
            Text(
                title.ifBlank { "Tu directo en Novvin" },
                color = LivePalette.Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(if (compact) 16.dp else 22.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LivePalette.Surface)
                    .border(1.dp, LivePalette.Border, RoundedCornerShape(18.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatCard(
                        icon = Icons.Filled.PlayArrow,
                        value = formatDuration(elapsedSince(startedAt)),
                        label = "Duración",
                        accent = LivePalette.Purple,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        icon = Icons.Filled.Groups,
                        value = formatCompactCount(viewerCount),
                        label = "Espectadores únicos",
                        accent = LivePalette.Cyan,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatCard(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        value = commentCount.coerceAtLeast(0).toString(),
                        label = "Comentarios",
                        accent = LivePalette.Pink,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        icon = Icons.Filled.Star,
                        value = formatCompactCount(reactionCount),
                        label = "Me gusta",
                        accent = LivePalette.Gold,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatCard(
                        icon = Icons.Filled.Groups,
                        value = formatCompactCount((viewerCount * 0.72f).toInt()),
                        label = "Pico simultáneo",
                        accent = LivePalette.Purple,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        icon = Icons.Filled.CardGiftcard,
                        value = formatCompactCount(giftDiamonds),
                        label = "Monedas recibidas",
                        accent = LivePalette.Gold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LivePalette.Surface)
                    .border(1.dp, LivePalette.Border, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Audiencia al finalizar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    LivePill("Registro de sesión", background = LivePalette.Purple.copy(alpha = .2f), contentColor = LivePalette.Cyan)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "$viewerCount espectadores conectados al cerrar el directo",
                    color = LivePalette.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (topDonors.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(LivePalette.Surface)
                        .border(1.dp, LivePalette.Border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Principales colaboradores", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    topDonors.take(3).forEachIndexed { index, donor ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(
                                    if (index == 0) LivePalette.Gold else LivePalette.Purple.copy(alpha = .24f)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (index + 1).toString(),
                                    color = if (index == 0) LivePalette.DeepSurface else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Text(
                                donor.first,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("+${donor.second}", color = LivePalette.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 16.dp else 22.dp))
            LiveGradientButton(
                text = "Compartir resumen",
                onClick = onShare,
                leading = {
                    Icon(Icons.Filled.Share, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            )
            Spacer(Modifier.height(10.dp))
            SheetOutlineButton(
                text = "Volver a Novvin",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun SummaryStatCard(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LivePalette.DeepSurface)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = LivePalette.Muted, fontSize = 10.sp)
    }
}


private enum class HostPanel { REQUESTS, GIFTS, NOTIFICATIONS, BATTLE, TOOLS, MUSIC }

@Composable
private fun LiveExperienceFrame(
    primaryTrack: VideoTrack?,
    primaryName: String,
    primaryAvatar: String?,
    primaryMirror: Boolean,
    remoteTracks: Map<String, VideoTrack>,
    liveKitVideos: List<LiveKitRoomBridge.RemoteVideo>,
    onAttachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    guests: List<LiveGuestParticipant>,
    egl: EglBase.Context?,
    scaling: RendererCommon.ScalingType,
    comments: List<LiveComment>,
    reactions: List<LiveReaction>,
    battle: LiveBattleState,
    top: @Composable (LiveResponsiveMetrics) -> Unit,
    event: @Composable BoxScope.() -> Unit,
    center: @Composable BoxScope.() -> Unit,
    actions: @Composable (LiveResponsiveMetrics) -> Unit,
    bottom: @Composable (LiveResponsiveMetrics) -> Unit,
    hostComposer: @Composable (() -> Unit)? = null,
    floatingOverlay: @Composable BoxScope.() -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val metrics = liveMetrics(maxWidth, maxHeight)
        val paneWidth = minOf(maxWidth, 560.dp)
        Box(Modifier.align(Alignment.Center).width(paneWidth).fillMaxHeight().background(Color.Black)) {
            LiveVideoLayout(
                primaryTrack,
                primaryName,
                primaryAvatar,
                primaryMirror,
                remoteTracks,
                liveKitVideos,
                onAttachLiveKit,
                onDetachLiveKit,
                guests,
                egl,
                scaling,
                Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.48f),
                        0.32f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f)
                    )
                )
            )
            LiveReactionsOverlay(reactions, Modifier.fillMaxSize())
            floatingOverlay()
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
                top(metrics)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            top = if (metrics.compact) 48.dp else 58.dp,
                            start = metrics.horizontalPadding,
                            end = metrics.horizontalPadding
                        )
                ) { event() }
            }
            if (battle.status == LiveBattleStatus.ACTIVE || battle.status == LiveBattleStatus.FINISHED) {
                BattleScoreOverlay(
                    battle,
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(
                            top = if (metrics.compact) 92.dp else 108.dp,
                            start = metrics.horizontalPadding,
                            end = metrics.horizontalPadding
                        )
                )
            }
            Box(Modifier.align(Alignment.Center)) { center() }
            if (hostComposer != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactChat(
                        comments,
                        metrics.chatHeight,
                        Modifier.padding(horizontal = metrics.horizontalPadding).fillMaxWidth(0.90f)
                    )
                    Box(Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding)) {
                        hostComposer()
                    }
                    bottom(metrics)
                }
            } else {
                val bottomOffset = metrics.composerHeight + 16.dp
                CompactChat(
                    comments,
                    metrics.chatHeight,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = metrics.horizontalPadding, bottom = bottomOffset)
                        .fillMaxWidth(0.72f)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = metrics.horizontalPadding, bottom = bottomOffset)
                ) { actions(metrics) }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { bottom(metrics) }
            }
        }
    }
}

@Composable
private fun LiveVideoLayout(
    primaryTrack: VideoTrack?,
    primaryName: String,
    primaryAvatar: String?,
    primaryMirror: Boolean,
    remoteTracks: Map<String, VideoTrack>,
    liveKitVideos: List<LiveKitRoomBridge.RemoteVideo>,
    onAttachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    guests: List<LiveGuestParticipant>,
    egl: EglBase.Context?,
    scaling: RendererCommon.ScalingType,
    modifier: Modifier = Modifier
) {
    val visibleGuests = guests.take(3)
    if (visibleGuests.isEmpty()) {
        LiveParticipantCell(
            track = primaryTrack,
            name = primaryName,
            avatarUrl = primaryAvatar,
            egl = egl,
            mirror = primaryMirror,
            scaling = scaling,
            liveKitVideo = null,
            onAttachLiveKit = onAttachLiveKit,
            onDetachLiveKit = onDetachLiveKit,
            modifier = modifier,
            showLabel = false
        )
        return
    }

    val cells = buildList {
        add(
            LiveVideoCell(
                track = primaryTrack,
                name = primaryName,
                avatarUrl = primaryAvatar,
                mirror = primaryMirror
            )
        )
        visibleGuests.forEachIndexed { index, guest ->
            val liveKitVideo = liveKitVideos.firstOrNull {
                it.participantName.equals(guest.name, ignoreCase = true)
            } ?: liveKitVideos.getOrNull(index)
            add(
                LiveVideoCell(
                    track = remoteTracks[guest.socketId],
                    liveKitVideo = liveKitVideo,
                    name = guest.name,
                    avatarUrl = guest.avatarUrl,
                    mirror = false
                )
            )
        }
    }

    Column(
        modifier = modifier
            .background(LivePalette.DeepSurface)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            cells.take(2).forEach { cell ->
                LiveParticipantCell(
                    track = cell.track,
                    name = cell.name,
                    avatarUrl = cell.avatarUrl,
                    egl = egl,
                    mirror = cell.mirror,
                    scaling = scaling,
                    liveKitVideo = cell.liveKitVideo,
                    onAttachLiveKit = onAttachLiveKit,
                    onDetachLiveKit = onDetachLiveKit,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    showLabel = true
                )
            }
            if (cells.size == 1) Spacer(Modifier.weight(1f))
        }
        if (cells.size > 2) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                cells.drop(2).take(2).forEach { cell ->
                    LiveParticipantCell(
                        track = cell.track,
                        name = cell.name,
                        avatarUrl = cell.avatarUrl,
                        egl = egl,
                        mirror = cell.mirror,
                        scaling = scaling,
                        liveKitVideo = cell.liveKitVideo,
                        onAttachLiveKit = onAttachLiveKit,
                        onDetachLiveKit = onDetachLiveKit,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        showLabel = true
                    )
                }
                if (cells.size == 3) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun battleAwareGuests(
    guests: List<LiveGuestParticipant>,
    battle: LiveBattleState
): List<LiveGuestParticipant> {
    if (battle.status != LiveBattleStatus.ACTIVE && battle.status != LiveBattleStatus.FINISHED) return guests
    if (battle.opponentUserId.isBlank() && battle.opponentSocketId.isBlank()) return guests
    if (guests.any {
            it.socketId == battle.opponentSocketId ||
                (battle.opponentUserId.isNotBlank() && it.userId.equals(battle.opponentUserId, true))
        }
    ) return guests
    return listOf(
        LiveGuestParticipant(
            socketId = battle.opponentSocketId.ifBlank { "battle:${battle.opponentUserId}" },
            userId = battle.opponentUserId,
            name = battle.opponentName.ifBlank { "Invitado" },
            avatarUrl = battle.opponentAvatarUrl
        )
    ) + guests
}

private data class LiveVideoCell(
    val track: VideoTrack?,
    val liveKitVideo: LiveKitRoomBridge.RemoteVideo? = null,
    val name: String,
    val avatarUrl: String?,
    val mirror: Boolean
)

@Composable
private fun LiveParticipantCell(
    track: VideoTrack?,
    name: String,
    avatarUrl: String?,
    egl: EglBase.Context?,
    mirror: Boolean,
    scaling: RendererCommon.ScalingType,
    liveKitVideo: LiveKitRoomBridge.RemoteVideo?,
    onAttachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetachLiveKit: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    modifier: Modifier,
    showLabel: Boolean
) {
    Box(
        modifier
            .clip(if (showLabel) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B123D), Color(0xFF080514))
                )
            )
    ) {
        if (track != null) {
            WebRtcVideoView(
                videoTrack = track,
                sharedEglContext = egl,
                mirror = mirror,
                scalingType = scaling,
                modifier = Modifier.fillMaxSize()
            )
        } else if (liveKitVideo != null) {
            LiveKitRemoteVideoSurface(
                video = liveKitVideo,
                onAttach = onAttachLiveKit,
                onDetach = onDetachLiveKit,
                modifier = Modifier.fillMaxSize(),
                label = name,
            )
        } else {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProfileImage(
                    imageUrl = avatarUrl,
                    fallbackName = name,
                    size = if (showLabel) 58.dp else 76.dp,
                    showBorder = true,
                    borderColor = LivePalette.Purple
                )
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = if (showLabel) 12.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showLabel) {
            LivePill(
                text = name,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                background = Color.Black.copy(alpha = 0.62f)
            )
        }
    }
}

@Composable
private fun ViewerTopBar(
    stream: LiveStream?,
    viewerCount: Int,
    following: Boolean?,
    metrics: LiveResponsiveMetrics,
    onFollow: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = metrics.horizontalPadding,
            vertical = if (metrics.compact) 6.dp else 10.dp
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProfileImage(
            imageUrl = stream?.hostAvatarUrl,
            fallbackName = stream?.hostName,
            size = metrics.topAvatarSize,
            showBorder = true,
            borderColor = LivePalette.Purple
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = stream?.hostName?.ifBlank { "Streamer" } ?: "Streamer",
                color = Color.White,
                fontSize = if (metrics.compact) 12.sp else 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "EN VIVO",
                    color = LivePalette.Red,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = formatCompactCount(viewerCount) + " viendo",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 9.sp
                )
            }
        }
        if (following != true) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LivePalette.Cyan)
                    .clickable(onClick = onFollow)
                    .padding(horizontal = if (metrics.compact) 9.dp else 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "Seguir",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        LivePill(
            text = formatCompactCount(viewerCount),
            leadingContent = {
                Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        )
        LiveRoundIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "Salir del directo",
            onClick = onClose,
            size = if (metrics.compact) 30.dp else 32.dp,
            containerColor = Color.Black.copy(alpha = 0.52f),
            borderColor = Color.White.copy(alpha = 0.12f)
        )
    }
}

@Composable
private fun GiftToast(event: LiveGiftEvent) {
    val scale = remember(event.createdAtMs) { Animatable(0.72f) }
    LaunchedEffect(event.createdAtMs) {
        scale.animateTo(1.10f, spring(dampingRatio = 0.58f, stiffness = 520f))
        scale.animateTo(1f, tween(150))
    }
    Row(
        Modifier
            .widthIn(max = 324.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = ((scale.value - 0.72f) / 0.28f).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF6D28D9).copy(alpha = .96f), Color(0xFF0E7490).copy(alpha = .92f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CardGiftcard, null, tint = LivePalette.Gold, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = event.senderName.ifBlank { "Usuario" },
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "envió " + event.gift.name,
                color = Color.White.copy(alpha = .82f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "+${event.gift.priceDiamonds}",
            color = LivePalette.Gold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun LiveStateBadge(text: String) {
    LivePill(
        text = text,
        background = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        leadingContent = {
            Box(Modifier.size(7.dp).clip(CircleShape).background(LivePalette.Red))
        }
    )
}

@Composable
private fun ConnectingState(stream: LiveStream?, text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProfileImage(
            imageUrl = stream?.hostAvatarUrl,
            fallbackName = stream?.hostName,
            size = 72.dp,
            showBorder = true,
            borderColor = LivePalette.Purple
        )
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = LivePalette.Cyan,
            strokeWidth = 2.dp
        )
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ViewerActions(
    metrics: LiveResponsiveMetrics,
    guestState: LiveGuestAccessState,
    guestBroadcasting: Boolean,
    giftsEnabled: Boolean,
    onGift: () -> Unit,
    onGuest: () -> Unit,
    onShare: () -> Unit,
    onReaction: () -> Unit,
    onMore: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 7.dp else 10.dp)
    ) {
        if (giftsEnabled) LiveAction(
            icon = Icons.Filled.CardGiftcard,
            label = "Regalo",
            color = LivePalette.Purple,
            size = metrics.actionSize,
            onClick = onGift
        )
        LiveAction(
            icon = if (guestBroadcasting) Icons.Filled.Videocam else Icons.Filled.PersonAdd,
            label = when (guestState.status) {
                LiveGuestRequestStatus.PENDING -> "Pendiente"
                LiveGuestRequestStatus.ACCEPTED -> "Unirme"
                else -> "Invitado"
            },
            color = if (guestState.status == LiveGuestRequestStatus.PENDING) LivePalette.Gold else LivePalette.Cyan,
            size = metrics.actionSize,
            onClick = onGuest
        )
        LiveAction(Icons.Filled.Share, "Compartir", Color.White, metrics.actionSize, onShare)
        LiveAction(Icons.Filled.Favorite, "Me gusta", LivePalette.Pink, metrics.actionSize, onReaction)
        LiveAction(Icons.Filled.MoreHoriz, "Más", Color.White, metrics.actionSize, onMore)
    }
}

@Composable
private fun LiveAction(
    icon: ImageVector,
    label: String,
    color: Color,
    size: Dp,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        LiveRoundIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            size = size,
            tint = color,
            containerColor = Color.Black.copy(alpha = 0.52f),
            borderColor = Color.White.copy(alpha = 0.15f)
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun CommentComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(LivePalette.Cyan),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        if (enabled) "Añade un comentario…" else "Chat pausado",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp
                    )
                }
                inner()
            }
        )
        LiveRoundIconButton(
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Enviar",
            onClick = onSend,
            size = 32.dp,
            tint = Color.White,
            containerColor = if (enabled && value.isNotBlank()) LivePalette.Cyan else Color.White.copy(alpha = 0.12f),
            borderColor = Color.Transparent
        )
    }
}


@Composable
private fun ReceiverChip(
    name: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LivePalette.Purple.copy(alpha = 0.22f) else LivePalette.DeepSurface)
            .border(
                1.dp,
                if (selected) LivePalette.Purple else LivePalette.Border,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = LivePalette.Cyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestRequestSheet(
    state: LiveGuestAccessState,
    onDismiss: () -> Unit,
    onRequest: () -> Unit,
    onCancel: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LiveSheetHeader(
                title = "Únete al directo",
                subtitle = "Participa junto al creador",
                icon = Icons.Filled.Groups,
                onClose = onDismiss
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(LivePalette.DeepSurface)
                    .border(1.dp, LivePalette.Border, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(LivePalette.Purple.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Videocam, null, tint = LivePalette.Cyan)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (state.status) {
                                LiveGuestRequestStatus.PENDING -> "Solicitud enviada"
                                LiveGuestRequestStatus.REJECTED -> "El creador no pudo aceptar"
                                LiveGuestRequestStatus.ACCEPTED -> "Solicitud aceptada"
                                else -> "Solicita participar"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            state.message ?: when (state.status) {
                                LiveGuestRequestStatus.PENDING -> "Te avisaremos en cuanto responda."
                                LiveGuestRequestStatus.REJECTED -> "Puedes volver a intentarlo más tarde."
                                else -> "Tu cámara y micrófono seguirán apagados hasta que confirmes."
                            },
                            color = LivePalette.Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            if (state.status == LiveGuestRequestStatus.PENDING) {
                SheetOutlineButton(
                    text = "Cancelar solicitud",
                    icon = Icons.Filled.Close,
                    onClick = onCancel,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            } else {
                LiveGradientButton(
                    text = if (state.status == LiveGuestRequestStatus.REJECTED) "Solicitar de nuevo" else "Solicitar unirme",
                    onClick = onRequest,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    leading = {
                        Icon(Icons.Filled.PersonAdd, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestSetupSheet(
    broadcasting: Boolean,
    micEnabled: Boolean,
    videoEnabled: Boolean,
    onDismiss: () -> Unit,
    onStart: (Boolean, Boolean) -> Unit,
    onMic: (Boolean) -> Unit,
    onVideo: (Boolean) -> Unit,
    onLeave: () -> Unit
) {
    var selectedMic by remember(micEnabled, broadcasting) { mutableStateOf(micEnabled) }
    var selectedVideo by remember(videoEnabled, broadcasting) { mutableStateOf(videoEnabled) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LiveSheetHeader(
                title = if (broadcasting) "Estás participando" else "Antes de unirte",
                subtitle = if (broadcasting) "Controla cómo te ven y escuchan" else "Revisa tu cámara y micrófono",
                icon = Icons.Filled.Videocam,
                onClose = onDismiss
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ToggleSettingRow(
                    icon = if (selectedVideo) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    title = "Cámara",
                    subtitle = if (selectedVideo) "Los participantes podrán verte" else "Entrarás sin video",
                    checked = selectedVideo,
                    onCheckedChange = {
                        selectedVideo = it
                        if (broadcasting) onVideo(it)
                    }
                )
                ToggleSettingRow(
                    icon = if (selectedMic) Icons.Filled.Mic else Icons.Filled.MicOff,
                    title = "Micrófono",
                    subtitle = if (selectedMic) "Los participantes podrán oírte" else "Entrarás en silencio",
                    checked = selectedMic,
                    onCheckedChange = {
                        selectedMic = it
                        if (broadcasting) onMic(it)
                    }
                )
                Text(
                    "La cámara y el micrófono no se activan hasta que pulses Entrar al directo.",
                    color = LivePalette.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (broadcasting) {
                SheetOutlineButton(
                    text = "Salir como invitado",
                    icon = Icons.Filled.CallEnd,
                    color = LivePalette.Red,
                    onClick = onLeave,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            } else {
                LiveGradientButton(
                    text = "Entrar al directo",
                    onClick = { onStart(selectedMic, selectedVideo) },
                    modifier = Modifier.padding(horizontal = 18.dp),
                    leading = {
                        Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerMoreSheet(
    canModerate: Boolean,
    onModerate: () -> Unit,
    onShare: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiveSheetHeader("Más opciones", "Acciones del directo", Icons.Filled.MoreHoriz, onDismiss)
            SheetActionRow(Icons.Filled.Share, "Compartir directo", "Invita a otras personas", onShare)
            if (canModerate) {
                SheetActionRow(Icons.Filled.Shield, "Moderación", "Gestiona el chat y los usuarios", onModerate)
            }
            SheetActionRow(
                Icons.Filled.CallEnd,
                "Salir del directo",
                "Dejar de ver esta transmisión",
                onExit,
                LivePalette.Red
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BattleInvitationSheet(
    battle: LiveBattleState,
    stream: LiveStream?,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onReject,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(LivePrimaryGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SportsEsports, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text("¡Te invitaron a una batalla!", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(
                (stream?.hostName ?: "El anfitrión") + " quiere competir contigo durante " +
                    battle.durationSeconds + " segundos.",
                color = LivePalette.Muted,
                fontSize = 13.sp
            )
            LiveGradientButton("Aceptar batalla", onAccept)
            SheetOutlineButton("Ahora no", Icons.Filled.Close, onClick = onReject)
        }
    }
}

@Composable
private fun LiveSheetHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 38.dp, height = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.22f))
    )
}

@Composable
private fun LiveSheetHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(LivePalette.Purple.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = LivePalette.Cyan, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = LivePalette.Muted, fontSize = 11.sp)
        }
        LiveRoundIconButton(
            Icons.Filled.Close,
            "Cerrar",
            onClose,
            size = 32.dp,
            containerColor = LivePalette.DeepSurface
        )
    }
}

@Composable
private fun ToggleSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LivePalette.DeepSurface)
            .border(1.dp, LivePalette.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Icon(icon, null, tint = if (checked) LivePalette.Cyan else LivePalette.Muted)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = LivePalette.Muted, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LivePalette.Purple,
                uncheckedThumbColor = LivePalette.Muted,
                uncheckedTrackColor = LivePalette.Border
            )
        )
    }
}

@Composable
private fun SheetOutlineButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = LivePalette.Cyan
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = LivePalette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HostTopBar(
    viewerCount: Int,
    stream: LiveStream?,
    metrics: LiveResponsiveMetrics,
    requestCount: Int,
    onRequests: () -> Unit,
    onEnd: () -> Unit
) {
    val elapsedSeconds by produceState(
        initialValue = elapsedSince(stream?.startedAt ?: stream?.createdAt),
        key1 = stream?.startedAt,
        key2 = stream?.createdAt
    ) {
        while (true) {
            value = elapsedSince(stream?.startedAt ?: stream?.createdAt)
            delay(1_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = metrics.horizontalPadding,
            vertical = if (metrics.compact) 6.dp else 10.dp
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (metrics.compact) 5.dp else 7.dp)
    ) {
        LivePill(
            text = "LIVE",
            background = LivePalette.Red,
            contentColor = Color.White,
            leadingContent = {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
            }
        )
        LivePill(
            text = formatCompactCount(viewerCount),
            leadingContent = {
                Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        )
        LivePill(text = formatDuration(elapsedSeconds))
        Spacer(Modifier.weight(1f))
        Box {
            LiveRoundIconButton(
                Icons.Filled.PersonAdd,
                "Invitados y solicitudes",
                onRequests,
                size = if (metrics.compact) 31.dp else 34.dp,
                tint = if (requestCount > 0) LivePalette.Cyan else Color.White,
                containerColor = Color.Black.copy(alpha = 0.55f)
            )
            if (requestCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(LivePalette.Red),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        requestCount.coerceAtMost(9).toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        LiveRoundIconButton(
            Icons.Filled.Close,
            "Finalizar",
            onEnd,
            size = if (metrics.compact) 31.dp else 34.dp,
            containerColor = Color.Black.copy(alpha = 0.55f),
            borderColor = Color.White.copy(alpha = 0.14f)
        )
    }
}

@Composable
private fun GuestRequestBanner(
    request: LiveGuestRequest,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LivePalette.Surface.copy(alpha = 0.94f))
            .border(1.dp, LivePalette.Purple.copy(alpha = 0.58f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        CircularProfileImage(
            imageUrl = request.avatarUrl,
            fallbackName = request.name,
            size = 32.dp,
            showBorder = true,
            borderColor = LivePalette.Cyan
        )
        Column(Modifier.weight(1f)) {
            Text(
                request.name + " quiere unirse",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (count > 1) count.toString() + " solicitudes pendientes" else "Toca para responder",
                color = LivePalette.Muted,
                fontSize = 10.sp
            )
        }
        LivePill("Ver", background = LivePalette.Purple)
    }
}

@Composable
private fun HostControlBar(
    metrics: LiveResponsiveMetrics,
    paused: Boolean,
    micEnabled: Boolean,
    source: LiveSource,
    videoEnabled: Boolean,
    battleActive: Boolean,
    onPauseResume: () -> Unit,
    onMic: () -> Unit,
    onCameraToggle: () -> Unit,
    onSwitchCamera: () -> Unit,
    onBattle: () -> Unit,
    onGifts: () -> Unit,
    onMore: () -> Unit
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(vertical = if (metrics.compact) 6.dp else 8.dp),
        contentPadding = PaddingValues(horizontal = metrics.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { HostControl(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (paused) "Reanudar" else "Pausa",
            active = paused,
            compact = metrics.compact,
            onClick = onPauseResume
        ) }
        item { HostControl(
            icon = if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            label = "Micrófono",
            active = !micEnabled,
            compact = metrics.compact,
            onClick = onMic
        ) }
        item { HostControl(
            icon = if (videoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            label = if (source == LiveSource.SCREEN) "Pantalla" else "Cámara",
            active = !videoEnabled,
            compact = metrics.compact,
            onClick = onCameraToggle
        ) }
        if (source == LiveSource.CAMERA && videoEnabled) item { HostControl(
            icon = Icons.Filled.Cameraswitch,
            label = "Girar",
            active = false,
            compact = metrics.compact,
            onClick = onSwitchCamera
        ) }
        item { HostControl(
            icon = Icons.Filled.SportsEsports,
            label = if (battleActive) "Batalla" else "PK",
            active = battleActive,
            compact = metrics.compact,
            onClick = onBattle
        ) }
        item { HostControl(
            icon = Icons.Filled.CardGiftcard,
            label = "Regalos",
            active = false,
            compact = metrics.compact,
            onClick = onGifts
        ) }
        item { HostControl(
            icon = Icons.Filled.MoreHoriz,
            label = "Más",
            active = false,
            compact = metrics.compact,
            onClick = onMore
        ) }
    }
}

@Composable
private fun HostControl(
    icon: ImageVector,
    label: String,
    active: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        LiveRoundIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            size = if (compact) 34.dp else 38.dp,
            tint = if (active) LivePalette.Cyan else Color.White,
            containerColor = if (active) LivePalette.Purple.copy(alpha = 0.40f) else LivePalette.Surface.copy(alpha = 0.84f)
        )
        Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MiniComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
            cursorBrush = SolidColor(LivePalette.Cyan),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        if (enabled) "Escribe en el chat…" else "Chat pausado",
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 11.sp
                    )
                }
                inner()
            }
        )
        LiveRoundIconButton(
            Icons.AutoMirrored.Filled.Send,
            "Enviar",
            onSend,
            size = 29.dp,
            containerColor = if (value.isNotBlank() && enabled) LivePalette.Cyan else Color.Transparent,
            borderColor = Color.Transparent
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestRequestsSheet(
    requests: List<LiveGuestRequest>,
    activeGuests: List<LiveGuestParticipant>,
    requestsAllowed: Boolean,
    onRequestsAllowed: (Boolean) -> Unit,
    onMuteGuest: (String) -> Unit,
    onRemoveGuest: (String) -> Unit,
    onDismiss: () -> Unit,
    onRespond: (String, Boolean) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveSheetHeader(
                "Solicitudes para unirse",
                activeGuests.size.toString() + "/3 invitados activos",
                Icons.Filled.Groups,
                onDismiss
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Permitir invitados", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (requestsAllowed) "Pueden solicitar unirse" else "Nuevas solicitudes cerradas",
                        color = LivePalette.Muted,
                        fontSize = 11.sp
                    )
                }
                Switch(checked = requestsAllowed, onCheckedChange = onRequestsAllowed)
            }
            activeGuests.forEach { guest ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(guest.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    LiveRoundIconButton(Icons.Filled.MicOff, "Silenciar a ${guest.name}",
                        { onMuteGuest(guest.userId) }, size = 32.dp)
                    LiveRoundIconButton(Icons.Filled.Close, "Retirar a ${guest.name}",
                        { onRemoveGuest(guest.userId) }, size = 32.dp, tint = LivePalette.Red)
                }
            }
            if (requests.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Groups, null, tint = LivePalette.Muted, modifier = Modifier.size(34.dp))
                    Text("No hay solicitudes pendientes", color = LivePalette.Muted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 410.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(requests, key = { it.socketId }) { request ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(LivePalette.DeepSurface)
                                .border(1.dp, LivePalette.Border, RoundedCornerShape(14.dp))
                                .padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProfileImage(
                                imageUrl = request.avatarUrl,
                                fallbackName = request.name,
                                size = 40.dp,
                                showBorder = true,
                                borderColor = LivePalette.Purple
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    request.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("Quiere participar en cámara", color = LivePalette.Muted, fontSize = 10.sp)
                            }
                            LiveRoundIconButton(
                                Icons.Filled.Close,
                                "Rechazar",
                                { onRespond(request.socketId, false) },
                                size = 34.dp,
                                tint = LivePalette.Red,
                                containerColor = LivePalette.Red.copy(alpha = 0.12f),
                                borderColor = LivePalette.Red.copy(alpha = 0.25f)
                            )
                            LiveRoundIconButton(
                                Icons.Filled.Check,
                                "Aceptar",
                                { onRespond(request.socketId, true) },
                                size = 34.dp,
                                tint = Color.White,
                                containerColor = LivePalette.Cyan,
                                borderColor = Color.Transparent
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostGiftsSheet(
    events: List<LiveGiftEvent>,
    onDismiss: () -> Unit
) {
    val grouped = events.groupBy { it.gift.name }.entries.sortedByDescending { entry ->
        entry.value.sumOf { it.gift.priceDiamonds }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveSheetHeader(
                "Regalos del directo",
                events.sumOf { it.gift.priceDiamonds }.toString() + " monedas recibidas",
                Icons.Filled.CardGiftcard,
                onDismiss
            )
            if (grouped.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.CardGiftcard, null, tint = LivePalette.Muted, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Los regalos aparecerán aquí", color = LivePalette.Muted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 390.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(grouped, key = { it.key }) { entry ->
                        val latest = entry.value.maxByOrNull { it.createdAtMs }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(LivePalette.DeepSurface)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(LivePalette.Purple.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.CardGiftcard, null, tint = LivePalette.Gold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.key, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    (latest?.senderName ?: "Usuario") + " y " + entry.value.size + " envío(s)",
                                    color = LivePalette.Muted,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                "+" + entry.value.sumOf { it.gift.priceDiamonds },
                                color = LivePalette.Cyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostBattleSheet(
    guests: List<LiveGuestParticipant>,
    candidates: List<LiveBattleCandidate>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onStartGuest: (String, Int) -> Unit,
    onStartCrossLive: (LiveBattleCandidate) -> Unit,
) {
    var selectedGuest by remember(guests) { mutableStateOf(guests.firstOrNull()?.socketId.orEmpty()) }
    var selectedCandidateId by remember(candidates) { mutableStateOf(candidates.firstOrNull()?.liveId.orEmpty()) }
    var opponentMode by remember(candidates, guests) {
        mutableStateOf(if (candidates.isNotEmpty()) "cross_live" else "guest")
    }
    var seconds by remember { mutableStateOf(180) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LiveSheetHeader("Iniciar batalla", "Compite por regalos y puntos", Icons.Filled.SportsEsports, onDismiss)
            if (!enabled) {
                Text(
                    "Las batallas están desactivadas para este directo.",
                    color = LivePalette.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)
                )
            } else if (guests.isEmpty() && candidates.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.PersonAdd, null, tint = LivePalette.Muted, modifier = Modifier.size(36.dp))
                    Text("No hay creadores disponibles", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Cuando otro creador inicie un LIVE público aparecerá aquí. También puedes competir con un invitado aceptado.", color = LivePalette.Muted, fontSize = 11.sp)
                }
            } else {
                if (candidates.isNotEmpty()) {
                    Text("Creadores en otros LIVE", color = LivePalette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        items(candidates, key = { it.liveId }) { candidate ->
                            ReceiverChip(
                                name = candidate.name + if (candidate.viewerCount > 0) " · ${candidate.viewerCount}" else "",
                                selected = opponentMode == "cross_live" && selectedCandidateId == candidate.liveId,
                                modifier = Modifier.width(158.dp),
                            ) {
                                opponentMode = "cross_live"
                                selectedCandidateId = candidate.liveId
                            }
                        }
                    }
                }
                if (guests.isNotEmpty()) {
                Text("Selecciona oponente", color = LivePalette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(guests, key = { it.socketId }) { guest ->
                        ReceiverChip(
                            name = guest.name,
                            selected = opponentMode == "guest" && selectedGuest == guest.socketId,
                            modifier = Modifier.width(138.dp)
                        ) {
                            opponentMode = "guest"
                            selectedGuest = guest.socketId
                        }
                    }
                }
                }
                Text("Duración", color = LivePalette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(60, 180, 300).forEach { option ->
                        ReceiverChip(
                            name = (option / 60).toString() + " min",
                            selected = seconds == option,
                            modifier = Modifier.weight(1f)
                        ) { seconds = option }
                    }
                }
                LiveGradientButton(
                    text = "Enviar invitación",
                    onClick = {
                        if (opponentMode == "cross_live") {
                            candidates.firstOrNull { it.liveId == selectedCandidateId }?.let(onStartCrossLive)
                        } else {
                            onStartGuest(selectedGuest, seconds)
                        }
                    },
                    enabled = if (opponentMode == "cross_live") selectedCandidateId.isNotBlank() else selectedGuest.isNotBlank(),
                    modifier = Modifier.padding(horizontal = 18.dp),
                    leading = {
                        Icon(Icons.Filled.SportsEsports, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostToolsSheet(
    paused: Boolean,
    source: LiveSource,
    videoEnabled: Boolean,
    onPauseResume: () -> Unit,
    onSettings: () -> Unit,
    onModeration: () -> Unit,
    onMoment: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCameraToggle: () -> Unit,
    onEffects: () -> Unit,
    onMusic: () -> Unit,
    onFloating: () -> Unit,
    onNotifications: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiveSheetHeader("Herramientas LIVE", "Control de transmisión", Icons.Filled.MoreHoriz, onDismiss)
            SheetActionRow(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (paused) "Reanudar directo" else "Pausar directo",
                "Mantiene la sala activa",
                onPauseResume
            )
            SheetActionRow(Icons.Filled.Settings, "Configuración", "Cámara, audio y calidad", onSettings)
            SheetActionRow(
                if (videoEnabled) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                when {
                    source == LiveSource.SCREEN && videoEnabled -> "Apagar pantalla"
                    source == LiveSource.SCREEN -> "Reactivar pantalla"
                    videoEnabled -> "Apagar cámara"
                    else -> "Activar cámara"
                },
                if (videoEnabled) "El audio del LIVE seguirá activo" else "Vuelve a publicar video sin reiniciar el LIVE",
                onCameraToggle,
            )
            SheetActionRow(Icons.Filled.Videocam, "Girar cámara", "Alterna entre cámara frontal y trasera", onSwitchCamera)
            SheetActionRow(Icons.Filled.Star, "Efectos", "Aplica el aspecto elegido en tiempo real", onEffects)
            SheetActionRow(Icons.Filled.MusicNote, "Música del LIVE", "Selecciona, pausa y ajusta el audio", onMusic)
            SheetActionRow(Icons.Filled.Shield, "Moderación", "Comentarios, palabras y moderadores", onModeration)
            SheetActionRow(Icons.Filled.Star, "Guardar momento", "Marca un instante destacado", onMoment)
            SheetActionRow(Icons.AutoMirrored.Filled.Chat, "Chat flotante", "Muestra el chat sobre otras apps", onFloating)
            SheetActionRow(Icons.Filled.Notifications, "Notificaciones", "Eventos del directo", onNotifications)
            SheetActionRow(Icons.Filled.CallEnd, "Finalizar directo", "Cierra la transmisión para todos", onEnd, LivePalette.Red)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostMusicSheet(
    state: LiveMusicPlaybackState,
    onPick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LivePalette.Surface,
        contentColor = Color.White,
        dragHandle = { LiveSheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiveSheetHeader("Música del LIVE", "Audio real para tus espectadores", Icons.Filled.MusicNote, onDismiss)
            SheetActionRow(
                Icons.Filled.MusicNote,
                if (state.isPrepared) "Cambiar canción" else "Elegir canción",
                state.displayName ?: "Elige un audio autorizado del dispositivo",
                onPick,
            )
            if (state.preparing) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = LivePalette.Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Preparando fragmento…", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            if (state.isPrepared) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reproducir en el LIVE", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Se mezcla con el micrófono y se repite", color = Color.White.copy(alpha = 0.64f), fontSize = 12.sp)
                    }
                    Switch(checked = state.enabled, onCheckedChange = onEnabled)
                }
                Text("Volumen", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
                    Slider(value = state.volume, onValueChange = onVolume, modifier = Modifier.weight(1f))
                    Text("${(state.volume * 100).toInt()}%", color = Color.White.copy(alpha = 0.72f), modifier = Modifier.width(44.dp))
                }
                SheetActionRow(Icons.Filled.Close, "Quitar canción", "Detiene y elimina el audio elegido", onRemove, LivePalette.Red)
            }
            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, color = LivePalette.Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun BattleScoreOverlay(
    battle: LiveBattleState,
    modifier: Modifier = Modifier
) {
    val remaining by produceState(
        initialValue = remainingBattleSeconds(battle),
        key1 = battle.startedAtMs,
        key2 = battle.status
    ) {
        while (battle.status == LiveBattleStatus.ACTIVE) {
            value = remainingBattleSeconds(battle)
            delay(1_000)
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.64f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight().background(LivePalette.Purple.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    battle.hostScore.toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Box(
                Modifier.width(68.dp).fillMaxHeight().background(LivePalette.DeepSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (battle.status == LiveBattleStatus.FINISHED) "FIN" else formatDuration(remaining.toLong()),
                    color = LivePalette.Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().background(LivePalette.Cyan.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    battle.opponentScore.toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        if (battle.status == LiveBattleStatus.FINISHED) {
            LivePill(
                text = when {
                    battle.hostScore > battle.opponentScore -> "Ganó el anfitrión"
                    battle.hostScore < battle.opponentScore -> "Ganó " + battle.opponentName
                    else -> "Empate"
                },
                modifier = Modifier.padding(top = 5.dp),
                background = LivePalette.Gold,
                contentColor = LivePalette.DeepSurface
            )
        }
    }
}

@Composable
private fun CompactChat(
    comments: List<LiveComment>,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val visible = comments.takeLast(60)
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }
    LazyColumn(
        modifier.height(height),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(visible, key = { it.id }) { comment ->
            Text(
                text = buildAnnotatedString {
                    pushStyle(SpanStyle(color = LivePalette.Cyan, fontWeight = FontWeight.ExtraBold))
                    append(comment.userName.ifBlank { "Usuario" })
                    pop()
                    append("  ")
                    pushStyle(SpanStyle(color = Color.White))
                    append(comment.content)
                    pop()
                },
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.43f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun elapsedSince(timestamp: String?): Long {
    if (timestamp.isNullOrBlank()) return 0L
    val started = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull() ?: return 0L
    return ((System.currentTimeMillis() - started) / 1_000L).coerceAtLeast(0L)
}

private fun remainingBattleSeconds(battle: LiveBattleState): Int {
    if (battle.status != LiveBattleStatus.ACTIVE || battle.startedAtMs <= 0L) {
        return battle.durationSeconds
    }
    val elapsed = ((System.currentTimeMillis() - battle.startedAtMs) / 1_000L).toInt()
    return (battle.durationSeconds - elapsed).coerceAtLeast(0)
}

private fun formatDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3_600
    val minutes = (safe % 3_600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        hours.toString().padStart(2, '0') + ":" +
            minutes.toString().padStart(2, '0') + ":" +
            seconds.toString().padStart(2, '0')
    } else {
        minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
    }
}

private fun formatCompactCount(value: Int): String = when {
    value >= 1_000_000 -> ((value / 100_000) / 10f).toString().removeSuffix(".0") + " M"
    value >= 1_000 -> ((value / 100) / 10f).toString().removeSuffix(".0") + " K"
    else -> value.coerceAtLeast(0).toString()
}
