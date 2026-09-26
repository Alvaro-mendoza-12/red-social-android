package conectate.com.data.localfallback

import android.content.Context
import android.util.Log
import conectate.com.BuildConfig
import conectate.com.core.network.BackendTrustPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Runtime source of truth for the cryptographically trusted Ubuntu backend endpoint. */
object RuntimeBackendEndpoint {
    private const val TAG = "RuntimeBackendEndpoint"
    private const val PREFERENCES = "runtime_backend_endpoint"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_BASE_URL_SAVED_AT = "base_url_saved_at"
    private const val KEY_CAPABILITY_SCHEMA = "capability_schema"
    private const val KEY_DISCOVERY_ENVELOPE = "discovery_envelope"
    private const val KEY_DISCOVERY_EXPIRES_AT = "discovery_expires_at"
    private const val CURRENT_CAPABILITY_SCHEMA = 5
    private const val EXPIRY_CLOCK_SKEW_SECONDS = 5L * 60L
    // A Quick Tunnel can disappear while its cached URL still looks valid.
    // Keep a short liveness lease so feed/message reads fail over before they
    // spend their whole interactive deadline on a dead hostname, without
    // adding a /ready request to every API call.
    private const val ENDPOINT_LIVENESS_TTL_MS = 30_000L
    private val requiredCapabilities = setOf(
        "anonymous_bottles_v1",
        "community_music_v1",
        "moment_music_v1",
        "moment_music_aac_lc_v1",
        "image_moments_exact_6s_v1",
        "video_sound_identity_v1",
        "video_feed_session_v1",
        "video_feed_live_v1",
        "message_markers_v1",
        "video_watch_later_v1",
        "video_collections_v1",
    )

    private data class TrustedCandidate(
        val url: String,
        val discoveryEnvelope: String = "",
        val expiresAtEpochSeconds: Long? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val allowHttp = BuildConfig.DEBUG
    private val configuredUrl = BackendTrustPolicy.canonicalBaseUrl(
        BuildConfig.FALLBACK_API_URL,
        allowHttp
    ).orEmpty()
    private val discoveryPublicKey = BuildConfig.BACKEND_DISCOVERY_PUBLIC_KEY.trim()
    private val _baseUrl = MutableStateFlow(configuredUrl)
    private val refreshGate = EndpointRefreshGate(android.os.SystemClock::elapsedRealtime)
    @Volatile private var appContext: Context? = null
    @Volatile private var currentDiscoveryEnvelope = ""
    @Volatile private var currentExpiresAtEpochSeconds: Long? = null
    @Volatile private var lastValidatedBaseUrl = ""
    @Volatile private var lastValidatedAtMs = 0L
    val baseUrlFlow: StateFlow<String> = _baseUrl.asStateFlow()

    val baseUrl: String
        get() {
            expireDynamicEndpointIfNeeded()
            return _baseUrl.value
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val cachedUrl = prefs.getString(KEY_BASE_URL, null).orEmpty()
        val cachedEnvelope = prefs.getString(KEY_DISCOVERY_ENVELOPE, null).orEmpty()
        val cachedCapabilitySchema = prefs.getInt(KEY_CAPABILITY_SCHEMA, 0)
        val candidate = if (cachedCapabilitySchema == CURRENT_CAPABILITY_SCHEMA) {
            trustCandidate(cachedUrl, cachedEnvelope)
        } else {
            null
        }
        if (candidate != null) {
            applyCandidate(candidate, persist = false)
        } else {
            clearCachedCandidate(prefs)
        }
    }

    /** Refreshes discovery after a request failed without ever trusting an unsigned new origin. */
    suspend fun refreshAfterEndpointFailure(failedUrl: String? = null): Boolean {
        val context = appContext ?: return false
        val failedBase = failedUrl?.let(::extractBaseUrl) ?: baseUrl
        val before = baseUrl
        refreshGate.refresh(skip = { failedBase.isNotBlank() && failedBase != baseUrl }) {
            invalidateValidation(failedBase)
            refreshUnlocked(context, skippedUrl = failedBase)
        }
        val changed = before != baseUrl
        if (changed) {
            Log.w(
                TAG,
                "Backend endpoint refreshed after failure: ${safeHost(before)} -> ${safeHost(baseUrl)}"
            )
        }
        return changed || (failedBase.isNotBlank() && failedBase != baseUrl)
    }

    /** Returns only an explicitly configured or cryptographically verified backend. */
    suspend fun ensureFreshEndpoint(): String? {
        val current = baseUrl
        if (isCurrentTrusted(current) && isValidationFresh(current)) return current
        val context = appContext ?: return current.takeIf(::isCurrentTrusted)

        // Several feed/avatar/message requests can arrive together on a cold
        // start. Re-check after taking the mutex so only the first one probes
        // DNS/the tunnel; the rest reuse the result.
        refreshGate.refresh(skip = { isCurrentTrusted(baseUrl) && isValidationFresh(baseUrl) }) {
            refreshUnlocked(context.applicationContext)
        }
        return baseUrl.takeIf(::isCurrentTrusted)
    }

    suspend fun refresh(context: Context): Boolean {
        val before = baseUrl
        refreshGate.refresh(skip = { isCurrentTrusted(baseUrl) && isValidationFresh(baseUrl) }) {
            refreshUnlocked(context.applicationContext)
        }
        return before != baseUrl
    }

    private suspend fun refreshUnlocked(
        context: Context,
        skippedUrl: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val before = baseUrl
        val localCandidates = listOfNotNull(
            trustCandidate(before, currentDiscoveryEnvelope),
            trustCandidate(configuredUrl),
        ).distinctBy(TrustedCandidate::url)
        // A packaged Quick Tunnel URL may already be gone on a fresh install.
        // Discover the signed current origin before spending a full probe
        // timeout on that temporary build-time hostname.
        val preferDiscovery = before == configuredUrl &&
            currentDiscoveryEnvelope.isBlank() &&
            BuildConfig.BACKEND_DISCOVERY_HOST.isNotBlank() &&
            runCatching { URI(configuredUrl).host?.endsWith(".trycloudflare.com") == true }
                .getOrDefault(false)
        val local = if (preferDiscovery) null else localCandidates.firstOrNull { candidate ->
            candidate.url != skippedUrl && runCatching { probe(candidate.url) }.getOrDefault(false)
        }
        if (local != null) {
            currentCoroutineContext().ensureActive()
            markValidated(local.url)
            applyCandidate(local, context = context)
            return@withContext true
        }

        val discovered = coroutineScope {
            val dns = async { fetchDnsCandidate() }
            val config = async { fetchConfigCandidate() }
            listOfNotNull(dns.await(), config.await()).distinctBy(TrustedCandidate::url)
        }.firstOrNull { candidate ->
            candidate.url != skippedUrl && runCatching { probe(candidate.url) }.getOrDefault(false)
        }

        if (discovered == null && preferDiscovery) {
            val fallback = localCandidates.firstOrNull { candidate ->
                candidate.url != skippedUrl && runCatching { probe(candidate.url) }.getOrDefault(false)
            }
            if (fallback != null) {
                currentCoroutineContext().ensureActive()
                markValidated(fallback.url)
                applyCandidate(fallback, context = context)
                return@withContext true
            }
        }
        if (discovered == null) return@withContext false

        currentCoroutineContext().ensureActive()
        markValidated(discovered.url)
        applyCandidate(discovered, context = context)
        true
    }

    private fun trustCandidate(urlValue: String, envelopeValue: String = ""): TrustedCandidate? {
        val canonical = BackendTrustPolicy.canonicalBaseUrl(urlValue, allowHttp) ?: return null
        if (BackendTrustPolicy.isExplicitlyTrusted(canonical, configuredUrl, allowHttp)) {
            return TrustedCandidate(url = canonical)
        }
        // Debug builds may use HTTP for an explicitly configured local server,
        // but a rotating public endpoint is still accepted only when its
        // HTTPS discovery envelope verifies against the pinned key.
        if (discoveryPublicKey.isBlank()) return null
        val verified = BackendTrustPolicy.verifyDiscoveryEnvelope(
            envelopeValue,
            discoveryPublicKey
        ) ?: return null
        if (verified.url != canonical) return null
        return TrustedCandidate(
            url = verified.url,
            discoveryEnvelope = verified.envelope,
            expiresAtEpochSeconds = verified.expiresAtEpochSeconds
        )
    }

    private fun applyCandidate(
        candidate: TrustedCandidate,
        context: Context? = null,
        persist: Boolean = true
    ) {
        _baseUrl.value = candidate.url
        currentDiscoveryEnvelope = candidate.discoveryEnvelope
        currentExpiresAtEpochSeconds = candidate.expiresAtEpochSeconds
        if (!persist) return
        val targetContext = context ?: appContext ?: return
        targetContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, candidate.url)
            .putLong(KEY_BASE_URL_SAVED_AT, System.currentTimeMillis())
            .putInt(KEY_CAPABILITY_SCHEMA, CURRENT_CAPABILITY_SCHEMA)
            .putString(KEY_DISCOVERY_ENVELOPE, candidate.discoveryEnvelope)
            .putLong(KEY_DISCOVERY_EXPIRES_AT, candidate.expiresAtEpochSeconds ?: 0L)
            .apply()
    }

    private fun expireDynamicEndpointIfNeeded() {
        val expiry = currentExpiresAtEpochSeconds ?: return
        if (expiry > System.currentTimeMillis() / 1_000L - EXPIRY_CLOCK_SKEW_SECONDS) return
        _baseUrl.value = configuredUrl
        currentDiscoveryEnvelope = ""
        currentExpiresAtEpochSeconds = null
        invalidateValidation()
    }

    private fun isValidationFresh(value: String): Boolean =
        value.isNotBlank() &&
            value == lastValidatedBaseUrl &&
            android.os.SystemClock.elapsedRealtime() - lastValidatedAtMs in 0..ENDPOINT_LIVENESS_TTL_MS

    private fun markValidated(value: String) {
        lastValidatedBaseUrl = value
        lastValidatedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** A successful API read extends an already capability-verified lease. */
    fun noteSuccessfulRead(requestUrl: String) {
        val origin = extractBaseUrl(requestUrl) ?: return
        if (origin == lastValidatedBaseUrl && origin == baseUrl && isCurrentTrusted(origin)) {
            lastValidatedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun invalidateValidation(value: String? = null) {
        if (value == null || value == lastValidatedBaseUrl) {
            lastValidatedBaseUrl = ""
            lastValidatedAtMs = 0L
        }
    }

    private fun isCurrentTrusted(value: String): Boolean {
        if (BackendTrustPolicy.isExplicitlyTrusted(value, configuredUrl, allowHttp)) return true
        val expiry = currentExpiresAtEpochSeconds ?: return false
        return currentDiscoveryEnvelope.isNotBlank() &&
            expiry > System.currentTimeMillis() / 1_000L - EXPIRY_CLOCK_SKEW_SECONDS
    }

    private fun fetchDnsCandidate(): TrustedCandidate? = runCatching {
        val host = BuildConfig.BACKEND_DISCOVERY_HOST.trim()
        if (host.isBlank() || discoveryPublicKey.isBlank()) return@runCatching null
        val encodedHost = java.net.URLEncoder.encode(host, "UTF-8")
        val urls = listOf(
            "https://dns.google/resolve?name=$encodedHost&type=TXT",
            "https://cloudflare-dns.com/dns-query?name=$encodedHost&type=TXT",
        )
        for (url in urls) {
            val candidate = runCatching {
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
                        .map(::unwrapDnsTxt)
                        .mapNotNull { envelope ->
                            BackendTrustPolicy.verifyDiscoveryEnvelope(
                                envelope,
                                discoveryPublicKey
                            )
                        }
                        .map { verified ->
                            TrustedCandidate(
                                url = verified.url,
                                discoveryEnvelope = verified.envelope,
                                expiresAtEpochSeconds = verified.expiresAtEpochSeconds
                            )
                        }
                        .firstOrNull()
                }
            }.onFailure {
                Log.w(TAG, "Resolvedor TXT no disponible: ${safeHost(url)}")
            }.getOrNull()
            if (candidate != null) return@runCatching candidate
        }
        null
    }.onFailure {
        Log.w(TAG, "No se pudo consultar el descubrimiento DNS firmado")
    }.getOrNull()

    private fun fetchConfigCandidate(): TrustedCandidate? = runCatching {
        val configUrl = BuildConfig.BACKEND_CONFIG_URL.trim()
        if (!configUrl.startsWith("https://")) return@runCatching null
        val request = Request.Builder().url(configUrl).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Config HTTP ${response.code}" }
            val payload = JSONObject(response.body?.string().orEmpty())
            trustCandidate(
                payload.optString("backendUrl").ifBlank { payload.optString("apiUrl") },
                payload.optString("discoveryEnvelope")
            )
        }
    }.onFailure {
        Log.w(TAG, "Descriptor HTTPS de backend no disponible")
    }.getOrNull()

    private fun probe(candidate: String): Boolean {
        val request = Request.Builder()
            .url("$candidate/ready")
            .header("ngrok-skip-browser-warning", "1")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val payload = runCatching { JSONObject(response.body?.string().orEmpty()) }
                .getOrNull() ?: return@use false
            val advertised = payload.optJSONArray("capabilities") ?: return@use false
            val capabilities = buildSet {
                for (index in 0 until advertised.length()) {
                    advertised.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val missing = requiredCapabilities - capabilities
            if (missing.isNotEmpty()) {
                Log.e(TAG, "Backend incompatible; faltan capacidades: ${missing.sorted().joinToString()}")
                false
            } else {
                payload.optBoolean("ok", false)
            }
        }
    }

    private fun unwrapDnsTxt(value: String): String = value
        .trim()
        .removeSurrounding("\"")
        .replace(Regex("\"\\s+\""), "")

    private fun clearCachedCandidate(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_BASE_URL_SAVED_AT)
            .remove(KEY_CAPABILITY_SCHEMA)
            .remove(KEY_DISCOVERY_ENVELOPE)
            .remove(KEY_DISCOVERY_EXPIRES_AT)
            .apply()
    }

    private fun safeHost(value: String): String = runCatching {
        URI(value).host.orEmpty()
    }.getOrDefault("")

    private fun extractBaseUrl(value: String): String? = runCatching {
        val parsed = URI(value.trim())
        val port = when {
            parsed.port == -1 || (parsed.scheme.equals("https", true) && parsed.port == 443) -> ""
            else -> ":${parsed.port}"
        }
        BackendTrustPolicy.canonicalBaseUrl(
            "${parsed.scheme}://${parsed.host}$port",
            allowHttp
        )
    }.getOrNull()
}
