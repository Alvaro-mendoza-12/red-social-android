package conectate.com.data.repository

import android.content.Context
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.LinkPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkPreviewRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = newSafeScope("LinkPreviewRepository", Dispatchers.IO)
    
    private val _previewCache = mutableMapOf<String, LinkPreview>()
    private val _loadingUrls = MutableStateFlow<Set<String>>(emptySet())
    val loadingUrls: StateFlow<Set<String>> = _loadingUrls.asStateFlow()
    
    companion object {
        private const val PREVIEW_TIMEOUT_MS = 8_000L
        private const val MAX_CACHE_SIZE = 100
        private val TRUSTED_DOMAINS = setOf(
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
            "wa.me", "whatsapp.com"
        )
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
        return try {
            val doc: Document = Jsoup.connect(url)
                .timeout(5000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .followRedirects(true)
                .get()
            
            val title = extractTitle(doc, url)
            val description = extractDescription(doc)
            val imageUrl = extractImage(doc, url)
            val siteName = extractSiteName(doc, url)
            val type = detectContentType(doc, url)
            
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
        // Check Open Graph type
        doc.select("meta[property=og:type]").firstOrNull()?.attr("content")?.let {
            return when (it.lowercase()) {
                "video", "video.movie", "video.episode", "video.tv_show", "video.other" -> "video"
                "music.song", "music.album", "music.playlist", "music.radio_station" -> "music"
                "article", "book", "profile" -> it.lowercase()
                else -> "website"
            }
        }
        
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
        return try {
            val host = URL(normalizeUrl(url)).host.lowercase()
            TRUSTED_DOMAINS.any { host.contains(it) }
        } catch (e: Exception) {
            false
        }
    }
    
    fun clearCache() {
        _previewCache.clear()
    }
}
