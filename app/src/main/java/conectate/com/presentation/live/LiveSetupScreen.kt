@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveCollaborationCapabilities
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LiveSetupViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _hashtags = MutableStateFlow("")
    val hashtags: StateFlow<String> = _hashtags.asStateFlow()

    private val _category = MutableStateFlow(LIVE_CATEGORIES.first())
    val category: StateFlow<String> = _category.asStateFlow()

    private val _source = MutableStateFlow(LiveSource.CAMERA)
    val source: StateFlow<LiveSource> = _source.asStateFlow()

    private val _micEnabled = MutableStateFlow(true)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality.P720)
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _cameraFilter = MutableStateFlow(liveRepository.cameraFilter.value)
    val cameraFilter: StateFlow<LiveCameraFilter> = _cameraFilter.asStateFlow()

    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

    private val _displayName = MutableStateFlow("Tu perfil")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _networkAvailable = MutableStateFlow(hasUsableNetwork(context))
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    private val _collaborationCapabilities = MutableStateFlow(LiveCollaborationCapabilities())
    val collaborationCapabilities: StateFlow<LiveCollaborationCapabilities> =
        _collaborationCapabilities.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
                if (currentUserId.isBlank()) return@runCatching
                val profile = userRepository.getUserById(currentUserId).getOrNull()
                _avatarUrl.value = profile?.avatar_url
                _displayName.value = profile?.displayName
                    ?: profile?.username
                    ?: "Tu perfil"
                liveRepository.getLiveCollaborationCapabilities()
                    .onSuccess { capabilities -> _collaborationCapabilities.value = capabilities }
            }
        }
    }

    fun setTitle(value: String) {
        _title.value = value.take(LIVE_TITLE_MAX_LENGTH)
        _error.value = null
    }

    fun setHashtags(value: String) {
        _hashtags.value = value.take(180)
    }

    fun setCategory(value: String) {
        if (value in LIVE_CATEGORIES) _category.value = value
    }

    fun setSource(value: LiveSource) {
        _source.value = value
    }

    fun setMicEnabled(value: Boolean) {
        _micEnabled.value = value
    }

    fun setQuality(value: LiveQuality) {
        _quality.value = value
    }

    fun setCameraFilter(value: LiveCameraFilter) {
        _cameraFilter.value = value
        liveRepository.setCameraFilter(value)
    }

    fun refreshNetworkState() {
        _networkAvailable.value = hasUsableNetwork(context)
    }

    fun clearError() {
        _error.value = null
    }

    fun startLive(onStarted: (liveId: String) -> Unit) {
        if (_isStarting.value) return
        refreshNetworkState()
        val readiness = evaluateLiveSetup(
            title = _title.value,
            category = _category.value,
            source = _source.value,
            micEnabled = _micEnabled.value,
            quality = _quality.value,
            networkAvailable = _networkAvailable.value
        )
        if (!readiness.canStart) {
            _error.value = readiness.titleMessage
                ?: readiness.checks.firstOrNull { !it.ready }?.detail
                ?: "Completa la comprobación antes de iniciar."
            return
        }

        viewModelScope.launch {
            _isStarting.value = true
            try {
                val tags = parseLiveHashtags(_hashtags.value, _category.value)
                val result = liveRepository.createLiveStream(
                    title = _title.value.trim(),
                    source = _source.value,
                    hashtags = tags
                )
                result.onSuccess { stream ->
                    liveRepository.setQuality(_quality.value)
                    liveRepository.setCameraFilter(_cameraFilter.value)
                    liveRepository.toggleMic(_micEnabled.value)
                    onStarted(stream.id)
                }.onFailure { throwable ->
                    _error.value = sanitizeUiError(throwable.message)
                }
            } catch (error: Exception) {
                _error.value = sanitizeUiError(error.message)
            } finally {
                _isStarting.value = false
            }
        }
    }

    private fun sanitizeUiError(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return "No se pudo iniciar el live."
        val lowered = raw.lowercase()
        val leaksAuth = lowered.contains("authorization") ||
            lowered.contains("bearer ") ||
            lowered.contains("apikey") ||
            lowered.contains("headers:") ||
            lowered.contains("supabase.co/rest/v1")
        return if (leaksAuth) "No se pudo iniciar el live." else raw
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSetupScreen(
    navController: NavController,
    viewModel: LiveSetupViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val hashtags by viewModel.hashtags.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val micEnabled by viewModel.micEnabled.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val cameraFilter by viewModel.cameraFilter.collectAsStateWithLifecycle()
    val collaborationCapabilities by viewModel.collaborationCapabilities.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val networkAvailable by viewModel.networkAvailable.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readiness = evaluateLiveSetup(
        title = title,
        category = category,
        source = source,
        micEnabled = micEnabled,
        quality = quality,
        networkAvailable = networkAvailable
    )

    LaunchedEffect(Unit) {
        viewModel.refreshNetworkState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Crear directo", fontWeight = FontWeight.Black)
                        Text(
                            "Configura y revisa antes de emitir",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, enabled = !isStarting) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 10.dp, tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.startLive { liveId ->
                                navController.navigate(Screen.LiveHost.createRoute(liveId)) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        enabled = !isStarting && readiness.canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Preparando estudio…")
                        } else {
                            Text("Revisar permisos e iniciar")
                        }
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                LivePreviewCard(
                    avatarUrl = avatarUrl,
                    displayName = displayName,
                    title = title,
                    category = category,
                    source = source,
                    networkAvailable = networkAvailable
                )
            }

            item {
                SetupSectionCard(
                    title = "Información del directo",
                    subtitle = "Un título claro y una categoría ayudan a encontrar tu transmisión."
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = viewModel::setTitle,
                        label = { Text("Título") },
                        placeholder = { Text("¿Qué va a pasar en este directo?") },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(readiness.titleMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                                Text("${title.length}/$LIVE_TITLE_MAX_LENGTH")
                            }
                        },
                        isError = readiness.titleMessage != null && title.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isStarting
                    )

                    Text("Categoría", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LIVE_CATEGORIES) { item ->
                            FilterChip(
                                selected = category == item,
                                onClick = { viewModel.setCategory(item) },
                                label = { Text(item) },
                                enabled = !isStarting
                            )
                        }
                    }

                    OutlinedTextField(
                        value = hashtags,
                        onValueChange = viewModel::setHashtags,
                        label = { Text("Temas y hashtags") },
                        placeholder = { Text("#novvin #comunidad") },
                        supportingText = { Text("Máximo $LIVE_HASHTAG_LIMIT; la categoría se incluye automáticamente.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isStarting
                    )
                }
            }

            item {
                SetupSectionCard(
                    title = "Fuente y audio",
                    subtitle = "Puedes cambiar entre cámara y pantalla durante el directo."
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 390.dp
                        if (compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LiveSourceChoice(
                                    selected = source == LiveSource.CAMERA,
                                    title = "Cámara",
                                    subtitle = "Frontal o trasera",
                                    icon = Icons.Default.Videocam,
                                    onClick = { viewModel.setSource(LiveSource.CAMERA) },
                                    enabled = !isStarting,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LiveSourceChoice(
                                    selected = source == LiveSource.SCREEN,
                                    title = "Compartir pantalla",
                                    subtitle = "Consentimiento por sesión",
                                    icon = Icons.Default.ScreenShare,
                                    onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                    enabled = !isStarting,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LiveSourceChoice(
                                    selected = source == LiveSource.CAMERA,
                                    title = "Cámara",
                                    subtitle = "Frontal o trasera",
                                    icon = Icons.Default.Videocam,
                                    onClick = { viewModel.setSource(LiveSource.CAMERA) },
                                    enabled = !isStarting,
                                    modifier = Modifier.weight(1f)
                                )
                                LiveSourceChoice(
                                    selected = source == LiveSource.SCREEN,
                                    title = "Pantalla",
                                    subtitle = "Permiso por sesión",
                                    icon = Icons.Default.ScreenShare,
                                    onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                    enabled = !isStarting,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    SetupToggleRow(
                        title = "Micrófono",
                        subtitle = if (micEnabled) "Activo al comenzar" else "Comenzarás silenciado",
                        checked = micEnabled,
                        onCheckedChange = viewModel::setMicEnabled,
                        enabled = !isStarting
                    )

                    if (source == LiveSource.CAMERA) {
                        Text(
                            "Estilo de cámara",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LiveCameraFilter.values().toList()) { option ->
                                FilterChip(
                                    selected = cameraFilter == option,
                                    onClick = { viewModel.setCameraFilter(option) },
                                    label = { Text(option.displayName()) },
                                    enabled = !isStarting
                                )
                            }
                        }
                        Text(
                            "El estilo se aplica al mismo video enviado. En equipos lentos se omite temporalmente para conservar audio y estabilidad.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SetupSectionCard(
                    title = "Calidad",
                    subtitle = "480p ahorra datos; 720p es la opción recomendada."
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 350.dp
                        if (compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiveQuality.values().forEach { option ->
                                    LiveQualityChoice(
                                        selected = quality == option,
                                        quality = option,
                                        onClick = { viewModel.setQuality(option) },
                                        enabled = !isStarting,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiveQuality.values().forEach { option ->
                                    LiveQualityChoice(
                                        selected = quality == option,
                                        quality = option,
                                        onClick = { viewModel.setQuality(option) },
                                        enabled = !isStarting,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SetupSectionCard(
                    title = "Interacción y seguridad",
                    subtitle = "Los controles críticos permanecen visibles por encima de animaciones y regalos."
                ) {
                    SetupCapabilityRow(
                        title = "Comentarios y moderación",
                        detail = "Chat, palabras bloqueadas, silenciar, expulsar y moderadores.",
                        available = true,
                        badge = "Disponible"
                    )
                    Divider()
                    SetupCapabilityRow(
                        title = "Regalos y corazones",
                        detail = "Catálogo original, vista previa, saldo y confirmación del servidor.",
                        available = true,
                        badge = "Disponible"
                    )
                    Divider()
                    SetupCapabilityRow(
                        title = "Puntos del canal",
                        detail = if (collaborationCapabilities.channelPointsEnabled) {
                            "Los espectadores ganan puntos por tiempo visible y canjean recompensas del creador."
                        } else {
                            "Sistema de puntos separado de monedas; requiere activar la política antifraude del servidor."
                        },
                        available = collaborationCapabilities.channelPointsEnabled,
                        badge = if (collaborationCapabilities.channelPointsEnabled) "Disponible" else "Bloqueado por configuración"
                    )
                    Divider()
                    SetupCapabilityRow(
                        title = "Invitados y batallas",
                        detail = if (collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable) {
                            "Invita hasta ${collaborationCapabilities.maxGuests} personas y crea batallas con puntuación del servidor."
                        } else {
                            "La interfaz se habilitará al conectar un proveedor SFU y autoridad de puntuación."
                        },
                        available = collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable,
                        badge = if (collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable) {
                            "Disponible"
                        } else if (collaborationCapabilities.guestsBattlesEnabled) {
                            "Requiere SFU"
                        } else {
                            "Bloqueado por configuración"
                        }
                    )
                    Divider()
                    SetupCapabilityRow(
                        title = "Filtros de cámara",
                        detail = "Se procesan antes de publicar y se degradan con seguridad según el rendimiento del equipo.",
                        available = true,
                        badge = "Disponible"
                    )
                }
            }

            item {
                SetupSectionCard(
                    title = "Comprobación previa",
                    subtitle = "Android solicitará cámara, micrófono o pantalla sólo cuando corresponda."
                ) {
                    readiness.checks.forEachIndexed { index, check ->
                        SetupCheckRow(check)
                        if (index != readiness.checks.lastIndex) Divider()
                    }
                    if (!networkAvailable) {
                        Button(
                            onClick = viewModel::refreshNetworkState,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Volver a comprobar la red")
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable) {
                        "Audiencia actual: pública. Los invitados y las batallas usan permisos del SFU y puntuación confirmada por el servidor."
                    } else {
                        "Audiencia actual: pública. Invitados y batallas requieren un SFU y autoridad de puntuación en servidor; permanecen bloqueados hasta que esa infraestructura pueda hacer cumplir los permisos."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

private fun LiveCameraFilter.displayName(): String = when (this) {
    LiveCameraFilter.NONE -> "Natural"
    LiveCameraFilter.WARM -> "Cálido"
    LiveCameraFilter.COOL -> "Frío"
    LiveCameraFilter.VIVID -> "Vívido"
    LiveCameraFilter.SOFT -> "Suave"
    LiveCameraFilter.MONO -> "Mono"
}

@Composable
private fun LivePreviewCard(
    avatarUrl: String?,
    displayName: String,
    title: String,
    category: String,
    source: LiveSource,
    networkAvailable: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 210.dp, max = 270.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF15152B), Color(0xFF322259), Color(0xFF0D6072))
                    )
                )
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Color(0xFFD92525), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "VISTA PREVIA",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
                Surface(color = Color.Black.copy(alpha = 0.42f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (networkAvailable) "Red disponible" else "Sin conexión",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }

            CircularProfileImage(
                imageUrl = avatarUrl,
                size = 82.dp,
                showBorder = true,
                modifier = Modifier.align(Alignment.Center)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(displayName, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    text = title.ifBlank { "Tu título aparecerá aquí" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$category · ${if (source == LiveSource.CAMERA) "Cámara" else "Pantalla"}",
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SetupSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun LiveSourceChoice(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 86.dp).border(1.5.dp, borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = container
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LiveQualityChoice(
    selected: Boolean,
    quality: LiveQuality,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(quality.label, fontWeight = FontWeight.Bold)
                Text(
                    when (quality) {
                        LiveQuality.P480 -> "Ahorro"
                        LiveQuality.P720 -> "Recomendado"
                        LiveQuality.P1080 -> "Alta"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        modifier = modifier.heightIn(min = 56.dp)
    )
}

@Composable
private fun SetupToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SetupCapabilityRow(
    title: String,
    detail: String,
    available: Boolean,
    badge: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (available) Color(0xFF16834F) else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(22.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = if (available) Color(0xFF16834F).copy(alpha = 0.13f) else MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupCheckRow(check: LivePreflightCheck) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (check.ready) Color(0xFF16834F) else MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center
        ) {
            Text(if (check.ready) "✓" else "!", color = Color.White, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(check.label, fontWeight = FontWeight.Bold)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun hasUsableNetwork(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
