package conectate.com.presentation.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.UserRepository
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LiveSetupViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _hashtags = MutableStateFlow("")
    val hashtags: StateFlow<String> = _hashtags.asStateFlow()

    private val _source = MutableStateFlow(LiveSource.CAMERA)
    val source: StateFlow<LiveSource> = _source.asStateFlow()

    private val _micEnabled = MutableStateFlow(true)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality.P720)
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

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
            }
        }
    }

    fun setTitle(value: String) {
        _title.value = value
    }

    fun setHashtags(value: String) {
        _hashtags.value = value
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

    fun clearError() {
        _error.value = null
    }

    fun startLive(onStarted: (liveId: String) -> Unit) {
        if (_isStarting.value) return
        viewModelScope.launch {
            _isStarting.value = true
            try {
                val tags = parseHashtags(_hashtags.value)
                val result = liveRepository.createLiveStream(
                    title = _title.value,
                    source = _source.value,
                    hashtags = tags
                )
                result.onSuccess { stream ->
                    liveRepository.setQuality(_quality.value)
                    liveRepository.toggleMic(_micEnabled.value)
                    onStarted(stream.id)
                }.onFailure { e ->
                    _error.value = sanitizeUiError(e.message)
                }
            } catch (e: Exception) {
                _error.value = sanitizeUiError(e.message)
            } finally {
                _isStarting.value = false
            }
        }
    }

    private fun parseHashtags(raw: String): List<String> {
        return raw
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.removePrefix("#") }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
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
    val title by viewModel.title.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()
    val source by viewModel.source.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val quality by viewModel.quality.collectAsState()
    val avatarUrl by viewModel.avatarUrl.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración de Live", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!error.isNullOrBlank()) {
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // 1) Foto de perfil del streamer (arriba izquierda)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                CircularProfileImage(
                    imageUrl = avatarUrl,
                    size = 64.dp,
                    showBorder = false
                )
            }

            // 2) Campo título del live
            OutlinedTextField(
                value = title,
                onValueChange = viewModel::setTitle,
                label = { Text("Título del live") },
                placeholder = { Text("Jugando Minecraft con seguidores") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isStarting
            )

            // 3) Campo hashtags
            OutlinedTextField(
                value = hashtags,
                onValueChange = viewModel::setHashtags,
                label = { Text("Hashtags") },
                placeholder = { Text("#gaming #directo #chat") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isStarting
            )

            // 4) Selección del tipo de transmisión
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tipo de transmisión", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val stackChoices = maxWidth < 380.dp
                    if (stackChoices) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LiveSourceChoice(
                                selected = source == LiveSource.CAMERA,
                                title = "Transmitir con cámara",
                                icon = Icons.Default.Videocam,
                                onClick = { viewModel.setSource(LiveSource.CAMERA) },
                                enabled = !isStarting,
                                modifier = Modifier.fillMaxWidth()
                            )
                            LiveSourceChoice(
                                selected = source == LiveSource.SCREEN,
                                title = "Compartir pantalla",
                                icon = Icons.Default.ScreenShare,
                                onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                enabled = !isStarting,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LiveSourceChoice(
                                selected = source == LiveSource.CAMERA,
                                title = "Transmitir con cámara",
                                icon = Icons.Default.Videocam,
                                onClick = { viewModel.setSource(LiveSource.CAMERA) },
                                enabled = !isStarting,
                                modifier = Modifier.weight(1f)
                            )
                            LiveSourceChoice(
                                selected = source == LiveSource.SCREEN,
                                title = "Compartir pantalla",
                                icon = Icons.Default.ScreenShare,
                                onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                enabled = !isStarting,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 5) Configuración de audio
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Micrófono", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Switch(checked = micEnabled, onCheckedChange = { viewModel.setMicEnabled(it) }, enabled = !isStarting)
            }

            // 6) Calidad de transmisión
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Calidad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveQualityChoice(
                        selected = quality == LiveQuality.P720,
                        title = LiveQuality.P720.label,
                        onClick = { viewModel.setQuality(LiveQuality.P720) },
                        enabled = !isStarting
                    )
                    LiveQualityChoice(
                        selected = quality == LiveQuality.P1080,
                        title = LiveQuality.P1080.label,
                        onClick = { viewModel.setQuality(LiveQuality.P1080) },
                        enabled = !isStarting
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 7) Botón iniciar live
            Button(
                onClick = {
                    viewModel.startLive { liveId ->
                        navController.navigate(Screen.LiveHost.createRoute(liveId)) {
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isStarting
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(18.dp)
                            .height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Iniciando…")
                } else {
                    Text("Iniciar transmisión")
                }
            }

            Text(
                text = "Tip: tu live aparecerá en la lista de directos y en el feed como recomendado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveSourceChoice(
    selected: Boolean,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        )
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LiveQualityChoice(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Button(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        )
    ) {
        Text(title)
    }
}
