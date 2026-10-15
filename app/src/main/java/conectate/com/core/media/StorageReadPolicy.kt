package conectate.com.core.media

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Authority and route checks for reads. A filename never determines its visibility. */
internal object StorageReadPolicy {
    fun accepts(url: String?, backendUrl: String?): Boolean = runCatching {
        val source = URI(url ?: return false)
        val backend = URI(backendUrl ?: return false)
        if (!source.scheme.equals("https", true) || !backend.scheme.equals("https", true)) return false
        if (source.rawUserInfo != null || backend.rawUserInfo != null) return false
        val host = source.host?.lowercase(Locale.ROOT) ?: return false
        val backendHost = backend.host?.lowercase(Locale.ROOT) ?: return false
        if (host != backendHost || port(source) != port(backend)) return false
        if (backend.rawQuery != null || backend.rawFragment != null) return false
        val basePath = backend.rawPath.orEmpty().trimEnd('/')
        val marker = "$basePath/storage/v1/object/"
        val rawPath = source.rawPath ?: return false
        if (!rawPath.startsWith(marker)) return false
        val segments = rawPath.removePrefix(marker).split('/')
        val objectSegments = if (segments.firstOrNull() in setOf("public", "sign")) segments.drop(1) else segments
        if (objectSegments.size < 2) return false
        objectSegments.all { raw ->
            // Decode exactly once, keeping '+' as a path character. Encoded separators
            // and traversal cannot turn one approved storage route into another route.
            val decoded = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            decoded.isNotEmpty() && decoded !in setOf(".", "..") &&
                decoded.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
        }
    }.getOrDefault(false)

    private fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
