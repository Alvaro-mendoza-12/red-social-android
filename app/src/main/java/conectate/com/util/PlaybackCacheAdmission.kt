package conectate.com.util

import androidx.media3.datasource.DataSpec
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.security.MessageDigest

/** Cached playback consults current server permissions and revision on EVERY open. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackCacheAdmission {
    fun key(spec: DataSpec, allowAudio: Boolean = false): String? {
        val url = spec.uri.toString()
        if (!StorageReadSession.isStorageRequest(url) || spec.uri.getQueryParameter("lease") != null || spec.httpMethod != DataSpec.HTTP_METHOD_GET) return null
        val identity = StorageReadSession.snapshot()?.identity ?: return null
        val source = MediaPlaybackDataSources.httpFactory().createDataSource()
        val headers = try {
            StorageReadSession.requireCurrent(identity)
            source.open(spec.buildUpon().setHttpMethod(DataSpec.HTTP_METHOD_HEAD).setPosition(0).setLength(-1).build())
            source.responseHeaders
        } finally { source.close() }
        StorageReadSession.requireCurrent(identity)
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        return revisionKey(url, spec.key ?: UbuntuStorageUrl.stableCacheKey(url) ?: url,
            header("X-Novvin-Playback-Cache"), header("ETag"), header("Content-Type"), allowAudio)
    }

    internal fun revisionKey(url: String, originalKey: String, permission: String?, etag: String?, contentType: String?, allowAudio: Boolean): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (permission != "permission-check-v1" || etag.isNullOrBlank() || etag.length > 512 ||
            !(type.startsWith("video/") || (allowAudio && type.startsWith("audio/")))) return null
        val revision = MessageDigest.getInstance("SHA-256").digest(etag.toByteArray()).joinToString("") { "%02x".format(it) }
        return StorageReadSession.scopedCacheKey(url, "$originalKey:revision:$revision")
    }
}
