package conectate.com.data.repository.message

import android.net.Uri
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder

internal class MessageMediaResolver(
    private val storageService: StorageService,
    private val authService: AuthService,
    private val supabaseConfig: SupabaseConfig,
    private val sessionStateStore: MessageSessionStateStore,
    private val offlineMediaCache: ChatMediaOfflineCache? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private data class CachedPlayableUrl(val value: String, val expiresAtElapsedMs: Long)
    private val playableUrlCache = mutableMapOf<String, CachedPlayableUrl>()
    private val playableUrlInFlight = mutableMapOf<String, CompletableDeferred<Result<String>>>()
    private val playableUrlCacheMutex = Mutex()
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
        val cleanScope = scopeType.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        val cleanMediaPath = mediaPath.trim()
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("No user"))

        if (cleanScope.isBlank() || cleanMessageId.isBlank() || cleanMediaPath.isBlank()) {
            return@withContext Result.failure(Exception("Datos incompletos para view-once"))
        }

        val consumptionKey = "$cleanScope:$cleanMessageId:$userId"
        if (sessionStateStore.isViewOnceConsumed(consumptionKey)) {
            return@withContext Result.failure(Exception("Este contenido ya fue visto"))
        }

        val playable = getPlayableUrl(
            path = cleanMediaPath,
            allowOfflineMediaCache = false
        )
        if (playable.isFailure) {
            return@withContext Result.failure(playable.exceptionOrNull() ?: Exception("No se pudo abrir contenido"))
        }
        val playableUrl = playable.getOrThrow()

        val token = authService.getAccessToken()
            ?: return@withContext Result.failure(Exception("No token"))

        var allowedByServer = false
        var rpcChecked = false
        runCatching {
            val payload = buildJsonObject {
                put("p_scope_type", cleanScope)
                put("p_message_id", cleanMessageId)
                put("p_device_id", deviceId.take(120))
            }
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_consume_view_once_message") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
            val rpcBody = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (rpcResponse.status.isSuccess()) {
                rpcChecked = true
                allowedByServer = parseViewOnceAllowed(rpcBody)
            } else if (!isMissingRpcFunction(rpcResponse.status.value, rpcBody, "secure_consume_view_once_message")) {
                android.util.Log.w(
                    "MessageMediaResolver",
                    "El control remoto de vista única no está disponible (${rpcResponse.status.value}); se usa la protección local."
                )
            }
        }

        val allowed = if (rpcChecked) {
            allowedByServer
        } else {
            !sessionStateStore.isViewOnceConsumed(consumptionKey)
        }

        if (!allowed) {
            return@withContext Result.failure(Exception("Este contenido ya fue visto"))
        }

        sessionStateStore.markViewOnceConsumed(consumptionKey)
        Result.success(playableUrl)
    }

    private fun parseViewOnceAllowed(rawBody: String): Boolean {
        if (rawBody.isBlank()) return false
        return runCatching {
            val element = json.parseToJsonElement(rawBody)
            when (element) {
                is JsonObject -> {
                    parseFlexibleBoolean(element["allowed"]) == true &&
                        parseFlexibleBoolean(element["already_consumed"]) != true
                }
                is JsonArray -> {
                    val first = element.firstOrNull() as? JsonObject
                    parseFlexibleBoolean(first?.get("allowed")) == true &&
                        parseFlexibleBoolean(first?.get("already_consumed")) != true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun parseFlexibleBoolean(raw: JsonElement?): Boolean? {
        val primitive = raw?.jsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        val text = primitive.contentOrNull?.trim()?.lowercase() ?: return null
        return when (text) {
            "true", "t", "1", "yes", "y" -> true
            "false", "f", "0", "no", "n" -> false
            else -> null
        }
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains(functionName.lowercase()) &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
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



