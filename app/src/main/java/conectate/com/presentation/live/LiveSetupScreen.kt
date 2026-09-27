@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.live

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveCollaborationCapabilities
import conectate.com.data.model.LiveCreatorLayoutStyle
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.data.webrtc.LiveCameraAdjustments
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import org.webrtc.EglBase

@HiltViewModel
class LiveSetupViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    @param:ApplicationContext private val context: Context
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

    private val _allowGuestRequests = MutableStateFlow(false)
    val allowGuestRequests: StateFlow<Boolean> = _allowGuestRequests.asStateFlow()

    private val _giftsEnabled = MutableStateFlow(true)
    val giftsEnabled: StateFlow<Boolean> = _giftsEnabled.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality.P720)
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _cameraFilter = MutableStateFlow(liveRepository.cameraFilter.value)
    val cameraFilter: StateFlow<LiveCameraFilter> = _cameraFilter.asStateFlow()

    private val _cameraAdjustments = MutableStateFlow(liveRepository.cameraAdjustments.value)
    val cameraAdjustments: StateFlow<LiveCameraAdjustments> = _cameraAdjustments.asStateFlow()

    private val _creatorLayoutStyle = MutableStateFlow(liveRepository.creatorLayoutStyle.value)
    val creatorLayoutStyle: StateFlow<LiveCreatorLayoutStyle> = _creatorLayoutStyle.asStateFlow()

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

    private val _previewBusy = MutableStateFlow(false)
    val previewBusy: StateFlow<Boolean> = _previewBusy.asStateFlow()

    private val _screenPermissionReady = MutableStateFlow(liveRepository.hasSetupScreenProjectionIntent())
    val screenPermissionReady: StateFlow<Boolean> = _screenPermissionReady.asStateFlow()

    val localVideoTrack = liveRepository.localVideoTrack
    val isFrontCamera = liveRepository.isFrontCamera
    val eglBaseContext = liveRepository.eglBaseContext
    val liveMusic = liveRepository.liveMusic
    private var openingLiveHost = false

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
        if (value != LiveSource.CAMERA) liveRepository.stopSetupCameraPreviewAsync()
    }

    fun setMicEnabled(value: Boolean) {
        _micEnabled.value = value
    }

    fun setAllowGuestRequests(value: Boolean) {
        if (_collaborationCapabilities.value.guestsBattlesEnabled &&
            _collaborationCapabilities.value.sfuAvailable
        ) {
            _allowGuestRequests.value = value
        }
    }

    fun setGiftsEnabled(value: Boolean) {
        _giftsEnabled.value = value
    }

    fun setQuality(value: LiveQuality) {
        _quality.value = value
    }

    fun setCameraFilter(value: LiveCameraFilter) {
        _cameraFilter.value = value
        liveRepository.setCameraFilter(value)
    }

    fun setCameraAdjustments(value: LiveCameraAdjustments) {
        _cameraAdjustments.value = value.normalized()
        liveRepository.setCameraAdjustments(_cameraAdjustments.value)
    }

    fun setCreatorLayoutStyle(value: LiveCreatorLayoutStyle) {
        _creatorLayoutStyle.value = value
        liveRepository.setCreatorLayoutStyle(value)
    }

    fun refreshNetworkState() {
        _networkAvailable.value = hasUsableNetwork(context)
    }

    fun clearError() {
        _error.value = null
    }

    fun reportPermissionError(message: String) {
        _error.value = message
    }

    fun startCameraPreview() {
        _source.value = LiveSource.CAMERA
        if (_previewBusy.value) return
        viewModelScope.launch {
            _previewBusy.value = true
            liveRepository.startSetupCameraPreview()
                .onFailure { _error.value = sanitizeUiError(it.message) }
            _previewBusy.value = false
        }
    }

    fun toggleCameraPreview() {
        if (_previewBusy.value) return
        if (localVideoTrack.value == null) {
            startCameraPreview()
            return
        }
        viewModelScope.launch {
            _previewBusy.value = true
            liveRepository.stopSetupCameraPreview()
            _previewBusy.value = false
        }
    }

    fun startScreenPreview(intent: Intent) {
        if (_previewBusy.value) return
        viewModelScope.launch {
            _previewBusy.value = true
            _source.value = LiveSource.SCREEN
            liveRepository.startSetupScreenPreview(intent)
                .onSuccess { _screenPermissionReady.value = true }
                .onFailure { _error.value = sanitizeUiError(it.message) }
            _previewBusy.value = false
        }
    }

    fun switchPreviewCamera() {
        if (!liveRepository.switchSetupCameraPreview()) {
            _error.value = "Primero activa la vista previa de cámara."
        }
    }

    fun confirmScreenPermission(intent: Intent) {
        liveRepository.storeSetupScreenProjectionIntent(intent)
        _screenPermissionReady.value = true
        _error.value = null
    }

    fun prepareLiveMusic(
        uri: android.net.Uri,
        displayName: String,
        startMs: Long = 0L,
        endMs: Long? = null,
    ) {
        viewModelScope.launch {
            liveRepository.prepareLiveMusic(uri, displayName, startMs, endMs)
                .onSuccess { AppFeedbackBus.showSuccess("La canción se mezclará en el LIVE") }
                .onFailure { error ->
                    _error.value = sanitizeUiError(error.message)
                    AppFeedbackBus.showError(error.message ?: "No se pudo preparar la canción")
                }
        }
    }

    fun setLiveMusicEnabled(enabled: Boolean) = liveRepository.setLiveMusicEnabled(enabled)

    fun setLiveMusicVolume(volume: Float) = liveRepository.setLiveMusicVolume(volume)

    fun clearLiveMusic() = liveRepository.clearLiveMusic()

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
                liveRepository.setInitialHostVideoEnabled(
                    _source.value != LiveSource.AUDIO && localVideoTrack.value != null,
                )
                val tags = parseLiveHashtags(_hashtags.value, _category.value)
                val result = liveRepository.createLiveStream(
                    title = _title.value.trim(),
                    source = _source.value,
                    hashtags = tags,
                    category = _category.value,
                    giftsEnabled = _giftsEnabled.value,
                )
                result.onSuccess { stream ->
                    liveRepository.setQuality(_quality.value)
                    liveRepository.setCameraFilter(_cameraFilter.value)
                    liveRepository.toggleMic(_micEnabled.value)
                    if (_allowGuestRequests.value) {
                        liveRepository.setGuestRequestsAllowed(stream.id, true)
                    }
                    openingLiveHost = true
                    onStarted(stream.id)
                }.onFailure { throwable ->
                    _error.value = sanitizeUiError(throwable.message)
                }
            } catch (error: Exception) {
                openingLiveHost = false
                _error.value = sanitizeUiError(error.message)
            } finally {
                _isStarting.value = false
            }
        }
    }

    override fun onCleared() {
        if (!openingLiveHost) liveRepository.stopSetupCameraPreviewAsync()
        super.onCleared()
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
fun LegacyLiveSetupScreen(
    navController: NavController,
    viewModel: LiveSetupViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val hashtags by viewModel.hashtags.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val micEnabled by viewModel.micEnabled.collectAsStateWithLifecycle()
    val allowGuestRequests by viewModel.allowGuestRequests.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val cameraFilter by viewModel.cameraFilter.collectAsStateWithLifecycle()
    val creatorLayoutStyle by viewModel.creatorLayoutStyle.collectAsStateWithLifecycle()
    val collaborationCapabilities by viewModel.collaborationCapabilities.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val networkAvailable by viewModel.networkAvailable.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val previewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
    val screenPermissionReady by viewModel.screenPermissionReady.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var startAfterScreenPermission by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAdvancedSheet by remember { mutableStateOf(false) }
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

    val cameraPreviewPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startCameraPreview()
        else viewModel.reportPermissionError("Concede permiso a la cámara para probarla antes del directo.")
    }

    val audioPreviewPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setMicEnabled(true)
            AppFeedbackBus.showSuccess("Micrófono autorizado para la prueba de sala.")
        } else {
            viewModel.reportPermissionError("Concede permiso al micrófono para probar un directo solo voz.")
        }
    }

    val screenPreviewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data
        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            viewModel.confirmScreenPermission(intent)
            if (startAfterScreenPermission) {
                viewModel.startLive { liveId ->
                    navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
                }
            }
        } else {
            viewModel.reportPermissionError("No se autorizó compartir pantalla. Puedes iniciar solo con voz o elegir cámara.")
        }
        startAfterScreenPermission = false
    }

    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            if (source == LiveSource.SCREEN && !screenPermissionReady) {
                startAfterScreenPermission = true
                runCatching { screenPreviewLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                    .onFailure {
                        startAfterScreenPermission = false
                        viewModel.reportPermissionError("No se pudo abrir el permiso para compartir pantalla.")
                    }
            } else {
                viewModel.startLive { liveId ->
                    navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
                }
            }
        } else {
            viewModel.reportPermissionError(
                if (Manifest.permission.CAMERA in denied) {
                    "La cámara necesita permiso para iniciar este directo."
                } else {
                    "El micrófono necesita permiso para que las demás personas te escuchen."
                }
            )
        }
    }

    val requestPreviewCamera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCameraPreview()
        } else {
            cameraPreviewPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val requestStart: () -> Unit = {
        val required = requiredHostRuntimePermissions(
            source = source,
            micEnabled = micEnabled,
            videoEnabled = source != LiveSource.AUDIO
        )
        val missing = required.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            startPermissionLauncher.launch(missing.toTypedArray())
        } else if (source == LiveSource.SCREEN && !screenPermissionReady) {
            startAfterScreenPermission = true
            runCatching { screenPreviewLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                .onFailure {
                    startAfterScreenPermission = false
                    viewModel.reportPermissionError("No se pudo abrir el permiso para compartir pantalla.")
                }
        } else {
            viewModel.startLive { liveId ->
                navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        containerColor = NovvinLiveColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (creatorLayoutStyle) {
                                LiveCreatorLayoutStyle.IMMERSIVE -> "Preparar directo"
                                else -> "Crear directo"
                            },
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            lineHeight = 25.sp,
                        )
                        Text(
                            when (creatorLayoutStyle) {
                                LiveCreatorLayoutStyle.ROOM -> "Sala de voz e invitados"
                                LiveCreatorLayoutStyle.FUSION -> "Controles completos antes de emitir"
                                else -> "Configura y revisa antes de emitir"
                            },
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = NovvinLiveColors.SecondaryText
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, enabled = !isStarting) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                },
                actions = {
                    TextButton(onClick = { showStyleSheet = true }, enabled = !isStarting) {
                        Text(creatorLayoutStyle.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Cambiar vista", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NovvinLiveColors.Background,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (false) {
            Surface(color = NovvinLiveColors.Surface, shadowElevation = 10.dp) {
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
                        onClick = requestStart,
                        enabled = !isStarting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NovvinLiveColors.Live)
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
                            Text("Iniciar directo", color = Color.White, fontWeight = FontWeight.Black)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshNetworkState()
                            when (source) {
                                LiveSource.CAMERA -> requestPreviewCamera()
                                LiveSource.SCREEN -> runCatching {
                                    screenPreviewLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }.onFailure { viewModel.reportPermissionError("No se pudo abrir la prueba de pantalla.") }
                                LiveSource.AUDIO -> {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.setMicEnabled(true)
                                        AppFeedbackBus.showSuccess("Micrófono listo para la sala de voz.")
                                    } else {
                                        audioPreviewPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        },
                        enabled = !isStarting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            when (creatorLayoutStyle) {
                                LiveCreatorLayoutStyle.STUDIO -> "Probar conexión"
                                LiveCreatorLayoutStyle.IMMERSIVE -> "Prueba técnica"
                                else -> "Probar sala"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            }
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                LiveCreatorPreviewStage(
                    style = creatorLayoutStyle,
                    avatarUrl = avatarUrl,
                    displayName = displayName,
                    title = title,
                    category = category,
                    source = source,
                    networkAvailable = networkAvailable,
                    localVideoTrack = localVideoTrack,
                    eglBaseContext = eglBaseContext,
                    previewBusy = previewBusy,
                    screenPermissionReady = screenPermissionReady,
                    micEnabled = micEnabled,
                    allowGuestRequests = allowGuestRequests,
                    maxGuests = collaborationCapabilities.maxGuests,
                    cameraFilter = cameraFilter,
                    onSourceChange = viewModel::setSource,
                    onTryCamera = requestPreviewCamera,
                    onSwitchCamera = viewModel::switchPreviewCamera,
                    onAuthorizeScreen = {
                        runCatching { screenPreviewLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                            .onFailure { viewModel.reportPermissionError("No se pudo abrir el permiso para compartir pantalla.") }
                    },
                    onToggleMic = { viewModel.setMicEnabled(!micEnabled) },
                    onOpenFilters = { if (source == LiveSource.CAMERA) showFilterSheet = true },
                    onToggleGuests = { viewModel.setAllowGuestRequests(!allowGuestRequests) },
                    onClose = { navController.popBackStack() },
                    onOpenAdvanced = { showAdvancedSheet = true },
                )
            }

            item {
                LiveCreatorCoreSettings(
                    style = creatorLayoutStyle,
                    title = title,
                    onTitleChange = viewModel::setTitle,
                    titleMessage = readiness.titleMessage,
                    category = category,
                    onCategoryChange = viewModel::setCategory,
                    hashtags = hashtags,
                    onHashtagsChange = viewModel::setHashtags,
                    allowGuestRequests = allowGuestRequests,
                    onGuestRequestsChange = viewModel::setAllowGuestRequests,
                    maxGuests = collaborationCapabilities.maxGuests,
                    guestControlsEnabled = collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable,
                    enabled = !isStarting,
                    onOpenAdvanced = { showAdvancedSheet = true },
                    modifier = Modifier.padding(
                        horizontal = if (creatorLayoutStyle == LiveCreatorLayoutStyle.STUDIO) 22.dp else 12.dp,
                        vertical = if (creatorLayoutStyle == LiveCreatorLayoutStyle.STUDIO) 14.dp else 0.dp,
                    ),
                )
            }

            if (false) {
            item {
                SetupSectionCard(
                    title = "Fuente y audio",
                    subtitle = "Puedes cambiar entre cámara y pantalla durante el directo."
                ) {
                    if (creatorLayoutStyle == LiveCreatorLayoutStyle.STUDIO) BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                                    icon = Icons.AutoMirrored.Filled.ScreenShare,
                                    onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                    enabled = !isStarting,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LiveSourceChoice(
                                    selected = source == LiveSource.AUDIO,
                                    title = "Solo voz",
                                    subtitle = "Foto de perfil y audio",
                                    icon = Icons.Default.Mic,
                                    onClick = { viewModel.setSource(LiveSource.AUDIO) },
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
                                    icon = Icons.AutoMirrored.Filled.ScreenShare,
                                    onClick = { viewModel.setSource(LiveSource.SCREEN) },
                                    enabled = !isStarting,
                                    modifier = Modifier.weight(1f)
                                )
                                LiveSourceChoice(
                                    selected = source == LiveSource.AUDIO,
                                    title = "Solo voz",
                                    subtitle = "Perfil + audio",
                                    icon = Icons.Default.Mic,
                                    onClick = { viewModel.setSource(LiveSource.AUDIO) },
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
                            "El estilo se aplica al mismo video que se envía. La prueba de arriba usa ese procesamiento real; ante presión térmica se recupera sin alternar imágenes filtradas y naturales.",
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
                    SetupToggleRow(
                        title = "Permitir solicitudes de invitados",
                        subtitle = if (allowGuestRequests) {
                            "Las personas verán espacios para solicitar unirse; tú decides quién entra."
                        } else {
                            "Nadie podrá publicar audio o cámara hasta que actives esta opción."
                        },
                        checked = allowGuestRequests,
                        onCheckedChange = viewModel::setAllowGuestRequests,
                        enabled = !isStarting && collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable,
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    SetupCapabilityRow(
                        title = "Comentarios y moderación",
                        detail = "Chat, palabras bloqueadas, silenciar, expulsar y moderadores.",
                        available = true,
                        badge = "Disponible"
                    )
                    HorizontalDivider()
                    SetupCapabilityRow(
                        title = "Regalos y corazones",
                        detail = "Catálogo original, vista previa, saldo y confirmación del servidor.",
                        available = true,
                        badge = "Disponible"
                    )
                    HorizontalDivider()
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
                    HorizontalDivider()
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
                    HorizontalDivider()
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
                        if (index != readiness.checks.lastIndex) HorizontalDivider()
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
            }

            item {
                LiveSetupPrimaryActions(
                    style = creatorLayoutStyle,
                    isStarting = isStarting,
                    error = error,
                    onStart = requestStart,
                    onTest = {
                        viewModel.refreshNetworkState()
                        when (source) {
                            LiveSource.CAMERA -> requestPreviewCamera()
                            LiveSource.SCREEN -> runCatching {
                                screenPreviewLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }.onFailure { viewModel.reportPermissionError("No se pudo abrir la prueba de pantalla.") }
                            LiveSource.AUDIO -> {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.setMicEnabled(true)
                                    AppFeedbackBus.showSuccess("Micrófono listo para la sala de voz.")
                                } else {
                                    audioPreviewPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    onMore = { showAdvancedSheet = true },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
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

    if (showStyleSheet) {
        LiveCreatorStyleSheet(
            selected = creatorLayoutStyle,
            onSelect = viewModel::setCreatorLayoutStyle,
            onDismiss = { showStyleSheet = false },
        )
    }
    if (showFilterSheet) {
        LiveCameraFilterSheet(
            selected = cameraFilter,
            onSelect = viewModel::setCameraFilter,
            onDismiss = { showFilterSheet = false },
        )
    }
    if (showAdvancedSheet) {
        LiveCreatorAdvancedSheet(
            source = source,
            micEnabled = micEnabled,
            onMicEnabledChange = viewModel::setMicEnabled,
            quality = quality,
            onQualityChange = viewModel::setQuality,
            cameraFilter = cameraFilter,
            onCameraFilterChange = viewModel::setCameraFilter,
            hashtags = hashtags,
            onHashtagsChange = viewModel::setHashtags,
            allowGuestRequests = allowGuestRequests,
            onGuestRequestsChange = viewModel::setAllowGuestRequests,
            capabilities = collaborationCapabilities,
            enabled = !isStarting,
            onDismiss = { showAdvancedSheet = false },
        )
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
private fun LiveCreatorCoreSettings(
    style: LiveCreatorLayoutStyle,
    title: String,
    onTitleChange: (String) -> Unit,
    titleMessage: String?,
    category: String,
    onCategoryChange: (String) -> Unit,
    hashtags: String,
    onHashtagsChange: (String) -> Unit,
    allowGuestRequests: Boolean,
    onGuestRequestsChange: (Boolean) -> Unit,
    maxGuests: Int,
    guestControlsEnabled: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpenAdvanced: () -> Unit = {},
) {
    Surface(
        modifier = modifier,
        color = NovvinLiveColors.Surface,
        contentColor = Color.White,
        shape = if (style == LiveCreatorLayoutStyle.STUDIO) RoundedCornerShape(0.dp) else RoundedCornerShape(22.dp),
        border = if (style == LiveCreatorLayoutStyle.STUDIO) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (style == LiveCreatorLayoutStyle.STUDIO) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (style == LiveCreatorLayoutStyle.STUDIO) 8.dp else 10.dp),
        ) {
            if (style == LiveCreatorLayoutStyle.IMMERSIVE || style == LiveCreatorLayoutStyle.FUSION) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally)
                        .width(56.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.35f)),
                )
            }

            Text(
                when (style) {
                    LiveCreatorLayoutStyle.STUDIO -> "Título"
                    LiveCreatorLayoutStyle.IMMERSIVE -> "Título de tu directo"
                    LiveCreatorLayoutStyle.ROOM -> "Título del directo"
                    LiveCreatorLayoutStyle.FUSION -> "Información del directo"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                placeholder = { Text("Charlando con la comunidad") },
                supportingText = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            titleMessage.orEmpty(),
                            color = if (titleMessage.isNullOrBlank()) NovvinLiveColors.SecondaryText else Color(0xFFFFA8A8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${title.length}/$LIVE_TITLE_MAX_LENGTH", color = NovvinLiveColors.SecondaryText)
                    }
                },
                isError = !titleMessage.isNullOrBlank() && title.isNotBlank(),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            )

            if (style == LiveCreatorLayoutStyle.STUDIO) {
                Text("Categoría", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LIVE_CATEGORIES.take(4)) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { onCategoryChange(item) },
                            label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            enabled = enabled,
                        )
                    }
                }
            } else {
                CompactSettingRow(
                    icon = Icons.Default.Info,
                    label = "Categoría",
                    value = category,
                    onClick = onOpenAdvanced,
                    enabled = enabled,
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(color = NovvinLiveColors.RaisedSurface, shape = CircleShape) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Invitados y batallas", fontWeight = FontWeight.Bold)
                    Text(
                        if (allowGuestRequests) "Solicitudes abiertas · hasta $maxGuests" else "Solicitudes cerradas",
                        color = NovvinLiveColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = allowGuestRequests,
                    onCheckedChange = onGuestRequestsChange,
                    enabled = enabled && guestControlsEnabled,
                )
            }
            if (style == LiveCreatorLayoutStyle.ROOM) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Moderación", fontWeight = FontWeight.Bold)
                        Text("Protección activada", color = NovvinLiveColors.Healthy, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onOpenAdvanced, enabled = enabled) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes avanzados", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCreatorLayoutSwitcher(
    selected: LiveCreatorLayoutStyle,
    onSelect: (LiveCreatorLayoutStyle) -> Unit,
    enabled: Boolean,
) {
    Surface(color = NovvinLiveColors.Background, contentColor = Color.White) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiveCreatorLayoutStyle.entries.forEach { style ->
                Surface(
                    onClick = { onSelect(style) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (style == selected) NovvinLiveColors.LiveSoft else NovvinLiveColors.RaisedSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(style.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCreatorScreenHeader(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onMore: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = Color.White, fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = NovvinLiveColors.SecondaryText, fontSize = 13.sp, lineHeight = 16.sp)
        }
        IconButton(onClick = onMore, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Color.White)
        }
    }
}

@Composable
private fun CompactSettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = NovvinLiveColors.SecondaryText, style = MaterialTheme.typography.labelMedium)
                Text(value, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Cambiar $label", tint = Color.White)
        }
    }
}

@Composable
private fun LiveSetupPrimaryActions(
    style: LiveCreatorLayoutStyle,
    isStarting: Boolean,
    error: String?,
    onStart: () -> Unit,
    onTest: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!error.isNullOrBlank()) {
            Text(error, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
        }
        if (style == LiveCreatorLayoutStyle.IMMERSIVE || style == LiveCreatorLayoutStyle.FUSION) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onTest, enabled = !isStarting, modifier = Modifier.weight(0.38f).heightIn(min = 54.dp)) {
                    Text(if (style == LiveCreatorLayoutStyle.IMMERSIVE) "Prueba técnica" else "Probar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onStart, enabled = !isStarting, modifier = Modifier.weight(0.62f).heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = NovvinLiveColors.Live)) {
                    if (isStarting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Iniciar directo", color = Color.White, fontWeight = FontWeight.Black)
                }
            }
        } else {
            Button(onClick = onStart, enabled = !isStarting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = NovvinLiveColors.Live)) {
                if (isStarting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Iniciar directo", color = Color.White, fontWeight = FontWeight.Black)
            }
            OutlinedButton(onClick = onTest, enabled = !isStarting, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text(if (style == LiveCreatorLayoutStyle.ROOM) "Probar sala" else "Probar conexión", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        TextButton(onClick = onMore, enabled = !isStarting, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Más ajustes", color = NovvinLiveColors.SecondaryText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCreatorAdvancedSheet(
    source: LiveSource,
    micEnabled: Boolean,
    onMicEnabledChange: (Boolean) -> Unit,
    quality: LiveQuality,
    onQualityChange: (LiveQuality) -> Unit,
    cameraFilter: LiveCameraFilter,
    onCameraFilterChange: (LiveCameraFilter) -> Unit,
    hashtags: String,
    onHashtagsChange: (String) -> Unit,
    allowGuestRequests: Boolean,
    onGuestRequestsChange: (Boolean) -> Unit,
    capabilities: LiveCollaborationCapabilities,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NovvinLiveColors.Surface) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ajustes del directo", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Text("Calidad, filtros y permisos siguen aplicándose al mismo flujo de emisión.", color = NovvinLiveColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            SetupToggleRow("Micrófono", if (micEnabled) "Activo" else "Silenciado", micEnabled, onMicEnabledChange, enabled)
            Text("Calidad", color = Color.White, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LiveQuality.entries.toList()) { option ->
                    FilterChip(selected = quality == option, onClick = { onQualityChange(option) }, enabled = enabled, label = { Text(option.label) })
                }
            }
            if (source == LiveSource.CAMERA) {
                Text("Filtro de cámara", color = Color.White, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LiveCameraFilter.entries.toList()) { option ->
                        FilterChip(selected = cameraFilter == option, onClick = { onCameraFilterChange(option) }, enabled = enabled, label = { Text(option.displayName()) })
                    }
                }
            }
            OutlinedTextField(value = hashtags, onValueChange = onHashtagsChange, enabled = enabled, singleLine = true, label = { Text("Temas y hashtags") }, modifier = Modifier.fillMaxWidth())
            SetupToggleRow("Solicitudes de invitados", if (allowGuestRequests) "Abiertas" else "Cerradas", allowGuestRequests, onGuestRequestsChange, enabled && capabilities.guestsBattlesEnabled && capabilities.sfuAvailable)
            Text("Comentarios, regalos, puntos y moderación se validan en servidor.", color = NovvinLiveColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LivePreviewCard(
    avatarUrl: String?,
    displayName: String,
    title: String,
    category: String,
    source: LiveSource,
    networkAvailable: Boolean,
    localVideoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context?,
    previewBusy: Boolean,
    screenPermissionReady: Boolean,
    onTryCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onAuthorizeScreen: () -> Unit
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
        ) {
            val hasCameraPreview =
                source == LiveSource.CAMERA && localVideoTrack != null && eglBaseContext != null
            if (hasCameraPreview) {
                WebRtcVideoView(
                    videoTrack = localVideoTrack,
                    sharedEglContext = eglBaseContext,
                    modifier = Modifier.fillMaxSize(),
                    mirror = true,
                    scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.78f))
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
                            "PRUEBA PRIVADA",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                    Surface(color = Color.Black.copy(alpha = 0.48f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            if (networkAvailable) "Red disponible" else "Sin conexión",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                if (hasCameraPreview) {
                    IconButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Cambiar cámara", tint = Color.White)
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProfileImage(
                            imageUrl = avatarUrl,
                            size = 72.dp,
                            showBorder = true
                        )
                        when (source) {
                            LiveSource.CAMERA -> Button(
                                onClick = onTryCamera,
                                enabled = !previewBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.92f))
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                                Spacer(Modifier.width(6.dp))
                                Text(if (previewBusy) "Abriendo…" else "Probar cámara", color = Color.Black)
                            }
                            LiveSource.SCREEN -> Button(
                                onClick = onAuthorizeScreen,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (screenPermissionReady) Color(0xFF0A765D) else Color.White.copy(alpha = 0.92f)
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (screenPermissionReady) "Pantalla autorizada" else "Probar permiso de pantalla")
                            }
                            LiveSource.AUDIO -> Text(
                                "Tu foto será visible mientras hablas",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(displayName, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        text = title.ifBlank { "Tu título aparecerá aquí" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$category · ${source.setupLabel()}",
                        color = Color.White.copy(alpha = 0.84f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun LiveSource.setupLabel(): String = when (this) {
    LiveSource.CAMERA -> "Cámara"
    LiveSource.SCREEN -> "Pantalla"
    LiveSource.AUDIO -> "Solo voz"
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
        colors = CardDefaults.cardColors(containerColor = NovvinLiveColors.Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NovvinLiveColors.SecondaryText)
            }
            CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
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
    val container = if (selected) NovvinLiveColors.LiveSoft.copy(alpha = 0.42f) else NovvinLiveColors.RaisedSurface
    val borderColor = if (selected) NovvinLiveColors.Live else Color.White.copy(alpha = 0.12f)
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.White)
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = NovvinLiveColors.SecondaryText)
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
            Text(title, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NovvinLiveColors.SecondaryText)
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
            tint = if (available) NovvinLiveColors.Healthy else NovvinLiveColors.Warning,
            modifier = Modifier.size(22.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = NovvinLiveColors.SecondaryText)
            Surface(
                color = if (available) NovvinLiveColors.Healthy.copy(alpha = 0.15f) else NovvinLiveColors.Warning.copy(alpha = 0.15f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (available) NovvinLiveColors.Healthy else NovvinLiveColors.Warning,
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
