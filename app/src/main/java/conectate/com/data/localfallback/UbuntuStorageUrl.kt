package conectate.com.data.localfallback

import java.net.URLEncoder

object UbuntuStorageUrl {
    private const val PUBLIC_MARKER = "/storage/v1/object/public/"
    private const val SIGNED_MARKER = "/storage/v1/object/sign/"
    private val cacheVariantParameters = setOf("format", "quality", "width")
    const val NGROK_SKIP_BROWSER_WARNING = "ngrok-skip-browser-warning"

    private val knownBuckets = setOf(
        "Multimedia-Conectate",
        "avatars",
        "videos",
        "live-replays",
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
        "post-comments-media",
        "group-audio",
        "profile-audio",
        "report-evidence"
    )

    val baseUrl: String
        get() = RuntimeBackendEndpoint.baseUrl

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

    fun stableCacheKey(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = raw.replace("\\", "/")
        val publicMarkerIndex = normalized.indexOf(PUBLIC_MARKER, ignoreCase = true)
        val signedMarkerIndex = normalized.indexOf(SIGNED_MARKER, ignoreCase = true)
        val markerIndex = listOf(publicMarkerIndex, signedMarkerIndex).filter { it >= 0 }.minOrNull() ?: -1
        return if (markerIndex >= 0) {
            val referenceWithQuery = normalized
                .substring(markerIndex)
                .substringBefore('#')
            val objectReference = referenceWithQuery.substringBefore('?')
                .replace(SIGNED_MARKER, PUBLIC_MARKER, ignoreCase = true)
            val variantQuery = referenceWithQuery.substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .mapNotNull { parameter ->
                    val key = parameter.substringBefore('=').trim().lowercase()
                    parameter.takeIf { key in cacheVariantParameters && parameter.contains('=') }
                }
                .sorted()
                .joinToString("&")
            "ubuntu-storage:$objectReference" + variantQuery.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
        } else {
            normalized
        }
    }

    /**
     * Requests a bounded WebP preview from the Ubuntu storage endpoint. Other
     * origins are left untouched because they may not implement this contract.
     */
    fun thumbnail(value: String?, width: Int = 1080, quality: Int = 78): String? {
        val rewritten = rewrite(value) ?: return null
        val root = baseUrl.trimEnd('/').takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return rewritten
        if (!rewritten.startsWith("$root$PUBLIC_MARKER", ignoreCase = true)) return rewritten
        val safeWidth = width.coerceIn(64, 1920)
        val safeQuality = quality.coerceIn(40, 90)
        val withoutFragment = rewritten.substringBefore('#')
        val separator = if (withoutFragment.contains('?')) '&' else '?'
        return "$withoutFragment${separator}width=$safeWidth&quality=$safeQuality&format=webp"
    }

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
