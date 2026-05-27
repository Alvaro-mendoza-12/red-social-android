package conectate.com.presentation.voicerooms

import android.Manifest
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import conectate.com.presentation.components.CircularProfileImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VoiceRoomActiveScreen(
    navController: NavController,
    roomId: String,
    viewModel: VoiceRoomActiveViewModel = hiltViewModel()
) {
    // Request audio and camera permissions
    val permissionsToRequest = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )
    val context = LocalContext.current
    
    // Screen Share Launcher
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionData = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && projectionData != null) {
            viewModel.toggleScreenShare(projectionData)
        } else {
             viewModel.stopVideo() // Reset state if cancelled
        }
    }
    
    val permissionsState = com.google.accompanist.permissions.rememberMultiplePermissionsState(permissionsToRequest)

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    // v22.0: Removed DisposableEffect for AudioManager.
    // Logic is now centralized in VoiceRoomRepository to support background audio correctly.
    // The UI should NOT reset audio mode on dispose.
    
    // Load room data and join ONLY when essential permissions are granted
    LaunchedEffect(roomId, permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.joinRoom(roomId)
        } else if (!permissionsState.shouldShowRationale) {
             permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    val room by viewModel.room.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()
    val isJoined by viewModel.isJoined.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val videoMode by viewModel.videoMode.collectAsState()
    val remoteVideoTracks by viewModel.remoteVideoTracks.collectAsState()
    val localVideoTrack by viewModel.localVideoTrack.collectAsState()
    val peerConnectionStates by viewModel.peerConnectionStates.collectAsState()
    val signalingStates by viewModel.signalingStates.collectAsState()
    val sharingStates by viewModel.sharingStates.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val fullScreenParticipantId by viewModel.fullScreenParticipantId.collectAsState()
    
    val cinemaState by viewModel.cinemaState.collectAsState()
    val isCinemaMode = cinemaState?.isCinemaMode == true
    
    var showEditDialog by remember { mutableStateOf(false) }
    var showContentBrowser by remember { mutableStateOf(false) }
    
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    
    // v13.0: Smart Back Navigation - Exit Cinema Mode before exiting room
    // v13.0: Smart Back Navigation - Exit Cinema Mode before exiting room
    // v18.0: Background Audio - "Back" minimizes (pops stack) but keeps room active.
    androidx.activity.compose.BackHandler(enabled = true) {
        val isCinemaActive = cinemaState?.isCinemaMode == true
        if (isCinemaActive) {
            viewModel.exitCinemaMode()
        } else {
            // Minimize only
            navController.popBackStack()
        }
    }
    
    // v18.0: Background Audio Support
    // We NO LONGER automatically leave the room on dispose (back navigation).
    // The connection must persist until the user explicitly clicks "Leave" or "Hang Up".
    // DisposableEffect removed.
    
    // Background based on state
    // Only black if Cinema Mode has CONTENT, or if we have full screen video
    val hasRealVideo = videoMode != VoiceRoomRepository.VideoMode.NONE || 
                       remoteVideoTracks.isNotEmpty()
    val isCinemaActiveWithContent = isCinemaMode && !cinemaState?.videoUrl.isNullOrBlank()
    val backgroundColor = if (hasRealVideo || isCinemaActiveWithContent || fullScreenParticipantId != null) 
        Color.Black 
    else 
        MaterialTheme.colorScheme.background

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            // Transparent top bar for video mode, visible otherwise
            // Always show TopBar but style differntly
            if (!isCinemaActiveWithContent && fullScreenParticipantId == null) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = room?.name ?: "Cargando...",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface 
                            )
                            Text(
                                text = "ID: ${room?.readableId ?: "..."}",
                                style = MaterialTheme.typography.bodySmall,
                                color = (if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.7f)
                            )
                            Text(
                                text = "Estado: ${if (isJoined) "Conectado" else "Conectando..."} · ${participants.size} en sala",
                                style = MaterialTheme.typography.bodySmall,
                                color = (if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.65f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                             room?.readableId?.let { id ->
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(id))
                                android.widget.Toast.makeText(context, "ID copiado", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentCopy, 
                                contentDescription = "Copiar ID",
                                tint = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        if (room?.creatorId == viewModel.getCurrentUserId()) {
                            IconButton(onClick = { showEditDialog = true }) {
                                Icon(
                                    Icons.Default.Edit, 
                                    contentDescription = "Editar Sala",
                                    tint = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            // v13.0: Smart navigation - exit cinema mode first
                            val isCinemaActive = cinemaState?.isCinemaMode == true
                            if (isCinemaActive) {
                                viewModel.exitCinemaMode()
                            } else {
                                navController.popBackStack()
                            }
                        }) {
                            Icon(
                                Icons.Default.ArrowBack, 
                                contentDescription = "Volver",
                                tint = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (hasRealVideo) Color.Black.copy(alpha = 0.4f) else Colors.Transparent,
                        navigationIconContentColor = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface,
                        titleContentColor = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = if (hasRealVideo) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        bottomBar = {
             val isPlayerFullscreen by viewModel.isPlayerFullscreen.collectAsState()
             var showChat by remember { mutableStateOf(false) }
             var showReactions by remember { mutableStateOf(false) }

             // v14.0: Hide Controls in Fullscreen
             AnimatedVisibility(
                 visible = !isPlayerFullscreen,
                 enter = androidx.compose.animation.fadeIn(),
                 exit = androidx.compose.animation.fadeOut()
             ) {
                 Column {

             ControlBar(
                 isMuted = isMuted,
                 isSpeaker = isSpeaker,
                 videoMode = videoMode,
                 onToggleMute = { viewModel.toggleMute() },
                 onToggleSpeaker = { viewModel.toggleSpeaker() },
                 onToggleCamera = { viewModel.toggleCamera() },
                 onToggleScreenShare = { 
                     if (videoMode == VoiceRoomRepository.VideoMode.SCREEN) {
                         viewModel.toggleScreenShare(Intent())
                     } else {
                         val mediaProjectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                         screenShareLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                     }
                 },
                 onShowChat = { showChat = true },
                 onShowReactions = { showReactions = true },
                 onToggleCinema = {
                     if (room?.creatorId == viewModel.getCurrentUserId()) {
                         if (isCinemaMode) {
                             viewModel.exitCinemaMode()
                         } else {
                             showContentBrowser = true
                         }
                     }
                 },
                 isCinemaActive = isCinemaMode,
                 isCreator = room?.creatorId == viewModel.getCurrentUserId(),
                 onLeave = { 
                    viewModel.leaveRoom()
                    navController.popBackStack()
                 },
                 unreadChatCount = viewModel.unreadChatCount.collectAsState().value
             )

             // Chat Sheet
             if (showChat) {
                 ChatBottomSheet(
                     onDismiss = { showChat = false },
                     viewModel = viewModel
                 )
                 LaunchedEffect(Unit) {
                     viewModel.markChatRead()
                 }
             }

             // Reaction Picker Overlay (simple one for now)
             if (showReactions) {
                 ReactionPicker(
                     onSelect = { emoji ->
                         viewModel.sendReaction(emoji)
                         showReactions = false
                     },
                     onDismiss = { showReactions = false }
                 )
             }
                 }  // cierre AnimatedVisibility
             }  // cierre bottomBar lambda
        },  // cierre parámetro bottomBar del Scaffold
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val showPasswordPrompt by viewModel.showPasswordPrompt.collectAsState()
            val passwordError by viewModel.passwordError.collectAsState()
            
            if (showPasswordPrompt) {
                PasswordPromptDialog(
                    passwordError = passwordError,
                    onConfirm = { viewModel.validatePassword(it) },
                    onDismiss = { navController.popBackStack() }
                )
            } else if (permissionsState.allPermissionsGranted && room != null) {
                val activeRoom = room ?: return@Box
                
                if (showEditDialog) {
                    EditRoomDialog(
                        currentName = activeRoom.name,
                        isPublic = activeRoom.isPublic,
                        currentPassword = activeRoom.password,
                        onConfirm = { name, isPublic, password ->
                            viewModel.updateRoom(name, isPublic, password)
                            showEditDialog = false
                        },
                        onDismiss = { showEditDialog = false }
                    )
                }

                if (isCinemaActiveWithContent) {
                    // Cinema Layout - Premium Deep Black Immersive
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CinemaPlayer(
                            videoUrl = cinemaState?.videoUrl ?: "",
                            isPlaying = cinemaState?.isPlaying ?: false,
                            currentPosition = cinemaState?.currentPosition ?: 0,
                            isCreator = room?.creatorId == currentUserId,
                            onSync = { playing, pos -> viewModel.syncCinema(playing, pos) },
                            onControl = { action, pos -> viewModel.controlCinema(action, pos) },
                            onChangeVideo = { showContentBrowser = true },
                            viewModel = viewModel
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Participants in a horizontal row during cinema mode
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(15.dp)
                        ) {
                            items(participants) { participant ->
                                 val isLocal = participant.id == currentUserId
                                 val track = if (isLocal) {
                                     if (videoMode != VoiceRoomRepository.VideoMode.NONE) localVideoTrack else null
                                 } else {
                                     remoteVideoTracks[participant.socketId]
                                 }
                                 
                                 if (track != null) {
                                     ParticipantVideoBubble(participant, track, viewModel.getEglBaseContext())
                                 } else {
                                     ParticipantCinemaCard(participant)
                                 }
                            }
                        }
                    }
                }
 else if (fullScreenParticipantId != null) {
                    val p = participants.find { it.id == fullScreenParticipantId }
                    if (p != null) {
                FullScreenParticipantView(
                     participant = p,
                     remoteVideoTracks = remoteVideoTracks,
                     localVideoTrack = localVideoTrack,
                     localVideoMode = videoMode,
                     eglBaseContext = viewModel.getEglBaseContext(),
                     currentUserId = currentUserId ?: "",
                     onFullscreenToggle = { viewModel.toggleFullScreen(null) }
                )
                    } else {
                        viewModel.toggleFullScreen(null)
                    }
                } else if (hasRealVideo) {
                    // Video Grid Layout
                VideoGridLayout(
                    participants = participants,
                    remoteVideoTracks = remoteVideoTracks,
                    peerConnectionStates = peerConnectionStates,
                    signalingStates = signalingStates,
                    sharingStates = sharingStates,
                    debugInfo = debugInfo,
                    localVideoTrack = localVideoTrack,
                    localVideoMode = videoMode,
                    eglBaseContext = viewModel.getEglBaseContext(),
                    currentUserId = currentUserId ?: "",
                    onVolumeChange = { id, vol -> viewModel.setParticipantVolume(id, vol) },
                    onFullscreenToggle = { id -> viewModel.toggleFullScreen(id) }
                )
                } else {
                    // Audio Only Grid
                    AudioOnlyLayout(
                        participants = participants,
                        peerConnectionStates = peerConnectionStates,
                        signalingStates = signalingStates,
                        debugInfo = debugInfo,
                        onVolumeChange = { id, vol -> viewModel.setParticipantVolume(id, vol) }
                    )
                }
                } else if (!permissionsState.allPermissionsGranted) {
                PermissionRequestContent { permissionsState.launchMultiplePermissionRequest() }
            } else {
                 // Loading State
                 Column(
                     modifier = Modifier.fillMaxSize(),
                     verticalArrangement = Arrangement.Center,
                     horizontalAlignment = Alignment.CenterHorizontally
                 ) {
                     CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                     Spacer(modifier = Modifier.height(16.dp))
                     Text(
                         text = "Cargando sala...", 
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                     Text(
                         text = "ID: $roomId",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                     )
                 }
            }

            if (showContentBrowser) {
                ContentBrowser(
                    onSelect = { url, title ->
                        viewModel.selectVideo(url, title)
                        showContentBrowser = false
                    },
                    onDismiss = { showContentBrowser = false },
                    onResolveUrl = { url -> viewModel.resolveUniversalLink(url) }
                )
            }

            // Floating Reactions Overlay
            val activeReactions by viewModel.activeReactions.collectAsState()
            Box(Modifier.fillMaxSize().padding(bottom = 100.dp), contentAlignment = Alignment.BottomCenter) {
                activeReactions.forEach { reaction ->
                    FloatingReaction(reaction)
                }
            }
        }
    }
}

@Composable
fun PasswordPromptDialog(
    passwordError: String?, // v15.3: Error message from VM
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) } // v15.3: Visibility toggle
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp), // Premium Look
        containerColor = MaterialTheme.colorScheme.surface,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Sala Privada", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "Esta sala requiere una contraseña para unirse. Por favor, introdúcela a continuación:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña de Acceso") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = passwordError != null,
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = if (passwordVisible) "Ocultar" else "Mostrar")
                        }
                    },
                    supportingText = {
                        if (passwordError != null) {
                            Text(text = passwordError, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Validar y Entrar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancelar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun VideoGridLayout(
    participants: List<conectate.com.data.model.VoiceParticipant>,
    remoteVideoTracks: Map<String, VideoTrack>,
    peerConnectionStates: Map<String, String>,
    signalingStates: Map<String, String>,
    sharingStates: Map<String, Boolean>,
    debugInfo: String?,
    localVideoTrack: VideoTrack?,
    localVideoMode: VoiceRoomRepository.VideoMode,
    eglBaseContext: org.webrtc.EglBase.Context?,
    currentUserId: String,
    onVolumeChange: (String, Float) -> Unit,
    onFullscreenToggle: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
         gridItems(participants, key = { it.id }) { participant ->
             val isLocal = participant.id == currentUserId 
             
             // Logic: Show video only if track is present AND the peer is actively sharing
             val isRemoteSharing = sharingStates[participant.socketId] == true
             
             val track = if (isLocal) {
                 if (localVideoMode != VoiceRoomRepository.VideoMode.NONE) localVideoTrack else null
             } else {
                 if (isRemoteSharing) remoteVideoTracks[participant.socketId] else null
             }
             
             if (track != null) {
                 key(track) { // Recreate renderer if track instance changes
                     Box(
                         modifier = Modifier
                             .aspectRatio(0.7f)
                             .clip(RoundedCornerShape(12.dp))
                             .background(Color.Black)
                             .clickable { onFullscreenToggle(participant.id) }
                     ) {
                         VideoRenderer(
                             track = track, 
                             eglBaseContext = eglBaseContext,
                             scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
                         )
                         
                         // Name Overlay
                         Surface(
                             color = Color.Black.copy(alpha = 0.5f),
                             shape = RoundedCornerShape(4.dp),
                             modifier = Modifier
                                 .align(Alignment.BottomStart)
                                 .padding(8.dp)
                         ) {
                             Text(
                                 text = if (isLocal) "Tú" else participant.name,
                                 color = Color.White,
                                 style = MaterialTheme.typography.labelMedium,
                                 modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                             )
                             
                              if (!isLocal) {
                                  val state = peerConnectionStates[participant.socketId]
                                  if (state != null) {
                                      Spacer(modifier = Modifier.width(4.dp))
                                      Box(modifier = Modifier
                                          .size(8.dp)
                                          .background(
                                              when (state) {
                                                  "CONNECTED", "COMPLETED" -> Color.Green
                                                  "FAILED", "DISCONNECTED" -> Color.Red
                                                  else -> Color.Yellow
                                              }, 
                                              CircleShape
                                          )
                                      )
                                  }
                              }
                                  
                              // DEBUG OVERLAY
                              Column(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                                  val sig = signalingStates[participant.socketId]
                                  if (sig != null) {
                                      Text(text = "Sig: $sig", color = Color.Green, style = MaterialTheme.typography.labelSmall)
                                  }
                                  if (debugInfo != null) {
                                      Text(text = debugInfo, color = Color.Cyan, style = MaterialTheme.typography.labelSmall, lineHeight = 10.sp)
                                  }
                              }
                             }
                         }
                     }
                  } else {
                      ParticipantCard(
                          participant = participant,
                          connectionState = peerConnectionStates[participant.socketId],
                          signalingState = signalingStates[participant.socketId],
                          debugInfo = debugInfo,
                          onVolumeChange = { vol -> onVolumeChange(participant.socketId, vol) }
                      )
                  }
              }
         }
    }

@Composable
fun VideoRenderer(
    track: VideoTrack,
    eglBaseContext: EglBase.Context?,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBaseContext, null)
                setScalingType(scalingType)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        update = { view ->
            // Update logic if needed
        },
        onRelease = { view ->
            track.removeSink(view)
            view.release()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun AudioOnlyLayout(
    participants: List<conectate.com.data.model.VoiceParticipant>,
    peerConnectionStates: Map<String, String>? = null,
    signalingStates: Map<String, String>? = null,
    debugInfo: String? = null,
    onVolumeChange: (String, Float) -> Unit
) {
    LazyVerticalGrid(

        columns = GridCells.Fixed(3), // 3 columns for audio avatars
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
         gridItems(participants) { participant ->
              ParticipantCard(
                  participant = participant,
                  connectionState = peerConnectionStates?.get(participant.socketId),
                  signalingState = signalingStates?.get(participant.socketId),
                  debugInfo = debugInfo,
                  onVolumeChange = { vol -> onVolumeChange(participant.socketId, vol) }
              )
         }
    }
}

@Composable
fun ParticipantCard(
    participant: conectate.com.data.model.VoiceParticipant,
    connectionState: String? = null,
    signalingState: String? = null,
    debugInfo: String? = null,
    onVolumeChange: (Float) -> Unit = {}
) {
    var showVolume by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showVolume = !showVolume }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
             CircularProfileImage(
                 imageUrl = participant.avatarUrl,
                 size = 80.dp,
                 showBorder = participant.isSpeaking,
                 borderColor = MaterialTheme.colorScheme.primary,
                 borderWidth = 3.dp
             )
             
             if (participant.isMuted) {
                 Box(
                     modifier = Modifier
                         .align(Alignment.BottomEnd)
                         .background(MaterialTheme.colorScheme.surface, CircleShape)
                         .padding(2.dp)
                 ) {
                     Box(
                         modifier = Modifier
                             .size(18.dp)
                             .background(MaterialTheme.colorScheme.error, CircleShape),
                         contentAlignment = Alignment.Center
                     ) {
                         Icon(
                             imageVector = Icons.Rounded.MicOff,
                             contentDescription = "Muted",
                             tint = Color.White,
                             modifier = Modifier.size(12.dp)
                         )
                     }
                 }
             }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = participant.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
        if (connectionState != null) {
            Text(
                text = when(connectionState) {
                     "CONNECTED" -> "Conectado"
                     "FAILED" -> "Error Conexión"
                     else -> connectionState
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (connectionState) {
                     "CONNECTED", "COMPLETED" -> Color.Green
                     "FAILED" -> Color.Red
                     else -> Color.Yellow
                 }
            )
        }
        
        if (signalingState != null) {
             Text(text = "Sig: $signalingState", color = Color.Green, style = MaterialTheme.typography.labelSmall)
        }
        if (debugInfo != null) {
             Text(text = debugInfo, color = Color.Cyan, style = MaterialTheme.typography.labelSmall, maxLines = 3, lineHeight = 10.sp)
        }
        
        AnimatedVisibility(visible = showVolume) {
            Slider(
                value = volume,
                onValueChange = { 
                    volume = it
                    onVolumeChange(it)
                },
                valueRange = 0f..2f, // Allow boost up to 2x? Or just 1.0 standard. WebRTC allows boost.
                steps = 19,
                modifier = Modifier.height(30.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlBar(
    isMuted: Boolean,
    isSpeaker: Boolean,
    videoMode: VoiceRoomRepository.VideoMode,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onShowChat: () -> Unit,
    onShowReactions: () -> Unit,
    onToggleCinema: () -> Unit,
    isCinemaActive: Boolean,
    isCreator: Boolean,
    onLeave: () -> Unit,
    unreadChatCount: Int = 0
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cinema Button (Premium/Creator only)
            if (isCreator) {
                ControlIcon(
                     icon = if (isCinemaActive) Icons.Default.MovieFilter else Icons.Default.Movie,
                     isActive = isCinemaActive,
                     activeColor = Color(0xFFFFD700), // Gold for premium feel
                     onClick = onToggleCinema
                )
            }
            
            // Screen Share
            ControlIcon(
                 icon = if (videoMode == VoiceRoomRepository.VideoMode.SCREEN) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
                 isActive = videoMode == VoiceRoomRepository.VideoMode.SCREEN,
                 activeColor = Color.Red, // Make it red when sharing like a "recording" indicator
                 onClick = onToggleScreenShare
            )
            
            // Reactions
            ControlIcon(
                 icon = Icons.Default.AddReaction,
                 isActive = false,
                 onClick = onShowReactions
            )
            
            // Camera
            ControlIcon(
                 icon = if (videoMode == VoiceRoomRepository.VideoMode.CAMERA) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                 isActive = videoMode == VoiceRoomRepository.VideoMode.CAMERA,
                 onClick = onToggleCamera
            )

            // Mic
            ControlIcon(
                 icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                 isActive = !isMuted,
                 activeColor = MaterialTheme.colorScheme.primary,
                 inactiveColor = MaterialTheme.colorScheme.error,
                 filled = true,
                 onClick = onToggleMute
            )
            
            // Chat
            Box {
                ControlIcon(
                     icon = Icons.Default.Chat,
                     isActive = unreadChatCount > 0, // Active tint if unread
                     activeColor = MaterialTheme.colorScheme.primary,
                     onClick = onShowChat
                )
                if (unreadChatCount > 0) {
                    Badge(
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (4).dp),
                        containerColor = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Speaker
            ControlIcon(
                 icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                 isActive = isSpeaker,
                 onClick = onToggleSpeaker
            )
            
            // End Call
            FloatingActionButton(
                onClick = onLeave,
                containerColor = MaterialTheme.colorScheme.error,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Rounded.CallEnd, contentDescription = "Colgar", tint = Color.White)
            }
        }
    }
}

@Composable
fun ControlIcon(
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val backgroundColor = if (filled && isActive) activeColor.copy(alpha = 0.1f) else Color.Transparent
    val contentColor = if (isActive) activeColor else inactiveColor
    
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .background(backgroundColor, CircleShape)
            .size(48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun PermissionRequestContent(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Permisos requeridos para unirse")
        Button(onClick = onRequest) {
            Text("Conceder Permisos")
        }
    }
}

object Colors {
    val Transparent = Color(0x00000000)
}

data class Participant(
    val id: String,
    val name: String,
    val socketId: String,
    val avatarUrl: String? = null,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = false
)


@Composable
private fun SpeakingWave(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    
    // Manually create animations to avoid @Composable inside standard map/forEach lambda
    val anim1 = infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse),
        label = "w1"
    )
    val anim2 = infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "w2"
    )
    val anim3 = infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "w3"
    )
    val anim4 = infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse),
        label = "w4"
    )
    

    val animValues = listOf(anim1, anim2, anim3, anim4)

    Row(
        modifier = Modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animValues.forEach { animValue ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animValue.value)
                    .background(color, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

@Composable
private fun ParticipantItem(
    name: String,
    isSpeaking: Boolean,
    isMuted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSpeaking) 
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSpeaking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSpeaking) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            
            if (isMuted) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "Silenciado",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            } else if (isSpeaking) {
                SpeakingWave(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


@Composable
fun EditRoomDialog(
    currentName: String,
    isPublic: Boolean,
    currentPassword: String?,
    onConfirm: (String, Boolean, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var public by remember { mutableStateOf(isPublic) }
    var password by remember { mutableStateOf(currentPassword ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Sala") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = public, onCheckedChange = { public = it })
                    Text("Pública")
                }
                if (!public) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, public, if (public) null else password) }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ParticipantCinemaCard(participant: conectate.com.data.model.VoiceParticipant) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (participant.isSpeaking) MaterialTheme.colorScheme.primary else Color.Gray)
        ) {
            CircularProfileImage(
                imageUrl = participant.avatarUrl,
                size = 50.dp,
                showBorder = participant.isSpeaking,
                borderColor = Color.White,
                borderWidth = 2.dp
            )
        }
        Text(
            text = participant.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White, // Forced white for cinema mode
            maxLines = 1,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ParticipantVideoBubble(
    participant: conectate.com.data.model.VoiceParticipant,
    track: org.webrtc.VideoTrack,
    eglBaseContext: org.webrtc.EglBase.Context?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            VideoRenderer(
                track = track,
                eglBaseContext = eglBaseContext,
                scalingType = org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL
            )
            
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = participant.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomSheet(
    onDismiss: () -> Unit,
    viewModel: VoiceRoomActiveViewModel
) {
    val messages by viewModel.messages.collectAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.6f)
                .padding(16.dp)
        ) {
            Text("Mensajes en sala", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(
                        message = msg, 
                        isMe = msg.userId == viewModel.getCurrentUserId(),
                        onReply = { replyTo = msg },
                        onCopy = { body ->
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(body))
                            android.widget.Toast.makeText(context, "Mensaje copiado", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            
            // Reply Preview
            androidx.compose.animation.AnimatedVisibility(visible = replyTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Respondiendo a ${replyTo?.userName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(replyTo?.text ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        IconButton(onClick = { replyTo = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar")
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Escribe un mensaje...") },
                    maxLines = 3
                )
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            val replyText = replyTo?.text ?: ""
                            // Clean up previous reply header if exists (Look for the "Respondiendo a" pattern)
                            // We assume the real message starts after the last double newline, OR we strip specifically.
                            // Better approach: Regex because users might type newlines.
                            // Pattern: "↪ Respondiendo a .*:\n(quoted text)\n\n(actual text)"
                            
                            val actualContent = if (replyText.contains("↪ Respondiendo a")) {
                                replyText.substringAfterLast("\n\n")
                            } else {
                                replyText
                            }
                            
                            val reply = replyTo
                            val msgText = if (reply != null) "↪ Respondiendo a ${reply.userName}:\n$actualContent\n\n$text" else text
                            viewModel.sendMessage(msgText)
                            text = ""
                            replyTo = null
                        }
                    },
                    enabled = text.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onReply: () -> Unit,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
               detectTapGestures(onLongPress = {
                   onCopy(message.text)
                   onReply()
               })
            },
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (!isMe) {
            Text(message.userName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, 
                topEnd = 16.dp, 
                bottomStart = if (isMe) 16.dp else 0.dp, 
                bottomEnd = if (isMe) 0.dp else 16.dp
            )
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ReactionPicker(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val emojis = listOf("❤️", "🔥", "👏", "😂", "😮", "🙌", "💯", "🎉")
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                emojis.forEach { emoji ->
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable { onSelect(emoji) }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingReaction(reaction: ReactionEvent) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -300f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "y"
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .offset(y = offsetY.dp)
            .graphicsLayer(alpha = opacity)
    ) {
        Text(reaction.emoji, style = MaterialTheme.typography.headlineLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendBottomSheet(
    onDismiss: () -> Unit,
    viewModel: VoiceRoomActiveViewModel
) {
    val inviteQuery by viewModel.inviteQuery.collectAsState()
    val inviteResults by viewModel.inviteResults.collectAsState()
    val isSearching by viewModel.isSearchingInvites.collectAsState()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Invitar Amigos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = inviteQuery,
                onValueChange = { viewModel.onInviteQueryChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar amigos...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isSearching) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(inviteResults) { user ->
                        InviteUserItem(
                            user = user,
                            onInvite = {
                                viewModel.inviteUser(user)
                                android.widget.Toast.makeText(
                                    context,
                                    "Invitación enviada a ${user.displayName}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                    if (inviteResults.isEmpty() && inviteQuery.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("No se encontraron usuarios")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InviteUserItem(
    user: UserInvite,
    onInvite: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile Image
            CircularProfileImage(
                imageUrl = user.avatarUrl,
                size = 40.dp,
                showBorder = false
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onInvite,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("Invitar")
            }
        }
    }
}



@Composable
fun FullScreenParticipantView(
    participant: conectate.com.data.model.VoiceParticipant,
    remoteVideoTracks: Map<String, org.webrtc.VideoTrack>,
    localVideoTrack: org.webrtc.VideoTrack?,
    localVideoMode: conectate.com.data.repository.VoiceRoomRepository.VideoMode,
    eglBaseContext: org.webrtc.EglBase.Context?,
    currentUserId: String,
    onFullscreenToggle: () -> Unit
) {
    val isLocal = participant.id == currentUserId
    
    val track = if (isLocal) {
        if (localVideoMode != conectate.com.data.repository.VoiceRoomRepository.VideoMode.NONE) localVideoTrack else null
    } else {
        remoteVideoTracks[participant.socketId]
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onFullscreenToggle() }
    ) {
        if (track != null) {
            VideoRenderer(
                track = track,
                eglBaseContext = eglBaseContext,
                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                 CircularProfileImage(imageUrl = participant.avatarUrl, size = 120.dp)
                 Spacer(Modifier.height(16.dp))
                 Text(participant.name, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
        
        // Overlay for back button or name
        IconButton(
            onClick = onFullscreenToggle,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar Pantalla Completa", tint = Color.White)
        }
    }
}
