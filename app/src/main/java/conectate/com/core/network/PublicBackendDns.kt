package conectate.com.core.network

import android.os.SystemClock
import android.util.Log
import conectate.com.BuildConfig
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * Android can inherit a Tailscale split-DNS answer from a shared Wi-Fi network
 * without having a Tailscale route itself. Resolve our public Funnel hostname
 * over HTTPS when the system supplies only private addresses. TLS still uses
 * the original hostname, so normal certificate validation is preserved.
 */
object PublicBackendDns : Dns {
    private const val TAG = "PublicBackendDns"
    private const val CACHE_MS = 5 * 60 * 1000L
    private const val MAX_STALE_MS = 15 * 60 * 1000L
    private val resolverExecutor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "NovvinPublicDns").apply { isDaemon = true }
    }
    private val backendHost = runCatching {
        URI(BuildConfig.FALLBACK_API_URL.trim()).host.orEmpty().lowercase()
    }.getOrDefault("").takeIf { it.endsWith(".ts.net") }.orEmpty()

    @Volatile private var cachedAddresses: List<InetAddress> = emptyList()
    @Volatile private var cacheExpiresAtMs = 0L

    private data class Resolver(val host: String, val path: String, val ip: ByteArray? = null)

    private val resolvers = listOf(
        Resolver("cloudflare-dns.com", "/dns-query"),
        Resolver("cloudflare-dns.com", "/dns-query", byteArrayOf(1, 1, 1, 1)),
        Resolver("dns.google", "/resolve", byteArrayOf(8, 8, 8, 8)),
    )

    override fun lookup(hostname: String): List<InetAddress> {
        if (backendHost.isBlank() || !hostname.equals(backendHost, ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = SystemClock.elapsedRealtime()
        if (now < cacheExpiresAtMs && cachedAddresses.isNotEmpty()) return cachedAddresses

        synchronized(this) {
            val refreshedNow = SystemClock.elapsedRealtime()
            if (refreshedNow < cacheExpiresAtMs && cachedAddresses.isNotEmpty()) {
                return cachedAddresses
            }
            val lookups = listOf<() -> List<InetAddress>>({ Dns.SYSTEM.lookup(hostname).filter(::isPublicAddress) }) +
                resolvers.map { resolver -> { resolveOverHttps(resolver, hostname).filter(::isPublicAddress) } }
            val addresses = firstSuccessfulDnsLookup(resolverExecutor, lookups, 4_000L)
            if (addresses.isNotEmpty()) {
                cachedAddresses = addresses
                cacheExpiresAtMs = SystemClock.elapsedRealtime() + CACHE_MS
                Log.i(TAG, "Public backend address resolved; parallel resolver race complete")
                return addresses
            }
        }
        if (cachedAddresses.isNotEmpty() &&
            SystemClock.elapsedRealtime() < cacheExpiresAtMs + MAX_STALE_MS
        ) return cachedAddresses
        throw UnknownHostException("No public address available for $hostname")
    }

    private fun resolveOverHttps(resolver: Resolver, hostname: String): List<InetAddress> {
        val bootstrapDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.equals(resolver.host, ignoreCase = true) && resolver.ip != null) {
                    listOf(InetAddress.getByAddress(resolver.ip))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }
        val client = OkHttpClient.Builder()
            .dns(bootstrapDns)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://${resolver.host}${resolver.path}?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val document = JSONObject(response.body?.string().orEmpty())
            if (document.optInt("Status", -1) != 0) return emptyList()
            val answers = document.optJSONArray("Answer") ?: return emptyList()
            return (0 until answers.length()).mapNotNull { index ->
                val answer = answers.optJSONObject(index) ?: return@mapNotNull null
                if (answer.optInt("type") != 1) return@mapNotNull null
                parseIpv4(answer.optString("data"))
            }
        }
    }

    private fun parseIpv4(value: String): InetAddress? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        // 100.64.0.0/10 is Tailscale/CGNAT space; Java does not mark it site-local.
        if (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 &&
            (bytes[1].toInt() and 0xc0) == 64
        ) return false
        // IPv6 unique-local addresses (fc00::/7) are also unreachable publicly.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        return true
    }
}
