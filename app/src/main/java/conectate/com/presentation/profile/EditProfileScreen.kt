package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import conectate.com.presentation.components.CircularProfileImage
import kotlinx.coroutines.delay

private const val PROFILE_AVATAR_MAX_VIDEO_MS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profileState by viewModel.profileState.collectAsState()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // State for fields
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var avatarMediaType by remember { mutableStateOf<String?>(null) }
    var avatarDurationMs by remember { mutableStateOf<Long?>(null) }
    var avatarVideoMuted by remember { mutableStateOf(true) }
    var coverImageUri by remember { mutableStateOf<Uri?>(null) }
    var trimCandidateUri by remember { mutableStateOf<Uri?>(null) }
    var trimCandidateDurationMs by remember { mutableStateOf(0L) }
    var isTrimmingAvatar by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(profileState) {
        if (!initialized && profileState is ProfileUiState.Success) {
            val user = (profileState as ProfileUiState.Success).user
            name = user.displayName
            bio = user.bio ?: ""
            country = user.country ?: ""
            city = user.city ?: ""
            avatarMediaType = user.avatar_media_type
            avatarDurationMs = user.avatar_duration_ms
            avatarVideoMuted = user.avatar_video_muted
            initialized = true
        }
    }
    
    // Load profile on screen entry (in case ViewModel was just created)
    LaunchedEffect(Unit) {
        viewModel.loadUserProfile(null)
    }

    val profileImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val mimeType = context.contentResolver.getType(uri)?.substringBefore(";")?.lowercase().orEmpty()
        val uriText = uri.toString().substringBefore("?").lowercase()
        val isVideo = mimeType.startsWith("video/") ||
            uriText.endsWith(".mp4") ||
            uriText.endsWith(".m4v") ||
            uriText.endsWith(".mov")
        if (isVideo) {
            val durationMs = viewModel.getProfileVideoDurationMs(uri)
            if (durationMs > PROFILE_AVATAR_MAX_VIDEO_MS) {
                trimCandidateUri = uri
                trimCandidateDurationMs = durationMs
            } else {
                imageUri = uri
                avatarMediaType = "video"
                avatarDurationMs = durationMs.takeIf { it > 0L }?.coerceAtMost(PROFILE_AVATAR_MAX_VIDEO_MS)
                avatarVideoMuted = true
            }
        } else {
            imageUri = uri
            avatarMediaType = if (mimeType == "image/gif" || uriText.endsWith(".gif")) "gif" else "image"
            avatarDurationMs = null
            avatarVideoMuted = true
        }
    }
    
    val coverImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        coverImageUri = uri
    }

    trimCandidateUri?.let { candidateUri ->
        ProfileAvatarTrimSheet(
            uri = candidateUri,
            durationMs = trimCandidateDurationMs,
            isTrimming = isTrimmingAvatar,
            onDismiss = {
                if (!isTrimmingAvatar) {
                    trimCandidateUri = null
                    trimCandidateDurationMs = 0L
                }
            },
            onTrim = { startMs ->
                isTrimmingAvatar = true
                viewModel.trimProfileAvatarVideo(
                    context = context,
                    inputUri = candidateUri,
                    startMs = startMs,
                    onSuccess = { trimmedUri ->
                        isTrimmingAvatar = false
                        trimCandidateUri = null
                        trimCandidateDurationMs = 0L
                        imageUri = trimmedUri
                        avatarMediaType = "video"
                        avatarDurationMs = PROFILE_AVATAR_MAX_VIDEO_MS
                        avatarVideoMuted = true
                    },
                    onError = { message ->
                        isTrimmingAvatar = false
                        errorMessage = message
                    }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Perfil") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
             when (val state = profileState) {
                is ProfileUiState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Cover Photo Section
                        Text(
                            "Foto de Portada",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            // Cover image preview
                            androidx.compose.foundation.Image(
                                painter = if (coverImageUri != null) {
                                    coil.compose.rememberAsyncImagePainter(coverImageUri)
                                } else if (!state.user.cover_url.isNullOrEmpty()) {
                                    coil.compose.rememberAsyncImagePainter(state.user.cover_url)
                                } else {
                                    coil.compose.rememberAsyncImagePainter(null)
                                },
                                contentDescription = "Portada",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .clickable { coverImageLauncher.launch("image/*") },
                                contentScale = ContentScale.Crop
                            )
                            
                            // Overlay for empty state
                            if (coverImageUri == null && state.user.cover_url.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .clickable { coverImageLauncher.launch("image/*") }
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Toca para agregar portada",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            
                            // Edit button
                            Button(
                                onClick = { coverImageLauncher.launch("image/*") },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Text("Cambiar")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Profile Photo Section
                        Text(
                            "Foto de Perfil",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        // Profile Image with Edit Overlay
                        Box(contentAlignment = Alignment.BottomEnd) {
                             CircularProfileImage(
                                imageUrl = imageUri?.toString() ?: state.user.avatar_url,
                                size = 120.dp,
                                mediaType = avatarMediaType ?: state.user.avatar_media_type,
                                muted = avatarVideoMuted,
                                allowSound = true
                            )
                            Button(
                                onClick = { profileImageLauncher.launch(arrayOf("image/*", "video/*")) },
                                modifier = Modifier.offset(y = 10.dp)
                            ) {
                                Text("Cambiar")
                            }
                        }

                        if ((avatarMediaType ?: state.user.avatar_media_type)?.contains("video", ignoreCase = true) == true) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Reproducir avatar con sonido")
                                Switch(
                                    checked = !avatarVideoMuted,
                                    onCheckedChange = { avatarVideoMuted = !it }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val canChangeName by remember(state) {
                            derivedStateOf {
                                val lastChange = state.user.last_name_change
                                if (lastChange == null) true
                                else {
                                    try {
                                        // Simple Parsing or proper DateTimeformatter
                                        // Assuming ISO-8601
                                        val lastDate = java.time.LocalDateTime.parse(lastChange)
                                        val diff = java.time.Duration.between(lastDate, java.time.LocalDateTime.now()).toDays()
                                        diff >= 7
                                    } catch (e: Exception) { true }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nombre Completo") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canChangeName,
                            supportingText = { 
                                if (!canChangeName) {
                                    Text("Solo puedes cambiar esto cada 7 días", color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("Solo puedes cambiar esto cada 7 días") 
                                }
                            }
                        )

                        OutlinedTextField(
                            value = bio,
                            onValueChange = { if (it.length <= 500) bio = it }, // Hard limit input
                            label = { Text("Biografía") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            supportingText = {
                                Text("${bio.length}/500 caracteres", 
                                    color = if (bio.length >= 500) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        OutlinedTextField(
                            value = country,
                            onValueChange = { country = it },
                            label = { Text("País") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("Ciudad") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (errorMessage != null) {
                            Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                val trimmedName = name.trim()
                                val trimmedBio = bio.trim()
                                
                                when {
                                    trimmedName.isEmpty() -> errorMessage = "El nombre no puede estar vacío"
                                    trimmedName.length < 2 -> errorMessage = "El nombre es muy corto"
                                    trimmedName.length > 50 -> errorMessage = "El nombre es muy largo"
                                    else -> {
                                        isLoading = true
                                        errorMessage = null
                                        viewModel.updateProfile(
                                            name = trimmedName,
                                            bio = trimmedBio,
                                            country = country.trim(),
                                            city = city.trim(),
                                            imageUri = imageUri,
                                            avatarMediaType = avatarMediaType,
                                            avatarVideoMuted = avatarVideoMuted,
                                            avatarDurationMs = avatarDurationMs,
                                            coverImageUri = coverImageUri,
                                            onSuccess = {
                                                isLoading = false
                                                // Force reload to show changes immediately
                                                viewModel.loadUserProfile(null)
                                                android.widget.Toast.makeText(
                                                    context, 
                                                    "¡Cambios guardados exitosamente!", 
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                navController.popBackStack()
                                            },
                                            onError = {
                                                isLoading = false
                                                errorMessage = it
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                             if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Guardar Cambios")
                            }
                        }
                    }
                }
                is ProfileUiState.Error -> {
                     Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Error al cargar perfil")
                        Button(onClick = { /* retry? */ }) { Text("Reintentar") }
                    }
                }
                ProfileUiState.Loading -> {
                     Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileAvatarTrimSheet(
    uri: Uri,
    durationMs: Long,
    isTrimming: Boolean,
    onDismiss: () -> Unit,
    onTrim: (Long) -> Unit
) {
    val maxStartMs = (durationMs - PROFILE_AVATAR_MAX_VIDEO_MS).coerceAtLeast(0L)
    var startMs by remember(uri, durationMs) { mutableStateOf(0f) }
    val safeStart = startMs.toLong().coerceIn(0L, maxStartMs)
    val safeEnd = (safeStart + PROFILE_AVATAR_MAX_VIDEO_MS).coerceAtMost(durationMs.coerceAtLeast(PROFILE_AVATAR_MAX_VIDEO_MS))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Recortar avatar", style = MaterialTheme.typography.titleLarge)
            ProfileAvatarVideoPreview(
                uri = uri,
                startMs = safeStart,
                endMs = safeEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Text("Segmento: ${formatAvatarTrimTime(safeStart)} - ${formatAvatarTrimTime(safeEnd)}")
            Slider(
                value = safeStart.toFloat(),
                onValueChange = { startMs = it },
                valueRange = 0f..maxStartMs.toFloat().coerceAtLeast(1f),
                enabled = maxStartMs > 0L && !isTrimming
            )
            Button(
                onClick = { onTrim(safeStart) },
                enabled = !isTrimming,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTrimming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Usar estos 2 segundos")
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun ProfileAvatarVideoPreview(
    uri: Uri,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            volume = 0f
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(startMs, endMs, exoPlayer) {
        exoPlayer.seekTo(startMs)
        exoPlayer.play()
        while (true) {
            if (exoPlayer.currentPosition >= endMs) {
                exoPlayer.seekTo(startMs)
            }
            delay(80)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { viewContext ->
            androidx.media3.ui.PlayerView(viewContext).apply {
                player = exoPlayer
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = exoPlayer },
        modifier = modifier
    )
}

private fun formatAvatarTrimTime(ms: Long): String {
    val totalTenths = (ms / 100L).coerceAtLeast(0L)
    val seconds = totalTenths / 10L
    val tenths = totalTenths % 10L
    return "$seconds.${tenths}s"
}
