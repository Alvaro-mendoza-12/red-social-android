package conectate.com.data.music

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class MomentMusicMixRequest(
    val videoUri: Uri,
    val musicUri: Uri,
    val musicStartMs: Long,
    val musicEndMs: Long,
    val musicVolume: Float,
    val originalAudioVolume: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val duckOriginalAudio: Boolean,
)

data class MomentMusicMixPlan(
    val musicStartMs: Long,
    val musicEndMs: Long,
    val musicVolume: Float,
    val originalAudioVolume: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
) {
    val musicDurationMs: Long get() = musicEndMs - musicStartMs
}

internal fun MomentMusicMixRequest.normalizedPlan(): MomentMusicMixPlan {
    return normalizedMomentMusicMixPlan(
        musicStartMs = musicStartMs,
        musicEndMs = musicEndMs,
        musicVolume = musicVolume,
        originalAudioVolume = originalAudioVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        duckOriginalAudio = duckOriginalAudio,
    )
}

internal fun normalizedMomentMusicMixPlan(
    musicStartMs: Long,
    musicEndMs: Long,
    musicVolume: Float,
    originalAudioVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    duckOriginalAudio: Boolean,
): MomentMusicMixPlan {
    val start = musicStartMs.coerceAtLeast(0L)
    val end = musicEndMs.coerceAtLeast(start + 1_000L)
    val duration = end - start
    return MomentMusicMixPlan(
        musicStartMs = start,
        musicEndMs = end,
        musicVolume = musicVolume.coerceIn(0f, 1f),
        originalAudioVolume = (
            originalAudioVolume.coerceIn(0f, 1f) *
                if (duckOriginalAudio) 0.35f else 1f
            ).coerceIn(0f, 1f),
        fadeInMs = fadeInMs.coerceIn(0L, minOf(5_000L, duration / 2L)),
        fadeOutMs = fadeOutMs.coerceIn(0L, minOf(5_000L, duration / 2L)),
    )
}

/**
 * Exports the exact previewed video + licensed soundtrack as one MP4.
 *
 * The original and soundtrack remain separate in the editor. Only the publication artifact is
 * flattened. Attribution and authoritative license metadata are still persisted by the backend.
 */
@OptIn(UnstableApi::class)
object MomentMusicCompositionExporter {
    suspend fun export(context: Context, request: MomentMusicMixRequest): Result<File> {
        val appContext = context.applicationContext
        val plan = request.normalizedPlan()
        val output = File(appContext.cacheDir, "moment_mix_${System.currentTimeMillis()}.mp4")
        if (output.exists()) output.delete()

        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val videoAudioProcessors = gainProcessors(
                    volume = plan.originalAudioVolume,
                    durationMs = Long.MAX_VALUE,
                    fadeInMs = 0L,
                    fadeOutMs = 0L,
                )
                val musicAudioProcessors = gainProcessors(
                    volume = plan.musicVolume,
                    durationMs = plan.musicDurationMs,
                    fadeInMs = plan.fadeInMs,
                    fadeOutMs = plan.fadeOutMs,
                )
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(request.videoUri))
                    .setEffects(Effects(videoAudioProcessors, emptyList()))
                    .build()
                val musicItem = EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(request.musicUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(plan.musicStartMs)
                                .setEndPositionMs(plan.musicEndMs)
                                .build(),
                        )
                        .build(),
                )
                    .setRemoveVideo(true)
                    .setEffects(Effects(musicAudioProcessors, emptyList()))
                    .build()
                val composition = Composition.Builder(
                    EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoItem)),
                    EditedMediaItemSequence.withAudioFrom(listOf(musicItem))
                        .buildUpon()
                        .setIsLooping(true)
                        .build(),
                ).build()
                val transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (!continuation.isActive) return
                            if (output.exists() && output.length() > 0L) {
                                continuation.resume(Result.success(output))
                            } else {
                                continuation.resume(Result.failure(IllegalStateException("MOMENT_MUSIC_EXPORT_EMPTY")))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: androidx.media3.transformer.ExportException,
                        ) {
                            output.delete()
                            if (continuation.isActive) continuation.resume(Result.failure(exception))
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    output.delete()
                }
                runCatching { transformer.start(composition, output.absolutePath) }
                    .onFailure { error ->
                        output.delete()
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
            }
        }
    }

    private fun gainProcessors(
        volume: Float,
        durationMs: Long,
        fadeInMs: Long,
        fadeOutMs: Long,
    ): List<AudioProcessor> {
        if (volume == 1f && fadeInMs == 0L && fadeOutMs == 0L) return emptyList()
        return listOf(MomentVolumeFadeAudioProcessor(volume, durationMs, fadeInMs, fadeOutMs))
    }
}

@OptIn(UnstableApi::class)
private class MomentVolumeFadeAudioProcessor(
    private val baseVolume: Float,
    private val durationMs: Long,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
) : BaseAudioProcessor() {
    private var processedFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val completeBytes = inputBuffer.remaining() - (inputBuffer.remaining() % bytesPerFrame)
        val output = replaceOutputBuffer(completeBytes)
        val channelCount = inputAudioFormat.channelCount
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val positionMs = processedFrames * 1_000L / inputAudioFormat.sampleRate.coerceAtLeast(1)
            val fadeIn = if (fadeInMs > 0L) (positionMs.toFloat() / fadeInMs).coerceIn(0f, 1f) else 1f
            val remainingMs = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else (durationMs - positionMs).coerceAtLeast(0L)
            val fadeOut = if (fadeOutMs > 0L && remainingMs != Long.MAX_VALUE) {
                (remainingMs.toFloat() / fadeOutMs).coerceIn(0f, 1f)
            } else 1f
            val gain = (baseVolume * minOf(fadeIn, fadeOut)).coerceIn(0f, 1f)
            repeat(channelCount) {
                val sample = inputBuffer.short.toInt()
                output.putShort((sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            processedFrames += 1L
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        processedFrames = 0L
    }
}
