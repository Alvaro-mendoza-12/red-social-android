package conectate.com.presentation.calls

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.CallParticipant
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallEndedSummary
import conectate.com.data.model.CallReactionEvent
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    navController: NavController,
    callId: String,
    viewModel: CallViewModel = hiltViewModel()
) {
    val call by viewModel.activeCall.collectAsStateWithLifecycle()
    val endedCallSummaryState by viewModel.endedCallSummary.collectAsStateWithLifecycle()
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeaker by viewModel.isSpeaker.collectAsStateWithLifecycle()
    val activeReactions by viewModel.activeReactions.collectAsStateWithLifecycle()
    var currentUserId = viewModel.getCurrentUserId()
    val context = LocalContext.current
    var hasMicPermission by remember { mutableStateOf(viewModel.hasMicrophonePermission()) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            viewModel.refreshAudioSession()
        }
    }

    var active = call?.takeIf { it.callId == callId }
    var endedSummary = endedCallSummaryState?.takeIf { it.callId == callId }

    var hasSeenActive by remember(callId) { mutableStateOf(false) }
    LaunchedEffect(active, endedSummary) {
        if (active != null) {
            hasSeenActive = true
        } else if (hasSeenActive && endedSummary == null) {
            navController.popBackStack()
        }
    }
    LaunchedEffect(active?.callId) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasMicPermission = granted
        if (granted) {
            viewModel.refreshAudioSession()
        } else {
            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(active?.callId, hasMicPermission) {
        if (active?.callId.isNullOrBlank() || !hasMicPermission) return viewModel.refreshAudioSessionRepeatedly(attempts = 3, delayMs = 500)
    }

    BackHandler(enabled = true) {
        // Do not hang up minimize by navigating back.
        viewModel.clearEndedCallSummary(callId)
        navController.popBackStack()
    }

    if (active == null) {
        if (endedSummary != null) {
            EndedCallSummaryScreen(
                summary = endedSummary,
                onSendMessage = {
                    val targetId = endedSummary.messageTargetId ?: return
                    viewModel.clearEndedCallSummary(callId)
                    navController.popBackStack()
                    navController.navigate(Screen.Chat.createRoute(targetId))
                },
                onClose = {
                    viewModel.clearEndedCallSummary(callId)
                    navController.popBackStack()
                }
            )
            return
        }
        Scaffold(containerColor = Color.Black) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasSeenActive) "" else "Cargando…",
                    color = Color.White
                )
            }
        }
        return
    }

    val dedupedParticipants = participants.distinctBy { participant ->
        participant.userId.ifBlank { participant.socketId }
    }
    val remoteParticipants = dedupedParticipants.filterNot { it.userId.equals(currentUserId, ignoreCase = true) }
    val primaryRemote = remoteParticipants.firstOrNull()
    val peerName = primaryRemote?.name ?: active.peerName ?: active.title
    val peerAvatarUrl = primaryRemote?.avatarUrl ?: active.peerAvatarUrl

    var durationText by remember(active.startedAtEpochMs, active.connectionState) { mutableStateOf("00:00") }
    LaunchedEffect(active.startedAtEpochMs, active.connectionState) {
        if (active.connectionState != CallConnectionState.CONNECTED || active.startedAtEpochMs == null) return
        while (true) {
            val elapsedMs = System.currentTimeMillis() - active.startedAtEpochMs
            val totalSeconds = (elapsedMs / 1000).toInt().coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            durationText = "%02d:%02d".format(minutes, seconds)
            delay(1000)
        }
    }

    val statusText = when (active.connectionState) {
        CallConnectionState.RINGING_OUTGOING -> "Llamando…"
        CallConnectionState.CONNECTING -> "Conectando…"
        CallConnectionState.CONNECTED -> durationText
        CallConnectionState.ENDED -> "Finalizada"
        else -> ""
    }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }

            val avatarSize = (maxHeight * 0.4f).coerceIn(160.dp, 320.dp)
            val shouldPulse = active.connectionState == CallConnectionState.RINGING_OUTGOING ||
                active.connectionState == CallConnectionState.CONNECTING

            val infinite = rememberInfiniteTransition(label = "callPulse")
            val pulseScale by infinite.animateFloat(
                initialValue = 0.92f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )
            val pulseAlpha by infinite.animateFloat(
                initialValue = 0.18f,
                targetValue = 0.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    if (!hasMicPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Activa el micrófono para hablar en la llamada",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFD54F)
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    if (remoteParticipants.size <= 1) {
                        Box(contentAlignment = Alignment.Center) {
                            if (shouldPulse) {
                                Box(
                                    modifier = Modifier
                                        .size(avatarSize)
                                        .graphicsLayer(
                                            scaleX = pulseScale,
                                            scaleY = pulseScale,
                                            alpha = pulseAlpha
                                        )
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(avatarSize)
                                        .graphicsLayer(
                                            scaleX = pulseScale * 1.12f,
                                            scaleY = pulseScale * 1.12f,
                                            alpha = pulseAlpha * 0.65f
                                        )
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                )
                            }

                            CircularProfileImage(
                                imageUrl = peerAvatarUrl,
                                size = avatarSize,
                                showBorder = false
                            )
                        }
                    } else {
                        Text(
                            text = "${remoteParticipants.size} personas conectadas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (remoteParticipants.size > 1) {
                        CallParticipantGrid(
                            participants = remoteParticipants,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    activeReactions.forEachIndexed { index, reaction ->
                        FloatingCallReaction(
                            reaction = reaction,
                            modifier = Modifier.offset {
                                IntOffset(
                                    x = if (index % 2 == 0) -60 else 60,
                                    y = 80 - (index * 18)
                                )
                            }
                        )
                    }
                }

                var showReactions by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (dedupedParticipants.size > 1) {
                        CallParticipantStrip(
                            participants = dedupedParticipants,
                            currentUserId = currentUserId
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    if (showReactions) {
                        ReactionRow(
                            onSelect = {
                                viewModel.sendReaction(it)
                                showReactions = false
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleMute(!isMuted) }) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mic",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { viewModel.toggleSpeaker(!isSpeaker) }) {
                            Icon(
                                imageVector = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = "Altavoz",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showReactions = !showReactions }) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "Reacción",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { viewModel.hangup() }) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Colgar",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CallParticipantStrip(
    participants: List<CallParticipant>,
    currentUserId: String?
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        items(
            items = participants,
            key = { it.socketId }
        ) { participant ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.width(70.dp)
            ) {
                CircularProfileImage(
                    imageUrl = participant.avatarUrl,
                    size = 48.dp,
                    showBorder = false
                )
                Text(
                    text = if (participant.userId.equals(currentUserId, ignoreCase = true)) "Tú" else participant.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CallParticipantGrid(
    participants: List<CallParticipant>,
    modifier: Modifier = Modifier
) {
    val rows = participants.chunked(2)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        rows.forEach { rowParticipants ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rowParticipants.forEach { participant ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(170.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.08f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProfileImage(
                                imageUrl = participant.avatarUrl,
                                size = 72.dp,
                                showBorder = false
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = participant.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = Color.White.copy(alpha = 0.08f),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (participant.isMuted) "Silenciado" else "Conectado",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.82f),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                if (rowParticipants.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EndedCallSummaryScreen(
    summary: CallEndedSummary,
    onSendMessage: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(containerColor = Color(0xFF101417)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                CircularProfileImage(
                    imageUrl = summary.peerAvatarUrl,
                    size = 112.dp,
                    showBorder = false
                )
                Text(
                    text = summary.peerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary.statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (summary.canMessage && !summary.messageTargetId.isNullOrBlank()) {
                        OutlinedButton(onClick = onSendMessage) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enviar mensaje")
                        }
                    }
                    Button(onClick = onClose) {
                        Icon(Icons.Default.CallEnd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}


@Composable
private fun ReactionRow(onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val emojis = listOf("👍", "😂", "🔥", "❤️", "👏")
        emojis.forEach { emoji ->
            IconButton(
                onClick = { onSelect(emoji) },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
            ) {
                Text(text = emoji, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FloatingCallReaction(
    reaction: CallReactionEvent,
    modifier: Modifier = Modifier
) {
    val floatTransition = rememberInfiniteTransition(label = "callReactionFloat")
    val offsetY by floatTransition.animateFloat(
        initialValue = 0f,
        targetValue = -140f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "callReactionOffset"
    )
    val alpha by floatTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "callReactionAlpha"
    )

    Box(
        modifier = modifier
            .offset(y = offsetY.dp)
            .graphicsLayer(alpha = alpha)
    ) {
        Text(
            text = reaction.emoji,
            style = MaterialTheme.typography.headlineLarge
        )
    }
}









