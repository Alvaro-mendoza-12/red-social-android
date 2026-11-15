package conectate.com.util

import androidx.media3.common.C
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl

/** Retains the authorized GET as it plays, without a second cold-start request. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun admittedVideoCacheSink(cache: Cache, spec: DataSpec, headers: Map<String, List<String>>): DataSink? {
    val url = spec.uri.toString()
    val identity = StorageReadSession.snapshot()?.identity ?: return null
    if (!StorageReadSession.isStorageRequest(url) || identity.accountId.startsWith("guest_") || spec.uri.getQueryParameter("lease") != null ||
        spec.httpMethod != DataSpec.HTTP_METHOD_GET) return null
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val originalKey = spec.key ?: UbuntuStorageUrl.stableCacheKey(url) ?: return null
    val key = PlaybackCacheAdmission.revisionKey(url, originalKey, header("X-Novvin-Playback-Cache"),
        header("ETag"), header("Content-Type"), allowAudio = false) ?: return null
    val span = cache.startReadWriteNonBlocking(key, spec.position, spec.length) ?: return null
    if (span.isCached) return null
    val sink = CacheDataSink.Factory().setCache(cache).createDataSink()
    val length = if (span.isOpenEnded) spec.length else if (spec.length == C.LENGTH_UNSET.toLong()) span.length
        else minOf(spec.length, span.length)
    try {
        StorageReadSession.requireCurrent(identity)
        sink.open(spec.buildUpon().setKey(key).setLength(length).build())
    } catch (error: Exception) {
        runCatching { sink.close() }
        cache.releaseHoleSpan(span)
        throw error
    }
    return object : DataSink {
        private var closed = false
        private var remaining = length
        override fun open(dataSpec: DataSpec) = error("Cache sink is already open")
        @Synchronized override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (closed || remaining == 0L) return
            StorageReadSession.requireCurrent(identity)
            val count = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            sink.write(buffer, offset, count)
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try { sink.close() } finally { cache.releaseHoleSpan(span) }
        }
    }
}
