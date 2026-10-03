package conectate.com.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Progressive extractors can read a small header and immediately reopen at the
 * selected audio offset. Keep that initial response briefly so a nearby forward
 * seek can consume it instead of paying for another HTTP request. No song is
 * downloaded ahead of demand or kept in an offline cache.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ShortAudioSeekDataSource(
    private val upstream: DataSource,
    private val deferClose: ((() -> Unit) -> Unit) = { task ->
        closer.schedule(task, RETAIN_MS, TimeUnit.MILLISECONDS)
    },
    private val nanoTime: () -> Long = System::nanoTime,
) : DataSource {
    private var original: DataSpec? = null
    private var upstreamActive = false
    private var opened = false
    private var position = 0L
    private var upstreamRemaining = C.LENGTH_UNSET.toLong()
    private var requestedRemaining = C.LENGTH_UNSET.toLong()
    private var reused = false
    private var generation = 0L
    private var retainedAtNs = 0L

    @Synchronized
    override fun open(dataSpec: DataSpec): Long {
        check(!opened) { "Source already open" }
        generation++
        val previous = original
        val gap = dataSpec.position - position
        val retainedForNs = nanoTime() - retainedAtNs
        val compatible = upstreamActive && previous != null && !reused &&
            retainedForNs in 0..TimeUnit.MILLISECONDS.toNanos(RETAIN_MS) &&
            gap in 0..MAX_FORWARD_BYTES && sameRequest(previous, dataSpec) &&
            (upstreamRemaining == C.LENGTH_UNSET.toLong() || gap <= upstreamRemaining)
        if (compatible) {
            try {
                val scratch = ByteArray(8_192)
                var toSkip = gap
                while (toSkip > 0L) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio seek cancelled")
                    val read = upstream.read(scratch, 0, minOf(scratch.size.toLong(), toSkip).toInt())
                    if (read <= 0) throw IOException("Audio ended before the selected offset")
                    advance(read)
                    toSkip -= read
                }
                reused = true
                requestedRemaining = boundedLength(dataSpec.length, upstreamRemaining)
                opened = true
                return requestedRemaining
            } catch (failure: IOException) {
                closeAfterFailure(failure)
                throw failure
            }
        }
        closeUpstream()
        original = dataSpec
        position = dataSpec.position
        reused = false
        upstreamActive = true // close() must also release an unsuccessful open.
        try {
            upstreamRemaining = upstream.open(dataSpec)
            requestedRemaining = boundedLength(dataSpec.length, upstreamRemaining)
            opened = true
            return requestedRemaining
        } catch (failure: Exception) {
            closeAfterFailure(failure)
            throw failure
        }
    }

    @Synchronized
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "Source is closed" }
        if (length == 0) return 0
        if (requestedRemaining == 0L) return C.RESULT_END_OF_INPUT
        val limit = if (requestedRemaining == C.LENGTH_UNSET.toLong()) length
            else minOf(length.toLong(), requestedRemaining).toInt()
        try {
            val count = upstream.read(buffer, offset, limit)
            if (count > 0) {
                advance(count)
                if (requestedRemaining != C.LENGTH_UNSET.toLong()) requestedRemaining -= count
            } else if (count == C.RESULT_END_OF_INPUT) {
                upstreamRemaining = 0L
                requestedRemaining = 0L
            }
            return count
        } catch (failure: IOException) {
            opened = false
            closeAfterFailure(failure)
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        opened = false
        val spec = original
        val maySeekFromHeader = upstreamActive && !reused && spec != null &&
            spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong() &&
            spec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            spec.uri.scheme in listOf("http", "https") &&
            position in 1..MAX_HEADER_BYTES && upstreamRemaining != 0L
        if (!maySeekFromHeader) {
            closeUpstream()
            return
        }
        val closedGeneration = ++generation
        retainedAtNs = nanoTime()
        deferClose {
            synchronized(this) {
                if (!opened && generation == closedGeneration) closeUpstream()
            }
        }
    }

    @Synchronized
    override fun getUri(): Uri? = if (opened) upstream.uri else null

    @Synchronized
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (opened) upstream.responseHeaders else emptyMap()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    private fun advance(count: Int) {
        position += count
        if (upstreamRemaining != C.LENGTH_UNSET.toLong()) upstreamRemaining -= count
    }

    private fun closeUpstream() {
        if (!upstreamActive) return
        upstreamActive = false
        original = null
        try { upstream.close() } finally {
            upstreamRemaining = C.LENGTH_UNSET.toLong()
            requestedRemaining = C.LENGTH_UNSET.toLong()
        }
    }

    private fun closeAfterFailure(failure: Exception) {
        try { closeUpstream() } catch (closeFailure: Exception) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }

    private fun sameRequest(a: DataSpec, b: DataSpec): Boolean =
        a.uri == b.uri && a.key == b.key && a.flags == b.flags &&
            a.httpMethod == b.httpMethod && a.httpRequestHeaders == b.httpRequestHeaders &&
            a.httpBody.contentEquals(b.httpBody) && a.customData == b.customData &&
            a.uriPositionOffset == b.uriPositionOffset

    private fun boundedLength(requested: Long, available: Long): Long = when {
        requested == C.LENGTH_UNSET.toLong() -> available
        available == C.LENGTH_UNSET.toLong() -> requested
        else -> minOf(requested, available)
    }

    companion object {
        private const val RETAIN_MS = 250L
        private const val MAX_HEADER_BYTES = 16_384L
        private const val MAX_FORWARD_BYTES = 262_144L
        private val closer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "novvin-audio-response-close").apply { isDaemon = true }
        }
    }
}
