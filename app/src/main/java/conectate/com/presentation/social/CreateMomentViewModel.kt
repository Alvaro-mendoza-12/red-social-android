package conectate.com.presentation.social

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.MomentRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.model.User
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.data.music.MomentMusicCatalogRepository
import conectate.com.data.music.CommunityMusicTrack
import conectate.com.data.music.CommunityMusicUploadDraft
import conectate.com.data.music.MomentMusicCompositionExporter
import conectate.com.data.music.MomentMusicMixRequest
import conectate.com.data.supabase.AuthService
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CreateMomentViewModel @Inject constructor(
    private val repository: MomentRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val musicCatalogRepository: MomentMusicCatalogRepository,
) : ViewModel() {

    data class CommunityMusicUploadUiState(
        val isUploading: Boolean = false,
        val progress: Float = 0f,
        val submittedTrack: CommunityMusicTrack? = null,
        val error: String? = null,
    )

    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    private val _galleryMedia = MutableStateFlow<List<GalleryMedia>>(emptyList())
    val galleryMedia = _galleryMedia.asStateFlow()

    private val _audienceUsers = MutableStateFlow<List<User>>(emptyList())
    val audienceUsers = _audienceUsers.asStateFlow()

    private val _isSearchingAudience = MutableStateFlow(false)
    val isSearchingAudience = _isSearchingAudience.asStateFlow()
    
    // State for trimming
    private val _trimmingState = MutableStateFlow<TrimmingState>(TrimmingState.Idle)
    val trimmingState = _trimmingState.asStateFlow()

    private val _musicCatalogState = MutableStateFlow(MusicCatalogUiState())
    val musicCatalogState = _musicCatalogState.asStateFlow()
    private val _communityMusicUploadState = MutableStateFlow(CommunityMusicUploadUiState())
    val communityMusicUploadState = _communityMusicUploadState.asStateFlow()
    private val musicCatalogLoader = MomentMusicCatalogLoader(
        scope = viewModelScope,
        state = _musicCatalogState,
        fetch = musicCatalogRepository::search,
    )

    fun searchApprovedMusic(query: String = "", category: String = "Todo", section: String = _musicCatalogState.value.section) {
        musicCatalogLoader.search(query, category, section)
    }

    fun loadMoreApprovedMusic() {
        musicCatalogLoader.loadMore()
    }

    fun toggleMusicFavorite(track: ApprovedMomentMusic) {
        val target = !track.isFavorite
        _musicCatalogState.value = _musicCatalogState.value.copy(
            items = _musicCatalogState.value.items.map { if (it.id == track.id) it.copy(isFavorite = target) else it },
            actionMessage = null,
        )
        viewModelScope.launch {
            musicCatalogRepository.setFavorite(track, target).onFailure { error ->
                _musicCatalogState.value = _musicCatalogState.value.copy(
                    items = _musicCatalogState.value.items.map { if (it.id == track.id) it.copy(isFavorite = track.isFavorite) else it },
                    actionMessage = error.message ?: "No se pudo actualizar Favoritos.",
                )
            }
        }
    }

    fun reportMusic(track: ApprovedMomentMusic, reason: String = "contenido_incorrecto") {
        viewModelScope.launch {
            musicCatalogRepository.report(track, reason).fold(
                onSuccess = { _musicCatalogState.value = _musicCatalogState.value.copy(actionMessage = "Reporte enviado para revisión.") },
                onFailure = { _musicCatalogState.value = _musicCatalogState.value.copy(actionMessage = musicActionError(it)) },
            )
        }
    }

    fun hideMusicRecommendation(track: ApprovedMomentMusic) {
        viewModelScope.launch {
            musicCatalogRepository.notInterested(track).fold(
                onSuccess = {
                    _musicCatalogState.value = _musicCatalogState.value.copy(
                        items = _musicCatalogState.value.items.filterNot { it.id == track.id },
                        actionMessage = "Ocultamos esta pista de tus recomendaciones.",
                    )
                },
                onFailure = { _musicCatalogState.value = _musicCatalogState.value.copy(actionMessage = musicActionError(it)) },
            )
        }
    }

    fun recordMusicPreviewStarted(track: ApprovedMomentMusic) {
        viewModelScope.launch {
            // Telemetry is best-effort and never blocks preview playback.
            musicCatalogRepository.recordPreviewStarted(track)
        }
    }

    fun clearMusicActionMessage() {
        _musicCatalogState.value = _musicCatalogState.value.copy(actionMessage = null)
    }

    private fun musicActionError(error: Throwable): String =
        error.message ?: "No se pudo completar la acción musical."

    fun uploadCommunityMusic(audioUri: Uri, coverUri: Uri?, draft: CommunityMusicUploadDraft) {
        if (_communityMusicUploadState.value.isUploading) return
        _communityMusicUploadState.value = CommunityMusicUploadUiState(isUploading = true)
        viewModelScope.launch {
            musicCatalogRepository.uploadCommunityTrack(audioUri, coverUri, draft) { progress ->
                _communityMusicUploadState.value = _communityMusicUploadState.value.copy(
                    isUploading = true,
                    progress = progress.coerceIn(0f, 1f),
                )
            }.fold(
                onSuccess = { track ->
                    _communityMusicUploadState.value = CommunityMusicUploadUiState(
                        submittedTrack = track,
                        progress = 1f,
                    )
                    searchApprovedMusic(_musicCatalogState.value.query, _musicCatalogState.value.category)
                },
                onFailure = { error ->
                    _communityMusicUploadState.value = CommunityMusicUploadUiState(
                        error = communityUploadError(error),
                    )
                },
            )
        }
    }

    fun clearCommunityMusicUploadState() {
        if (!_communityMusicUploadState.value.isUploading) {
            _communityMusicUploadState.value = CommunityMusicUploadUiState()
        }
    }

    private fun communityUploadError(error: Throwable): String = when (error.message) {
        "AUTH_REQUIRED" -> "Tu sesión venció. Inicia sesión otra vez antes de publicar música."
        "NOT_FOUND" -> "El servidor activo todavía no tiene disponible Música de Comunidad. Actualiza Novvin y vuelve a intentarlo."
        "RIGHTS_DECLARATIONS_REQUIRED" -> "Debes aceptar todas las declaraciones de derechos."
        "COMMUNITY_MUSIC_STORAGE_OWNERSHIP_REQUIRED" -> "El servidor no pudo validar la propiedad del archivo subido."
        "COMMUNITY_MUSIC_PUBLIC_URL_REQUIRED" -> "El archivo se subió, pero no se pudo generar una URL reproducible."
        "COMMUNITY_MUSIC_COVER_PUBLIC_URL_REQUIRED" -> "La portada se subió, pero no se pudo preparar su URL."
        "MUSIC_TERMS_VERSION_REQUIRED" -> "Las condiciones cambiaron. Vuelve a revisar y aceptar la versión vigente."
        "MUSIC_AUDIO_DECODE_FAILED", "MUSIC_AUDIO_STREAM_REQUIRED" -> "El servidor no pudo decodificar el audio. Prueba con MP3, M4A, WAV, OGG o FLAC."
        "MUSIC_DURATION_MISMATCH", "VALID_AUDIO_DURATION_REQUIRED" -> "La duración detectada no es válida o no coincide con el archivo."
        "MUSIC_PREVIEW_GENERATION_FAILED" -> "No se pudo generar la vista previa de la pista."
        "MUSIC_ANTIVIRUS_UNAVAILABLE" -> "La validación de seguridad musical no está disponible en el servidor."
        "MUSIC_MALWARE_DETECTED" -> "La validación de seguridad bloqueó este archivo."
        "BACKEND_UNAVAILABLE" -> "El servidor de música no está disponible en este momento."
        else -> error.message ?: "No se pudo enviar la pista a revisión."
    }

    fun loadGalleryMedia(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mediaList = mutableListOf<GalleryMedia>()
            
            // 1. Fetch Images
            val imageProjection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DATE_ADDED
            )
            val imageUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            
            try {
                context.contentResolver.query(imageUri, imageProjection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val contentUri = android.content.ContentUris.withAppendedId(imageUri, id)
                        
                        mediaList.add(GalleryMedia(id, contentUri, MediaType.IMAGE, 0L, dateAdded))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CreateMomentViewModel", "Error fetching images", e)
            }

            // 2. Fetch Videos
            val videoProjection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DURATION,
                android.provider.MediaStore.Video.Media.DATE_ADDED
            )
            val videoUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            
            try {
                context.contentResolver.query(videoUri, videoProjection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)
                    val dateColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_ADDED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val duration = cursor.getLong(durationColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val contentUri = android.content.ContentUris.withAppendedId(videoUri, id)
                        
                        mediaList.add(GalleryMedia(id, contentUri, MediaType.VIDEO, duration, dateAdded))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CreateMomentViewModel", "Error fetching videos", e)
            }

            // 3. Sort and Limit
            val sortedList = mediaList.sortedByDescending { it.dateAdded }.take(500)
            
            _galleryMedia.value = sortedList
            android.util.Log.d("CreateMomentViewModel", "Total media loaded: ${sortedList.size}")
        }
    }

    fun uploadMoment(
        context: android.content.Context,
        uri: Uri,
        mediaType: String,
        durationHours: Int,
        durationType: String,
        visibility: String,
        visibilityType: String,
        excludedIds: List<String>,
        audienceIds: List<String> = emptyList(),
        caption: String? = null,
        allowReplies: Boolean = true,
        overlayText: String? = null,
        overlayColor: String = "#FFFFFFFF",
        overlayFont: String = "sans",
        overlayScale: Float = 1f,
        overlayX: Float = 0.5f,
        overlayY: Float = 0.38f,
        musicUri: Uri? = null,
        musicRemoteUrl: String? = null,
        musicTitle: String? = null,
        musicId: String? = null,
        musicArtist: String? = null,
        musicPresentation: String = "card",
        musicArtworkUrl: String? = null,
        musicSource: String? = null,
        musicSourceUrl: String? = null,
        musicLicense: String? = null,
        musicLicenseUrl: String? = null,
        musicStartMs: Long = 0L,
        musicDurationMs: Long = 6_000L,
        musicVolume: Float = 1f,
        originalAudioVolume: Float = 1f,
        musicFadeInMs: Long = 0L,
        musicFadeOutMs: Long = 0L,
        duckOriginalAudio: Boolean = false,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (_isUploading.value) return@launch
            _isUploading.value = true
            var generatedComposition: java.io.File? = null
            try {
                var publicationUri = uri
                var musicEmbedded = false
                val selectedMusicUri = musicUri
                    ?: musicRemoteUrl?.takeIf(String::isNotBlank)?.let { remoteUrl ->
                        Uri.parse(UbuntuMedia.resolveUrl(remoteUrl) ?: remoteUrl)
                    }
                if (mediaType.equals("video", ignoreCase = true) && selectedMusicUri != null) {
                    val segmentEndMs = musicStartMs.coerceAtLeast(0L) + musicDurationMs.coerceAtLeast(1_000L)
                    val mixed = MomentMusicCompositionExporter.export(
                        context = context.applicationContext,
                        request = MomentMusicMixRequest(
                            videoUri = uri,
                            musicUri = selectedMusicUri,
                            musicStartMs = musicStartMs,
                            musicEndMs = segmentEndMs,
                            musicVolume = musicVolume,
                            originalAudioVolume = originalAudioVolume,
                            fadeInMs = musicFadeInMs,
                            fadeOutMs = musicFadeOutMs,
                            duckOriginalAudio = duckOriginalAudio,
                        ),
                    ).getOrElse { error ->
                        onError("No se pudo combinar la música con el video: ${error.message ?: "exportación incompatible"}")
                        return@launch
                    }
                    generatedComposition = mixed
                    publicationUri = Uri.fromFile(mixed)
                    musicEmbedded = true
                }
                val result = repository.createMoment(
                    mediaUri = publicationUri,
                    mediaType = mediaType,
                    durationHours = durationHours,
                    durationType = durationType,
                    visibility = visibility,
                    visibilityType = visibilityType,
                    excludedIds = excludedIds,
                    audienceIds = audienceIds,
                    caption = caption,
                    allowReplies = allowReplies,
                    overlayText = overlayText,
                    overlayColor = overlayColor,
                    overlayFont = overlayFont,
                    overlayScale = overlayScale,
                    overlayX = overlayX,
                    overlayY = overlayY,
                    musicUri = musicUri,
                    musicRemoteUrl = musicRemoteUrl,
                    musicTitle = musicTitle,
                    musicId = musicId,
                    musicArtist = musicArtist,
                    musicPresentation = musicPresentation,
                    musicArtworkUrl = musicArtworkUrl,
                    musicSource = musicSource,
                    musicSourceUrl = musicSourceUrl,
                    musicLicense = musicLicense,
                    musicLicenseUrl = musicLicenseUrl,
                    musicStartMs = musicStartMs,
                    musicDurationMs = musicDurationMs,
                    musicVolume = musicVolume,
                    originalAudioVolume = originalAudioVolume,
                    musicFadeInMs = musicFadeInMs,
                    musicFadeOutMs = musicFadeOutMs,
                    duckOriginalAudio = duckOriginalAudio,
                    musicEmbedded = musicEmbedded,
                )
                if (result.isSuccess) {
                    try {
                        repository.refreshMoments()
                        android.util.Log.d("CreateMomentViewModel", "Moments list refreshed after creation")
                    } catch (error: Exception) {
                        android.util.Log.e("CreateMomentViewModel", "Error refreshing moments after creation", error)
                    }
                    onSuccess()
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "No se pudo publicar el momento"
                    onError(errorMessage)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.e("CreateMomentViewModel", "Uncaught moment publish failure", error)
                onError(error.message ?: "No se pudo publicar el momento")
            } finally {
                generatedComposition?.delete()
                _isUploading.value = false
            }
        }
    }

    fun searchAudienceUsers(query: String) {
        val clean = query.trim()
        if (clean.length < 2) {
            _audienceUsers.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearchingAudience.value = true
            try {
                val currentUserId = authService.getCurrentUserId()
                _audienceUsers.value = userRepository.searchUsers(clean)
                    .getOrDefault(emptyList())
                    .filter { it.id.isNotBlank() && it.id != currentUserId }
                    .distinctBy { it.id }
                    .take(40)
            } catch (error: Exception) {
                android.util.Log.e("CreateMomentViewModel", "Audience search failed", error)
                _audienceUsers.value = emptyList()
            } finally {
                _isSearchingAudience.value = false
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun trimVideo(
        context: android.content.Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        // Fix: Transformer requires a Looper (Main Thread) to initialize and start
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            _trimmingState.value = TrimmingState.Trimming(0f)
            
            try {
                val outputFile = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File(context.cacheDir, "trimmed_${System.currentTimeMillis()}.mp4")
                }

                val mediaItem = androidx.media3.common.MediaItem.fromUri(inputUri)
                    .buildUpon()
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()

                val transformer = androidx.media3.transformer.Transformer.Builder(context)
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult) {
                            if (outputFile.exists() && outputFile.length() > 0L) {
                                _trimmingState.value = TrimmingState.Idle
                                onSuccess(Uri.fromFile(outputFile))
                            } else {
                                outputFile.delete()
                                val message = "El recorte no generó un video válido"
                                _trimmingState.value = TrimmingState.Error(message)
                                onError(message)
                            }
                        }

                        override fun onError(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult, exception: androidx.media3.transformer.ExportException) {
                            outputFile.delete()
                            _trimmingState.value = TrimmingState.Error(exception.message ?: "Error trimming")
                            onError(exception.message ?: "Error trimming")
                        }
                    })
                    .build()

                transformer.start(mediaItem, outputFile.absolutePath)
                
            } catch (e: Exception) {
                _trimmingState.value = TrimmingState.Error(e.message ?: "Unknown error")
                onError(e.message ?: "Unknown error")
            }
        }
    }
}

data class GalleryMedia(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val duration: Long,
    val dateAdded: Long
)

enum class MediaType {
    IMAGE, VIDEO
}

sealed class TrimmingState {
    object Idle : TrimmingState()
    data class Trimming(val progress: Float) : TrimmingState()
    data class Error(val message: String) : TrimmingState()
}
