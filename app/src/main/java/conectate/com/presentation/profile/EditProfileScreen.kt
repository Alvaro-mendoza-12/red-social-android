package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.SimpleImageCropDialog
import conectate.com.presentation.messages.MessageMediaComposer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    var gender by remember { mutableStateOf("unspecified") }
    var birthDate by remember { mutableStateOf("") }
    var showGenderDropdown by remember { mutableStateOf(false) }
    var showBirthDatePicker by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var avatarMediaType by remember { mutableStateOf<String?>(null) }
    var avatarDurationMs by remember { mutableStateOf<Long?>(null) }
    var avatarVideoMuted by remember { mutableStateOf(true) }
    var coverImageUri by remember { mutableStateOf<Uri?>(null) }
    var trimCandidateUri by remember { mutableStateOf<Uri?>(null) }
    var trimCandidateDurationMs by remember { mutableStateOf(0L) }
    var pendingImageCrop by remember { mutableStateOf<Pair<Uri, ProfileImageTarget>?>(null) }
    var fullScreenPreviewUrl by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }

    var isTrimmingAvatar by remember { mutableStateOf(false) }

    var showUnifiedMediaPicker by remember { mutableStateOf(false) }
    var unifiedPickerTarget by remember { mutableStateOf(ProfileImageTarget.AVATAR) }

    LaunchedEffect(profileState) {
        if (!initialized && profileState is ProfileUiState.Success) {
            val user = (profileState as ProfileUiState.Success).user
            name = user.displayName
            bio = user.bio ?: ""
            country = user.country ?: ""
            city = user.city ?: ""
            gender = user.gender
            birthDate = user.birth_date.orEmpty()
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

    val parsedBirthDate = remember(birthDate) {
        runCatching { LocalDate.parse(birthDate) }.getOrNull()
    }
    val birthDateIsValid = birthDate.isBlank() ||
        parsedBirthDate?.let { !it.isAfter(LocalDate.now()) } == true

    if (showBirthDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = parsedBirthDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showBirthDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (!selected.isAfter(LocalDate.now())) {
                                birthDate = selected.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                showBirthDatePicker = false
                            }
                        }
                    },
                ) { Text("Usar fecha") }
            },
            dismissButton = {
                TextButton(onClick = { showBirthDatePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    pendingImageCrop?.let { (sourceUri, target) ->
        val preset = if (target == ProfileImageTarget.AVATAR) {
            ImageCropPreset.SQUARE
        } else {
            ImageCropPreset.COVER
        }
        SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = if (target == ProfileImageTarget.AVATAR) {
                "Recortar foto de perfil"
            } else {
                "Recortar portada"
            },
            presets = listOf(preset),
            initialPreset = preset,
            onDismiss = { pendingImageCrop = null },
            onCropped = { croppedUri ->
                if (target == ProfileImageTarget.AVATAR) {
                    imageUri = croppedUri
                    avatarMediaType = "image"
                    avatarDurationMs = null
                    avatarVideoMuted = true
                } else {
                    coverImageUri = croppedUri
                }
                pendingImageCrop = null
            }
        )
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

    Box(modifier = Modifier.fillMaxSize()) {

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
                    val rewrittenCoverUrl = remember(state.user.cover_url) {
                        UbuntuStorageUrl.rewrite(state.user.cover_url)
                    }
                    val coverCacheKey = remember(state.user.cover_url) {
                        UbuntuStorageUrl.stableCacheKey(state.user.cover_url)
                    }
                    val persistedCoverRequest = remember(rewrittenCoverUrl, coverCacheKey) {
                        rewrittenCoverUrl?.let { coverUrl ->
                            ImageRequest.Builder(context)
                                .data(coverUrl)
                                .memoryCacheKey(coverCacheKey)
                                .diskCacheKey(coverCacheKey)
                                .placeholderMemoryCacheKey(coverCacheKey)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .build()
                        }
                    }
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
                                } else if (persistedCoverRequest != null) {
                                    coil.compose.rememberAsyncImagePainter(persistedCoverRequest)
                                } else {
                                    coil.compose.rememberAsyncImagePainter(null)
                                },
                                contentDescription = "Portada",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .clickable { 
                                        unifiedPickerTarget = ProfileImageTarget.COVER
                                        showUnifiedMediaPicker = true 
                                    },
                                contentScale = ContentScale.Crop
                            )
                            
                            // Overlay for empty state
                            if (coverImageUri == null && state.user.cover_url.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .clickable { 
                                            unifiedPickerTarget = ProfileImageTarget.COVER
                                            showUnifiedMediaPicker = true
                                        }
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
                                onClick = { 
                                    unifiedPickerTarget = ProfileImageTarget.COVER
                                    showUnifiedMediaPicker = true 
                                },
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
                                onClick = { 
                                    unifiedPickerTarget = ProfileImageTarget.AVATAR
                                    showUnifiedMediaPicker = true
                                },
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

                        ExposedDropdownMenuBox(
                            expanded = showGenderDropdown,
                            onExpandedChange = { showGenderDropdown = it },
                        ) {
                            OutlinedTextField(
                                value = when (gender) {
                                    "man" -> "Hombre"
                                    "woman" -> "Mujer"
                                    else -> "Prefiero no decirlo"
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Me identifico como") },
                                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(showGenderDropdown)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                            )
                            ExposedDropdownMenu(
                                expanded = showGenderDropdown,
                                onDismissRequest = { showGenderDropdown = false },
                            ) {
                                listOf(
                                    "man" to "Hombre",
                                    "woman" to "Mujer",
                                    "unspecified" to "Prefiero no decirlo",
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            gender = value
                                            showGenderDropdown = false
                                        },
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = parsedBirthDate?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                                ?: birthDate,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Fecha de nacimiento") },
                            leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showBirthDatePicker = true }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "Elegir fecha")
                                }
                            },
                            isError = !birthDateIsValid,
                            supportingText = {
                                Text("El día se usa para avisar a tus amigos en tu cumpleaños.")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBirthDatePicker = true },
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
                                    !birthDateIsValid -> errorMessage = "Selecciona una fecha de nacimiento válida"
                                    else -> {
                                        isLoading = true
                                        errorMessage = null
                                        viewModel.updateProfile(
                                            name = trimmedName,
                                            bio = trimmedBio,
                                            country = country.trim(),
                                            city = city.trim(),
                                            gender = gender,
                                            birthDate = birthDate.takeIf { it.isNotBlank() },
                                            imageUri = imageUri,
                                            avatarMediaType = avatarMediaType,
                                            avatarVideoMuted = avatarVideoMuted,
                                            avatarDurationMs = avatarDurationMs,
                                            coverImageUri = coverImageUri,
                                            onSuccess = {
                                                isLoading = false
                                                // No need to force reload, optimistic update in ViewModel handles it
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

    if (showUnifiedMediaPicker) {
        MessageMediaComposer(
            initialFilter = if (unifiedPickerTarget == ProfileImageTarget.COVER) "image" else "all",
            maxItems = 1,
            allowedMediaTypes = if (unifiedPickerTarget == ProfileImageTarget.COVER) {
                setOf("image")
            } else {
                setOf("image", "video")
            },
            title = if (unifiedPickerTarget == ProfileImageTarget.COVER) {
                "Elegir foto de portada"
            } else {
                "Elegir foto o video de perfil"
            },
            confirmContentDescription = "Usar en el perfil",
            showViewOnceOption = false,
            showCaptionInput = false,
            isSending = false,
            onDismiss = { showUnifiedMediaPicker = false },
            onSend = { selected, _ ->
                showUnifiedMediaPicker = false
                selected.firstOrNull()?.let { media ->
                    val uri = media.uri
                    if (unifiedPickerTarget == ProfileImageTarget.AVATAR) {
                        val mimeType = context.contentResolver.getType(uri)?.substringBefore(";")?.lowercase().orEmpty()
                        val uriText = uri.toString().substringBefore("?").lowercase()
                        val isVideo = media.type.startsWith("video") ||
                            mimeType.startsWith("video/") ||
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
                            if (mimeType == "image/gif" || uriText.endsWith(".gif")) {
                                imageUri = uri
                                avatarMediaType = "gif"
                                avatarDurationMs = null
                                avatarVideoMuted = true
                            } else {
                                pendingImageCrop = uri to ProfileImageTarget.AVATAR
                            }
                        }
                    } else {
                        pendingImageCrop = uri to ProfileImageTarget.COVER
                    }
                }
            }
        )
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

enum class ProfileImageTarget {
    AVATAR,
    COVER
}
