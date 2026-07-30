package conectate.com.presentation.proximity

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.messages.getBestLastKnownLocation
import conectate.com.presentation.messages.hasLocationPermission
import conectate.com.presentation.messages.StickerPickerDialog
import conectate.com.presentation.navigation.Screen
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class LocationAction { ENABLE_NEARBY, START_RANDOM }

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
    val randomStatus by viewModel.randomStatus.collectAsState()
    val session by viewModel.anonymousSession.collectAsState()
    val sessions by viewModel.anonymousSessions.collectAsState()
    val messages by viewModel.anonymousMessages.collectAsState()
    val normalChatPeerBySession by viewModel.normalChatPeerBySession.collectAsState()
    val gender by viewModel.gender.collectAsState()
    val lookingFor by viewModel.lookingFor.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var radiusSlider by remember(radiusKm) { mutableFloatStateOf(radiusKm.toFloat()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingLocationAction by remember { mutableStateOf<LocationAction?>(null) }

    fun performLocationAction(action: LocationAction) {
        val location = getBestLastKnownLocation(context)
        if (location == null) {
            localError = "No se pudo obtener una ubicación reciente. Activa la ubicación del dispositivo e inténtalo de nuevo."
            return
        }
        viewModel.setRadius(radiusSlider.toDouble())
        when (action) {
            LocationAction.ENABLE_NEARBY -> viewModel.enableNearby(location.latitude, location.longitude)
            LocationAction.START_RANDOM -> viewModel.startAnonymousMatch(
                latitude = location.latitude,
                longitude = location.longitude,
                gender = gender,
                lookingFor = lookingFor
            )
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val action = pendingLocationAction
        pendingLocationAction = null
        if (granted && action != null) performLocationAction(action)
        else if (!granted) localError = "Se necesita permiso de ubicación para usar proximidad."
    }

    fun requestLocation(action: LocationAction) {
        if (hasLocationPermission(context)) {
            performLocationAction(action)
        } else {
            pendingLocationAction = action
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personas cerca") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Radar") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Aleatorio") },
                    icon = { Icon(Icons.Default.Shuffle, contentDescription = null) }
                )
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
                    onEnable = { requestLocation(LocationAction.ENABLE_NEARBY) },
                    onRefresh = viewModel::refreshNearby,
                    onDisable = viewModel::disableNearby,
                    onOpenProfile = { navController.navigate(Screen.UserProfile.createRoute(it)) }
                )
                else -> AnonymousTab(
                    status = randomStatus,
                    session = session,
                    sessions = sessions,
                    messages = messages,
                    radius = radiusSlider,
                    gender = gender,
                    lookingFor = lookingFor,
                    onRadiusChange = {
                        radiusSlider = it
                        viewModel.setRadius(it.toDouble())
                    },
                    onGenderChange = viewModel::setGender,
                    onLookingForChange = viewModel::setLookingFor,
                    onStart = { requestLocation(LocationAction.START_RANDOM) },
                    onCancel = viewModel::cancelAnonymousMatch,
                    onSend = viewModel::sendAnonymousMessage,
                    onSendSticker = viewModel::sendAnonymousSticker,
                    onSendMedia = viewModel::sendAnonymousMedia,
                    onReveal = viewModel::revealNextLevel,
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
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(if (enabled) Icons.Default.LocationOn else Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "Actualizar" else "Activar radar")
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
            Divider()
        }
    }
}

@Composable
private fun RadarVisualization(
    enabled: Boolean,
    radiusKm: Float,
    profiles: List<NearbyProfile>,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "proximity-radar")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar-sweep",
    )
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
                    val radians = Math.toRadians(sweepAngle.toDouble())
                    val beamEnd = Offset(
                        x = center.x + cos(radians).toFloat() * outerRadius,
                        y = center.y + sin(radians).toFloat() * outerRadius,
                    )
                    drawLine(
                        color = primary.copy(alpha = 0.88f),
                        start = center,
                        end = beamEnd,
                        strokeWidth = 5f,
                    )
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
                    if (enabled) "Radar activo · ${radiusKm.toInt()} km" else "Radar apagado",
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
            CircularProfileImage(imageUrl = profile.avatarUrl, size = 52.dp, showBorder = true)
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
private fun AnonymousTab(
    status: String,
    session: AnonymousChatSession?,
    sessions: List<AnonymousChatSession>,
    messages: List<AnonymousChatMessage>,
    radius: Float,
    gender: String,
    lookingFor: String,
    onRadiusChange: (Float) -> Unit,
    onGenderChange: (String) -> Unit,
    onLookingForChange: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
    onSendSticker: (String?, String) -> Unit,
    onSendMedia: (android.net.Uri, String) -> Unit,
    onReveal: () -> Unit,
    onEnd: () -> Unit,
    onSelectSession: (String) -> Unit,
    onBackToSessions: () -> Unit,
    normalChatPeerId: String?,
    onOpenProfile: (String) -> Unit,
    onOpenNormalChat: (String) -> Unit,
) {
    if (session != null) {
        AnonymousConversation(
            session = session,
            messages = messages,
            onSend = onSend,
            onSendSticker = onSendSticker,
            onSendMedia = onSendMedia,
            onReveal = onReveal,
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
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Conversación anónima",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Conoce a alguien sin mostrar tu identidad. Ambos deciden paso a paso qué compartir.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { ProximityPrivacyNotice() }
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
            item {
                Text(
                    "Puedes mantener varias conversaciones y buscar otra persona sin cerrar las anteriores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { RadiusControl(radius, onRadiusChange) }
        item { PreferenceSelector(
            label = "Me identifico como",
            value = gender,
            options = listOf(
                "unspecified" to "Prefiero no decir",
                "woman" to "Mujer",
                "man" to "Hombre"
            ),
            onValueChange = onGenderChange
        ) }
        item { PreferenceSelector(
            label = "Quiero conversar con",
            value = lookingFor,
            options = listOf(
                "any" to "Cualquier persona",
                "woman" to "Mujeres",
                "man" to "Hombres"
            ),
            onValueChange = onLookingForChange
        ) }
        item { when (status) {
            "waiting" -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(22.dp))
                        CircularProgressIndicator(modifier = Modifier.size(54.dp), strokeWidth = 5.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Buscando conexión compatible…", fontWeight = FontWeight.Bold)
                        Text(
                            "Puedes permanecer aquí; la búsqueda se actualiza automáticamente.",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancelar")
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
            else -> Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PersonSearch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Buscar chat aleatorio")
            }
        } }
        item {
            Text(
                "Seguridad: no mostramos tu ubicación exacta ni tu perfil hasta que tú lo autorices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
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
                CircularProfileImage(imageUrl = session.peer.avatarUrl, size = 46.dp, showBorder = true)
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
                    progress = session.friendshipPercent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Nivel de confianza: ${session.friendshipPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: options.first().second
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = {
                        onValueChange(option.first)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AnonymousConversation(
    session: AnonymousChatSession,
    messages: List<AnonymousChatMessage>,
    onSend: (String) -> Unit,
    onSendSticker: (String?, String) -> Unit,
    onSendMedia: (android.net.Uri, String) -> Unit,
    onReveal: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    normalChatPeerId: String?,
    onOpenProfile: (String) -> Unit,
    onOpenNormalChat: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showStickerPicker by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSendMedia(it, "image") }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSendMedia(it, "audio") }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    if (showStickerPicker) {
        StickerPickerDialog(
            onDismiss = { showStickerPicker = false },
            onStickerSelected = { sticker ->
                onSendSticker(sticker.media_url, sticker.title.ifBlank { "Sticker" })
                showStickerPicker = false
            }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver a chats anónimos")
            }
            if (session.peer.avatarUrl != null) {
                CircularProfileImage(imageUrl = session.peer.avatarUrl, size = 44.dp, showBorder = true)
            } else {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(10.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.peer.displayName, fontWeight = FontWeight.Bold)
                Text(
                    "Confianza: ${session.friendshipPercent}% · Identidad: ${session.peerRevealLevel}/3",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onEnd) {
                Icon(Icons.Default.Cancel, contentDescription = "Finalizar chat")
            }
        }
        LinearProgressIndicator(
            progress = session.friendshipPercent / 100f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            when {
                session.friendshipPercent >= 100 -> "Chat normal desbloqueado"
                session.friendshipPercent >= 67 -> "Fotos desbloqueadas · falta compartir el perfil"
                session.friendshipPercent >= 50 -> "Audios desbloqueados"
                session.friendshipPercent >= 33 -> "Stickers desbloqueados"
                else -> "Sigan conversando y compartiendo identidad para desbloquear opciones"
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (session.peer.interests.isNotEmpty() || session.peer.hobbies.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (session.peer.interests + session.peer.hobbies).take(3).forEach { item ->
                    AssistChip(onClick = {}, label = { Text(item, maxLines = 1) })
                }
            }
        }
        session.peer.userId?.let { userId ->
            OutlinedButton(
                onClick = { onOpenProfile(userId) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Ver perfil compartido")
            }
        }
        normalChatPeerId?.let { userId ->
            Button(
                onClick = { onOpenNormalChat(userId) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Continuar en chats")
            }
        }
        if (session.ownRevealLevel < 3) {
            OutlinedButton(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    when (session.ownRevealLevel) {
                        0 -> "Compartir mis intereses"
                        1 -> "Compartir mi nombre y foto"
                        else -> "Compartir acceso a mi perfil"
                    }
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id.ifBlank { it.clientMessageId } }) { message ->
                AnonymousMessageBubble(message)
            }
        }
        if (session.friendshipPercent >= 33) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { showStickerPicker = true },
                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                    label = { Text("Sticker") }
                )
                if (session.friendshipPercent >= 50) {
                    AssistChip(
                        onClick = { audioPicker.launch("audio/*") },
                        leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                        label = { Text("Audio") }
                    )
                }
                if (session.friendshipPercent >= 67) {
                    AssistChip(
                        onClick = { imagePicker.launch("image/*") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        label = { Text("Foto") }
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val content = draft.trim()
                    draft = ""
                    onSend(content)
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Enviar")
            }
        }
    }
}

@Composable
private fun AnonymousMessageBubble(message: AnonymousChatMessage) {
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
                                    .clip(RoundedCornerShape(8.dp)),
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
            }
        }
    }
}
