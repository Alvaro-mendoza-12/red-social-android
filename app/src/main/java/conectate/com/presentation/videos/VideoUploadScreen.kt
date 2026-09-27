package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.model.VideoChapter
import conectate.com.data.model.parseVideoChapterTime
import conectate.com.data.model.validVideoChapters
import conectate.com.data.model.videoChapterTimeLabel
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.util.isDirectlyPublishableMp4
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@HiltViewModel
class VideoUploadViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val draftStore: VideoUploadDraftStore,
) : ViewModel() {

    data class SelectedSound(val id: String, val sourceVideoId: String?,
                             val title: String?, val artist: String?, val url: String?)

    private var ownedEditorExport: java.io.File? = null
    private var draftAccountId: String? = authService.getCurrentUserId()
    private val _draftId = MutableStateFlow(java.util.UUID.randomUUID().toString())
    val draftId: StateFlow<String> = _draftId.asStateFlow()
    private val _drafts = MutableStateFlow<List<VideoUploadDraft>>(emptyList())
    val drafts: StateFlow<List<VideoUploadDraft>> = _drafts.asStateFlow()
    private val _isSavingDraft = MutableStateFlow(false)
    val isSavingDraft: StateFlow<Boolean> = _isSavingDraft.asStateFlow()

    init {
        viewModelScope.launch {
            authService.observeSession().map { it?.user?.id?.trim()?.lowercase() }
                .distinctUntilChanged().collect { account ->
                    if (account != draftAccountId) {
                        draftAccountId = account
                        resetState()
                        _drafts.value = emptyList()
                        refreshDrafts()
                    }
                }
        }
    }

    fun refreshDrafts() {
        val account = authService.getCurrentUserId() ?: return
        viewModelScope.launch {
            runCatching { draftStore.list(account) }.onSuccess { loaded ->
                if (authService.getCurrentUserId() == account) _drafts.value = loaded
            }
        }
    }

    private fun currentDraft(): VideoUploadDraft = VideoUploadDraft(
        id = _draftId.value,
        title = _title.value,
        description = _description.value,
        tags = _tags.value,
        isPublic = _isPublic.value,
        allowClipSharing = _allowClipSharing.value,
        isSensitive = _isSensitive.value,
        videoDurationMs = _videoDurationMs.value,
        requiresEditing = _requiresEditing.value,
        soundId = soundId,
        soundSourceVideoId = soundSourceVideoId,
        soundTitle = soundTitle,
        soundArtist = soundArtist,
        soundUrl = soundUrl,
        soundCompositionReady = _soundCompositionReady.value,
        chapters = _chapters.value,
    )

    private suspend fun persistDraft(account: String): VideoUploadDraft {
        val saved = draftStore.save(account, currentDraft(), _videoUri.value, _thumbnailUri.value)
        if (authService.getCurrentUserId() != account) {
            throw IllegalStateException("Cambiaste de cuenta mientras se guardaba el borrador.")
        }
        _videoUri.value = if (saved.hasVideo) draftStore.videoUri(account, saved.id) else null
        _thumbnailUri.value = if (saved.hasThumbnail) draftStore.thumbnailUri(account, saved.id) else null
        cleanupOwnedEditorExport()
        refreshDrafts()
        return saved
    }

    fun saveDraft() {
        val account = authService.getCurrentUserId() ?: run {
            _uploadError.value = "Inicia sesión para guardar el borrador."
            return
        }
        if (_isUploading.value || !_isSavingDraft.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                persistDraft(account)
                AppFeedbackBus.showSuccess("Borrador guardado")
            } catch (error: Exception) {
                _uploadError.value = error.toUserFacingMessage("No se pudo guardar el borrador.")
            } finally {
                _isSavingDraft.value = false
            }
        }
    }

    fun restoreDraft(id: String, context: android.content.Context) {
        val account = authService.getCurrentUserId() ?: return
        if (_isUploading.value || !_isSavingDraft.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val draft = draftStore.load(account, id) ?: run {
                    _uploadError.value = "El borrador ya no está disponible."
                    refreshDrafts()
                    return@launch
                }
                val source = if (draft.hasVideo) draftStore.videoUri(account, id) else null
                val thumbnail = if (draft.hasThumbnail) draftStore.thumbnailUri(account, id) else null
                if (authService.getCurrentUserId() != account) return@launch
                cleanupOwnedEditorExport()
                _draftId.value = id
                _title.value = draft.title.take(120)
                _description.value = draft.description.take(1_000)
                _tags.value = draft.tags
                _isPublic.value = draft.isPublic
                _isSensitive.value = draft.isSensitive
                _videoUri.value = source
                _thumbnailUri.value = thumbnail
                soundId = draft.soundId
                soundSourceVideoId = draft.soundSourceVideoId
                soundTitle = draft.soundTitle
                soundArtist = draft.soundArtist
                soundUrl = draft.soundUrl
                _selectedSound.value = draft.soundId?.let {
                    SelectedSound(it, draft.soundSourceVideoId, draft.soundTitle, draft.soundArtist, draft.soundUrl)
                }
                _soundCompositionReady.value = draft.soundCompositionReady
                _videoDurationMs.value = draft.videoDurationMs
                _requiresEditing.value = draft.requiresEditing
                _allowClipSharing.value = draft.allowClipSharing
                _chapters.value = draft.chapters.takeIf {
                    validVideoChapters(it, draft.videoDurationMs)
                }.orEmpty()
                _uploadError.value = if (draft.hasVideo && source == null) {
                    "Falta el archivo del borrador. Selecciona de nuevo el video."
                } else null
                source?.let { uri ->
                    val info = withContext(Dispatchers.IO) { readVideoInfo(uri, context.applicationContext) }
                    if (authService.getCurrentUserId() == account && _draftId.value == id) {
                        _videoDurationMs.value = info.durationMs
                        _requiresEditing.value = evaluateVideoPreparation(
                            info.durationMs, info.width, info.height, info.fps, info.fileSizeBytes,
                            formatCompatible = info.formatCompatible,
                        ).requiresEditor
                    }
                }
            } catch (error: Exception) {
                _uploadError.value = error.toUserFacingMessage("No se pudo restaurar el borrador.")
            } finally {
                _isSavingDraft.value = false
            }
        }
    }

    fun deleteDraft(id: String) {
        val account = authService.getCurrentUserId() ?: return
        if (_isUploading.value || !_isSavingDraft.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val deleted = draftStore.delete(account, id)
                if (authService.getCurrentUserId() != account) return@launch
                if (deleted && _draftId.value == id) resetState()
                if (!deleted) _uploadError.value = "No se pudo eliminar el borrador."
                if (deleted) {
                    videoRepository.discardUnpublishedVideoDraft(id).onFailure {
                        android.util.Log.w("VideoUpload", "La limpieza remota del borrador queda pendiente", it)
                    }
                }
                refreshDrafts()
            } catch (error: Exception) {
                _uploadError.value = error.toUserFacingMessage("No se pudo eliminar el borrador.")
            } finally {
                _isSavingDraft.value = false
            }
        }
    }

    fun startNewDraft() {
        if (!_isUploading.value && !_isSavingDraft.value) resetState()
    }

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()
    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()
    private var mentionSearchJob: Job? = null

    private val _isPublic = MutableStateFlow(true)
    val isPublic: StateFlow<Boolean> = _isPublic.asStateFlow()
    private val _allowClipSharing = MutableStateFlow(true)
    val allowClipSharing: StateFlow<Boolean> = _allowClipSharing.asStateFlow()

    private val _isSensitive = MutableStateFlow(false)
    val isSensitive: StateFlow<Boolean> = _isSensitive.asStateFlow()

    private val _tags = MutableStateFlow("")
    val tags: StateFlow<String> = _tags.asStateFlow()
    private val _chapters = MutableStateFlow<List<VideoChapter>>(emptyList())
    val chapters: StateFlow<List<VideoChapter>> = _chapters.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _thumbnailUri = MutableStateFlow<Uri?>(null)
    val thumbnailUri: StateFlow<Uri?> = _thumbnailUri.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()
    private val _isPreparingVideo = MutableStateFlow(false)
    val isPreparingVideo: StateFlow<Boolean> = _isPreparingVideo.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _requiresEditing = MutableStateFlow(false)
    val requiresEditing: StateFlow<Boolean> = _requiresEditing.asStateFlow()

    private var soundId: String? = null
    private var soundSourceVideoId: String? = null
    private var soundTitle: String? = null
    private var soundArtist: String? = null
    private var soundUrl: String? = null
    private val _selectedSound = MutableStateFlow<SelectedSound?>(null)
    val selectedSound: StateFlow<SelectedSound?> = _selectedSound.asStateFlow()
    private val _soundCompositionReady = MutableStateFlow(true)
    val soundCompositionReady: StateFlow<Boolean> = _soundCompositionReady.asStateFlow()

    fun updateTitle(value: String) { _title.value = value.take(120) }
    fun updateDescription(value: String) {
        val clean = value.take(1_000)
        _description.value = clean
        mentionSearchJob?.cancel()
        val query = Regex("(?:^|\\s)@([A-Za-z0-9_.-]{2,24})$")
            .find(clean)?.groupValues?.get(1)
        if (query == null) {
            _mentionSuggestions.value = emptyList()
            return
        }
        mentionSearchJob = viewModelScope.launch {
            delay(180)
            userRepository.searchMentionCandidates(query).onSuccess { users ->
                _mentionSuggestions.value = users
            }.onFailure { _mentionSuggestions.value = emptyList() }
        }
    }
    fun addDescriptionMention(user: conectate.com.data.model.User) {
        val username = user.username?.trim()?.takeIf { it.isNotBlank() } ?: return
        val current = _description.value
        val match = Regex("(?:^|\\s)@([A-Za-z0-9_.-]{2,24})$").find(current) ?: return
        _description.value = current.replaceRange(match.range.last - match.groupValues[1].length,
            current.length, "@$username ").take(1_000)
        mentionSearchJob?.cancel()
        _mentionSuggestions.value = emptyList()
    }
    fun updateIsPublic(value: Boolean) { _isPublic.value = value }
    fun updateAllowClipSharing(value: Boolean) { _allowClipSharing.value = value }
    fun updateIsSensitive(value: Boolean) { _isSensitive.value = value }
    fun updateTags(value: String) { _tags.value = value }

    fun addChapter(title: String, time: String): Boolean {
        val startMs = parseVideoChapterTime(time)
        val candidate = VideoChapter(startMs ?: -1L, title.trim())
        val updated = (_chapters.value + candidate).sortedBy(VideoChapter::startMs)
        if (startMs == null || !validVideoChapters(updated, _videoDurationMs.value)) {
            _uploadError.value = "Escribe un título y un tiempo válido dentro del video (por ejemplo, 0:12). Máximo 50 capítulos."
            return false
        }
        _chapters.value = updated
        _uploadError.value = null
        return true
    }

    fun removeChapter(startMs: Long) {
        _chapters.value = _chapters.value.filterNot { it.startMs == startMs }
    }

    fun configureSound(
        id: String?,
        sourceVideoId: String?,
        title: String?,
        artist: String?,
        url: String?,
    ) {
        soundId = id?.trim()?.takeIf { it.isNotBlank() }
        soundSourceVideoId = sourceVideoId?.trim()?.takeIf { it.isNotBlank() }
        soundTitle = title?.trim()?.takeIf { it.isNotBlank() }
        soundArtist = artist?.trim()?.takeIf { it.isNotBlank() }
        soundUrl = url?.trim()?.takeIf { it.isNotBlank() }
        _selectedSound.value = soundId?.let {
            SelectedSound(it, soundSourceVideoId, soundTitle, soundArtist, soundUrl)
        }
        _soundCompositionReady.value = soundId == null
    }

    fun clearSound() {
        soundId = null
        soundSourceVideoId = null
        soundTitle = null
        soundArtist = null
        soundUrl = null
        _selectedSound.value = null
        _soundCompositionReady.value = true
    }

    suspend fun setVideoUri(
        uri: Uri,
        context: android.content.Context,
        containsSelectedSound: Boolean = false,
        preserveChapters: Boolean = false,
    ): Boolean? {
        if (_isUploading.value || _isSavingDraft.value || !_isPreparingVideo.compareAndSet(false, true)) return null
        try {
            // Content providers and metadata extraction may perform blocking disk or network work.
            val info = withContext(Dispatchers.IO) { readVideoInfo(uri, context.applicationContext) }
            val durationMs = info.durationMs
            val decision = evaluateVideoPreparation(
                durationMs = durationMs,
                width = info.width,
                height = info.height,
                fps = info.fps,
                fileSizeBytes = info.fileSizeBytes,
                formatCompatible = info.formatCompatible,
            )

            val nextOwnedExport = resolveOwnedEditorExportFile(context, uri)
            val previousOwnedExport = ownedEditorExport
            val isSameOwnedFile = runCatching {
                previousOwnedExport?.canonicalFile == nextOwnedExport?.canonicalFile
            }.getOrDefault(false)
            if (!isSameOwnedFile) {
                previousOwnedExport?.let { previous -> runCatching { previous.delete() } }
            }
            ownedEditorExport = nextOwnedExport
            if (_videoUri.value != null && _videoUri.value != uri) {
                _draftId.value = java.util.UUID.randomUUID().toString()
                _chapters.value = if (preserveChapters) {
                    _chapters.value.filter { it.startMs < durationMs }
                } else emptyList()
            }
            _videoUri.value = uri
            _videoDurationMs.value = durationMs
            _requiresEditing.value = decision.requiresEditor
            _soundCompositionReady.value = soundId == null || containsSelectedSound
            _uploadError.value = when (decision.reason) {
                VideoPreparationReason.UNKNOWN_DURATION ->
                    "No pudimos validar la duración. Ábrelo en el editor antes de publicar."
                VideoPreparationReason.TOO_LONG ->
                    "El video dura ${formatDuration(durationMs)}. Recórtalo a un máximo de 45 segundos."
                VideoPreparationReason.HIGH_RESOLUTION ->
                    "Novvin adaptará la resolución del video antes de publicarlo."
                VideoPreparationReason.HIGH_FRAME_RATE ->
                    "Novvin adaptará la fluidez del video antes de publicarlo."
                VideoPreparationReason.HIGH_BITRATE ->
                    "Novvin optimizará el video para que se reproduzca sin pausas antes de publicarlo."
                VideoPreparationReason.UNSUPPORTED_FORMAT ->
                    "Este formato necesita convertirse a MP4 compatible en el editor antes de publicarlo."
                VideoPreparationReason.READY -> null
            }
            return decision.requiresEditor
        } finally {
            _isPreparingVideo.value = false
        }
    }
    
    fun setThumbnailUri(uri: Uri?) { _thumbnailUri.value = uri } // Allow nullable

    private data class VideoPickInfo(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val fileSizeBytes: Long = -1L,
        val formatCompatible: Boolean = false,
    )

    private fun readVideoInfo(uri: Uri, context: android.content.Context): VideoPickInfo {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0
                val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?: 0f
                val fileSizeBytes = context.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { descriptor -> descriptor.statSize }
                    ?: -1L
                VideoPickInfo(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    fileSizeBytes = fileSizeBytes,
                    formatCompatible = isDirectlyPublishableMp4(context, uri),
                )
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoUpload", "Could not inspect selected video metadata", e)
            VideoPickInfo()
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    fun uploadVideo(onComplete: (Boolean) -> Unit) {
        if (_isUploading.value || _isPreparingVideo.value || _isSavingDraft.value) return
        val video = _videoUri.value
        if (video == null || _title.value.isBlank()) {
            _uploadError.value = "Selecciona un video y escribe un título."
            return
        }
        if (_requiresEditing.value) {
            _uploadError.value = "Recorta o procesa el video en el editor antes de publicarlo."
            return
        }
        if (!_soundCompositionReady.value) {
            _uploadError.value = "Procesa el video para integrar el sonido seleccionado antes de publicarlo."
            return
        }

        if (!_isUploading.compareAndSet(false, true)) return
        viewModelScope.launch {
            _uploadProgress.value = 0f
            _uploadError.value = null

            try {
                val account = authService.getCurrentUserId()
                    ?: throw IllegalStateException("Inicia sesión para publicar el video.")
                val savedDraft = persistDraft(account)
                val persistedVideo = _videoUri.value
                    ?: throw IllegalStateException("No se pudo conservar el video del borrador.")
                // Parse tags
                val tagsList = _tags.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val result = videoRepository.uploadVideo(
                    title = _title.value,
                    description = _description.value,
                    videoUri = persistedVideo,
                    thumbnailUri = _thumbnailUri.value,
                    isPublic = _isPublic.value,
                    isSensitive = _isSensitive.value,
                    tags = tagsList,
                    soundId = soundId,
                    soundSourceVideoId = soundSourceVideoId,
                    soundTitle = soundTitle,
                    soundArtist = soundArtist,
                    soundUrl = soundUrl,
                    clientPublishId = savedDraft.id,
                    chapters = _chapters.value,
                    allowClipSharing = _allowClipSharing.value,
                    onUploadProgress = { progress -> _uploadProgress.value = progress }
                )
                
                result.fold(
                    onSuccess = {
                        _uploadProgress.value = 1.0f
                        _isUploading.value = false
                        cleanupOwnedEditorExport()
                        runCatching { draftStore.delete(account, savedDraft.id) }
                            .onFailure { cleanupError ->
                                android.util.Log.w(
                                    "VideoUpload", "El video se publicó, pero no se pudo limpiar el borrador local", cleanupError,
                                )
                            }
                        _draftId.value = java.util.UUID.randomUUID().toString()
                        refreshDrafts()
                        onComplete(true)
                    },
                    onFailure = {
                        if (it is CancellationException) throw it
                        _isUploading.value = false
                        _uploadError.value = it.toUserFacingMessage("No se pudo subir el video.")
                        onComplete(false)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uploadError.value = e.toUserFacingMessage("No se pudo subir el video.")
                onComplete(false)
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun resetState() {
        mentionSearchJob?.cancel()
        _mentionSuggestions.value = emptyList()
        cleanupOwnedEditorExport()
        _draftId.value = java.util.UUID.randomUUID().toString()
        _title.value = ""
        _description.value = ""
        _isPublic.value = true
        _allowClipSharing.value = true
        _isSensitive.value = false
        _tags.value = ""
        _chapters.value = emptyList()
        _videoUri.value = null
        _videoDurationMs.value = 0L
        _requiresEditing.value = false
        _thumbnailUri.value = null
        _uploadError.value = null
        _uploadProgress.value = 0f
        clearSound()
    }

    private fun cleanupOwnedEditorExport() {
        ownedEditorExport?.let { file -> runCatching { file.delete() } }
        ownedEditorExport = null
    }

    override fun onCleared() {
        if (!_isUploading.value) cleanupOwnedEditorExport()
        super.onCleared()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun VideoUploadScreen(
    navController: NavController,
    viewModel: VideoUploadViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val mentionSuggestionsRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(mentionSuggestions) {
        if (mentionSuggestions.isNotEmpty()) {
            delay(120)
            mentionSuggestionsRequester.bringIntoView()
        }
    }
    val isPublic by viewModel.isPublic.collectAsState()
    val allowClipSharing by viewModel.allowClipSharing.collectAsState()
    val isSensitive by viewModel.isSensitive.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val thumbnailUri by viewModel.thumbnailUri.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isPreparingVideo by viewModel.isPreparingVideo.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val uploadError by viewModel.uploadError.collectAsState()
    val videoDurationMs by viewModel.videoDurationMs.collectAsState()
    val requiresEditing by viewModel.requiresEditing.collectAsState()
    val soundCompositionReady by viewModel.soundCompositionReady.collectAsState()
    val selectedSound by viewModel.selectedSound.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val isSavingDraft by viewModel.isSavingDraft.collectAsState()
    val currentDraftId by viewModel.draftId.collectAsState()
    var deletingDraftId by remember { mutableStateOf<String?>(null) }
    var confirmNewDraft by remember { mutableStateOf(false) }
    var chapterTitle by rememberSaveable { mutableStateOf("") }
    var chapterTime by rememberSaveable { mutableStateOf("") }
    var thumbnailToCrop by remember { mutableStateOf<Uri?>(null) }
    val sourceEntry = navController.previousBackStackEntry
    var pendingSoundUrl by rememberSaveable {
        mutableStateOf(sourceEntry?.savedStateHandle?.get<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_URL))
    }
    var pendingSoundTitle by rememberSaveable {
        mutableStateOf(sourceEntry?.savedStateHandle?.get<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_TITLE))
    }
    var pendingSoundArtist by rememberSaveable {
        mutableStateOf(sourceEntry?.savedStateHandle?.get<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_ARTIST))
    }
    var pendingSoundId by rememberSaveable {
        mutableStateOf(sourceEntry?.savedStateHandle?.get<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_ID))
    }
    var pendingSoundSourceVideoId by rememberSaveable {
        mutableStateOf(sourceEntry?.savedStateHandle?.get<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_SOURCE_ID))
    }

    LaunchedEffect(pendingSoundId, pendingSoundSourceVideoId, pendingSoundTitle, pendingSoundArtist, pendingSoundUrl) {
        if (pendingSoundId.isNullOrBlank()) {
            viewModel.clearSound()
        } else {
            viewModel.configureSound(
                id = pendingSoundId,
                sourceVideoId = pendingSoundSourceVideoId,
                title = pendingSoundTitle,
                artist = pendingSoundArtist,
                url = pendingSoundUrl,
            )
        }
    }

    LaunchedEffect(sourceEntry) {
        sourceEntry?.savedStateHandle?.remove<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_URL)
        sourceEntry?.savedStateHandle?.remove<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_TITLE)
        sourceEntry?.savedStateHandle?.remove<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_ARTIST)
        sourceEntry?.savedStateHandle?.remove<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_SOURCE_ID)
        sourceEntry?.savedStateHandle?.remove<String>(conectate.com.presentation.video.PENDING_VIDEO_SOUND_ID)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refreshDrafts() }

    deletingDraftId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingDraftId = null },
            title = { Text("Eliminar borrador") },
            text = { Text("Se borrarán el texto y los archivos guardados de este borrador.") },
            confirmButton = { TextButton(onClick = {
                deletingDraftId = null
                viewModel.deleteDraft(id)
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deletingDraftId = null }) { Text("Cancelar") } },
        )
    }
    if (confirmNewDraft) AlertDialog(
        onDismissRequest = { confirmNewDraft = false },
        title = { Text("Nuevo video") },
        text = { Text("Guarda este borrador primero si deseas recuperarlo después.") },
        confirmButton = { TextButton(onClick = {
            confirmNewDraft = false
            viewModel.startNewDraft()
        }) { Text("Empezar nuevo") } },
        dismissButton = { TextButton(onClick = { confirmNewDraft = false }) { Text("Cancelar") } },
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewPlaying by remember(videoUri) { mutableStateOf(false) }
    var previewFailed by remember(videoUri) { mutableStateOf(false) }
    val previewPlayer = remember(videoUri) {
        videoUri?.let { uri ->
            runCatching {
                ExoPlayer.Builder(context.applicationContext).build().apply {
                    playWhenReady = false
                    repeatMode = Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
            }.getOrNull()
        }
    }

    DisposableEffect(previewPlayer, lifecycleOwner) {
        val player = previewPlayer
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) previewPlaying = false
            }

            override fun onPlayerError(error: PlaybackException) {
                previewPlaying = false
                previewFailed = true
                player?.pause()
            }
        }
        player?.addListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                player?.pause()
                previewPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            player?.release()
        }
    }

    LaunchedEffect(uploadError) {
        uploadError?.takeIf { it.isNotBlank() }?.let(AppFeedbackBus::showError)
    }

    val selectionScope = rememberCoroutineScope()
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectionScope.launch {
                val mustEdit = viewModel.setVideoUri(selectedUri, context) ?: return@launch
                if (mustEdit || selectedSound != null) {
                    navController.navigate(
                        conectate.com.presentation.navigation.Screen.VideoEditor.createRoute(
                            selectedUri,
                            selectedSound?.url?.let(Uri::parse),
                        ),
                    )
                }
            }
        }
    }

    val thumbnailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> thumbnailToCrop = uri }

    thumbnailToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar portada del video",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.PORTRAIT,
            onDismiss = { thumbnailToCrop = null },
            onCropped = { croppedUri ->
                viewModel.setThumbnailUri(croppedUri)
                thumbnailToCrop = null
            }
        )
    }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Handle result from Editor
    val uploadBackStackEntry by navController.currentBackStackEntryAsState()
    val editedVideoUriState = uploadBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>("editedVideo", null)
        ?.collectAsState()
    val editedVideoUri = editedVideoUriState?.value
    
    LaunchedEffect(editedVideoUri) {
        editedVideoUri?.let { uriString ->
            val uri = Uri.parse(uriString)
            viewModel.setVideoUri(
                uri = uri,
                context = context,
                containsSelectedSound = selectedSound != null,
                preserveChapters = true,
            )
            // Clear the handle so it doesn't trigger again
            uploadBackStackEntry?.savedStateHandle?.remove<String>("editedVideo")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subir Video") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                }
            )
        },
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        }
    ) { padding ->
        if (isUploading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp).fillMaxWidth()
                ) {
                    Text("Subiendo video...", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(uploadProgress * 100).toInt()}%")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (drafts.isNotEmpty()) {
                    Text("Borradores", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth())
                    drafts.forEach { draft ->
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = if (draft.id == currentDraftId) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(draft.title.ifBlank { "Video sin título" }, maxLines = 1)
                                    Text(if (draft.hasVideo) "Video guardado" else "Solo texto",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { viewModel.restoreDraft(draft.id, context) },
                                    enabled = !isSavingDraft) { Text("Retomar") }
                                IconButton(onClick = { deletingDraftId = draft.id },
                                    enabled = !isSavingDraft) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar borrador")
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::saveDraft,
                        enabled = !isSavingDraft && (videoUri != null || title.isNotBlank() || description.isNotBlank()),
                        modifier = Modifier.weight(1f)) {
                        Text(if (isSavingDraft) "Guardando…" else "Guardar borrador")
                    }
                    TextButton(onClick = { confirmNewDraft = true },
                        enabled = !isSavingDraft) { Text("Nuevo") }
                }
                // Video Selector (Shows preview if selected)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable {
                            if (videoUri == null) videoPickerLauncher.launch("video/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (videoUri != null) {
                        if (previewPlayer != null && !previewFailed) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = previewPlayer
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                update = { playerView ->
                                    if (playerView.player !== previewPlayer) {
                                        playerView.player = previewPlayer
                                    }
                                    playerView.useController = false
                                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        if (previewPlayer.isPlaying) {
                                            previewPlayer.pause()
                                            previewPlaying = false
                                        } else {
                                            if (previewPlayer.playbackState == Player.STATE_ENDED) previewPlayer.seekTo(0)
                                            previewPlayer.play()
                                            previewPlaying = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!previewPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Reproducir vista previa",
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            }
                        } else {
                            AsyncImage(
                                model = videoUri,
                                contentDescription = "Miniatura del video",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(48.dp))
                            Text("Seleccionar Video (Obligatorio)")
                        }
                    }
                }

                selectedSound?.let { sound ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.MusicNote, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sound.title ?: "Sonido seleccionado",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                sound.artist?.let { artist ->
                                    Text(
                                        artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            if (!soundCompositionReady) {
                                IconButton(onClick = {
                                    pendingSoundUrl = null
                                    pendingSoundTitle = null
                                    pendingSoundArtist = null
                                    pendingSoundId = null
                                    pendingSoundSourceVideoId = null
                                    viewModel.clearSound()
                                }) {
                                    Icon(Icons.Default.Close, "Quitar sonido")
                                }
                            }
                        }
                    }
                }

                if (videoUri != null) {
                    if (requiresEditing) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCut, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (videoDurationMs > 0L) {
                                        if (videoDurationMs > MAX_VIDEO_UPLOAD_DURATION_MS) {
                                            "Selecciona el fragmento de 45 segundos que quieres publicar."
                                        } else {
                                            "Prepara el video para publicarlo en calidad compatible."
                                        }
                                    } else {
                                        "Procesa el video en el editor para poder publicarlo."
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            navController.navigate(
                                conectate.com.presentation.navigation.Screen.VideoEditor.createRoute(
                                    videoUri!!,
                                    selectedSound?.url?.let(Uri::parse),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (requiresEditing) "Preparar video" else "Editar video")
                    }

                    TextButton(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cambiar video")
                    }
                }

                // Thumbnail Selector (Optional)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable { thumbnailPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailUri != null) {
                         AsyncImage(
                            model = thumbnailUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Remove button
                        IconButton(
                            onClick = { viewModel.setThumbnailUri(null) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                             Icon(Icons.Default.Close, contentDescription = "Quitar", tint = Color.White)
                        }

                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(32.dp))
                            Text("Seleccionar Miniatura (Opcional)")
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (mentionSuggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .bringIntoViewRequester(mentionSuggestionsRequester),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Mencionar usuario", style = MaterialTheme.typography.labelMedium)
                            mentionSuggestions.forEach { user ->
                                TextButton(
                                    onClick = { viewModel.addDescriptionMention(user) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("@${user.username}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                
                 OutlinedTextField(
                    value = tags,
                    onValueChange = { viewModel.updateTags(it) },
                    label = { Text("Hashtags (separados por coma)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ej: fyp, viral, musica") }
                )

                if (videoUri != null && !requiresEditing) {
                    Text("Capítulos del video", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth())
                    Text("Ayudan a saltar a cada parte. Hasta 50; el tiempo debe estar dentro del video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth())
                    chapters.forEach { chapter ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${videoChapterTimeLabel(chapter.startMs)}  ${chapter.title}",
                                modifier = Modifier.weight(1f), maxLines = 2)
                            IconButton(onClick = { viewModel.removeChapter(chapter.startMs) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Quitar capítulo ${chapter.title}")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = chapterTime,
                            onValueChange = { chapterTime = it.take(5) },
                            label = { Text("Tiempo") },
                            placeholder = { Text("0:12") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = chapterTitle,
                            onValueChange = { chapterTitle = it.take(80) },
                            label = { Text("Título") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                    }
                    OutlinedButton(onClick = {
                        if (viewModel.addChapter(chapterTitle, chapterTime)) {
                            chapterTitle = ""
                            chapterTime = ""
                        }
                    }, enabled = chapters.size < 50, modifier = Modifier.fillMaxWidth()) {
                        Text("Añadir capítulo")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Público")
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { viewModel.updateIsPublic(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Permitir enlaces a fragmentos")
                        Text("Comparte un punto del video original sin generar una copia.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = allowClipSharing,
                        onCheckedChange = viewModel::updateAllowClipSharing)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Contenido sensible (+18)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Se cubrirá según la preferencia de cada persona.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSensitive,
                        onCheckedChange = viewModel::updateIsSensitive
                    )
                }

                if (uploadError != null) {
                    Text(uploadError!!, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        viewModel.uploadVideo { success ->
                            if (success) {
                                viewModel.resetState() 
                                AppFeedbackBus.showSuccess("Video subido exitosamente")
                                
                                // Navigate to Home with Videos tab selected
                                val targetRoute = conectate.com.presentation.navigation.Screen.Home.createRoute(
                                    startTab = conectate.com.presentation.navigation.Screen.Videos.route
                                )
                                
                                try {
                                    navController.navigate(targetRoute) {
                                        // Pop up to the root/login to ensure we clear the stack if needed
                                        // But mainly we want to clear the previous Home instance so it reloads with the new tab
                                        popUpTo(conectate.com.presentation.navigation.Screen.Home.route) { 
                                            inclusive = true 
                                        }
                                        launchSingleTop = true
                                    }
                                } catch (e: Exception) {
                                    // Fallback
                                    navController.navigate("home")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Validation: Only Video and Title required now
                    enabled = videoUri != null && title.isNotBlank() && !requiresEditing && !isPreparingVideo
                        && soundCompositionReady
                ) {
                    Text(if (isPreparingVideo) "Analizando video…" else "Publicar Video")
                }
            }
        }
    }
}
