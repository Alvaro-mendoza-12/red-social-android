package conectate.com.data.localfallback

import conectate.com.BuildConfig
import java.net.URLEncoder

object UbuntuStorageUrl {
    private const val PUBLIC_MARKER = "/storage/v1/object/public/"
    const val NGROK_SKIP_BROWSER_WARNING = "ngrok-skip-browser-warning"

    private val knownBuckets = setOf(
        "Multimedia-Conectate",
        "avatars",
        "videos",
        "images",
        "audios",
        "thumbnails",
        "chat-media",
        "video-comments-media",
        "groups",
        "communities",
        "post-media",
        "moment-media",
        "challenges",
        "group-media",
        "community-media",
        "post-comments-media"
    )

    val baseUrl: String
        get() = BuildConfig.FALLBACK_API_URL.trim().replace(Regex("/+$"), "")

    val isConfigured: Boolean
        get() = baseUrl.startsWith("http", ignoreCase = true)

    val publicRequestHeaders: Map<String, String>
        get() = if (baseUrl.contains("ngrok", ignoreCase = true)) {
            mapOf(NGROK_SKIP_BROWSER_WARNING to "1")
        } else {
            emptyMap()
        }

    fun rewrite(value: String?): String? {
        val raw = value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: return null
        val root = baseUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: return raw
        val normalizedRaw = raw.replace("\\", "/")
        val markerIndex = normalizedRaw.indexOf(PUBLIC_MARKER, ignoreCase = true)
        if (markerIndex >= 0) {
            return root + normalizedRaw.substring(markerIndex)
        }

        val noSlash = normalizedRaw.removePrefix("/")
        if (noSlash.startsWith("storage/v1/object/public/", ignoreCase = true)) {
            return "$root/$noSlash"
        }

        if (normalizedRaw.startsWith("http", ignoreCase = true)) {
            return normalizedRaw
        }

        val bucket = noSlash.substringBefore("/")
        val path = noSlash.substringAfter("/", missingDelimiterValue = "")
        if (bucket in knownBuckets && path.isNotBlank()) {
            return publicObjectUrl(bucket, path)
        }

        return raw
    }

    fun rewriteOrEmpty(value: String?): String = rewrite(value).orEmpty()

    fun rewriteAll(values: Iterable<String?>): List<String> =
        values.mapNotNull(::rewrite)
            .filter { it.isNotBlank() }
            .distinct()

    fun publicObjectUrl(bucketName: String, objectPath: String): String {
        val root = baseUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: return ""
        val encodedPath = objectPath.trim()
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { encodeSegment(it) }
        if (encodedPath.isBlank()) return ""
        return "$root/storage/v1/object/public/${encodeSegment(bucketName)}/$encodedPath"
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
