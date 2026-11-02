package conectate.com.util

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Encodes a closed PCM file. Success includes the muxer's final metadata write. */
internal class AacVoiceEncoder(private val outputDir: File) {
    fun encode(input: File, checkActive: () -> Unit): File? {
        if (!input.isFile || input.length() == 0L) return null
        require(input.length() % 2L == 0L) { "Incomplete PCM sample" }
        val output = File.createTempFile("voice_message_", ".m4a", outputDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var success = false
        try {
            checkActive()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
                ?: throw IOException("AAC encoder unavailable")
            val encoder = MediaCodec.createByCodecName(name).also { codec = it }
            val container = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .also { muxer = it }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codecStarted = true
            var inputDone = false
            var outputDone = false
            var track = -1
            var totalBytes = 0L
            var sampleCount = 0L
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(4_096)
            val deadline = SystemClock.elapsedRealtime() + 90_000L
            FileInputStream(input).use { source ->
                while (!outputDone) {
                    checkActive()
                    check(SystemClock.elapsedRealtime() < deadline) { "AAC encoder timed out" }
                    if (!inputDone) {
                        val index = encoder.dequeueInputBuffer(2_000L)
                        if (index >= 0) {
                            val buffer = encoder.getInputBuffer(index) ?: throw IOException("Missing AAC input buffer")
                            buffer.clear()
                            val remaining = input.length() - totalBytes
                            val size = minOf(bytes.size.toLong(), buffer.remaining().toLong(), remaining).toInt() / 2 * 2
                            val timestamp = totalBytes * 1_000_000L / (SAMPLE_RATE * 2L)
                            if (remaining == 0L) {
                                encoder.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                check(size > 0) { "AAC input buffer cannot hold one PCM sample" }
                                var count = 0
                                while (count < size) {
                                    checkActive()
                                    val read = source.read(bytes, count, size - count)
                                    if (read <= 0) throw IOException("PCM input ended unexpectedly")
                                    count += read
                                }
                                buffer.put(bytes, 0, count)
                                encoder.queueInputBuffer(index, 0, count, timestamp, 0)
                                totalBytes += count
                            }
                        }
                    }
                    val index = encoder.dequeueOutputBuffer(info, 2_000L)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed twice" }
                            track = container.addTrack(encoder.outputFormat)
                            container.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            try {
                                val buffer = encoder.getOutputBuffer(index) ?: throw IOException("Missing AAC output buffer")
                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                                if (info.size > 0) {
                                    check(muxerStarted && track >= 0) { "AAC sample arrived before its format" }
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    container.writeSampleData(track, buffer, info)
                                    sampleCount++
                                }
                                outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            } finally { encoder.releaseOutputBuffer(index, false) }
                        }
                    }
                }
            }
            checkActive()
            check(muxerStarted && sampleCount > 0) { "AAC recording contains no audio samples" }
            container.stop() // Errors here invalidate the entire output, rather than returning a broken M4A.
            muxerStarted = false
            check(output.length() > 0L) { "AAC output is empty" }
            checkActive()
            success = true
            return output
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!success) output.delete()
        }
    }

    private companion object { const val SAMPLE_RATE = 44_100 }
}
