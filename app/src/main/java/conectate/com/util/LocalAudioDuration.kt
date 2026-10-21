package conectate.com.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val durationReaders = Semaphore(2)

/** Reads only an already available file. Never starts a network request from a chat row. */
internal suspend fun localAudioDurationMs(context: Context, source: String?): Long? = withContext(Dispatchers.IO) {
    if (source.isNullOrBlank()) return@withContext null
    val uri = Uri.parse(source)
    if (uri.scheme != null && uri.scheme !in setOf("file", "content")) return@withContext null
    durationReaders.withPermit {
        currentCoroutineContext().ensureActive()
        val localPath = if (uri.scheme == "file") uri.path ?: return@withPermit null else source
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            if (uri.scheme == "content") retriever.setDataSource(context, uri)
            else retriever.setDataSource(localPath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
        if (metadata != null) return@withPermit metadata

        // Browser MediaRecorder WebM/Opus files can omit the container's duration.
        // Read packet timestamps without decoding audio, with bounded work off Main.
        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "content") extractor.setDataSource(context, uri, null)
            else extractor.setDataSource(localPath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withPermit null
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                if (duration > 0) return@withPermit duration
            }
            extractor.selectTrack(track)
            val deadline = SystemClock.elapsedRealtime() + 1500
            var lastUs = -1L
            var deltaUs = 0L
            repeat(100_000) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() > deadline) return@withPermit null
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0) return@withPermit ((lastUs + deltaUs) / 1000).takeIf { it > 0 }
                if (lastUs >= 0 && sampleUs > lastUs) deltaUs = sampleUs - lastUs
                lastUs = sampleUs
                if (!extractor.advance()) return@withPermit ((lastUs + deltaUs) / 1000).takeIf { it > 0 }
            }
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { extractor.release() }
    }
}
