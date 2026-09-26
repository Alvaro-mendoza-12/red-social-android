package conectate.com.presentation.voicerooms

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Catalog of content whose playback source can be verified by the application.
 *
 * Third-party metadata catalogs are intentionally not converted into streams on
 * the client. Until NOVVIN has a licensed provider, the built-in catalog only
 * exposes public-domain collections from Internet Archive.
 */
class CinemaCatalog {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun searchPublicDomain(query: String): List<ContentItem> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().take(MAX_SEARCH_QUERY_CHARS)
        if (normalizedQuery.length < MIN_SEARCH_QUERY_CHARS) return@withContext emptyList()

        runCatching {
            val url = ARCHIVE_SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "title:($normalizedQuery) AND mediatype:(movies) AND " +
                        "collection:(feature_films OR scifi OR classic_cartoons)"
                )
                .addQueryParameter("fl[]", "identifier")
                .addQueryParameter("fl[]", "title")
                .addQueryParameter("fl[]", "description")
                .addQueryParameter("rows", MAX_SEARCH_RESULTS.toString())
                .addQueryParameter("output", "json")
                .build()

            val json = JSONObject(
                executeJsonBody(
                    Request.Builder().url(url).build(),
                    operation = "Archive search"
                )
            )
            val docs = json.optJSONObject("response")?.optJSONArray("docs")
                ?: return@runCatching emptyList()

            buildList {
                for (index in 0 until minOf(docs.length(), MAX_SEARCH_RESULTS)) {
                    val item = docs.optJSONObject(index) ?: continue
                    val identifier = item.optString("identifier").trim()
                    val title = item.optString("title").trim()
                    if (!isSafeArchiveIdentifier(identifier) || title.isBlank()) continue
                    add(
                        ContentItem(
                            title = title.take(MAX_TITLE_CHARS),
                            posterUrl = "https://archive.org/services/img/$identifier",
                            description = item.optString("description", "Obra de dominio público.")
                                .trim()
                                .take(MAX_DESCRIPTION_CHARS),
                            provider = ARCHIVE_PROVIDER,
                            providerId = identifier
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Archive search failed", error)
        }.getOrDefault(emptyList())
    }

    suspend fun searchUniversal(query: String, type: String = "all"): List<ContentItem> {
        return if (type == "all" || type == "movie" || type == "public_domain") {
            searchPublicDomain(query)
        } else {
            emptyList()
        }
    }

    suspend fun resolveStream(item: ContentItem): String? = withContext(Dispatchers.IO) {
        if (item.provider != ARCHIVE_PROVIDER || !isSafeArchiveIdentifier(item.providerId)) {
            return@withContext null
        }

        runCatching {
            val metadataUrl = ARCHIVE_METADATA_URL.toHttpUrl().newBuilder()
                .addPathSegment(item.providerId)
                .build()
            val json = JSONObject(
                executeJsonBody(
                    Request.Builder().url(metadataUrl).build(),
                    operation = "Archive metadata"
                )
            )
            val files = json.optJSONArray("files") ?: return@runCatching null
            val candidates = buildList {
                for (index in 0 until files.length()) {
                    val file = files.optJSONObject(index) ?: continue
                    val name = file.optString("name").trim()
                    if (!isSafeArchiveFileName(name) || !isSupportedArchiveVideo(name)) continue
                    add(
                        ArchiveVideoCandidate(
                            name = name,
                            format = file.optString("format").trim(),
                            sizeBytes = file.optLong("size", Long.MAX_VALUE)
                        )
                    )
                }
            }
            val selected = candidates.minWithOrNull(
                compareBy<ArchiveVideoCandidate> { candidateScore(it) }
                    .thenBy { it.sizeBytes }
            ) ?: return@runCatching null

            ARCHIVE_DOWNLOAD_URL.toHttpUrl().newBuilder()
                .addPathSegment(item.providerId)
                .addPathSegment(selected.name)
                .build()
                .toString()
        }.onFailure { error ->
            Log.w(TAG, "Archive playback resolution failed", error)
        }.getOrNull()
    }

    suspend fun getTrending(): Map<String, List<ContentItem>> = mapOf(
        "Cine de dominio público 🎞️" to CURATED_PUBLIC_DOMAIN
    )

    private fun executeJsonBody(request: Request, operation: String): String {
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$operation failed with HTTP ${response.code}")
            }
            val body = response.body ?: return@use "{}"
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_JSON_RESPONSE_BYTES) {
                throw IOException("$operation response exceeds the configured limit")
            }
            val source = body.source()
            source.request(MAX_JSON_RESPONSE_BYTES + 1L)
            if (source.buffer.size > MAX_JSON_RESPONSE_BYTES) {
                throw IOException("$operation response exceeds the configured limit")
            }
            source.readUtf8().ifBlank { "{}" }
        }
    }

    private fun candidateScore(candidate: ArchiveVideoCandidate): Int {
        val name = candidate.name.lowercase()
        val format = candidate.format.lowercase()
        return when {
            "512kb" in name -> 0
            "h.264" in format || "h264" in format -> 1
            name.endsWith(".mp4") -> 2
            else -> 3
        }
    }

    private fun isSafeArchiveIdentifier(value: String): Boolean =
        ARCHIVE_IDENTIFIER_REGEX.matches(value)

    private fun isSafeArchiveFileName(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_ARCHIVE_FILE_NAME_CHARS &&
            !value.contains('/') &&
            !value.contains('\\') &&
            value != "." &&
            value != ".."

    private fun isSupportedArchiveVideo(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.endsWith(".mp4") || normalized.endsWith(".webm")
    }

    private data class ArchiveVideoCandidate(
        val name: String,
        val format: String,
        val sizeBytes: Long
    )

    private companion object {
        private const val TAG = "CinemaCatalog"
        private const val ARCHIVE_PROVIDER = "archive"
        private const val ARCHIVE_SEARCH_URL = "https://archive.org/advancedsearch.php"
        private const val ARCHIVE_METADATA_URL = "https://archive.org/metadata"
        private const val ARCHIVE_DOWNLOAD_URL = "https://archive.org/download"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val WRITE_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 30L
        private const val MAX_JSON_RESPONSE_BYTES = 4L * 1024L * 1024L
        private const val MIN_SEARCH_QUERY_CHARS = 2
        private const val MAX_SEARCH_QUERY_CHARS = 100
        private const val MAX_SEARCH_RESULTS = 18
        private const val MAX_TITLE_CHARS = 200
        private const val MAX_DESCRIPTION_CHARS = 600
        private const val MAX_ARCHIVE_FILE_NAME_CHARS = 512
        private val ARCHIVE_IDENTIFIER_REGEX = Regex("^[A-Za-z0-9._-]{1,200}$")

        private val CURATED_PUBLIC_DOMAIN = listOf(
            ContentItem(
                title = "La noche de los muertos vivientes",
                posterUrl = "https://archive.org/services/img/night_of_the_living_dead",
                provider = ARCHIVE_PROVIDER,
                providerId = "night_of_the_living_dead",
                description = "Cine clásico disponible en Internet Archive."
            ),
            ContentItem(
                title = "Charada",
                posterUrl = "https://archive.org/services/img/Charade1963",
                provider = ARCHIVE_PROVIDER,
                providerId = "Charade1963",
                description = "Cine clásico disponible en Internet Archive."
            ),
            ContentItem(
                title = "El maquinista de La General",
                posterUrl = "https://archive.org/services/img/TheGeneral_107",
                provider = ARCHIVE_PROVIDER,
                providerId = "TheGeneral_107",
                description = "Cine clásico disponible en Internet Archive."
            )
        )
    }
}
