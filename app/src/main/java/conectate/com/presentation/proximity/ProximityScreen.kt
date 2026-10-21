package conectate.com.presentation.proximity

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import conectate.com.presentation.components.rememberRealtimeProgress
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.AnonymousBottle
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.messages.getBestCurrentLocation
import conectate.com.presentation.messages.hasLocationPermission
import conectate.com.presentation.messages.isDeviceLocationEnabled
import conectate.com.presentation.navigation.Screen
import coil.compose.AsyncImage
import conectate.com.presentation.components.FullScreenImageDialog
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityScreen(
    navController: NavController,
    viewModel: ProximityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.nearbyProfiles.collectAsState()
    val nearbyEnabled by viewModel.nearbyEnabled.collectAsState()
    val radiusKm by viewModel.radiusKm.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val session by viewModel.anonymousSession.collectAsState()
    val sessions by viewModel.anonymousSessions.collectAsState()
    val messages by viewModel.anonymousMessages.collectAsState()
    val outgoingBottle by viewModel.outgoingBottle.collectAsState()
    val incomingBottle by viewModel.incomingBottle.collectAsState()
    val normalChatPeerBySession by viewModel.normalChatPeerBySession.collectAsState()
    val bottleInboxStatus by viewModel.bottleInboxStatus.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var radiusSlider by remember(radiusKm) { mutableFloatStateOf(radiusKm.toFloat()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingLocationRequest by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val locationScope = rememberCoroutineScope()

    fun performLocationAction() {
        if (locating) return
        if (!isDeviceLocationEnabled(context)) {
            localError = "La ubicación del dispositivo está desactivada. Actívala y vuelve a intentarlo."
            return
        }
        locationScope.launch {
            locating = true
            localError = null
            val location = getBestCurrentLocation(context)
            locating = false
            if (location == null) {
                localError = "No se obtuvo una ubicación actual. Comprueba que GPS o Wi-Fi estén activos e inténtalo de nuevo."
                return@launch
            }
            viewModel.setRadius(radiusSlider.toDouble())
            viewModel.enableNearby(location.latitude, location.longitude)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val requestPending = pendingLocationRequest
        pendingLocationRequest = false
        if (granted && requestPending) performLocationAction()
        else if (!granted) localError = "Se necesita permiso de ubicación para usar proximidad."
    }

    fun requestLocation() {
        if (hasLocationPermission(context)) {
            performLocationAction()
        } else {
            pendingLocationRequest = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val visibleError = localError ?: errorMessage
    LaunchedEffect(visibleError) {
        if (!visibleError.isNullOrBlank()) {
            AppFeedbackBus.showError(visibleError)
            kotlinx.coroutines.delay(4_000)
            localError = null
            viewModel.clearError()
        }
    }

    val bottleLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(selectedTab, bottleLifecycleOwner) {
        fun updateVisibility() {
            viewModel.setBottleInboxVisible(selectedTab == 1 &&
                bottleLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateVisibility() }
        bottleLifecycleOwner.lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            bottleLifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setBottleInboxVisible(false)
        }
    }

    val conversationOpen = selectedTab == 1 && session != null
    Scaffold(
        topBar = {
            if (!conversationOpen) {
                TopAppBar(
                    title = { Text("Conectar") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!conversationOpen) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Radar") },
                        icon = { Icon(Icons.Default.MyLocation, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Botella al mar") },
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
                    )
                }
            }
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            when (selectedTab) {
                0 -> NearbyTab(
                    profiles = profiles,
                    enabled = nearbyEnabled,
                    radius = radiusSlider,
                    onRadiusChange = {
                        radiusSlider = it
                        viewModel.setRadius(it.toDouble())
                    },
                    onEnable = { requestLocation() },
                    locating = locating,
                    onRefresh = viewModel::refreshNearby,
                    onDisable = viewModel::disableNearby,
                    onOpenProfile = { navController.navigate(Screen.UserProfile.createRoute(it)) }
                )
                else -> AnonymousTab(
                    session = session,
                    sessions = sessions,
                    messages = messages,
                    outgoingBottle = outgoingBottle,
                    incomingBottle = incomingBottle,
                    inboxStatus = bottleInboxStatus,
                    busy = isLoading,
                    onLaunchBottle = viewModel::sendAnonymousBottle,
                    onCancelBottle = viewModel::cancelOutgoingBottle,
                    onAcceptBottle = viewModel::acceptIncomingBottle,
                    onDeclineBottle = viewModel::declineIncomingBottle,
                    onSend = viewModel::sendAnonymousMessage,
                    onSendSticker = viewModel::sendAnonymousSticker,
                    onSendMedia = viewModel::sendAnonymousMedia,
                    onEnd = viewModel::endAnonymousSession,
                    onSelectSession = viewModel::selectAnonymousSession,
                    onBackToSessions = viewModel::showAnonymousSessionList,
                    normalChatPeerId = session?.id?.let(normalChatPeerBySession::get),
                    onOpenProfile = { userId -> navController.navigate(Screen.UserProfile.createRoute(userId)) },
                    onOpenNormalChat = { userId -> navController.navigate(Screen.Chat.createRoute(userId)) },
                )
            }
        }
    }
}

@Composable
private fun NearbyTab(
    profiles: List<NearbyProfile>,
    enabled: Boolean,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    onEnable: () -> Unit,
    locating: Boolean,
    onRefresh: () -> Unit,
    onDisable: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RadarVisualization(
                enabled = enabled,
                radiusKm = radius,
                profiles = profiles,
            )
            Spacer(Modifier.height(12.dp))
            ProximityPrivacyNotice()
            Spacer(Modifier.height(12.dp))
            RadiusControl(radius, onRadiusChange)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnable,
                    enabled = !locating,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (locating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(if (enabled) Icons.Default.LocationOn else Icons.Default.MyLocation, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (locating) "Buscando ubicación…" else if (enabled) "Actualizar" else "Activar radar")
                }
                if (enabled) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar personas")
                    }
                    IconButton(onClick = onDisable) {
                        Icon(Icons.Default.LocationOff, contentDescription = "Desactivar radar")
                    }
                }
            }
        }

        if (enabled && profiles.isEmpty()) {
            item {
                Text(
                    "No hay personas disponibles dentro de este radio.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(profiles, key = { it.userId }) { profile ->
            NearbyProfileRow(profile = profile, onClick = { onOpenProfile(profile.userId) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun RadarVisualization(
    enabled: Boolean,
    radiusKm: Float,
    profiles: List<NearbyProfile>,
) {
    val sweepProgress = rememberRealtimeProgress(
        key = "radar-sweep",
        durationMillis = 2_800L,
        running = enabled,
        repeat = true,
    )
    val pulseProgress = rememberRealtimeProgress(
        key = "radar-pulse",
        durationMillis = 1_900L,
        running = enabled,
        repeat = true,
    )
    val sweepAngle = sweepProgress * 360f
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f),
        shape = RoundedCornerShape(24.dp),
        color = surface,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = min(size.width, size.height) * 0.46f
                repeat(4) { index ->
                    drawCircle(
                        color = primary.copy(alpha = 0.16f + index * 0.03f),
                        radius = outerRadius * (index + 1) / 4f,
                        center = center,
                        style = Stroke(width = 2f),
                    )
                }
                drawLine(
                    color = primary.copy(alpha = 0.14f),
                    start = Offset(center.x - outerRadius, center.y),
                    end = Offset(center.x + outerRadius, center.y),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = primary.copy(alpha = 0.14f),
                    start = Offset(center.x, center.y - outerRadius),
                    end = Offset(center.x, center.y + outerRadius),
                    strokeWidth = 2f,
                )
                if (enabled) {
                    drawArc(
                        color = primary.copy(alpha = 0.28f),
                        startAngle = sweepAngle - 48f,
                        sweepAngle = 48f,
                        useCenter = true,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                    )
                    val sweepRadians = Math.toRadians(sweepAngle.toDouble())
                    drawLine(
                        color = primary.copy(alpha = 0.92f),
                        start = center,
                        end = Offset(
                            x = center.x + cos(sweepRadians).toFloat() * outerRadius,
                            y = center.y + sin(sweepRadians).toFloat() * outerRadius,
                        ),
                        strokeWidth = 5f,
                    )
                    drawCircle(
                        color = primary.copy(alpha = (1f - pulseProgress) * 0.28f),
                        radius = outerRadius * pulseProgress,
                        center = center,
                        style = Stroke(width = 4f),
                    )
                    repeat(14) { trailIndex ->
                        val trailFraction = 1f - (trailIndex / 14f)
                        val radians = Math.toRadians(
                            (sweepAngle - trailIndex * 2.5f).toDouble()
                        )
                        val beamEnd = Offset(
                            x = center.x + cos(radians).toFloat() * outerRadius,
                            y = center.y + sin(radians).toFloat() * outerRadius,
                        )
                        drawLine(
                            color = primary.copy(alpha = 0.08f + 0.76f * trailFraction),
                            start = center,
                            end = beamEnd,
                            strokeWidth = 2f + 5f * trailFraction,
                        )
                    }
                    profiles.take(24).forEach { profile ->
                        val angle = Math.toRadians((profile.userId.hashCode().toUInt().toLong() % 360).toDouble())
                        val fraction = (profile.distanceKm / radiusKm.coerceAtLeast(1f))
                            .toFloat()
                            .coerceIn(0.12f, 0.95f)
                        drawCircle(
                            color = Color(0xFF45E08A),
                            radius = 7f,
                            center = Offset(
                                center.x + cos(angle).toFloat() * outerRadius * fraction,
                                center.y + sin(angle).toFloat() * outerRadius * fraction,
                            ),
                        )
                    }
                    drawCircle(color = primary, radius = 9f, center = center)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (enabled) Icons.Default.MyLocation else Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = primary,
                )
                Text(
                    if (enabled) "Buscando · ${radiusKm.toInt()} km" else "Radar apagado",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (enabled) "${profiles.size} personas dentro del radio" else "Actívalo para comenzar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProximityPrivacyNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                "Tu ubicación se redondea, caduca en 2 horas y nunca se muestra a otras personas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RadiusControl(radius: Float, onRadiusChange: (Float) -> Unit) {
    Text("Radio: ${radius.toInt()} km", fontWeight = FontWeight.SemiBold)
    androidx.compose.material3.Slider(
        value = radius,
        onValueChange = onRadiusChange,
        valueRange = 1f..20f,
        steps = 18,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NearbyProfileRow(profile: NearbyProfile, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(ownerId = profile.userId, imageUrl = profile.avatarUrl, size = 52.dp, showBorder = true)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val location = listOf(profile.city, profile.country).filter { it.isNotBlank() }.joinToString(", ")
                if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.bodySmall)
                if (profile.interests.isNotEmpty()) {
                    Text(profile.interests.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (profile.distanceKm < 1) "< 1 km" else "${profile.distanceKm} km",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnonymousTab(
    session: AnonymousChatSession?,
    sessions: List<AnonymousChatSession>,
    messages: List<AnonymousChatMessage>,
    outgoingBottle: AnonymousBottle?,
    incomingBottle: AnonymousBottle?,
    inboxStatus: BottleInboxStatus,
    busy: Boolean,
    onLaunchBottle: (String) -> Unit,
    onCancelBottle: () -> Unit,
    onAcceptBottle: () -> Unit,
    onDeclineBottle: () -> Unit,
    onSend: (String) -> Unit,
    onSendSticker: (String?, String) -> Unit,
    onSendMedia: (android.net.Uri, String) -> Unit,
    onEnd: () -> Unit,
    onSelectSession: (String) -> Unit,
    onBackToSessions: () -> Unit,
    normalChatPeerId: String?,
    onOpenProfile: (String) -> Unit,
    onOpenNormalChat: (String) -> Unit,
) {
    var bottleMessage by remember { mutableStateOf("") }
    LaunchedEffect(outgoingBottle?.id) {
        if (outgoingBottle != null) bottleMessage = ""
    }

    if (session != null) {
        AnonymousConversation(
            session = session,
            messages = messages,
            onSend = onSend,
            onSendSticker = onSendSticker,
            onSendMedia = onSendMedia,
            onEnd = onEnd,
            onBack = onBackToSessions,
            normalChatPeerId = normalChatPeerId,
            onOpenProfile = onOpenProfile,
            onOpenNormalChat = onOpenNormalChat,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Una botella al mar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Envía un mensaje o recibe una botella de otra persona. Si alguien responde, empieza un chat anónimo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item(key = "bottle_inbox") {
            BottleInboxCard(
                bottle = incomingBottle,
                status = inboxStatus,
                busy = busy,
                onAccept = onAcceptBottle,
                onDecline = onDeclineBottle,
            )
        }

        if (outgoingBottle != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 4.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (outgoingBottle.status == "offered") "Esperando una respuesta" else "Tu botella está viajando",
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (outgoingBottle.status == "offered") {
                                        "Alguien la está leyendo. Si no la acepta, seguirá automáticamente."
                                    } else {
                                        "Buscando a alguien que quiera responder…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(
                            "“${outgoingBottle.content}”",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (outgoingBottle.attemptCount > 0) {
                            Text(
                                "Ha continuado su viaje ${outgoingBottle.attemptCount} ${if (outgoingBottle.attemptCount == 1) "vez" else "veces"}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onCancelBottle,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retirar botella")
                        }
                    }
                }
            }
        } else {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Enviar botella", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = bottleMessage,
                            onValueChange = { bottleMessage = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Algo que te gustaría decirle a alguien…") },
                            supportingText = { Text("${bottleMessage.length}/500") },
                            minLines = 3,
                            maxLines = 6,
                        )
                        Button(
                            onClick = { onLaunchBottle(bottleMessage) },
                            enabled = bottleMessage.isNotBlank() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Lanzar botella")
                        }
                    }
                }
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "La búsqueda es global y no comparte tu ubicación ni tu identidad. Cada persona recibe una sola oportunidad para aceptar o pasar.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (sessions.isNotEmpty()) {
            item {
                Text(
                    "Tus chats anónimos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(sessions, key = { it.id }) { anonymousSession ->
                AnonymousSessionRow(
                    session = anonymousSession,
                    onClick = { onSelectSession(anonymousSession.id) },
                )
            }
        }
        item {
            Text(
                "Al aceptar una botella empieza el chat anónimo. La identidad se revela gradualmente según la confianza de la conversación.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun BottleInboxCard(
    bottle: AnonymousBottle?,
    status: BottleInboxStatus,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Recibir botellas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (bottle == null) {
                Text(
                    when (status) {
                        BottleInboxStatus.INACTIVE, BottleInboxStatus.CONNECTING -> "Conectando para recibir…"
                        BottleInboxStatus.ERROR -> "No pudimos conectar. Volveremos a intentarlo automáticamente."
                        BottleInboxStatus.WAITING, BottleInboxStatus.RECEIVED -> "Todavía no te ha llegado una botella."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Puedes recibir sin enviar un mensaje. Mantén este apartado abierto para que te lleguen botellas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Te llegó una botella", fontWeight = FontWeight.SemiBold)
                Text(bottle.content, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Responde para abrir un chat anónimo o pasa para que siga hacia otra persona.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDecline,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Pasar") }
                    Button(
                        onClick = onAccept,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Responder") }
                }
            }
        }
    }
}

@Composable
private fun AnonymousSessionRow(
    session: AnonymousChatSession,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.peer.avatarUrl != null) {
                CircularProfileImage(ownerId = session.peer.userId, imageUrl = session.peer.avatarUrl, size = 46.dp, showBorder = true)
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(11.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(session.peer.displayName, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { session.friendshipPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    session.lastMessage?.let { last ->
                        "${if (session.lastMessageSender == "me") "Tú: " else ""}$last"
                    } ?: "Sin mensajes todavía",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Confianza: ${session.friendshipPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.unreadCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        session.unreadCount.coerceAtMost(99).toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AnonymousMessageBubble(message: AnonymousChatMessage) {
    var imageOpened by remember(message.id, message.mediaUrl) { mutableStateOf(false) }
    val mine = message.sender == "me"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                when (message.contentType) {
                    "image", "sticker" -> {
                        message.mediaUrl?.let { mediaUrl ->
                            AsyncImage(
                                model = mediaUrl,
                                contentDescription = if (message.contentType == "sticker") "Sticker" else "Foto",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (message.contentType == "sticker") 150.dp else 220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClickLabel = "Ampliar imagen") { imageOpened = true },
                                contentScale = ContentScale.Fit
                            )
                        }
                        if (message.mediaUrl.isNullOrBlank() || message.contentType == "image") {
                            Text(
                                message.content,
                                modifier = Modifier.padding(top = if (message.mediaUrl.isNullOrBlank()) 0.dp else 5.dp)
                            )
                        }
                    }
                    "audio" -> {
                        message.mediaUrl?.let { CommentAudioPlayer(audioUrl = it) }
                            ?: Text(message.content)
                    }
                    else -> Text(message.content)
                }
                if (mine) Text(if (message.isRead) "Visto" else "Enviado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
            }
        }
    }
    if (imageOpened && message.contentType in setOf("image", "sticker")) {
        message.mediaUrl?.takeIf(String::isNotBlank)?.let { url ->
            FullScreenImageDialog(imageUrl = url, onDismiss = { imageOpened = false })
        }
    }
}
