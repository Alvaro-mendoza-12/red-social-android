package conectate.com.data.webrtc

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class PreparedLiveMusic(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
)

/**
 * Decodes a user-selected audio fragment and mixes it into WebRTC's captured
 * PCM buffer. The file never replaces the microphone track: the mixer can
 * independently mute the microphone, keep music playing, change volume and
 * loop without renegotiating the LIVE session.
 */
class LiveMusicAudioMixer(private val context: Context) : JavaAudioDeviceModule.AudioBufferCallback {
    private data class Clip(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
    ) {
        val frameCount: Int get() = samples.size / channels.coerceAtLeast(1)
    }

    private val lock = Any()
    @Volatile private var clip: Clip? = null
    @Volatile private var enabled = false
    @Volatile private var microphoneEnabled = true
    @Volatile private var volume = DEFAULT_VOLUME
    private var sourceFramePosition = 0.0

    val hasPreparedMusic: Boolean get() = clip != null
    val isEnabled: Boolean get() = enabled && clip != null

    suspend fun prepare(
        uri: Uri,
        startMs: Long = 0L,
        endMs: Long? = null,
    ): Result<PreparedLiveMusic> = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decode(uri, startMs, endMs)
            synchronized(lock) {
                clip = decoded
                sourceFramePosition = 0.0
                enabled = true
            }
            PreparedLiveMusic(
                durationMs = decoded.frameCount * 1_000L / decoded.sampleRate.coerceAtLeast(1),
                sourceSampleRate = decoded.sampleRate,
                sourceChannels = decoded.channels,
            )
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value && clip != null
    }

    fun setMicrophoneEnabled(value: Boolean) {
        microphoneEnabled = value
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    fun clear() {
        synchronized(lock) {
            enabled = false
            clip = null
            sourceFramePosition = 0.0
        }
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT &&
            audioFormat != AudioFormat.ENCODING_DEFAULT
        ) return captureTimeNs

        val safeChannels = channelCount.coerceAtLeast(1)
        val safeBytes = bytesRead.coerceAtMost(buffer.capacity()).coerceAtLeast(0)
        val outputFrames = safeBytes / (Short.SIZE_BYTES * safeChannels)
        if (outputFrames <= 0) return captureTimeNs

        val pcm = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        synchronized(lock) {
            val activeClip = clip
            if (!microphoneEnabled) {
                for (index in 0 until outputFrames * safeChannels) pcm.put(index, 0)
            }
            if (!enabled || activeClip == null || activeClip.frameCount <= 0 || volume <= 0f) {
                return captureTimeNs
            }

            val step = activeClip.sampleRate.toDouble() / sampleRate.coerceAtLeast(1).toDouble()
            for (outputFrame in 0 until outputFrames) {
                val frame0 = sourceFramePosition.toInt().mod(activeClip.frameCount)
                val frame1 = (frame0 + 1).mod(activeClip.frameCount)
                val fraction = sourceFramePosition - sourceFramePosition.toInt()
                val sample0 = monoSample(activeClip, frame0)
                val sample1 = monoSample(activeClip, frame1)
                val musicSample = ((sample0 + (sample1 - sample0) * fraction) * volume).toInt()

                for (channel in 0 until safeChannels) {
                    val index = outputFrame * safeChannels + channel
                    val mixed = pcm.get(index).toInt() + musicSample
                    pcm.put(index, mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
                sourceFramePosition += step
                while (sourceFramePosition >= activeClip.frameCount) {
                    sourceFramePosition -= activeClip.frameCount
                }
            }
        }
        return captureTimeNs
    }

    private fun monoSample(clip: Clip, frame: Int): Double {
        val base = frame * clip.channels
        var sum = 0L
        for (channel in 0 until clip.channels) sum += clip.samples[base + channel].toLong()
        return sum.toDouble() / clip.channels.coerceAtLeast(1)
    }

    private fun decode(uri: Uri, startMs: Long, endMs: Long?): Clip {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                extractor.setDataSource(uri.toString(), emptyMap<String, String>())
            } else {
                extractor.setDataSource(context, uri, null)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("El archivo seleccionado no contiene una pista de audio.")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("No se pudo identificar el formato de audio.")
            val declaredDurationUs = inputFormat.getLongOrNull(MediaFormat.KEY_DURATION)
            if (declaredDurationUs != null && declaredDurationUs <= 0L) {
                error("El audio seleccionado está vacío.")
            }
            extractor.selectTrack(trackIndex)
            val clipStartUs = startMs.coerceAtLeast(0L) * 1_000L
            val requestedEndUs = endMs?.coerceAtLeast(startMs + 1L)?.times(1_000L)
            val clipEndUs = minOf(
                requestedEndUs ?: (clipStartUs + MAX_FRAGMENT_MS * 1_000L),
                clipStartUs + MAX_FRAGMENT_MS * 1_000L,
                declaredDurationUs ?: Long.MAX_VALUE,
            )
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (extractor.sampleTime in 0 until clipStartUs) extractor.advance()
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val accumulator = ShortAccumulator()
            val info = MediaCodec.BufferInfo()
            var outputSampleRate = inputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 48_000
            var outputChannels = inputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var inputEnded = false
            var outputEnded = false
            val maxPresentationUs = clipEndUs
            val decodeDeadlineMs = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS

            while (!outputEnded) {
                if (SystemClock.elapsedRealtime() >= decodeDeadlineMs) {
                    error("El audio tardó demasiado en prepararse. Prueba con otro archivo.")
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("No se pudo preparar el decodificador de audio.")
                        val sampleTime = extractor.sampleTime
                        val sampleSize = if (sampleTime < 0L || sampleTime >= maxPresentationUs) {
                            -1
                        } else {
                            extractor.readSampleData(inputBuffer, 0)
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTime.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                            ?: outputSampleRate
                        outputChannels = outputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                            ?: outputChannels
                        val encoding = outputFormat.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT &&
                            encoding != AudioFormat.ENCODING_DEFAULT
                        ) error("Este formato de audio no se puede mezclar en el LIVE.")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            accumulator.append(outputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer())
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val samples = accumulator.toArray()
            if (samples.isEmpty()) error("No se pudo decodificar el audio seleccionado.")
            if (!hasAudiblePcmSignal(samples)) {
                error("La pista seleccionada no contiene sonido audible. Elige otra canción.")
            }
            return Clip(samples, outputSampleRate.coerceAtLeast(8_000), outputChannels.coerceIn(1, 8))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class ShortAccumulator {
        private var values = ShortArray(INITIAL_SAMPLE_CAPACITY)
        private var size = 0

        fun append(source: java.nio.ShortBuffer) {
            val count = source.remaining()
            ensureCapacity(size + count)
            source.get(values, size, count)
            size += count
        }

        fun toArray(): ShortArray = values.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var next = values.size
            while (next < required) next = ceil(next * 1.5).toInt().coerceAtLeast(required)
            values = values.copyOf(next)
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    companion object {
        const val MAX_FRAGMENT_MS = 60_000L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val DECODE_TIMEOUT_MS = 30_000L
        private const val INITIAL_SAMPLE_CAPACITY = 48_000
        private const val DEFAULT_VOLUME = 0.35f
    }
}

internal fun hasAudiblePcmSignal(samples: ShortArray): Boolean {
    if (samples.isEmpty()) return false
    var peak = 0
    var absoluteSum = 0L
    var inspected = 0
    val step = (samples.size / 48_000).coerceAtLeast(1)
    var index = 0
    while (index < samples.size) {
        val absolute = kotlin.math.abs(samples[index].toInt())
        if (absolute > peak) peak = absolute
        absoluteSum += absolute
        inspected += 1
        index += step
    }
    val average = if (inspected == 0) 0L else absoluteSum / inspected
    return peak >= 64 && average >= 2
}
