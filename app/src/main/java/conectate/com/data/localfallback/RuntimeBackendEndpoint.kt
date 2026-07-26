package conectate.com.data.localfallback

import android.content.Context
import android.util.Log
import conectate.com.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Runtime source of truth for the Ubuntu backend endpoint. */
object RuntimeBackendEndpoint {
    private const val TAG = "RuntimeBackendEndpoint"
    private const val PREFERENCES = "runtime_backend_endpoint"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_BASE_URL_SAVED_AT = "base_url_saved_at"
    // Quick Tunnel URLs rotate when the mini PC/tunnel restarts. A long cache
    // leaves users calling an old host and produces "Unable to resolve host".
    private const val QUICK_TUNNEL_MAX_CACHE_MS = 30 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val defaultUrl = normalize(BuildConfig.FALLBACK_API_URL)
    private val _baseUrl = MutableStateFlow(defaultUrl)
    private val refreshMutex = Mutex()
    @Volatile private var appContext: Context? = null
    @Volatile private var lastResolvedAt: Long = 0L
    val baseUrlFlow: StateFlow<String> = _baseUrl.asStateFlow()

    val baseUrl: String
        get() = _baseUrl.value

    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") || (BuildConfig.DEBUG && baseUrl.startsWith("http://"))

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val cached = prefs
            .getString(KEY_BASE_URL, null)
            .orEmpty()
        val cachedAt = prefs.getLong(KEY_BASE_URL_SAVED_AT, 0L)
        if (isAllowed(cached) && !isExpiredQuickTunnel(cached, cachedAt)) {
            _baseUrl.value = normalize(cached)
            lastResolvedAt = cachedAt
        } else if (cached.isNotBlank()) {
            Log.w(TAG, "Ignoring stale quick-tunnel endpoint cached in device: ${safeHost(cached)}")
            prefs.edit().remove(KEY_BASE_URL).remove(KEY_BASE_URL_SAVED_AT).apply()
        }
    }

    /**
     * Used after a request fails against a rotated quick-tunnel URL. The app may
     * have started with a cached trycloudflare endpoint while DuckDNS already
     * points to the replacement tunnel; refresh and expose the new base URL.
     */
    suspend fun refreshAfterEndpointFailure(failedUrl: String? = null): Boolean {
        val context = appContext ?: return false
        val before = _baseUrl.value
        val changed = refresh(context)
        if (changed) {
            Log.w(TAG, "Backend endpoint refreshed after failure: $before -> ${_baseUrl.value}")
            return true
        }
        val failedBase = failedUrl?.let(::extractBaseUrl)
        return failedBase != null && failedBase != _baseUrl.value
    }

    /**
     * Ensures that a rotating Quick Tunnel is resolved before a mutating request.
     * A stale BuildConfig URL must never be treated as a healthy endpoint merely
     * because it has a syntactically valid HTTPS address.
     */
    suspend fun ensureFreshEndpoint(): String? {
        val context = appContext ?: return baseUrl.takeIf(::isAllowed)
        val current = baseUrl
        val needsRefresh = !isAllowed(current) ||
            (isQuickTunnel(current) &&
                (lastResolvedAt <= 0L ||
                    System.currentTimeMillis() - lastResolvedAt > QUICK_TUNNEL_MAX_CACHE_MS))
        if (needsRefresh) {
            refresh(context)
        }
        return baseUrl.takeIf(::isAllowed)
    }

    suspend fun refresh(context: Context): Boolean = refreshMutex.withLock {
        refreshUnlocked(context)
    }

    private suspend fun refreshUnlocked(context: Context): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(
            fetchDnsEndpoint(),
            fetchConfigEndpoint(),
        ).distinct()
        val candidate = candidates.firstOrNull { runCatching { probe(it) }.getOrDefault(false) }
            ?: return@withContext false

        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, candidate)
            .putLong(KEY_BASE_URL_SAVED_AT, System.currentTimeMillis())
            .apply()
        val changed = candidate != _baseUrl.value
        _baseUrl.value = candidate
        lastResolvedAt = System.currentTimeMillis()
        changed
    }

    private fun fetchDnsEndpoint(): String? = runCatching {
        val host = BuildConfig.BACKEND_DISCOVERY_HOST.trim()
        if (host.isBlank()) return@runCatching null
        val encodedHost = java.net.URLEncoder.encode(host, "UTF-8")
        val urls = listOf(
            "https://dns.google/resolve?name=$encodedHost&type=TXT",
            "https://cloudflare-dns.com/dns-query?name=$encodedHost&type=TXT",
        )
        for (url in urls) {
            val endpoint = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "DNS HTTP ${response.code}" }
                    val payload = JSONObject(response.body?.string().orEmpty())
                    if (payload.optInt("Status", 0) != 0) return@use null
                    val answers = payload.optJSONArray("Answer") ?: return@use null
                    (0 until answers.length()).asSequence()
                        .mapNotNull { index -> answers.optJSONObject(index)?.optString("data") }
                        .map { normalize(it.trim().removeSurrounding("\"")) }
                        .firstOrNull(::isAllowed)
                }
            }.onFailure { Log.w(TAG, "Resolvedor TXT no disponible: $url", it) }.getOrNull()
            if (endpoint != null) return@runCatching endpoint
        }
        null
    }.onFailure { Log.w(TAG, "No se pudo consultar DuckDNS TXT", it) }.getOrNull()

    private fun fetchConfigEndpoint(): String? = runCatching {
        val configUrl = BuildConfig.BACKEND_CONFIG_URL.trim()
        if (!configUrl.startsWith("https://")) return@runCatching null
        val request = Request.Builder().url(configUrl).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Config HTTP ${response.code}" }
            val payload = JSONObject(response.body?.string().orEmpty())
            normalize(payload.optString("backendUrl").ifBlank { payload.optString("apiUrl") })
                .takeIf(::isAllowed)
        }
    }.onFailure { Log.w(TAG, "Descriptor HTTP no disponible", it) }.getOrNull()

    private fun probe(candidate: String): Boolean {
        val request = Request.Builder()
            .url("$candidate/ready")
            .header("ngrok-skip-browser-warning", "1")
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun isAllowed(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.startsWith("https://") || (BuildConfig.DEBUG && normalized.startsWith("http://"))
    }

    private fun normalize(value: String): String = value.trim().trimEnd('/')

    private fun isExpiredQuickTunnel(value: String, savedAt: Long): Boolean {
        val normalized = normalize(value)
        if (!isQuickTunnel(normalized)) return false
        if (savedAt <= 0L) return true
        return System.currentTimeMillis() - savedAt > QUICK_TUNNEL_MAX_CACHE_MS
    }

    private fun isQuickTunnel(value: String): Boolean =
        normalize(value).contains(".trycloudflare.com", ignoreCase = true)

    private fun safeHost(value: String): String = runCatching {
        URI(normalize(value)).host.orEmpty()
    }.getOrDefault("")

    private fun extractBaseUrl(value: String): String? {
        val normalized = normalize(value)
        val marker = listOf("/v1/", "/auth/", "/rest/", "/storage/").mapNotNull { token ->
            normalized.indexOf(token).takeIf { it > 0 }
        }.minOrNull()
        return if (marker != null) normalized.substring(0, marker) else normalized.takeIf(::isAllowed)
    }
}
