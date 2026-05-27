package conectate.com.presentation.videos

import android.content.Context
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
import com.google.common.collect.ImmutableList
import conectate.com.util.VideoDeviceTier
import conectate.com.util.VideoPerformanceProfile
import conectate.com.util.VideoPerformanceProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class VideoEditorViewModel @Inject constructor() : ViewModel() {

    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress = _exportProgress.asStateFlow()

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

    private var exoPlayer: ExoPlayer? = null

    fun getPlayer(context: Context): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        return ExoPlayer.Builder(context).build().apply {
            getOrInitProfile(context)
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            exoPlayer = this
        }
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        exoPlayer = null
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
            durationMs = durationMs
        )
        _timeline.value = EditorTimeline(
            tracks = listOf(mainTrack.copy(clips = listOf(initialClip)))
        )

        val player = getPlayer(context)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.setPlaybackSpeed(1f)
        _editorHint.value = "Modo ${profile.label}"
    }

    private fun syncTimelineWithPlayer() {
        val player = exoPlayer ?: return
        val videoClips = _timeline.value.tracks
            .firstOrNull { it.type == TrackType.VIDEO }
            ?.clips
            ?.filter { it.uri != null }
            .orEmpty()
        if (videoClips.isEmpty()) return

        val currentPosition = player.currentPosition
        val wasPlaying = player.isPlaying

        player.setMediaItems(
            videoClips.mapNotNull { clip ->
                val uri = clip.uri ?: return@mapNotNull null
                MediaItem.fromUri(uri).buildUpon()
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startOffsetMs)
                            .setEndPositionMs(clip.startOffsetMs + clip.durationMs)
                            .build()
                    )
                    .build()
            }
        )
        player.prepare()
        player.seekTo(currentPosition)
        if (wasPlaying) player.play()
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
    }

    fun updateCurrentTime(timeMs: Long) {
        _currentTimeMs.value = timeMs
        exoPlayer?.seekTo(timeMs)
    }

    fun duplicateClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long) {
        val current = _timeline.value
        var clipToDuplicate: EditorClip? = null
        var trackType: TrackType? = null
        current.tracks.forEach { track ->
            track.clips.find { it.id == clipId }?.let {
                clipToDuplicate = it
                trackType = track.type
            }
        }
        val original = clipToDuplicate
        if (original != null && trackType != null) {
            val newClip = original.copy(
                id = System.currentTimeMillis() + (0..1000).random(),
                startMs = original.endMs
            )
            _timeline.value = _timeline.value.copy(
                tracks = _timeline.value.tracks.map { track ->
                    if (track.type == trackType) {
                        val list = track.clips.toMutableList()
                        val idx = list.indexOfFirst { it.id == clipId }
                        if (idx != -1) list.add(idx + 1, newClip)
                        track.copy(clips = list)
                    } else track
                }
            )
            syncTimelineWithPlayer()
        }
    }

    fun splitClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, atTimeMs: Long) {
        _timeline.value = _timeline.value.copy(
            tracks = _timeline.value.tracks.map { track ->
                val clipIndex = track.clips.indexOfFirst { it.id == clipId }
                if (clipIndex == -1) return@map track
                val clip = track.clips[clipIndex]
                val relativeTime = atTimeMs - clip.startMs
                if (relativeTime <= 0 || relativeTime >= clip.durationMs) return@map track
                val first = clip.copy(durationMs = relativeTime)
                val second = clip.copy(
                    id = System.currentTimeMillis() + 1,
                    startMs = atTimeMs,
                    startOffsetMs = clip.startOffsetMs + relativeTime,
                    durationMs = clip.durationMs - relativeTime
                )
                val list = track.clips.toMutableList()
                list.removeAt(clipIndex)
                list.add(clipIndex, first)
                list.add(clipIndex + 1, second)
                track.copy(clips = list)
            }
        )
        syncTimelineWithPlayer()
    }

    fun removeClip(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long) {
        _timeline.value = _timeline.value.copy(
            tracks = _timeline.value.tracks.map { track ->
                track.copy(clips = track.clips.filter { it.id != clipId })
            }
        )
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
            startMs = timeMs,
            durationMs = 5000L,
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
            startMs = timeMs,
            durationMs = 3000L
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
        val profile = getOrInitProfile(context)

        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            _exportProgress.value = 0f

            val primary = resolveExportConfig(profile, _selectedExportQuality.value)
            val fallback = ExportConfig("safe", 720, minOf(profile.autoExportBitrate, 2_400_000), 96_000)
            val attempts = if (primary == fallback) listOf(primary) else listOf(primary, fallback)
            var successUri: Uri? = null
            var lastError: Throwable? = null

            for ((index, config) in attempts.withIndex()) {
                try {
                    if (index > 0) _editorHint.value = "Reintentando con perfil compatible."
                    successUri = runExportAttempt(context, timeline, profile, config)
                    break
                } catch (e: Throwable) {
                    lastError = e
                }
            }

            _isExporting.value = false
            val exportedUri = successUri
            if (exportedUri != null) {
                _exportProgress.value = 1f
                onSuccess(exportedUri)
            } else {
                _exportProgress.value = 0f
                onError(lastError?.message ?: "No se pudo exportar el video")
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
        if (sequences.isEmpty()) throw IllegalStateException("No hay secuencias válidas")

        val composition = Composition.Builder(sequences).build()
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

        transformer.start(composition, outputFile.absolutePath)
        val holder = ProgressHolder()
        while (!done.isCompleted) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                _exportProgress.value = holder.progress / 100f
            }
            delay(220)
        }
        failure?.let { throw it }
        if (!outputFile.exists() || outputFile.length() <= 0L) throw IllegalStateException("Archivo inválido")
        return Uri.fromFile(outputFile)
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

        if (abs(clip.brightness) > 0.01f) effects += Brightness(clip.brightness.coerceIn(-1f, 1f))
        val c = (clip.contrast - 1f).coerceIn(-0.9f, 0.9f)
        if (abs(c) > 0.01f) effects += Contrast(c)
        val sat = ((clip.saturation - 1f) * 100f).coerceIn(-100f, 100f)
        if (abs(sat) > 0.5f) effects += HslAdjustment.Builder().adjustSaturation(sat).build()

        when (clip.filterType) {
            FilterType.NONE -> Unit
            FilterType.BLACK_WHITE -> effects += RgbFilter.createGrayscaleFilter()
            FilterType.SEPIA -> effects += HslAdjustment.Builder().adjustHue(18f).adjustSaturation(-20f).build()
            FilterType.VINTAGE -> effects += HslAdjustment.Builder().adjustSaturation(-12f).adjustLightness(-6f).build()
            FilterType.HIGH_CONTRAST -> effects += Contrast(0.42f)
            FilterType.HIGH_SATURATION -> effects += HslAdjustment.Builder().adjustSaturation(45f).build()
        }

        val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
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
                overlays += BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
            }
        if (overlays.isNotEmpty()) effects += OverlayEffect(ImmutableList.copyOf(overlays))
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
        syncTimelineWithPlayer()
    }

    fun applyFilter(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, filterType: FilterType) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(filterType = filterType) }
        syncTimelineWithPlayer()
    }

    fun setAspectRatio(@Suppress("UNUSED_PARAMETER") context: Context, clipId: Long, aspectRatio: AspectRatio) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(aspectRatio = aspectRatio) }
        syncTimelineWithPlayer()
    }

    fun addMusicTrack(@Suppress("UNUSED_PARAMETER") context: Context, musicUri: Uri) {
        val tracks = _timeline.value.tracks.toMutableList()
        var audioTrackIndex = tracks.indexOfFirst { it.type == TrackType.AUDIO }
        if (audioTrackIndex == -1) {
            tracks.add(EditorTrack(type = TrackType.AUDIO))
            audioTrackIndex = tracks.size - 1
        }
        val duration = tracks.find { it.type == TrackType.VIDEO }?.clips?.firstOrNull()?.durationMs ?: 30_000L
        val musicClip = EditorClip(
            id = System.currentTimeMillis(),
            type = ClipType.AUDIO,
            uri = musicUri,
            startMs = 0,
            durationMs = duration
        )
        tracks[audioTrackIndex] = tracks[audioTrackIndex].copy(clips = listOf(musicClip))
        _timeline.value = _timeline.value.copy(tracks = tracks)
    }

    fun setOriginalAudioVolume(clipId: Long, volume: Float) {
        _timeline.value = _timeline.value.updateClip(clipId) { it.copy(originalAudioVolume = volume.coerceIn(0f, 1f)) }
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
