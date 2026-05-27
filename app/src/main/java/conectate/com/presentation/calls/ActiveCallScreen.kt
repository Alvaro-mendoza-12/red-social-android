package conectate.com.presentation.calls

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.CallConnectionState
import conectate.com.presentation.components.WebRtcVideoView
import kotlinx.coroutines.delay

@Composable
fun ActiveCallScreen(
    viewModel: CallViewModel,
    onClose: () -> Unit
) {
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteVideoTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeaker by viewModel.isSpeaker.collectAsStateWithLifecycle()
    val eglContext = viewModel.eglContext

    var isCameraEnabled by remember { mutableStateOf(localVideoTrack != null) }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (isCameraEnabled) viewModel.toggleVideo(false)
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (isCameraEnabled) viewModel.toggleVideo(true)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var durationSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(activeCall?.connectionState) {
        if (activeCall?.connectionState == CallConnectionState.CONNECTED) {
            while (true) {
                delay(1000)
                durationSeconds++
            }
        }
    }

    if (activeCall == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        // 1. Remote Video / Background
        val remoteTrack = remoteVideoTracks.values.firstOrNull()
        if (remoteTrack != null) {
            WebRtcVideoView(
                videoTrack = remoteTrack,
                sharedEglContext = eglContext,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Audio-only or Calling fallback with gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1A1A), Color(0xFF000000))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(60.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = activeCall?.peerName ?: activeCall?.title ?: "Llamada",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = getStatusText(activeCall?.connectionState, durationSeconds),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // 2. Local Video (Modern PiP)
        if (localVideoTrack != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                WebRtcVideoView(
                    videoTrack = localVideoTrack,
                    sharedEglContext = eglContext,
                    mirror = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. Top Action (Minimize)
        IconButton(
            onClick = { /* Minimize */ },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 8.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }

        // 4. Bottom Controls (Modern Island)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.wrapContentWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Toggle
                CallControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    isActive = !isMuted,
                    onClick = { viewModel.toggleMute(!isMuted) }
                )

                // Video Toggle
                CallControlButton(
                    icon = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    isActive = isCameraEnabled,
                    onClick = { 
                        isCameraEnabled = !isCameraEnabled
                        viewModel.toggleVideo(isCameraEnabled)
                    }
                )

                // Speaker Toggle
                CallControlButton(
                    icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                    isActive = isSpeaker,
                    onClick = { viewModel.toggleSpeaker(!isSpeaker) }
                )

                // Hang up - Distinguished
                IconButton(
                    onClick = { viewModel.hangup() },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Finalizar",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        
        // Flip Camera if enabled
        if (isCameraEnabled) {
            IconButton(
                onClick = { viewModel.switchCamera() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 24.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(Icons.Default.FlipCameraAndroid, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun CallControlButton(
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (isActive) Color.White.copy(alpha = 0.15f) else Color.White)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) Color.White else Color.Black,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun getStatusText(state: CallConnectionState?, duration: Long): String {
    return when (state) {
        CallConnectionState.RINGING_OUTGOING -> "Llamando..."
        CallConnectionState.RINGING_INCOMING -> "Llamada entrante..."
        CallConnectionState.CONNECTING -> "Conectando..."
        CallConnectionState.CONNECTED -> formatDuration(duration)
        CallConnectionState.RECONNECTING -> "Reconectando..."
        CallConnectionState.ENDED -> "Llamada finalizada"
        else -> ""
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
