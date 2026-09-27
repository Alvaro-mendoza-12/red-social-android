@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveCreatorLayoutStyle
import conectate.com.data.model.LiveSource
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

internal object NovvinLiveColors {
    val Background = Color(0xFF0B0B0E)
    val Surface = Color(0xFF17171C)
    val RaisedSurface = Color(0xFF232329)
    val Live = Color(0xFFE7183C)
    val LiveSoft = Color(0xFF861E39)
    val Healthy = Color(0xFF19D3B2)
    val Warning = Color(0xFFFFB36B)
    val SecondaryText = Color(0xFFB9BAC4)
}

@Composable
internal fun LiveCreatorStyleSheet(
    selected: LiveCreatorLayoutStyle,
    onSelect: (LiveCreatorLayoutStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NovvinLiveColors.Surface,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Elige tu vista de creación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "La vista cambia la organización de tus controles, no los permisos ni la calidad del live.",
                color = NovvinLiveColors.SecondaryText,
            )
            LiveCreatorLayoutStyle.entries.forEach { style ->
                Surface(
                    onClick = {
                        onSelect(style)
                        onDismiss()
                    },
                    color = if (selected == style) NovvinLiveColors.LiveSoft.copy(alpha = 0.46f) else NovvinLiveColors.RaisedSurface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(style.displayName, fontWeight = FontWeight.Bold)
                            Text(style.description(), color = NovvinLiveColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected == style) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Vista seleccionada", tint = NovvinLiveColors.Healthy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveCameraFilterSheet(
    selected: LiveCameraFilter,
    onSelect: (LiveCameraFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NovvinLiveColors.Surface,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filtros de cámara", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("El filtro se aplica a la previsualización y al video publicado.", color = NovvinLiveColors.SecondaryText)
            LiveCameraFilter.entries.forEach { filter ->
                Surface(
                    onClick = {
                        onSelect(filter)
                        onDismiss()
                    },
                    color = if (selected == filter) NovvinLiveColors.LiveSoft.copy(alpha = 0.46f) else NovvinLiveColors.RaisedSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(filter.uiName(), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        if (selected == filter) Icon(Icons.Default.CheckCircle, contentDescription = "Filtro seleccionado", tint = NovvinLiveColors.Healthy)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveCreatorPreviewStage(
    style: LiveCreatorLayoutStyle,
    avatarUrl: String?,
    displayName: String,
    title: String,
    category: String,
    source: LiveSource,
    networkAvailable: Boolean,
    micEnabled: Boolean,
    allowGuestRequests: Boolean,
    maxGuests: Int,
    cameraFilter: LiveCameraFilter,
    localVideoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context?,
    previewBusy: Boolean,
    screenPermissionReady: Boolean,
    onSourceChange: (LiveSource) -> Unit,
    onTryCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onAuthorizeScreen: () -> Unit,
    onToggleMic: () -> Unit,
    onOpenFilters: () -> Unit,
    onToggleGuests: () -> Unit,
    onClose: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val height = when (style) {
        LiveCreatorLayoutStyle.STUDIO -> 378.dp
        LiveCreatorLayoutStyle.IMMERSIVE -> 520.dp
        LiveCreatorLayoutStyle.ROOM -> 405.dp
        LiveCreatorLayoutStyle.FUSION -> 480.dp
    }
    val hasCameraPreview = source == LiveSource.CAMERA && localVideoTrack != null && eglBaseContext != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF171324), Color(0xFF401727), Color(0xFF093B43)),
                ),
            ),
    ) {
        if (hasCameraPreview) {
            WebRtcVideoView(
                videoTrack = localVideoTrack,
                sharedEglContext = eglBaseContext,
                mirror = true,
                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.20f), Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    ),
                ),
        )

        when (style) {
            LiveCreatorLayoutStyle.STUDIO -> Row(
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveTechnicalPill("PRUEBA PRIVADA", NovvinLiveColors.Live)
                LiveTechnicalPill(
                    if (networkAvailable) "Red estable" else "Sin red",
                    if (networkAvailable) NovvinLiveColors.Healthy else NovvinLiveColors.Warning,
                )
            }

            LiveCreatorLayoutStyle.IMMERSIVE,
            LiveCreatorLayoutStyle.FUSION -> {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                    Column {
                        Text(
                            if (style == LiveCreatorLayoutStyle.IMMERSIVE) "Preparar directo" else "Crear directo",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(0xFF38DC7A), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (networkAvailable) "Red detectada · transmisión sin verificar" else "Revisa tu conexión",
                                color = if (networkAvailable) Color(0xFF55E58C) else NovvinLiveColors.Warning,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                LiveTechnicalPill(
                    if (networkAvailable) "Red detectada" else "Sin red",
                    if (networkAvailable) Color(0xFF27E38C) else NovvinLiveColors.Warning,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 14.dp),
                )
            }

            LiveCreatorLayoutStyle.ROOM -> {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                    Column {
                        Text("Crear directo", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            if (networkAvailable) "●  Listo técnicamente    ◉  Buen estado" else "●  Revisa tu conexión",
                            color = if (networkAvailable) NovvinLiveColors.Healthy else NovvinLiveColors.Warning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(
                    onClick = onOpenAdvanced,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp)
                        .size(44.dp).background(Color.Black.copy(alpha = 0.48f), CircleShape),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Ajustes del directo", tint = Color.White)
                }
                LiveTechnicalPill(
                    "Invitados ${if (allowGuestRequests) "0/$maxGuests" else "cerrados"}",
                    Color(0xFFFF6780),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 66.dp),
                )
            }
        }

        if (style == LiveCreatorLayoutStyle.STUDIO) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiveStatusButton(Icons.Default.CameraAlt, "Cámara", hasCameraPreview, onTryCamera)
                LiveStatusButton(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, "Micrófono", micEnabled, onToggleMic)
            }
            Surface(
                onClick = onOpenFilters,
                enabled = source == LiveSource.CAMERA,
                color = Color.Black.copy(alpha = 0.68f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(76.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text("Filtros", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Box(Modifier.size(7.dp).background(NovvinLiveColors.Healthy, CircleShape))
                }
            }
        }

        if (style == LiveCreatorLayoutStyle.IMMERSIVE || style == LiveCreatorLayoutStyle.FUSION) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LiveRailButton(Icons.Default.Cameraswitch, "Girar", onSwitchCamera, hasCameraPreview, showLabel = style == LiveCreatorLayoutStyle.IMMERSIVE)
                LiveRailButton(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, "Micrófono", onToggleMic, true, showLabel = style == LiveCreatorLayoutStyle.IMMERSIVE)
                LiveRailButton(Icons.Default.Tune, "Filtros", onOpenFilters, source == LiveSource.CAMERA, showLabel = style == LiveCreatorLayoutStyle.IMMERSIVE)
            }
        }

        if (!hasCameraPreview) {
            when {
                style == LiveCreatorLayoutStyle.ROOM || source == LiveSource.AUDIO -> RoomPreview(
                    avatarUrl = avatarUrl,
                    displayName = displayName,
                    guestCount = maxGuests.coerceIn(1, 6),
                    requestsEnabled = allowGuestRequests,
                    onToggleGuests = onToggleGuests,
                )
                source == LiveSource.SCREEN -> PreviewEmptyState(
                    avatarUrl = avatarUrl,
                    label = if (screenPermissionReady) "Pantalla autorizada" else "Probar pantalla",
                    onClick = onAuthorizeScreen,
                )
                else -> PreviewEmptyState(
                    avatarUrl = avatarUrl,
                    label = if (previewBusy) "Abriendo…" else "Probar cámara",
                    onClick = onTryCamera,
                )
            }
        }

        LiveSourceSegmentedControl(
            selected = source,
            onSelect = onSourceChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )

        if (style == LiveCreatorLayoutStyle.IMMERSIVE) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 82.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LiveTechnicalPill("Tu audio", NovvinLiveColors.Healthy)
                LiveTechnicalPill(if (networkAvailable) "Excelente" else "Sin red", if (networkAvailable) NovvinLiveColors.Healthy else NovvinLiveColors.Warning)
            }
        }

    }
}

@Composable
private fun BoxScope.RoomPreview(
    avatarUrl: String?,
    displayName: String,
    guestCount: Int,
    requestsEnabled: Boolean,
    onToggleGuests: () -> Unit,
) {
    Column(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 82.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CircularProfileImage(imageUrl = avatarUrl, size = 78.dp, showBorder = true)
        AudioMeter(active = true)
        Text("Solo voz", color = Color(0xFFFF5B78), fontWeight = FontWeight.Black, style = MaterialTheme.typography.bodyMedium)
        Text("Tú eres el anfitrión", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
    }

    RoomGuestSlot(
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp, top = 18.dp),
        requestsEnabled = requestsEnabled,
        onClick = onToggleGuests,
    )
    RoomGuestSlot(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp, top = 18.dp),
        requestsEnabled = requestsEnabled,
        onClick = onToggleGuests,
    )
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 26.dp, end = 26.dp, bottom = 72.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        repeat(guestCount.coerceAtMost(4)) {
            RoomGuestSlot(requestsEnabled = requestsEnabled, onClick = onToggleGuests)
        }
    }
}

@Composable
private fun RoomGuestSlot(
    requestsEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.48f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (requestsEnabled) Color(0xFFFF5B78) else Color.White.copy(alpha = 0.28f),
            ),
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            if (requestsEnabled) "Solicitud abierta" else "Cerrado",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BoxScope.PreviewEmptyState(avatarUrl: String?, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProfileImage(imageUrl = avatarUrl, size = 82.dp, showBorder = true)
        Surface(onClick = onClick, color = Color.White, contentColor = Color.Black, shape = RoundedCornerShape(999.dp)) {
            Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun LiveSourceSegmentedControl(
    selected: LiveSource,
    onSelect: (LiveSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveSource.entries.forEach { source ->
            val active = selected == source
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) NovvinLiveColors.Live else Color.Transparent)
                    .clickable { onSelect(source) }
                    .padding(horizontal = 8.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(source.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text(source.displayName(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LiveTechnicalPill(text: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color.Black.copy(alpha = 0.60f), contentColor = Color.White, shape = RoundedCornerShape(999.dp)) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    showLabel: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.30f))
        }
        if (showLabel) Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LiveStatusButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(18.dp)) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Box(Modifier.padding(top = 4.dp).size(7.dp).background(if (active) Color(0xFF36D878) else NovvinLiveColors.Warning, CircleShape))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AudioMeter(active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(10.dp, 18.dp, 26.dp, 16.dp, 22.dp, 12.dp).forEach { height ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) NovvinLiveColors.Healthy else Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

private fun LiveCreatorLayoutStyle.description(): String = when (this) {
    LiveCreatorLayoutStyle.STUDIO -> "Información clara y controles ordenados."
    LiveCreatorLayoutStyle.IMMERSIVE -> "La cámara ocupa el centro de la preparación."
    LiveCreatorLayoutStyle.ROOM -> "Optimizada para voz, invitados y conversación."
    LiveCreatorLayoutStyle.FUSION -> "Previsualización inmersiva con controles completos."
}

private fun LiveCameraFilter.uiName(): String = when (this) {
    LiveCameraFilter.NONE -> "Natural"
    LiveCameraFilter.WARM -> "Cálido"
    LiveCameraFilter.COOL -> "Frío"
    LiveCameraFilter.VIVID -> "Vívido"
    LiveCameraFilter.SOFT -> "Suave"
    LiveCameraFilter.MONO -> "Monocromo"
}

private fun LiveSource.displayName(): String = when (this) {
    LiveSource.CAMERA -> "Cámara"
    LiveSource.SCREEN -> "Pantalla"
    LiveSource.AUDIO -> "Solo voz"
}

private fun LiveSource.icon() = when (this) {
    LiveSource.CAMERA -> Icons.Default.CameraAlt
    LiveSource.SCREEN -> Icons.AutoMirrored.Filled.ScreenShare
    LiveSource.AUDIO -> Icons.Default.Mic
}
