package conectate.com.data.repository.message

import android.content.Context
import android.net.Uri
import conectate.com.core.media.StorageReadSession
import conectate.com.core.media.ViewOnceLeasePolicy
import conectate.com.core.media.ViewOnceMediaStore
import android.os.SystemClock
import conectate.com.core.media.ChatMediaOfflineCache
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder

internal class MessageMediaResolver(
    private val storageService: StorageService,
    private val authService: AuthService,
    private val supabaseConfig: SupabaseConfig,
    private val sessionStateStore: MessageSessionStateStore,
    private val offlineMediaCache: ChatMediaOfflineCache? = null,
    private val context: Context
) {
    private data class CachedPlayableUrl(val value: String, val expiresAtElapsedMs: Long)
    private val playableUrlCache = mutableMapOf<String, CachedPlayableUrl>()
    private val playableUrlInFlight = mutableMapOf<String, CompletableDeferred<Result<String>>>()
    private val playableUrlCacheMutex = Mutex()
    private val viewOnceMutex = Mutex()
    private val playableUrlTtlMs = 50L * 60L * 1000L

    /**
     * Resolves chat media without ever persisting a URL itself. When [allowOfflineMediaCache]
     * is true, a durable account-scoped local copy is preferred and a successfully opened
     * remote image, audio or video is queued for caching. Callers for view-once media must
     * pass false.
     */
    suspend fun getPlayableUrl(
        path: String,
        allowOfflineMediaCache: Boolean = true,
        isPrefetch: Boolean = false
    ): Result<String> {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return Result.failure(Exception("Ruta multimedia invalida"))
        }
        val accountScope = authService.getCurrentUserId()?.trim()?.lowercase().orEmpty()
        suspend fun finish(result: Result<String>): Result<String> {
            if (authService.getCurrentUserId()?.trim()?.lowercase().orEmpty() != accountScope) {
                return Result.failure(Exception("La cuenta activa cambió"))
            }
            if (allowOfflineMediaCache && result.isSuccess) {
                withContext(Dispatchers.IO) {
                    if (authService.getCurrentUserId()?.trim()?.lowercase().orEmpty() == accountScope) {
                        cacheResolvedMedia(trimmed, result.getOrThrow(), isPrefetch, accountScope)
                    }
                }
            }
            return if (authService.getCurrentUserId()?.trim()?.lowercase().orEmpty() == accountScope) {
                result
            } else {
                Result.failure(Exception("La cuenta activa cambió"))
            }
        }
        if (allowOfflineMediaCache) {
            offlineMediaCache?.resolveCachedUri(trimmed, retain = !isPrefetch)?.let { cachedUri ->
                return finish(Result.success(cachedUri))
            }
        }
        if (trimmed.contains("/storage/v1/object/public/")) {
            val playableUrl = UbuntuStorageUrl.rewrite(trimmed) ?: trimmed
            return finish(Result.success(playableUrl))
        }

        val location = resolveStorageLocation(trimmed)
            ?: return Result.failure(Exception("Ruta multimedia invalida"))
        val cacheKey = "$accountScope:${location.bucket}:${location.objectPath}"

        val now = SystemClock.elapsedRealtime()
        var ownsResolution = false
        var cachedUrl: String? = null
        val pending = playableUrlCacheMutex.withLock {
            prunePlayableUrlCache(now)
            cachedUrl = playableUrlCache[cacheKey]
                ?.takeIf { it.expiresAtElapsedMs > now }
                ?.value
            if (cachedUrl != null) return@withLock null

            playableUrlInFlight[cacheKey] ?: CompletableDeferred<Result<String>>().also {
                playableUrlInFlight[cacheKey] = it
                ownsResolution = true
            }
        }

        cachedUrl?.let { return finish(Result.success(it)) }
        checkNotNull(pending)
        if (!ownsResolution) return finish(pending.await())

        val result: Result<String> = try {
            val signed = storageService.createSignedUrl(location.bucket, location.objectPath)
            val resolved = if (signed.isSuccess) {
                val signedUrl = signed.getOrThrow()
                UbuntuStorageUrl.rewrite(signedUrl) ?: signedUrl
            } else {
                android.util.Log.w(
                    "MessageMediaResolver",
                    "No se pudo resolver una URL firmada de multimedia; se aplica el respaldo permitido."
                )
                if (trimmed.startsWith("http", ignoreCase = true)) {
                    UbuntuStorageUrl.rewrite(trimmed) ?: trimmed
                } else {
                    UbuntuStorageUrl.rewrite(
                        storageService.getPublicUrl(location.bucket, location.objectPath)
                    ).orEmpty()
                }
            }
            Result.success(resolved)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                playableUrlCacheMutex.withLock {
                    playableUrlInFlight.remove(cacheKey)
                }
                pending.cancel(cancellation)
            }
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }

        withContext(NonCancellable) {
            playableUrlCacheMutex.withLock {
                if (result.isSuccess && result.getOrThrow().isNotBlank()) {
                    prunePlayableUrlCache(SystemClock.elapsedRealtime(), reserveEntry = true)
                    playableUrlCache[cacheKey] = CachedPlayableUrl(
                        result.getOrThrow(),
                        SystemClock.elapsedRealtime() + playableUrlTtlMs
                    )
                }
                playableUrlInFlight.remove(cacheKey)
            }
            pending.complete(result)
        }
        return finish(result)
    }

    private fun cacheResolvedMedia(source: String, resolvedUrl: String, isPrefetch: Boolean, accountId: String) {
        if (isPrefetch) {
            offlineMediaCache?.cacheOpenedMedia(source, resolvedUrl, expectedAccountId = accountId)
        } else {
            offlineMediaCache?.cacheExplicitlyOpenedMedia(source, resolvedUrl, expectedAccountId = accountId)
        }
    }

    private fun prunePlayableUrlCache(nowElapsedMs: Long, reserveEntry: Boolean = false) {
        playableUrlCache.entries.removeAll { (_, cached) -> cached.expiresAtElapsedMs <= nowElapsedMs }
        val targetSize = if (reserveEntry) MAX_PLAYABLE_URL_CACHE_SIZE - 1 else MAX_PLAYABLE_URL_CACHE_SIZE
        val excess = playableUrlCache.size - targetSize
        if (excess <= 0) return
        playableUrlCache.entries
            .sortedBy { it.value.expiresAtElapsedMs }
            .take(excess)
            .forEach { playableUrlCache.remove(it.key) }
    }

    fun getShareableUrl(path: String): Result<String> {
        var trimmed = path.trim()
        if (trimmed.isBlank()) {
            return Result.failure(Exception("Ruta multimedia invalida"))
        }
        if (trimmed.startsWith("http", ignoreCase = true)) {
            return Result.success(UbuntuStorageUrl.rewrite(trimmed) ?: trimmed)
        }

        var location = resolveStorageLocation(trimmed)
            ?: return Result.failure(Exception("Ruta multimedia invalida"))

        return Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(location.bucket, location.objectPath)).orEmpty())
    }

    suspend fun consumeViewOnceMedia(
        scopeType: String,
        messageId: String,
        mediaPath: String,
        deviceId: String = android.os.Build.MODEL ?: "android"
    ): Result<String> = withContext(Dispatchers.IO) {
        viewOnceMutex.withLock {
            var localFile: String? = null
            try {
                val cleanScope = scopeType.trim().lowercase()
                val cleanMessageId = messageId.trim().lowercase()
                require(cleanScope in setOf("chat", "dm", "group", "community") && cleanMessageId.isNotBlank() && mediaPath.isNotBlank()) { "No se puede abrir este archivo." }
                val userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: error("Inicia sesión para abrir el archivo.")
                val key = "$cleanScope:$cleanMessageId:$userId"
                check(!sessionStateStore.isViewOnceConsumed(key)) { "Este contenido ya fue visto." }
                val token = authService.ensureValidAccessToken().getOrThrow()
                check(authService.getCurrentUserId()?.trim()?.lowercase() == userId) { "La cuenta activa cambió." }
                val identity = StorageReadSession.snapshot()?.identity ?: error("Inicia sesión para abrir el archivo.")
                StorageReadSession.requireCurrent(identity)
                val payload = buildJsonObject {
                    put("p_scope_type", cleanScope); put("p_message_id", cleanMessageId)
                    put("p_device_id", deviceId.take(120)); put("p_max_file_bytes", ViewOnceLeasePolicy.MAX_BYTES)
                }
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_consume_view_once_message") {
                    header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json"); setBody(payload)
                }
                check(response.status.isSuccess()) { "No se pudo autorizar el archivo de una sola visualización (${response.status.value})." }
                val body = response.bodyAsText()
                require(body.length <= 16 * 1024) { "El servidor devolvió una respuesta inesperada." }
                val lease = ViewOnceLeasePolicy.parse(body, UbuntuStorageUrl.baseUrl)
                StorageReadSession.requireCurrent(identity)
                check(authService.getCurrentUserId()?.trim()?.lowercase() == userId) { "La cuenta activa cambió." }
                sessionStateStore.markViewOnceConsumed(key)
                val delivered = ViewOnceMediaStore.download(context, lease)
                localFile = delivered
                StorageReadSession.requireCurrent(identity)
                check(authService.getCurrentUserId()?.trim()?.lowercase() == userId) { "La cuenta activa cambió." }
                Result.success(delivered)
            } catch (cancelled: CancellationException) {
                localFile?.let(ViewOnceMediaStore::release); throw cancelled
            } catch (error: Throwable) {
                localFile?.let(ViewOnceMediaStore::release); Result.failure(error)
            }
        }
    }

    private data class StorageLocation(
        val bucket: String,
        val objectPath: String
    )

    private fun resolveStorageLocation(rawPath: String): StorageLocation? {
        val knownBuckets = listOf(
            "chat-media",
            "group-media",
            "community-media",
            "groups",
            "communities",
            "group-audio",
            "community-audio",
            "videos",
            "thumbnails",
            "avatars",
            "posts"
        )

        val input = rawPath.trim()
        if (input.isBlank()) return null
        val withoutQuery = input.substringBefore("?").trim()

        fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        fun cleanObjectPath(value: String): String = decode(value).trim().removePrefix("/").substringBefore("?")

        val nonHttp = withoutQuery.removePrefix("/")
        val directBucket = nonHttp.substringBefore("/", "")
        if (directBucket in knownBuckets) {
            var objectPath = cleanObjectPath(nonHttp.substringAfter("/", ""))
            if (objectPath.isNotBlank()) {
                return StorageLocation(directBucket, objectPath)
            }
        }

        val uri = runCatching { Uri.parse(withoutQuery) }.getOrNull()
        val segments = uri?.pathSegments
            ?.map { decode(it) }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (segments.isNotEmpty()) {
            val publicIdx = segments.indexOf("public")
            if (publicIdx >= 0 && publicIdx + 2 < segments.size) {
                var bucket = segments[publicIdx + 1]
                var objectPath = cleanObjectPath(segments.subList(publicIdx + 2, segments.size).joinToString("/"))
                if (bucket in knownBuckets && objectPath.isNotBlank()) {
                    return StorageLocation(bucket, objectPath)
                }
            }

            val signIdx = segments.indexOf("sign")
            if (signIdx >= 0 && signIdx + 2 < segments.size) {
                var bucket = segments[signIdx + 1]
                var objectPath = cleanObjectPath(segments.subList(signIdx + 2, segments.size).joinToString("/"))
                if (bucket in knownBuckets && objectPath.isNotBlank()) {
                    return StorageLocation(bucket, objectPath)
                }
            }

            val storageObjectIdx = segments.indexOf("object")
            if (storageObjectIdx >= 0 && storageObjectIdx + 2 < segments.size) {
                var bucket = segments[storageObjectIdx + 1]
                var objectPath = cleanObjectPath(segments.subList(storageObjectIdx + 2, segments.size).joinToString("/"))
                if (bucket in knownBuckets && objectPath.isNotBlank()) {
                    return StorageLocation(bucket, objectPath)
                }
            }
        }

        knownBuckets.firstOrNull { withoutQuery.contains("$it/") }?.let { bucket ->
            var objectPath = cleanObjectPath(withoutQuery.substringAfter("$bucket/", ""))
            if (objectPath.isNotBlank()) {
                return StorageLocation(bucket, objectPath)
            }
        }

        return null
    }

    private companion object {
        const val MAX_PLAYABLE_URL_CACHE_SIZE = 512
    }
}



