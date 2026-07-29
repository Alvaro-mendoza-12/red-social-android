package conectate.com.presentation.videos

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TimestampWrapper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import conectate.com.util.VideoDeviceTier
import conectate.com.util.VideoPerformanceProfile
import conectate.com.util.VideoPerformanceProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@HiltViewModel
class VideoEditorViewModel @Inject constructor() : ViewModel() {

    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress = _exportProgress.asStateFlow()

    private val _exportStage = MutableStateFlow(EditorExportStage.IDLE)
    val exportStage = _exportStage.asStateFlow()

    private val _timeline = MutableStateFlow(EditorTimeline())
    val timeline = _timeline.asStateFlow()

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId = _selectedClipId.asStateFlow()

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs = _currentTimeMs.asStateFlow()

    private val _performanceProfile = MutableStateFlow<VideoPerformanceProfile?>(null)
    val performanceProfile = _performanceProfile.asStateFlow()

    private val _selectedExportQuality = MutableStateFlow(ExportQuality.AUTO)
    val selectedExportQuality = _selectedExportQuality.asStateFlow()

    private val _editorHint = MutableStateFlow<String?>(null)
    val editorHint = _editorHint.asStateFlow()

    private val _timelineThumbnails = MutableStateFlow<List<TimelineThumbnail>>(emptyList())
    val timelineThumbnails = _timelineThumbnails.asStateFlow()

    private val _isPreparingTimeline = MutableStateFlow(false)
    val isPreparingTimeline = _isPreparingTimeline.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var thumbnailJob: Job? = null
    private var thumbnailRequestId = 0

    fun getPlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            getOrInitProfile(context)
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        return exoPlayer!!
    }

    override fun onCleared() {
        super.onCleared()
        thumbnailRequestId += 1
        thumbnailJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        _timelineThumbnails.value.forEach { thumbnail ->
            if (!thumbnail.bitmap.isRecycled) thumbnail.bitmap.recycle()
        }
        _timelineThumbnails.value = emptyList()
    }

    fun setExportQuality(quality: ExportQuality) {
        _selectedExportQuality.value = quality
    }

    fun dismissEditorHint() {
        _editorHint.value = null
    }

    private fun getOrInitProfile(context: Context): VideoPerformanceProfile {
        val current = _performanceProfile.value
        if (current != null) return current
        val detected = VideoPerformanceProfiles.detect(context)
        _performanceProfile.value = detected
        return detected
    }

    private fun resolveDurationMs(context: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            raw?.toLongOrNull()?.coerceIn(1_000L, 180_000L) ?: 30_000L
        } catch (_: Exception) {
            30_000L
        }
    }

    fun setInitialVideo(context: Context, uri: Uri) {
        val profile = getOrInitProfile(context)
        val durationMs = resolveDurationMs(context, uri)
        val mainTrack = EditorTrack(type = TrackType.VIDEO)
        val initialClip = EditorClip(
            id = System.currentTimeMillis(),
            uri = uri,
            durationMs = durationMs,
            sourceDurationMs = durationMs
        )
        _timeline.value = EditorTimeline(
            tracks = listOf(mainTrack.copy(clips = listOf(initialClip)))
        )
        _selectedClipId.value = initialClip.id
        _currentTimeMs.value = 0L

        getPlayer(context).setPlaybackSpeed(1f)
        syncTimelineWithPlayer()
        loadTimelineThumbnails(context.applicationContext, uri, durationMs, profile)
        _editorHint.value = "Modo ${profile.label}"
    }

    private fun loadTimelineThumbnails(
        context: Context,
        uri: Uri,
        durationMs: Long,
        profile: VideoPerformanceProfile
    ) {
        thumbnailJob?.cancel()
        val requestId = ++thumbnailRequestId
        _isPreparingTimeline.value = true
        val frameCount = when (profile.tier) {
            VideoDeviceTier.LOW -> 6
            VideoDeviceTier.BALANCED -> 8
            VideoDeviceTier.HIGH -> 10
        }
        thumbnailJob = viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val frames = mutableListOf<TimelineThumbnail>()
            try {
                retriever.setDataSource(context, uri)
                calculateTimelineThumbnailTimes(durationMs, frameCount).forEach { timeMs ->
                    val source = retriever.getFrameAtTime(
                        timeMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: return@forEach
                    val targetWidth = 160
                    val targetHeight = ((source.height.toFloat() / source.width.coerceAtLeast(1)) * targetWidth)
                        .roundToInt()
                        .coerceIn(72, 160)
                    val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
                    if (scaled !== source) source.recycle()
                    frames += TimelineThumbnail(timeMs = timeMs, bitmap = scaled)
                }
                if (requestId == thumbnailRequestId) {
                    _timelineThumbnails.value = frames
                } else {
                    frames.forEach { frame ->
                        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                    }
                }
            } catch (cancelled: CancellationException) {
                frames.forEach { frame ->
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                }
                throw cancelled
            } catch (_: Throwable) {
                frames.forEach { frame ->
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                }
                if (requestId == thumbnailRequestId) {
                    _timelineThumbnails.value = emptyList()
                }
            } finally {
                runCatching { retriever.release() }
                if (requestId == thumbnailRequestId) {
                    _isPreparingTimeline.value = false
                }
            }
        }
    }

    private fun syncTimelineWithPlayer() {
        val player = exoPlayer ?: return
        val videoClips = previewVideoClips()
        if (videoClips.isEmpty()) return

        val currentTimelinePosition = _currentTimeMs.value
        val wasPlaying = player.isPlaying

        player.setMediaItems(
            videoClips.map { clip ->
                MediaItem.fromUri(clip.uri!!).buildUpon()
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startOffsetMs)
                            .setEndPositionMs(
                                (clip.startOffsetMs + clip.durationMs).coerceAtMost(
                                    clip.sourceDurationMs.takeIf { it > 0L }
                                        ?: (clip.startOffsetMs + clip.durationMs)
                                )
                            )
                            .build()
                    )
                    .build()
            }
        )
        player.prepare()
        seekPlayerToTimelineTime(currentTimelinePosition)
        syncSelectedClipPreview()
        if (wasPlaying) player.play()
    }

    private fun previewVideoClips(): List<EditorClip> {
        return _timeline.value.tracks
            .firstOrNull { it.type == TrackType.VIDEO }
            ?.clips
            ?.filter { it.uri != null && it.durationMs > 0L }
            ?.sortedBy { it.startMs }
            .orEmpty()
    }

    private fun seekPlayerToTimelineTime(requestedTimeMs: Long) {
        val player = exoPlayer ?: return
        val clips = previewVideoClips()
        if (clips.isEmpty()) return
        val durationMs = clips.maxOfOrNull { it.endMs } ?: 0L
        val safeTime = requestedTimeMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val index = clips.indexOfFirst { safeTime < it.endMs }
            .takeIf { it >= 0 }
            ?: clips.lastIndex
        val clip = clips[index]
        val relativePosition = (safeTime - clip.startMs).coerceIn(0L, clip.durationMs)
        player.seekTo(index, relativePosition)
        _currentTimeMs.value = clip.startMs + relativePosition
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun syncSelectedClipPreview() {
        val clip = _selectedClipId.value?.let(::findClipById)
            ?.takeIf { it.type == ClipType.VIDEO }
            ?: _timeline.value.tracks
                .firstOrNull { it.type == TrackType.VIDEO }
                ?.clips
                ?.firstOrNull()
        val player = exoPlayer ?: return
        if (clip == null) {
            player.setVideoEffects(emptyList())
            player.volume = 1f
            return
        }
        val effects = mutableListOf<Effect>()
        clip.aspectRatio.ratio?.let { ratio ->
            effects += Presentation.createForAspectRatio(
                ratio,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        }
        effects += buildColorEffects(clip)
        player.setVideoEffects(effects)
        player.volume = clip.originalAudioVolume.coerceIn(0f, 1f)
    }

    private fun findClipById(clipId: Long): EditorClip? {
        return _timeline.value.tracks.firstNotNullOfOrNull { track ->
            track.clips.firstOrNull { it.id == clipId }
        }
    }

    fun selectClip(id: Long?) {
        _selectedClipId.value = id
        val player = exoPlayer ?: return
        val maxSpeed = (_performanceProfile.value?.editorMaxSpeed ?: 3f).coerceAtLeast(1f)
        val speed = id?.let { findClipById(it)?.speed }?.coerceIn(0.5f, maxSpeed) ?: 1f
        player.setPlaybackSpeed(speed)
        syncSelectedClipPreview()
    }

    fun updateCurrentTime(timeMs: Long) {
        seekPlayerToTimelineTime(timeMs)
    }

    /** Advances the visible playhead from the decoder without forcing another seek. */
    fun updatePlaybackPosition() {
        val player = exoPlayer ?: return
        val clips = previewVideoClips()
        val clip = clips.getOrNull(player.currentMediaItemIndex) ?: return
        _currentTimeMs.value = (clip.startMs + player.currentPosition)
            .coerceIn(clip.startMs, clip.endMs)
    }

    fun duplicateClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long) {
        val current = _timeline.value
        val updated = current.duplicateClipAfter(
            clipId = clipId,
            newClipId = System.currentTimeMillis() + (0..1000).random()
        )
        if (updated != current) {
            _timeline.value = updated
            syncTimelineWithPlayer()
        }
    }

    fun splitClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, atTimeMs: Long) {
        val current = _timeline.value
        val updated = current.splitClipAt(
            clipId = clipId,
            atTimeMs = atTimeMs,
            newClipId = System.currentTimeMillis() + 1L
        )
        if (updated != current) {
            _timeline.value = updated
            syncTimelineWithPlayer()
        }
    }

    fun removeClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long) {
        _timeline.value = _timeline.value.copy(
            tracks = _timeline.value.tracks.map { track ->
                track.copy(clips = track.clips.filter { it.id != clipId })
            }
        ).reflowVideoTracks()
        if (_selectedClipId.value == clipId) {
            _selectedClipId.value = null
            exoPlayer?.setPlaybackSpeed(1f)
        }
        syncTimelineWithPlayer()
    }

    fun updateClipPosition(clipId: Long, x: Float, y: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(x = x, y = y) }
    }

    fun updateTextOverlay(clipId: Long, text: String, x: Float, y: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(text = text, x = x, y = y) }
    }

    fun updateClipVolume(clipId: Long, volume: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    fun updateClipSpeed(clipId: Long, speed: Float) {
        val maxSpeed = (_performanceProfile.value?.editorMaxSpeed ?: 3f).coerceAtLeast(1f)
        val clamped = speed.coerceIn(0.5f, maxSpeed)
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(speed = clamped) }
        if (_selectedClipId.value == clipId) exoPlayer?.setPlaybackSpeed(clamped)
    }

    fun updateClipTrim(context: Context, clipId: Long, trimStartMs: Long, trimEndMs: Long) {
        val clip = findClipById(clipId) ?: return
        val sourceDuration = clip.sourceDurationMs
            .takeIf { it > 0L }
            ?: resolveDurationMs(context, clip.uri ?: return)
        val safeRange = sanitizeTrimRange(sourceDuration, trimStartMs, trimEndMs)
        _timeline.value = _timeline.value.updateClip(clipId) {
            it.copy(
                startOffsetMs = safeRange.first,
                durationMs = safeRange.last - safeRange.first
            )
        }.reflowVideoTracks()
        _currentTimeMs.value = clip.startMs
        syncTimelineWithPlayer()
    }

    private fun canAddOverlay(): Boolean {
        val maxOverlays = _performanceProfile.value?.editorOverlayLimit ?: 8
        val currentCount = _timeline.value.tracks
            .filter { it.type == TrackType.TEXT || it.type == TrackType.STICKER }
            .sumOf { it.clips.size }
        if (currentCount >= maxOverlays) {
            _editorHint.value = "Límite de capas alcanzado ($maxOverlays) para este equipo."
            return false
        }
        return true
    }

    fun addTextOverlay(text: String, timeMs: Long) {
        if (!canAddOverlay()) return
        val cleanText = text.trim().take(140)
        if (cleanText.isBlank()) return
        val projectDurationMs = _timeline.value.durationMs().coerceAtLeast(500L)
        val overlayStartMs = timeMs.coerceIn(0L, (projectDurationMs - 500L).coerceAtLeast(0L))
        val tracks = _timeline.value.tracks.toMutableList()
        var textTrackIndex = tracks.indexOfFirst { it.type == TrackType.TEXT }
        if (textTrackIndex == -1) {
            tracks.add(EditorTrack(type = TrackType.TEXT))
            textTrackIndex = tracks.size - 1
        }
        val newClip = EditorClip(
            id = System.currentTimeMillis(),
            type = ClipType.TEXT,
            text = cleanText,
            startMs = overlayStartMs,
            durationMs = (projectDurationMs - overlayStartMs).coerceIn(500L, 5_000L),
            fontSize = 32f
        )
        tracks[textTrackIndex] = tracks[textTrackIndex].copy(clips = tracks[textTrackIndex].clips + newClip)
        _timeline.value = _timeline.value.copy(tracks = tracks)
    }

    val stickerOptions = listOf(
        android.R.drawable.star_on,
        android.R.drawable.ic_menu_edit,
        android.R.drawable.ic_dialog_info,
        android.R.drawable.ic_menu_camera,
        android.R.drawable.ic_menu_gallery
    )

    fun addStickerOverlay(@Suppress("UNUSED_PARAMETER") context: Context, resId: Int, timeMs: Long) {
        if (!canAddOverlay()) return
        val projectDurationMs = _timeline.value.durationMs().coerceAtLeast(500L)
        val overlayStartMs = timeMs.coerceIn(0L, (projectDurationMs - 500L).coerceAtLeast(0L))
        val tracks = _timeline.value.tracks.toMutableList()
        var stickerTrackIndex = tracks.indexOfFirst { it.type == TrackType.STICKER }
        if (stickerTrackIndex == -1) {
            tracks.add(EditorTrack(type = TrackType.STICKER))
            stickerTrackIndex = tracks.size - 1
        }
        val newClip = EditorClip(
            id = System.currentTimeMillis(),
            type = ClipType.STICKER,
            resId = resId,
            startMs = overlayStartMs,
            durationMs = (projectDurationMs - overlayStartMs).coerceIn(500L, 3_000L)
        )
        tracks[stickerTrackIndex] = tracks[stickerTrackIndex].copy(clips = tracks[stickerTrackIndex].clips + newClip)
        _timeline.value = _timeline.value.copy(tracks = tracks)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun exportVideo(context: Context, onSuccess: (Uri) -> Unit, onError: (String) -> Unit) {
        val timeline = _timeline.value
        if (timeline.tracks.none { it.type == TrackType.VIDEO && it.clips.any { clip -> clip.uri != null } }) {
            onError("No hay clips de video para exportar")
            return
        }
        if (timeline.durationMs() > MAX_EXPORT_DURATION_MS) {
            onError("El video supera los 45 segundos. Recórtalo antes de guardarlo.")
            return
        }
        val profile = getOrInitProfile(context)

        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            _exportProgress.value = 0f
            _exportStage.value = EditorExportStage.PREPARING
            try {
                val primary = resolveExportConfig(profile, _selectedExportQuality.value)
                val fallback = ExportConfig("safe", 720, minOf(profile.autoExportBitrate, 2_400_000), 96_000)
                val attempts = if (primary == fallback) listOf(primary) else listOf(primary, fallback)
                var successUri: Uri? = null
                var lastError: Throwable? = null

                for ((index, config) in attempts.withIndex()) {
                    try {
                        if (index > 0) {
                            _editorHint.value = "Reintentando con un formato compatible."
                            _exportStage.value = EditorExportStage.OPTIMIZING
                        }
                        successUri = withTimeout(EXPORT_TIMEOUT_MS) {
                            runExportAttempt(context, timeline, profile, config)
                        }
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }

                val exportedUri = successUri
                if (exportedUri != null) {
                    _exportProgress.value = 1f
                    _exportStage.value = EditorExportStage.COMPLETED
                    withContext(Dispatchers.Main) { onSuccess(exportedUri) }
                } else {
                    _exportProgress.value = 0f
                    _exportStage.value = EditorExportStage.FAILED
                    withContext(Dispatchers.Main) {
                        onError(userFacingExportError(lastError))
                    }
                }
            } finally {
                _isExporting.value = false
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun runExportAttempt(
        context: Context,
        timeline: EditorTimeline,
        profile: VideoPerformanceProfile,
        config: ExportConfig
    ): Uri {
        val outputFile = File(context.cacheDir, "pro_edited_${config.name}_${System.currentTimeMillis()}.mp4")
        if (outputFile.exists()) outputFile.delete()

        val sequences = mutableListOf<EditedMediaItemSequence>()
        timeline.tracks.filter { it.type == TrackType.VIDEO }.forEach { track ->
            val items = track.clips.mapNotNull { clip ->
                val uri = clip.uri ?: return@mapNotNull null
                val mediaItem = MediaItem.fromUri(uri).buildUpon()
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startOffsetMs)
                            .setEndPositionMs(clip.startOffsetMs + clip.durationMs)
                            .build()
                    )
                    .build()

                val videoEffects = buildClipEffects(context, clip, timeline, profile, config)
                val audioProcessors = mutableListOf<AudioProcessor>()
                if (abs(clip.originalAudioVolume - 1f) > 0.01f) {
                    audioProcessors += VolumeAudioProcessor(clip.originalAudioVolume)
                }
                if (abs(clip.speed - 1f) > 0.01f) {
                    val speedProvider = object : SpeedProvider {
                        override fun getSpeed(timeUs: Long): Float = clip.speed.coerceIn(0.5f, 4f)
                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                    }
                    runCatching {
                        val linked = Effects.createExperimentalSpeedChangingEffect(speedProvider)
                        audioProcessors += linked.first
                        videoEffects += linked.second
                    }.onFailure {
                        videoEffects += SpeedChangeEffect(clip.speed.coerceIn(0.5f, 4f))
                    }
                }

                EditedMediaItem.Builder(mediaItem)
                    .setFrameRate(30)
                    .setEffects(Effects(audioProcessors, videoEffects))
                    .build()
            }
            if (items.isNotEmpty()) {
                @Suppress("DEPRECATION")
                sequences += EditedMediaItemSequence(items)
            }
        }
        val musicClips = timeline.tracks
            .filter { it.type == TrackType.AUDIO }
            .flatMap { it.clips }
            .filter { it.type == ClipType.AUDIO && it.uri != null }
        musicClips.forEach { clip ->
            val sourceDuration = clip.sourceDurationMs
                .takeIf { it > 0L }
                ?: clip.durationMs.coerceAtLeast(1_000L)
            val playableDuration = minOf(sourceDuration, clip.durationMs.coerceAtLeast(1_000L))
            val musicItem = MediaItem.fromUri(clip.uri!!).buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startOffsetMs.coerceAtLeast(0L))
                        .setEndPositionMs(
                            (clip.startOffsetMs + playableDuration).coerceAtMost(sourceDuration)
                        )
                        .build()
                )
                .build()
            val audioEffects = mutableListOf<AudioProcessor>()
            if (abs(clip.musicVolume - 1f) > 0.01f) {
                audioEffects += VolumeAudioProcessor(clip.musicVolume)
            }
            val editedMusic = EditedMediaItem.Builder(musicItem)
                .setRemoveVideo(true)
                .setEffects(Effects(audioEffects, emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(editedMusic)
                .setIsLooping(clip.durationMs > playableDuration)
                .build()
        }
        if (sequences.isEmpty()) throw IllegalStateException("No hay secuencias válidas")

        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(musicClips.isNotEmpty())
            .build()
        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(config.videoBitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setiFrameIntervalSeconds(1f)
            .build()
        val audioSettings = AudioEncoderSettings.Builder()
            .setBitrate(config.audioBitrate)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setRequestedAudioEncoderSettings(audioSettings)
            .build()
        val request = TransformationRequest.Builder()
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        val done = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val transformer = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .setTransformationRequest(request)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    done.complete(Unit)
                }
                override fun onError(composition: Composition, result: ExportResult, exception: androidx.media3.transformer.ExportException) {
                    failure = exception
                    done.complete(Unit)
                }
            })
            .build()

        try {
            _exportStage.value = EditorExportStage.RENDERING
            transformer.start(composition, outputFile.absolutePath)
            val holder = ProgressHolder()
            while (!done.isCompleted) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _exportProgress.value = holder.progress / 100f
                    if (holder.progress >= 95) {
                        _exportStage.value = EditorExportStage.FINALIZING
                    }
                }
                delay(220)
            }
        } catch (error: Throwable) {
            runCatching { transformer.cancel() }
            runCatching { outputFile.delete() }
            throw error
        }
        if (failure != null) throw failure!!
        if (!outputFile.exists() || outputFile.length() <= 0L) throw IllegalStateException("Archivo inválido")
        return Uri.fromFile(outputFile)
    }

    private fun userFacingExportError(error: Throwable?): String {
        val normalized = error?.message.orEmpty().lowercase()
        return when {
            normalized.contains("timeout") || normalized.contains("timed out") ->
                "El procesamiento tardó demasiado. Cierra otras aplicaciones e inténtalo otra vez."
            normalized.contains("encoder") || normalized.contains("codec") ->
                "Este teléfono no pudo procesar ese formato. Prueba la calidad Compatible."
            normalized.contains("space") || normalized.contains("storage") ->
                "No hay espacio suficiente para guardar el video editado."
            else -> "No se pudo guardar el video. Inténtalo nuevamente con la calidad Compatible."
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildClipEffects(
        context: Context,
        clip: EditorClip,
        timeline: EditorTimeline,
        profile: VideoPerformanceProfile,
        config: ExportConfig
    ): MutableList<Effect> {
        val effects = mutableListOf<Effect>()
        clip.aspectRatio.ratio?.let { ratio ->
            effects += Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        effects += Presentation.createForHeight(config.videoHeight)

        effects += buildColorEffects(clip)

        timeline.tracks
            .filter { it.type == TrackType.TEXT || it.type == TrackType.STICKER }
            .flatMap { it.clips }
            .filter { overlay -> overlay.startMs < clip.endMs && overlay.endMs > clip.startMs }
            .take(profile.editorOverlayLimit)
            .forEach { overlay ->
                val bitmap = if (overlay.type == ClipType.TEXT) {
                    createBitmapFromText(overlay.text ?: "", overlay.fontSize.coerceIn(16f, 56f), overlay.color)
                } else {
                    createStickerBitmap(context, overlay.resId ?: android.R.drawable.star_on)
                }
                val xPos = ((overlay.x / 500f) - 1f).coerceIn(-1f, 1f)
                val yPos = (1f - (overlay.y / 500f)).coerceIn(-1f, 1f)
                val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(xPos, yPos).build()
                val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
                val visibleStartMs = (maxOf(overlay.startMs, clip.startMs) - clip.startMs)
                    .coerceAtLeast(0L)
                val visibleEndMs = (minOf(overlay.endMs, clip.endMs) - clip.startMs)
                    .coerceAtMost(clip.durationMs)
                if (visibleEndMs > visibleStartMs) {
                    effects += TimestampWrapper(
                        OverlayEffect(
                            listOf<androidx.media3.effect.TextureOverlay>(bitmapOverlay)
                        ),
                        visibleStartMs * 1_000L,
                        visibleEndMs * 1_000L
                    )
                }
            }
        return effects
    }

    private companion object {
        const val MAX_EXPORT_DURATION_MS = 45_000L
        const val EXPORT_TIMEOUT_MS = 4 * 60 * 1_000L
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildColorEffects(clip: EditorClip): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (abs(clip.brightness) > 0.01f) {
            effects += Brightness(clip.brightness.coerceIn(-1f, 1f))
        }
        val contrast = (clip.contrast - 1f).coerceIn(-0.9f, 0.9f)
        if (abs(contrast) > 0.01f) effects += Contrast(contrast)
        val saturation = ((clip.saturation - 1f) * 100f).coerceIn(-100f, 100f)
        if (abs(saturation) > 0.5f) {
            effects += HslAdjustment.Builder().adjustSaturation(saturation).build()
        }

        when (clip.filterType) {
            FilterType.NONE -> Unit
            FilterType.BLACK_WHITE -> effects += RgbFilter.createGrayscaleFilter()
            FilterType.SEPIA -> effects += HslAdjustment.Builder()
                .adjustHue(18f)
                .adjustSaturation(-20f)
                .build()
            FilterType.VINTAGE -> effects += HslAdjustment.Builder()
                .adjustSaturation(-12f)
                .adjustLightness(-6f)
                .build()
            FilterType.HIGH_CONTRAST -> effects += Contrast(0.42f)
            FilterType.HIGH_SATURATION -> effects += HslAdjustment.Builder()
                .adjustSaturation(45f)
                .build()
        }
        return effects
    }

    private fun resolveExportConfig(profile: VideoPerformanceProfile, quality: ExportQuality): ExportConfig {
        return when (quality) {
            ExportQuality.AUTO -> ExportConfig("auto", profile.autoExportHeight, profile.autoExportBitrate, 128_000)
            ExportQuality.PERFORMANCE -> ExportConfig("performance", 720, 2_600_000, 96_000)
            ExportQuality.BALANCED -> ExportConfig("balanced", 1080, 4_600_000, 128_000)
            ExportQuality.HIGH -> {
                val h = if (profile.tier == VideoDeviceTier.LOW) 720 else 1080
                val b = if (profile.tier == VideoDeviceTier.LOW) 3_200_000 else 8_000_000
                ExportConfig("high", h, b, 160_000)
            }
        }
    }

    private fun createStickerBitmap(context: Context, resId: Int): android.graphics.Bitmap {
        return try {
            val drawable = AppCompatResources.getDrawable(context, resId)
            if (drawable != null) {
                val b = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val c = android.graphics.Canvas(b)
                drawable.setBounds(0, 0, c.width, c.height)
                drawable.draw(c)
                b
            } else createPlaceholderSticker()
        } catch (_: Exception) {
            createPlaceholderSticker()
        }
    }

    private fun createBitmapFromText(text: String, fontSize: Float, color: Int): android.graphics.Bitmap {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = fontSize
        paint.color = color
        paint.textAlign = android.graphics.Paint.Align.LEFT
        val baseline = -paint.ascent()
        val width = (paint.measureText(text) + 0.5f).toInt().coerceAtLeast(1)
        val height = (baseline + paint.descent() + 0.5f).toInt().coerceAtLeast(1)
        val image = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(image).drawText(text, 0f, baseline, paint)
        return image
    }

    private fun createPlaceholderSticker(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW }
        canvas.drawCircle(50f, 50f, 40f, paint)
        return bitmap
    }

    fun updateClipFilter(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, brightness: Float, contrast: Float, saturation: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) {
            it.copy(
                brightness = brightness.coerceIn(-1f, 1f),
                contrast = contrast.coerceIn(0f, 2f),
                saturation = saturation.coerceIn(0f, 2f)
            )
        }
        syncSelectedClipPreview()
    }

    fun applyFilter(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, filterType: FilterType) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(filterType = filterType) }
        syncSelectedClipPreview()
    }

    fun setAspectRatio(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, aspectRatio: AspectRatio) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(aspectRatio = aspectRatio) }
        syncSelectedClipPreview()
    }

    fun addMusicTrack(context: Context, musicUri: Uri) {
        val tracks = _timeline.value.tracks.toMutableList()
        var audioTrackIndex = tracks.indexOfFirst { it.type == TrackType.AUDIO }
        if (audioTrackIndex == -1) {
            tracks.add(EditorTrack(type = TrackType.AUDIO))
            audioTrackIndex = tracks.size - 1
        }
        val duration = tracks.find { it.type == TrackType.VIDEO }
            ?.clips
            ?.maxOfOrNull { it.endMs }
            ?: 30_000L
        val sourceDuration = resolveDurationMs(context, musicUri)
        val musicClip = EditorClip(
            id = System.currentTimeMillis(),
            type = ClipType.AUDIO,
            uri = musicUri,
            startMs = 0,
            durationMs = duration,
            sourceDurationMs = sourceDuration
        )
        tracks[audioTrackIndex] = tracks[audioTrackIndex].copy(clips = listOf(musicClip))
        _timeline.value = _timeline.value.copy(tracks = tracks)
    }

    fun setOriginalAudioVolume(clipId: Long, volume: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(originalAudioVolume = volume.coerceIn(0f, 1f)) }
        if (_selectedClipId.value == clipId) syncSelectedClipPreview()
    }

    fun setMusicVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _timeline.value = _timeline.value.copy(
            tracks = _timeline.value.tracks.map { track ->
                if (track.type == TrackType.AUDIO) {
                    track.copy(clips = track.clips.map { it.copy(musicVolume = clamped) })
                } else track
            }
        )
    }
}

private data class ExportConfig(
    val name: String,
    val videoHeight: Int,
    val videoBitrate: Int,
    val audioBitrate: Int
)

data class EditorTimeline(
    val tracks: List<EditorTrack> = emptyList()
) {
    fun updateClip(clipId: Long, block: (EditorClip) -> EditorClip): EditorTimeline {
        return copy(
            tracks = tracks.map { track ->
                track.copy(clips = track.clips.map { clip -> if (clip.id == clipId) block(clip) else clip })
            }
        )
    }
}

data class EditorTrack(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val type: TrackType,
    val clips: List<EditorClip> = emptyList()
)

data class EditorClip(
    val id: Long,
    val type: ClipType = ClipType.VIDEO,
    val uri: Uri? = null,
    val text: String? = null,
    val resId: Int? = null,
    val startMs: Long = 0,
    val durationMs: Long = 0,
    val sourceDurationMs: Long = 0,
    val startOffsetMs: Long = 0,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val x: Float = 250f,
    val y: Float = 250f,
    val color: Int = android.graphics.Color.WHITE,
    val fontSize: Float = 24f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val filterType: FilterType = FilterType.NONE,
    val aspectRatio: AspectRatio = AspectRatio.ORIGINAL,
    val originalAudioVolume: Float = 1.0f,
    val musicVolume: Float = 1.0f
) {
    val endMs: Long get() = startMs + durationMs
}

enum class TrackType { VIDEO, AUDIO, TEXT, STICKER }
enum class ClipType { VIDEO, AUDIO, TEXT, STICKER }

enum class FilterType {
    NONE,
    BLACK_WHITE,
    SEPIA,
    VINTAGE,
    HIGH_CONTRAST,
    HIGH_SATURATION
}

enum class AspectRatio(val displayName: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f),
    RATIO_9_16("9:16", 9f / 16f)
}

enum class ExportQuality(val label: String) {
    AUTO("Auto"),
    PERFORMANCE("Rendimiento"),
    BALANCED("Equilibrado"),
    HIGH("Alta")
}

enum class EditorExportStage(val label: String) {
    IDLE("Listo para exportar"),
    PREPARING("Preparando proyecto"),
    OPTIMIZING("Optimizando para tu equipo"),
    RENDERING("Procesando video"),
    FINALIZING("Finalizando archivo"),
    COMPLETED("Video listo"),
    FAILED("No se pudo exportar")
}

data class TimelineThumbnail(
    val timeMs: Long,
    val bitmap: Bitmap
)

internal fun calculateTimelineThumbnailTimes(durationMs: Long, requestedCount: Int): List<Long> {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val count = requestedCount.coerceIn(1, 12)
    val segment = safeDuration.toDouble() / count
    return List(count) { index ->
        ((index + 0.5) * segment)
            .toLong()
            .coerceIn(0L, safeDuration - 1L)
    }
}

internal fun EditorTimeline.durationMs(): Long {
    val videoDuration = tracks.asSequence()
        .filter { it.type == TrackType.VIDEO }
        .flatMap { it.clips.asSequence() }
        .maxOfOrNull { it.endMs }
        ?.coerceAtLeast(0L)
    if (videoDuration != null && videoDuration > 0L) return videoDuration
    return tracks.asSequence()
        .flatMap { it.clips.asSequence() }
        .maxOfOrNull { it.endMs }
        ?.coerceAtLeast(0L)
        ?: 0L
}

internal fun EditorTimeline.reflowVideoTracks(): EditorTimeline {
    return copy(
        tracks = tracks.map { track ->
            if (track.type != TrackType.VIDEO) return@map track
            var cursorMs = 0L
            track.copy(
                clips = track.clips.map { clip ->
                    clip.copy(startMs = cursorMs).also { cursorMs += it.durationMs }
                }
            )
        }
    )
}

internal fun EditorTimeline.duplicateClipAfter(clipId: Long, newClipId: Long): EditorTimeline {
    var changed = false
    val updated = copy(
        tracks = tracks.map { track ->
            val index = track.clips.indexOfFirst { it.id == clipId }
            if (index < 0) return@map track
            val original = track.clips[index]
            val clips = track.clips.toMutableList().apply {
                add(index + 1, original.copy(id = newClipId, startMs = original.endMs))
            }
            changed = true
            track.copy(clips = clips)
        }
    )
    return if (changed) updated.reflowVideoTracks() else this
}

internal fun EditorTimeline.splitClipAt(
    clipId: Long,
    atTimeMs: Long,
    newClipId: Long
): EditorTimeline {
    var changed = false
    val updated = copy(
        tracks = tracks.map { track ->
            val index = track.clips.indexOfFirst { it.id == clipId }
            if (index < 0) return@map track
            val clip = track.clips[index]
            val relativeTimeMs = atTimeMs - clip.startMs
            if (relativeTimeMs <= 0L || relativeTimeMs >= clip.durationMs) return@map track
            val first = clip.copy(durationMs = relativeTimeMs)
            val second = clip.copy(
                id = newClipId,
                startMs = atTimeMs,
                startOffsetMs = clip.startOffsetMs + relativeTimeMs,
                durationMs = clip.durationMs - relativeTimeMs
            )
            val clips = track.clips.toMutableList().apply {
                removeAt(index)
                add(index, first)
                add(index + 1, second)
            }
            changed = true
            track.copy(clips = clips)
        }
    )
    return if (changed) updated.reflowVideoTracks() else this
}

internal fun sanitizeTrimRange(
    sourceDurationMs: Long,
    requestedStartMs: Long,
    requestedEndMs: Long
): LongRange {
    val duration = sourceDurationMs.coerceAtLeast(1_000L)
    val minimumClipDuration = minOf(1_000L, duration)
    val start = requestedStartMs.coerceIn(0L, duration - minimumClipDuration)
    val end = requestedEndMs.coerceIn(start + minimumClipDuration, duration)
    return start..end
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class VolumeAudioProcessor(gain: Float) : BaseAudioProcessor() {
    private val gain = gain.coerceIn(0f, 1f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
            val sample = inputBuffer.short.toInt()
            val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(scaled.toShort())
        }
        output.flip()
    }
}
