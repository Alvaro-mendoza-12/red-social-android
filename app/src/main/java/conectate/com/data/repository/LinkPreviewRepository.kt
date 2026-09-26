package conectate.com.data.repository

import conectate.com.data.model.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PREVIEW_REDIRECTS = 4
private const val MAX_PREVIEW_BODY_BYTES = 1_000_000
private val TRUSTED_PREVIEW_DOMAINS = setOf(
    "youtube.com", "youtu.be",
    "twitter.com", "x.com",
    "instagram.com",
    "facebook.com", "fb.com",
    "tiktok.com",
    "spotify.com", "open.spotify.com",
    "news.google.com",
    "reddit.com",
    "github.com",
    "linkedin.com",
    "wa.me", "whatsapp.com",
)

internal fun isTrustedPreviewUrl(value: String): Boolean {
    return runCatching {
        val url = URL(value)
        val host = url.host.trim().trim('[', ']').lowercase().trimEnd('.')
        url.protocol.equals("https", ignoreCase = true) &&
            url.userInfo.isNullOrBlank() &&
            host.isNotBlank() &&
            TRUSTED_PREVIEW_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
    }.getOrDefault(false)
}

@Singleton
class LinkPreviewRepository @Inject constructor() {
    private val _previewCache = mutableMapOf<String, LinkPreview>()
    private val _loadingUrls = MutableStateFlow<Set<String>>(emptySet())
    val loadingUrls: StateFlow<Set<String>> = _loadingUrls.asStateFlow()
    
    companion object {
        private const val PREVIEW_TIMEOUT_MS = 8_000L
        private const val MAX_CACHE_SIZE = 100
    }
    
    suspend fun getPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url)
        
        // Check cache first
        _previewCache[normalizedUrl]?.let { return@withContext it }
        
        // Check if already loading
        if (_loadingUrls.value.contains(normalizedUrl)) {
            return@withContext null
        }
        
        _loadingUrls.value = _loadingUrls.value + normalizedUrl
        
        try {
            val preview = withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
                fetchPreview(normalizedUrl)
            }
            
            preview?.let {
                // Cache the result
                if (_previewCache.size >= MAX_CACHE_SIZE) {
                    _previewCache.remove(_previewCache.keys.first())
                }
                _previewCache[normalizedUrl] = it
            }
            
            preview
        } catch (e: Exception) {
            null
        } finally {
            _loadingUrls.value = _loadingUrls.value - normalizedUrl
        }
    }
    
    private fun fetchPreview(url: String): LinkPreview? {
        if (!isTrustedPreviewUrl(url)) return createFallbackPreview(url)
        return try {
            val (doc, finalUrl) = fetchTrustedDocument(url) ?: return createFallbackPreview(url)
            
            val title = extractTitle(doc, finalUrl)
            val description = extractDescription(doc)
            val imageUrl = extractImage(doc, finalUrl)
            val siteName = extractSiteName(doc, finalUrl)
            val type = detectContentType(doc, finalUrl)
            
            LinkPreview(
                url = url,
                title = title,
                description = description,
                imageUrl = imageUrl,
                siteName = siteName,
                type = type
            )
        } catch (e: Exception) {
            // Fallback: create basic preview from URL
            createFallbackPreview(url)
        }
    }

    private fun fetchTrustedDocument(initialUrl: String): Pair<Document, String>? {
        var currentUrl = initialUrl
        repeat(MAX_PREVIEW_REDIRECTS + 1) { redirectCount ->
            if (!isTrustedPreviewUrl(currentUrl)) return null
            val response = Jsoup.connect(currentUrl)
                .timeout(5000)
                .userAgent("NOVVIN Link Preview/3.3 (Android)")
                .followRedirects(false)
                .maxBodySize(MAX_PREVIEW_BODY_BYTES)
                .execute()
            if (response.statusCode() in 300..399) {
                if (redirectCount >= MAX_PREVIEW_REDIRECTS) return null
                val location = response.header("Location")?.trim().orEmpty()
                if (location.isBlank()) return null
                currentUrl = URL(URL(currentUrl), location).toString()
                return@repeat
            }
            if (response.statusCode() !in 200..299) return null
            return response.parse() to currentUrl
        }
        return null
    }
    
    private fun extractTitle(doc: Document, url: String): String {
        // Try Open Graph title first
        doc.select("meta[property=og:title]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it
        }
        
        // Try Twitter Card title
        doc.select("meta[name=twitter:title]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it
        }
        
        // Fall back to HTML title
        doc.title().takeIf { it.isNotBlank() }?.let { return it }
        
        // Last resort: extract from URL
        return extractTitleFromUrl(url)
    }
    
    private fun extractDescription(doc: Document): String {
        // Try Open Graph description
        doc.select("meta[property=og:description]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it.take(200)
        }
        
        // Try Twitter Card description
        doc.select("meta[name=twitter:description]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it.take(200)
        }
        
        // Try meta description
        doc.select("meta[name=description]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it.take(200)
        }
        
        // Try first paragraph
        doc.select("p").firstOrNull()?.text()?.let {
            if (it.isNotBlank()) return it.take(200)
        }
        
        return ""
    }
    
    private fun extractImage(doc: Document, baseUrl: String): String? {
        // Try Open Graph image
        doc.select("meta[property=og:image]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return resolveUrl(it, baseUrl)
        }
        
        // Try Twitter Card image
        doc.select("meta[name=twitter:image]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return resolveUrl(it, baseUrl)
        }
        
        // Try first large image
        doc.select("img[src]").forEach { img ->
            val src = img.attr("src")
            val width = img.attr("width").toIntOrNull() ?: 0
            val height = img.attr("height").toIntOrNull() ?: 0
            
            // Prefer images larger than 100x100
            if (src.isNotBlank() && (width > 100 || height > 100 || (width == 0 && height == 0))) {
                return resolveUrl(src, baseUrl)
            }
        }
        
        return null
    }
    
    private fun extractSiteName(doc: Document, url: String): String {
        // Try Open Graph site name
        doc.select("meta[property=og:site_name]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it
        }
        
        // Try Twitter domain
        doc.select("meta[name=twitter:domain]").firstOrNull()?.attr("content")?.let {
            if (it.isNotBlank()) return it
        }
        
        // Extract from URL
        return try {
            URL(url).host.removePrefix("www.").substringBefore(".")
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Website"
        }
    }
    
    private fun detectContentType(doc: Document, url: String): String {
        val ogType = doc.select("meta[property=og:type]").firstOrNull()?.attr("content")
        val ogTypeResult: String? = if (ogType != null) {
            when (ogType.lowercase()) {
                "video", "video.movie", "video.episode", "video.tv_show", "video.other" -> "video"
                "music.song", "music.album", "music.playlist", "music.radio_station" -> "music"
                "article", "book", "profile" -> ogType.lowercase()
                else -> null
            }
        } else null

        if (ogTypeResult != null) return ogTypeResult
        
        // Detect by domain
        return when {
            url.contains("youtube.com") || url.contains("youtu.be") -> "video"
            url.contains("spotify.com") -> "music"
            url.contains("twitter.com") || url.contains("x.com") -> "social"
            url.contains("instagram.com") -> "social"
            url.contains("tiktok.com") -> "video"
            url.contains("github.com") -> "code"
            else -> "website"
        }
    }
    
    private fun createFallbackPreview(url: String): LinkPreview {
        val title = extractTitleFromUrl(url)
        val siteName = try {
            URL(url).host.removePrefix("www.").substringBefore(".")
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Link"
        }
        
        return LinkPreview(
            url = url,
            title = title,
            description = "",
            imageUrl = null,
            siteName = siteName,
            type = "website"
        )
    }
    
    private fun extractTitleFromUrl(url: String): String {
        return try {
            val path = URL(url).path
            path.substringAfterLast("/")
                .replace("-", " ")
                .replace("_", " ")
                .replaceFirstChar { it.uppercase() }
                .takeIf { it.isNotBlank() } ?: "Link"
        } catch (e: Exception) {
            "Link"
        }
    }
    
    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = URL(baseUrl)
                "${base.protocol}://${base.host}$url"
            }
            else -> url
        }
    }
    
    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("www.") -> "https://$url"
            else -> "https://$url"
        }
    }
    
    fun isTrustedDomain(url: String): Boolean {
        return isTrustedPreviewUrl(normalizeUrl(url))
    }
    
    fun clearCache() {
        _previewCache.clear()
    }
}
