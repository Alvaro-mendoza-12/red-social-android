package conectate.com.presentation.voicerooms

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.components.CircularProfileImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RoomDetailUiState {
    object Loading : RoomDetailUiState()
    data class Success(val room: VoiceRoom) : RoomDetailUiState()
    data class Error(val message: String) : RoomDetailUiState()
}

@HiltViewModel
class RoomDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val voiceRoomRepository: VoiceRoomRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    private val roomId: String = checkNotNull(savedStateHandle["roomId"])

    private val _uiState = MutableStateFlow<RoomDetailUiState>(RoomDetailUiState.Loading)
    val uiState: StateFlow<RoomDetailUiState> = _uiState.asStateFlow()

    private val _isMuted = MutableStateFlow(true)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    init {
        loadRoom()
    }

    private fun loadRoom() {
        viewModelScope.launch {
            try {
                val result = voiceRoomRepository.getRoomById(roomId)
                result.fold(
                    onSuccess = { room ->
                        _uiState.value = RoomDetailUiState.Success(room)
                        val currentUserId = authService.getCurrentUserId()
                        if (currentUserId != null && !room.participantIds.contains(currentUserId)) {
                            joinRoom()
                        }
                        _isSpeaker.value = room.speakerIds.contains(currentUserId ?: "")
                    },
                    onFailure = { error ->
                        _uiState.value = RoomDetailUiState.Error(
                            error.message ?: "Error al cargar sala"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = RoomDetailUiState.Error(e.message ?: "Error")
            }
        }
    }

    private fun joinRoom() {
        viewModelScope.launch {
            val currentUserId = authService.getCurrentUserId() ?: return@launch
            val userResult = userRepository.getUserById(currentUserId)
            userResult.onSuccess { user ->
                user?.let {
                    val displayName = if (it.email.isNotEmpty()) it.email else "Usuario"
                    voiceRoomRepository.joinRoom(roomId, it.id, displayName)
                }
            }
        }
    }

    fun leaveRoom(onComplete: () -> Unit) {
        viewModelScope.launch {
            voiceRoomRepository.leaveRoom(roomId)
            onComplete()
        }
    }

    fun toggleMute() {
        val newMuteState = !_isMuted.value
        _isMuted.value = newMuteState
        voiceRoomRepository.toggleMute(newMuteState)
    }

    fun toggleSpeaker() {
        viewModelScope.launch {
            val currentUserId = authService.getCurrentUserId() ?: return@launch
            val newSpeakerState = !_isSpeaker.value
            voiceRoomRepository.toggleSpeaker(newSpeakerState)
            _isSpeaker.value = newSpeakerState
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailScreen(
    navController: NavController,
    viewModel: RoomDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (val state = uiState) {
                        is RoomDetailUiState.Success -> {
                            Text(
                                text = state.room.name,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> Text("Sala de Voz")
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.leaveRoom {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Salir"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is RoomDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            is RoomDetailUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Room info
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "EN VIVO",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (state.room.description.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.room.description,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.People,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${state.room.participantCount}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${state.room.speakerIds.size}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // Participants list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Speakers section
                        item {
                            Text(
                                text = "Hablando",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(state.room.participantNames.values.toList().take(state.room.speakerIds.size)) { name ->
                            ParticipantCard(
                                name = name,
                                isSpeaking = true
                            )
                        }

                        // Listeners section
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Escuchando",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(state.room.participantNames.values.toList().drop(state.room.speakerIds.size)) { name ->
                            ParticipantCard(
                                name = name,
                                isSpeaking = false
                            )
                        }
                    }

                    // Controls
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Mute/Unmute button
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                FloatingActionButton(
                                    onClick = { viewModel.toggleMute() },
                                    containerColor = if (isMuted)
                                        MaterialTheme.colorScheme.errorContainer
                                    else
                                        MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (isMuted) "Activar micrófono" else "Silenciar"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isMuted) "Silenciado" else "Activo",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // Request speaker button
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                FloatingActionButton(
                                    onClick = { viewModel.toggleSpeaker() },
                                    containerColor = if (isSpeaker)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Icon(
                                        imageVector = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                        contentDescription = "Hablar"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isSpeaker) "Hablando" else "Escuchando",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // Leave button
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                FloatingActionButton(
                                    onClick = {
                                        viewModel.leaveRoom {
                                            navController.popBackStack()
                                        }
                                    },
                                    containerColor = MaterialTheme.colorScheme.error
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ExitToApp,
                                        contentDescription = "Salir",
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Salir",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            is RoomDetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ParticipantCard(
    name: String,
    isSpeaking: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSpeaking)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(
                imageUrl = "",
                size = 40.dp
            )

            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            if (isSpeaking) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Hablando",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
