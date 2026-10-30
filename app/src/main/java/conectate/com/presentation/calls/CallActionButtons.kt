package conectate.com.presentation.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import conectate.com.data.repository.CallRepository
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.SignalingNetwork
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class CallLaunchUiState(
    val isStarting: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class CallLauncherViewModel @Inject constructor(
    private val callRepository: CallRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CallLaunchUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigation = Channel<String>(Channel.BUFFERED)
    val navigation = _navigation.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { SignalingNetwork.prepare(SignalingConfig.BASE_URL) }
    }

    fun startCall(
        title: String,
        invitedUserIds: List<String>,
        contextType: String,
        contextId: String?,
        isVideo: Boolean
    ) {
        if (_uiState.value.isStarting) return
        val invitees = invitedUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (invitees.isEmpty()) {
            _uiState.value = CallLaunchUiState(error = "No hay participantes disponibles para llamar.")
            return
        }
        viewModelScope.launch {
            _uiState.value = CallLaunchUiState(isStarting = true)
            callRepository.startOutgoingCall(
                title = title,
                invitedUserIds = invitees,
                contextType = contextType,
                contextId = contextId,
                isVideo = isVideo
            ).fold(
                onSuccess = { callId ->
                    if (isVideo) callRepository.toggleVideo(true)
                    _uiState.value = CallLaunchUiState()
                    _navigation.send(callId)
                },
                onFailure = { error ->
                    _uiState.value = CallLaunchUiState(
                        error = error.message ?: "No se pudo iniciar la llamada."
                    )
                }
            )
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

@Composable
fun CallActionButtons(
    navController: NavController,
    title: String,
    invitedUserIds: List<String>,
    contextType: String,
    contextId: String?,
    viewModel: CallLauncherViewModel = hiltViewModel(),
    onCallStarted: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onCallStartedLatest by rememberUpdatedState(onCallStarted)
    var pendingVideoCall by remember { mutableStateOf<Boolean?>(null) }

    fun launchCall(isVideo: Boolean) {
        viewModel.startCall(title, invitedUserIds, contextType, contextId, isVideo)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val isVideo = pendingVideoCall ?: return@rememberLauncherForActivityResult
        pendingVideoCall = null
        val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = !isVideo || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted && cameraGranted) {
            launchCall(isVideo)
        } else {
            Toast.makeText(
                context,
                if (isVideo) "Activa micrófono y cámara para la videollamada." else "Activa el micrófono para llamar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestAndLaunch(isVideo: Boolean) {
        val required = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (required.isEmpty()) {
            launchCall(isVideo)
        } else {
            pendingVideoCall = isVideo
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigation.collect { callId ->
            onCallStartedLatest?.invoke()
            navController.navigate(Screen.Call.createRoute(callId)) { launchSingleTop = true }
        }
    }
    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeError()
    }

    if (uiState.isStarting) {
        IconButton(onClick = {}, enabled = false) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(
            onClick = { requestAndLaunch(false) },
            enabled = invitedUserIds.any { it.isNotBlank() }
        ) {
            Icon(Icons.Default.Call, contentDescription = "Llamada de voz")
        }
        IconButton(
            onClick = { requestAndLaunch(true) },
            enabled = invitedUserIds.any { it.isNotBlank() }
        ) {
            Icon(Icons.Default.Videocam, contentDescription = "Videollamada")
        }
    }
}
