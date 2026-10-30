package conectate.com.data.supabase

import conectate.com.core.media.MediaUploadGuard
import conectate.com.core.observability.AppMetrics
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import conectate.com.core.network.PublicBackendDns
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

@Serializable

data class StorageResponse(
    @SerialName("Key") val Key: String? = null,
    @SerialName("Id") val Id: String? = null,
    @SerialName("Name") val Name: String? = null,
    @SerialName("BucketId") val BucketId: String? = null,
    @SerialName("Owner") val Owner: String? = null,
    @SerialName("LastModified") val LastModified: String? = null,
    @SerialName("ETag") val ETag: String? = null,
    @SerialName("Version") val Version: String? = null,
    @SerialName("Size") val Size: Int? = null,
    @SerialName("ContentType") val ContentType: String? = null,
    @SerialName("CreatedAt") val CreatedAt: String? = null,
    @SerialName("UpdatedAt") val UpdatedAt: String? = null
)

@Singleton
class StorageService @Inject constructor(
    private val config: SupabaseConfig,
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val mediaUploadGuard: MediaUploadGuard,
    private val appMetrics: AppMetrics,
    @param:ApplicationContext private val context: Context
) {

    private val responseJson = Json { ignoreUnknownKeys = true }
    private val resumableSessionPreferences by lazy {
        context.getSharedPreferences("resumable_upload_sessions", Context.MODE_PRIVATE)
    }

    private val largeUploadClient = OkHttpClient.Builder()
        .dns(PublicBackendDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(conectate.com.data.localfallback.StorageUploadPolicy.RESPONSE_IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        // Uploads are mutable PUT requests. If the response is lost after the
        // server commits the object, an implicit retry can upload twice or
        // turn a successful create-only upload into a misleading conflict.
        // Retry decisions must be explicit and based on server state.
        .retryOnConnectionFailure(false)
        .build()

    suspend fun uploadFile(
        bucketName: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
        allowOverwrite: Boolean = true
    ): Result<StorageResponse> {
        val startedAt = System.currentTimeMillis()
        var normalizedPath = normalizeStoragePath(fileName) ?: return Result.failure(
            Exception("Invalid storage path")
        )
        val canonicalContentType = normalizeContentType(contentType)
        val metricAttrs = mapOf(
            "bucket" to bucketName,
            "content_type" to canonicalContentType,
            "bytes" to bytes.size.toString()
        )

        return try {
            val validation = mediaUploadGuard.validate(
                bucketName = bucketName,
                fileName = normalizedPath,
                bytes = bytes,
                contentType = canonicalContentType
            )
            if (!validation.allowed) {
                appMetrics.recordFailure(
                    metric = "media_upload",
                    attributes = metricAttrs + ("reason" to (validation.reason ?: "validation_failed"))
                )
                return Result.failure(Exception(validation.reason ?: "Upload blocked by media policy"))
            }

            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(
                Exception("No access token available")
            )

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val fileDisplayName = normalizedPath.substringAfterLast('/').ifBlank { "archivo" }
                val upload = localFallbackApiClient.uploadFile(
                    accessToken = accessToken,
                    bucketName = bucketName,
                    objectPath = normalizedPath,
                    bytes = bytes,
                    contentType = canonicalContentType,
                    fileName = fileDisplayName
                )
                return if (upload.isSuccess) {
                    val localObject = upload.getOrThrow()
                    appMetrics.incrementCounter("media_upload_success", attributes = metricAttrs + ("backend" to "local_fallback"))
                    appMetrics.recordLatency(
                        metric = "media_upload",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = metricAttrs + ("backend" to "local_fallback")
                    )
                    Result.success(
                        StorageResponse(
                            Key = localObject.path,
                            Id = localObject.id,
                            Name = localObject.path,
                            BucketId = localObject.bucket,
                            Size = localObject.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            ContentType = localObject.contentType,
                            CreatedAt = localObject.createdAt,
                            UpdatedAt = localObject.updatedAt
                        )
                    )
                } else {
                    val error = upload.exceptionOrNull() ?: Exception("No se pudo subir el archivo al servidor Ubuntu.")
                    if (error is CancellationException) throw error
                    appMetrics.recordFailure(
                        metric = "media_upload",
                        throwable = error,
                        attributes = metricAttrs + ("backend" to "local_fallback")
                    )
                    Result.failure(error)
                }
            }

            val mediaHash = sha256Hex(bytes)
            val registerResult = registerMediaObject(
                accessToken = accessToken,
                bucketName = bucketName,
                objectPath = normalizedPath,
                contentType = canonicalContentType,
                sizeBytes = bytes.size.toLong(),
                sha256 = mediaHash
            )
            if (registerResult.isFailure) {
                val reason = registerResult.exceptionOrNull()?.message ?: "media_registration_failed"
                android.util.Log.e("StorageService", "Media registration blocked for $bucketName/$normalizedPath: $reason")
                appMetrics.recordFailure(
                    metric = "media_upload",
                    attributes = metricAttrs + ("reason" to reason)
                )
                return Result.failure(registerResult.exceptionOrNull() ?: Exception(reason))
            }

            var response = config.httpClient.post {
                url {
                    takeFrom(config.supabaseUrl)
                    appendPathSegments("storage", "v1", "object", bucketName)
                    normalizedPath.split("/").filter { it.isNotEmpty() }.forEach { appendPathSegments(it) }
                }
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
                header("Content-Type", canonicalContentType)
                header("x-upsert", allowOverwrite.toString())
                header("cache-control", "3600")
                setBody(bytes)
            }

            if (response.status.isSuccess()) {
                val storageResponse: StorageResponse = response.body()
                appMetrics.incrementCounter("media_upload_success", attributes = metricAttrs)
                appMetrics.recordLatency(
                    metric = "media_upload",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = metricAttrs
                )
                Result.success(storageResponse)
            } else {
                var errorBody = try { response.body<String>() } catch (e: Exception) { "No error body" }
                android.util.Log.e("StorageService", "Upload failed: ${response.status} - $errorBody")
                appMetrics.recordFailure(
                    metric = "media_upload",
                    attributes = metricAttrs + ("http_status" to response.status.value.toString())
                )
                Result.failure(
                    buildSupabaseHttpException(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        fallbackMessage = "No se pudo subir el archivo."
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("StorageService", "Exception uploading file", e)
            appMetrics.recordFailure(
                metric = "media_upload",
                throwable = e,
                attributes = metricAttrs
            )
            Result.failure(e)
        }
    }


    /**
     * Upload a large file from a [Uri] to storage, streaming it with progress callbacks.
     * Returns a [StorageResponse] whose [StorageResponse.Key] contains the server-assigned
     * object path (includes owner prefix), so callers must use that key when building
     * the public URL to avoid ownership-mismatch 403 errors.
     */
    suspend fun uploadFileFromUri(
        bucketName: String,
        fileName: String,
        sourceUri: Uri,
        sizeBytes: Long,
        contentType: String,
        allowOverwrite: Boolean,
        onProgress: (Float) -> Unit
    ): Result<StorageResponse> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val startedElapsedMs = android.os.SystemClock.elapsedRealtime()
        val normalizedPath = normalizeStoragePath(fileName)
            ?: return@withContext Result.failure(Exception("Invalid storage path"))
        val canonicalContentType = normalizeContentType(contentType)
        val metricAttrs = mapOf(
            "bucket" to bucketName,
            "content_type" to canonicalContentType,
            "bytes" to sizeBytes.toString()
        )

        val accessToken = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("No access token available"))

        val headerBytes = try {
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                val buffer = ByteArray(512)
                val read = stream.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            } ?: ByteArray(0)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val validation = mediaUploadGuard.validateMetadata(
            bucketName = bucketName,
            fileName = normalizedPath,
            sizeBytes = sizeBytes,
            contentType = canonicalContentType,
            headerBytes = headerBytes
        )
        if (!validation.allowed) {
            appMetrics.recordFailure(
                metric = "media_upload",
                attributes = metricAttrs + ("reason" to (validation.reason ?: "validation_failed"))
            )
            return@withContext Result.failure(Exception(validation.reason ?: "Upload blocked by media policy"))
        }

        val isLocalFallbackUpload = authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured
        if (shouldAttemptResumableUpload(sizeBytes, isLocalFallbackUpload)) {
            val resumableStartedAtMs = android.os.SystemClock.elapsedRealtime()
            val resumable = uploadLocalFallbackResumable(
                accessToken = accessToken,
                bucketName = bucketName,
                normalizedPath = normalizedPath,
                sourceUri = sourceUri,
                sizeBytes = sizeBytes,
                contentType = canonicalContentType,
                allowOverwrite = allowOverwrite,
                onProgress = onProgress
            )
            if (resumable != null) {
                val resumableAttrs = metricAttrs + ("backend" to "local_fallback_resumable")
                appMetrics.recordLatency(
                    metric = "media_upload_resumable",
                    durationMs = android.os.SystemClock.elapsedRealtime() - resumableStartedAtMs,
                    attributes = resumableAttrs,
                )
                if (resumable.isSuccess) {
                    appMetrics.incrementCounter("media_upload_success", attributes = resumableAttrs)
                    appMetrics.recordLatency(
                        metric = "media_upload",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = resumableAttrs,
                    )
                } else {
                    appMetrics.recordFailure(
                        metric = "media_upload",
                        throwable = resumable.exceptionOrNull(),
                        attributes = resumableAttrs,
                    )
                }
                return@withContext resumable
            }
        }
        val backendName = if (isLocalFallbackUpload) "local_fallback_stream" else "supabase_stream"
        val requestBodyStartedAtMs = AtomicLong(0L)
        val requestBodyFinishedAtMs = AtomicLong(0L)
        val uploadUrl = run {
            val base = if (isLocalFallbackUpload) {
                localFallbackApiClient.baseUrl.trimEnd('/')
            } else {
                config.supabaseUrl.trimEnd('/')
            }
            val pathSegments = normalizedPath.split("/").filter { it.isNotEmpty() }
                .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            "$base/storage/v1/object/$bucketName/$pathSegments"
        }

        val requestBody = object : RequestBody() {
            override fun contentType() = canonicalContentType.toMediaTypeOrNull()
            override fun contentLength() = sizeBytes
            override fun writeTo(sink: BufferedSink) {
                requestBodyStartedAtMs.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
                val buffer = ByteArray(128 * 1024)
                var uploaded = 0L
                val source = context.contentResolver.openInputStream(sourceUri)
                    ?: throw java.io.IOException("Cannot open source URI")
                try {
                    source.use { stream ->
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            if (sizeBytes > 0) onProgress((uploaded.toFloat() / sizeBytes).coerceIn(0f, 1f))
                        }
                    }
                } finally {
                    requestBodyFinishedAtMs.set(android.os.SystemClock.elapsedRealtime())
                }
            }
        }

        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", config.supabaseAnonKey)
            .header("Content-Type", canonicalContentType)
            .header("x-upsert", allowOverwrite.toString())
            .header("cache-control", "3600")
            .build()

        try {
            largeUploadClient.newCall(request).awaitStorageResponse().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    recordStreamUploadTiming(
                        startedElapsedMs = startedElapsedMs,
                        requestBodyStartedAtMs = requestBodyStartedAtMs.get(),
                        requestBodyFinishedAtMs = requestBodyFinishedAtMs.get(),
                        sizeBytes = sizeBytes,
                        metricAttrs = metricAttrs,
                        backendName = backendName,
                        outcome = "http_${response.code}",
                    )
                    appMetrics.recordFailure("media_upload", attributes = metricAttrs + ("http_status" to response.code.toString()))
                    return@withContext Result.failure(
                        buildSupabaseHttpException(response.code, responseText, "No se pudo subir el archivo.")
                    )
                }
                onProgress(1f)
                recordStreamUploadTiming(
                    startedElapsedMs = startedElapsedMs,
                    requestBodyStartedAtMs = requestBodyStartedAtMs.get(),
                    requestBodyFinishedAtMs = requestBodyFinishedAtMs.get(),
                    sizeBytes = sizeBytes,
                    metricAttrs = metricAttrs,
                    backendName = backendName,
                    outcome = "success",
                )
                appMetrics.incrementCounter("media_upload_success", attributes = metricAttrs + ("backend" to backendName))
                appMetrics.recordLatency(
                    metric = "media_upload",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = metricAttrs + mapOf("backend" to backendName, "bytes" to sizeBytes.toString())
                )
                // Parse server response to get the real storage path (includes owner prefix)
                val serverKey = runCatching {
                    val json = responseJson.parseToJsonElement(responseText)
                    val obj = json as? JsonObject
                    val serverPath = obj?.get("path")?.jsonPrimitive?.content
                    val fullKey = obj?.get("Key")?.jsonPrimitive?.content
                    when {
                        !serverPath.isNullOrBlank() -> serverPath.trim()
                        !fullKey.isNullOrBlank() && fullKey.contains('/') -> fullKey.substringAfter('/')
                        else -> null
                    }
                }.getOrNull() ?: normalizedPath
                Result.success(
                    StorageResponse(
                        Key = serverKey,
                        Name = serverKey,
                        BucketId = bucketName,
                        Size = sizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        ContentType = canonicalContentType
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            recordStreamUploadTiming(
                startedElapsedMs = startedElapsedMs,
                requestBodyStartedAtMs = requestBodyStartedAtMs.get(),
                requestBodyFinishedAtMs = requestBodyFinishedAtMs.get(),
                sizeBytes = sizeBytes,
                metricAttrs = metricAttrs,
                backendName = backendName,
                outcome = "failure",
            )
            appMetrics.recordFailure("media_upload", e, metricAttrs)
            Result.failure(e)
        }
    }

    /**
     * Separates bytes written by OkHttp from time spent waiting for the server
     * response. Attributes deliberately exclude object paths, user IDs and
     * file names so upload diagnostics never add another PII data source.
     */
    private fun recordStreamUploadTiming(
        startedElapsedMs: Long,
        requestBodyStartedAtMs: Long,
        requestBodyFinishedAtMs: Long,
        sizeBytes: Long,
        metricAttrs: Map<String, String>,
        backendName: String,
        outcome: String,
    ) {
        val timing = StreamUploadTimingCalculator.calculate(
            uploadStartedElapsedMs = startedElapsedMs,
            requestBodyStartedElapsedMs = requestBodyStartedAtMs,
            requestBodyFinishedElapsedMs = requestBodyFinishedAtMs,
            completedElapsedMs = android.os.SystemClock.elapsedRealtime(),
            sizeBytes = sizeBytes,
        )
        val attributes = metricAttrs + mapOf(
            "backend" to backendName,
            "outcome" to outcome,
            "throughput_kibps" to timing.throughputKibPerSecond.toString(),
        )
        appMetrics.recordLatency("media_upload_transport", timing.transportMs, attributes)
        appMetrics.recordLatency(
            "media_upload_response_wait",
            timing.responseWaitMs,
            attributes,
        )
    }

    private data class TusUploadStatus(
        val offset: Long,
        val length: Long,
        val state: String,
        val objectPath: String?,
    )

    private data class PersistedTusUploadSession(
        val url: HttpUrl,
        val createdAtMs: Long,
    )

    private class TusUploadHttpException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)

    /**
     * Returns null only when the installed local server does not advertise
     * TUS. That preserves compatibility during a rolling server deployment;
     * a server that does advertise TUS must either complete or surface its
     * concrete error instead of silently falling back to a duplicate PUT.
     */
    private suspend fun uploadLocalFallbackResumable(
        accessToken: String,
        bucketName: String,
        normalizedPath: String,
        sourceUri: Uri,
        sizeBytes: Long,
        contentType: String,
        allowOverwrite: Boolean,
        onProgress: (Float) -> Unit,
    ): Result<StorageResponse>? {
        val endpoint = "${localFallbackApiClient.baseUrl.trimEnd('/')}/storage/v1/upload/resumable"
            .toHttpUrlOrNull()
            ?: return null
        return try {
            val sourceHash = context.contentResolver.openInputStream(sourceUri)?.use {
                uploadSourceSha256(it, sizeBytes)
            } ?: throw IOException("No se pudo abrir el archivo seleccionado.")
            val sessionKey = resumableSessionKey(
                accountId = authService.getCurrentUserId().orEmpty(),
                endpoint = endpoint,
                bucketName = bucketName,
                objectPath = normalizedPath,
                sizeBytes = sizeBytes,
                contentType = contentType,
                allowOverwrite = allowOverwrite,
                sourceHash = sourceHash,
            )
            var sessionUrl = loadPersistedTusSession(sessionKey, endpoint)?.url
            var initialStatus = sessionUrl?.let { persistedUrl ->
                try {
                    readTusUploadStatus(persistedUrl, accessToken)
                } catch (error: TusUploadHttpException) {
                    if (error.statusCode == 404 || error.statusCode == 410) {
                        clearPersistedTusSession(sessionKey)
                        sessionUrl = null
                        null
                    } else {
                        throw error
                    }
                }
            }
            if (initialStatus != null && initialStatus.length != sizeBytes) {
                clearPersistedTusSession(sessionKey)
                sessionUrl = null
                initialStatus = null
            }
            if (initialStatus?.state == "rejected") {
                clearPersistedTusSession(sessionKey)
                throw IOException("El servidor rechazó el archivo durante la validación.")
            }

            if (sessionUrl == null) {
                val createdSessionUrl = createTusUploadSession(
                    endpoint = endpoint,
                    accessToken = accessToken,
                    bucketName = bucketName,
                    objectPath = normalizedPath,
                    sizeBytes = sizeBytes,
                    contentType = contentType,
                    allowOverwrite = allowOverwrite,
                ) ?: return null
                sessionUrl = createdSessionUrl
                persistTusSession(sessionKey, createdSessionUrl)
                initialStatus = readTusUploadStatus(createdSessionUrl, accessToken)
            }

            val activeSessionUrl = sessionUrl
                ?: throw IOException("No se pudo recuperar la sesión de subida reanudable.")
            var offset = initialStatus?.offset ?: 0L
            var recoveries = 0
            while (offset < sizeBytes) {
                currentCoroutineContext().ensureActive()
                try {
                    val nextOffset = uploadTusChunk(
                        sessionUrl = activeSessionUrl,
                        accessToken = accessToken,
                        sourceUri = sourceUri,
                        offsetBytes = offset,
                        totalBytes = sizeBytes,
                        onProgress = onProgress,
                    )
                    if (nextOffset !in (offset + 1)..sizeBytes) {
                        throw IOException("El servidor devolvió un offset de subida inválido.")
                    }
                    offset = nextOffset
                    recoveries = 0
                } catch (error: TusUploadHttpException) {
                    if (error.statusCode != 409 && error.statusCode !in 500..599) throw error
                    offset = recoverTusOffset(activeSessionUrl, accessToken, sizeBytes, recoveries++)
                } catch (error: IOException) {
                    if (recoveries >= 3) throw error
                    offset = recoverTusOffset(activeSessionUrl, accessToken, sizeBytes, recoveries++)
                }
            }
            val completed = awaitTusCompletion(activeSessionUrl, accessToken, sizeBytes)
            val serverPath = completed.objectPath?.trim().takeIf { !it.isNullOrBlank() }
                ?: throw IOException("El servidor no confirmó la ruta del archivo subido.")
            clearPersistedTusSession(sessionKey)
            onProgress(1f)
            Result.success(
                StorageResponse(
                    Key = serverPath,
                    Name = serverPath,
                    BucketId = bucketName,
                    Size = sizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    ContentType = contentType,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun resumableSessionKey(
        accountId: String,
        endpoint: HttpUrl,
        bucketName: String,
        objectPath: String,
        sizeBytes: Long,
        contentType: String,
        allowOverwrite: Boolean,
        sourceHash: String,
    ): String {
        val identity = listOf(
            accountId.trim().lowercase(),
            endpoint.scheme,
            endpoint.host,
            endpoint.port.toString(),
            bucketName.trim().lowercase(),
            objectPath,
            sizeBytes.toString(),
            contentType,
            allowOverwrite.toString(),
            sourceHash,
        ).joinToString("\u001f")
        return sha256Hex(identity.toByteArray(Charsets.UTF_8))
    }

    private fun loadPersistedTusSession(key: String, endpoint: HttpUrl): PersistedTusUploadSession? {
        val url = resumableSessionPreferences.getString("$key.url", null)?.toHttpUrlOrNull()
            ?: return null
        val createdAtMs = resumableSessionPreferences.getLong("$key.created_at_ms", 0L)
        if (!isReusableTusSession(endpoint, url, createdAtMs, System.currentTimeMillis())) {
            clearPersistedTusSession(key)
            return null
        }
        return PersistedTusUploadSession(url, createdAtMs)
    }

    private fun persistTusSession(key: String, sessionUrl: HttpUrl) {
        val persisted = resumableSessionPreferences.edit()
            .putString("$key.url", sessionUrl.toString())
            .putLong("$key.created_at_ms", System.currentTimeMillis())
            // The session must reach durable storage before the first PATCH;
            // otherwise an immediate process kill can orphan uploaded bytes.
            .commit()
        if (!persisted) throw IOException("No se pudo conservar la sesión de subida reanudable.")
    }

    private fun clearPersistedTusSession(key: String) {
        resumableSessionPreferences.edit()
            .remove("$key.url")
            .remove("$key.created_at_ms")
            .apply()
    }

    private suspend fun createTusUploadSession(
        endpoint: HttpUrl,
        accessToken: String,
        bucketName: String,
        objectPath: String,
        sizeBytes: Long,
        contentType: String,
        allowOverwrite: Boolean,
    ): HttpUrl? {
        val metadata = tusMetadataHeader(
            linkedMapOf(
                "bucketName" to bucketName,
                "objectName" to objectPath,
                "contentType" to contentType,
                "cacheControl" to "3600",
            ),
        )
        val request = Request.Builder()
            .url(endpoint)
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", config.supabaseAnonKey)
            .header("Tus-Resumable", RESUMABLE_UPLOAD_PROTOCOL_VERSION)
            .header("Upload-Length", sizeBytes.toString())
            .header("Upload-Metadata", metadata)
            .header("x-upsert", allowOverwrite.toString())
            .build()
        return largeUploadClient.newCall(request).createTusSessionOrNull(endpoint)
    }

    private suspend fun recoverTusOffset(
        sessionUrl: HttpUrl,
        accessToken: String,
        totalBytes: Long,
        recoveryIndex: Int,
    ): Long {
        if (recoveryIndex >= 3) throw IOException("La red interrumpió la subida varias veces.")
        delay(350L * (recoveryIndex + 1))
        val status = readTusUploadStatus(sessionUrl, accessToken)
        if (status.state == "rejected") throw IOException("El servidor rechazó el archivo durante la validación.")
        if (status.length != totalBytes || status.offset !in 0..totalBytes) {
            throw IOException("El estado reanudable del servidor no coincide con el archivo.")
        }
        return status.offset
    }

    private suspend fun awaitTusCompletion(
        sessionUrl: HttpUrl,
        accessToken: String,
        totalBytes: Long,
    ): TusUploadStatus {
        repeat(4) { attempt ->
            val status = readTusUploadStatus(sessionUrl, accessToken)
            if (status.length != totalBytes || status.offset != totalBytes) {
                throw IOException("El servidor no completó todos los bytes del archivo.")
            }
            if (status.state == "completed") return status
            if (status.state == "rejected") throw IOException("El servidor rechazó el archivo durante la validación.")
            delay(250L * (attempt + 1))
        }
        throw IOException("El servidor no terminó de procesar el archivo subido.")
    }

    private suspend fun readTusUploadStatus(sessionUrl: HttpUrl, accessToken: String): TusUploadStatus {
        val request = Request.Builder()
            .url(sessionUrl)
            .head()
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", config.supabaseAnonKey)
            .header("Tus-Resumable", RESUMABLE_UPLOAD_PROTOCOL_VERSION)
            .build()
        largeUploadClient.newCall(request).awaitStorageResponse().use { response ->
            if (!response.isSuccessful) {
                throw TusUploadHttpException(response.code, "No se pudo consultar el progreso de la subida.")
            }
            val offset = resumableUploadOffset(response.header("Upload-Offset"))
                ?: throw IOException("El servidor no devolvió un offset reanudable válido.")
            val length = resumableUploadOffset(response.header("Upload-Length"))
                ?: throw IOException("El servidor no devolvió el tamaño reanudable válido.")
            return TusUploadStatus(
                offset = offset,
                length = length,
                state = response.header("Upload-State")?.trim()?.lowercase().orEmpty(),
                objectPath = response.header("Upload-Object-Path")?.trim(),
            )
        }
    }

    private suspend fun uploadTusChunk(
        sessionUrl: HttpUrl,
        accessToken: String,
        sourceUri: Uri,
        offsetBytes: Long,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        val chunkBytes = boundedResumableChunkSize(totalBytes, offsetBytes)
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/offset+octet-stream".toMediaTypeOrNull()
            override fun contentLength() = chunkBytes

            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(sourceUri)?.use { source ->
                    source.skipFully(offsetBytes)
                    val buffer = ByteArray(128 * 1024)
                    var remaining = chunkBytes
                    var written = 0L
                    while (remaining > 0L) {
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw EOFException("El archivo local terminó antes de lo esperado.")
                        sink.write(buffer, 0, read)
                        remaining -= read
                        written += read
                        onProgress(((offsetBytes + written).toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                } ?: throw IOException("No se pudo abrir el archivo seleccionado.")
            }
        }
        val request = Request.Builder()
            .url(sessionUrl)
            .patch(requestBody)
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", config.supabaseAnonKey)
            .header("Tus-Resumable", RESUMABLE_UPLOAD_PROTOCOL_VERSION)
            .header("Upload-Offset", offsetBytes.toString())
            .build()
        largeUploadClient.newCall(request).awaitStorageResponse().use { response ->
            if (response.code != 204) {
                throw TusUploadHttpException(response.code, "No se pudo enviar un fragmento de la subida.")
            }
            return resumableUploadOffset(response.header("Upload-Offset"))
                ?: throw IOException("El servidor no confirmó el fragmento subido.")
        }
    }

    private fun InputStream.skipFully(offsetBytes: Long) {
        var remaining = offsetBytes
        val singleByte = ByteArray(1)
        while (remaining > 0L) {
            val skipped = skip(remaining)
            when {
                skipped > 0L -> remaining -= skipped
                read(singleByte) >= 0 -> remaining -= 1L
                else -> throw EOFException("El archivo local terminó antes del offset reanudable.")
            }
        }
    }

    suspend fun downloadFile(bucketName: String, fileName: String): Result<ByteArray> {
        return try {
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured &&
                fileName.startsWith("http", ignoreCase = true)
            ) {
                return localFallbackApiClient.downloadPublicFile(UbuntuStorageUrl.rewrite(fileName) ?: fileName)
            }

            var normalizedPath = normalizeStoragePath(fileName)
                ?: return Result.failure(Exception("Invalid storage path"))
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val publicUrl = localFallbackApiClient.publicObjectUrl(bucketName, normalizedPath)
                return localFallbackApiClient.downloadPublicFile(publicUrl)
            }
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            var response = config.httpClient.get {
                url {
                    takeFrom(config.supabaseUrl)
                    appendPathSegments("storage", "v1", "object", bucketName)
                    normalizedPath.split("/").filter { it.isNotEmpty() }.forEach { appendPathSegments(it) }
                }
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
            }

            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.body()
                Result.success(bytes)
            } else {
                var errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                Result.failure(
                    buildSupabaseHttpException(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        fallbackMessage = "No se pudo descargar el archivo."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(bucketName: String, fileName: String): Result<Unit> {
        return try {
            var normalizedPath = normalizeStoragePath(fileName)
                ?: return Result.failure(Exception("Invalid storage path"))
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                return localFallbackApiClient.deleteFile(accessToken, bucketName, normalizedPath)
            }

            var response = config.httpClient.delete {
                url {
                    takeFrom(config.supabaseUrl)
                    appendPathSegments("storage", "v1", "object", bucketName)
                    normalizedPath.split("/").filter { it.isNotEmpty() }.forEach { appendPathSegments(it) }
                }
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                var errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                Result.failure(
                    buildSupabaseHttpException(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        fallbackMessage = "No se pudo eliminar el archivo."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFileByPublicUrl(publicUrl: String): Result<Unit> {
        val marker = "/storage/v1/object/public/"
        val normalized = publicUrl.trim().replace("\\", "/")
        val markerIndex = normalized.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return Result.failure(Exception("Invalid public storage URL"))
        val encodedReference = normalized
            .substring(markerIndex + marker.length)
            .substringBefore('?')
            .substringBefore('#')
        val segments = encodedReference.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return Result.failure(Exception("Invalid public storage URL"))
        val bucket = URLDecoder.decode(segments.first(), "UTF-8")
        val objectPath = segments.drop(1)
            .joinToString("/") { URLDecoder.decode(it, "UTF-8") }
        return deleteFile(bucket, objectPath)
    }

    suspend fun listFiles(bucketName: String, path: String = ""): Result<List<String>> {
        return try {
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val prefix = normalizeStoragePath(path) ?: ""
                return localFallbackApiClient
                    .listStorageObjects(accessToken, bucketName, prefix)
                    .map { objects -> objects.map { it.path } }
            }

            var response = config.httpClient.get {
                url {
                    takeFrom(config.supabaseUrl)
                    appendPathSegments("storage", "v1", "object", "list", bucketName)
                }
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
                parameter("prefix", path)
            }

            if (response.status.isSuccess()) {
                val files: List<Map<String, Any>> = response.body()
                val fileNames = files.mapNotNull { it["name"] as? String }
                Result.success(fileNames)
            } else {
                var errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                Result.failure(
                    buildSupabaseHttpException(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        fallbackMessage = "No se pudo consultar el almacenamiento."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getPublicUrl(bucketName: String, fileName: String): String {
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            if (fileName.startsWith("http", ignoreCase = true)) {
                return UbuntuStorageUrl.rewrite(fileName).orEmpty()
            }
            var normalizedPath = normalizeStoragePath(fileName)
                ?.removePrefix("${bucketName.trim()}/")
                ?: return ""
            return localFallbackApiClient.publicObjectUrl(bucketName, normalizedPath)
        }
        var baseUrl = config.supabaseUrl.removeSuffix("/")
        var normalizedPath = normalizeStoragePath(fileName)
            ?.removePrefix("${bucketName.trim()}/")
            ?: return ""
        // Join segments manually for simple GET URL
        val encodedFileName = normalizedPath
            .split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$baseUrl/storage/v1/object/public/$bucketName/$encodedFileName"
    }

    suspend fun createSignedUrl(bucketName: String, fileName: String, expiresIn: Int = 3600): Result<String> {
        return try {
            val localStorageMarker = listOf(
                "/storage/v1/object/public/${bucketName.trim()}/",
                "/storage/v1/object/sign/${bucketName.trim()}/"
            ).firstOrNull { fileName.contains(it, ignoreCase = true) }
            val pathCandidate = localStorageMarker
                ?.let { marker -> fileName.substringAfter(marker, "").substringBefore('?').substringBefore('#') }
                ?: fileName
            var normalizedPath = normalizeStoragePath(pathCandidate)
                ?: return Result.failure(Exception("Invalid storage path"))
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val accessToken = authService.getAccessTokenAwait()
                    ?: return Result.failure(Exception("No access token available"))
                val encodedBucket = java.net.URLEncoder.encode(bucketName.trim(), "UTF-8").replace("+", "%20")
                val encodedPath = normalizedPath.split("/")
                    .filter(String::isNotBlank)
                    .joinToString("/") {
                        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                val response = config.httpClient.post(
                    "${UbuntuStorageUrl.baseUrl.trimEnd('/')}/storage/v1/object/sign/$encodedBucket/$encodedPath"
                ) {
                    header(HttpHeaders.Authorization, "Bearer ${accessToken.trim()}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("expiresIn", expiresIn) })
                }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    return Result.failure(
                        buildSupabaseHttpException(
                            statusCode = response.status.value,
                            responseBody = body,
                            fallbackMessage = "No se pudo generar el enlace privado del archivo."
                        )
                    )
                }
                val payload = Json.parseToJsonElement(body) as? JsonObject
                    ?: return Result.failure(Exception("Invalid signed URL response"))
                val signedUrl = payload["signedURL"]?.jsonPrimitive?.content
                    ?: payload["signedUrl"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No signedURL in response"))
                return Result.success(UbuntuStorageUrl.rewrite(signedUrl) ?: signedUrl)
            }
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            val signBody = mapOf("expiresIn" to expiresIn)
            
            var response = config.httpClient.post {
                url {
                    takeFrom(config.supabaseUrl)
                    appendPathSegments("storage", "v1", "object", "sign", bucketName)
                    normalizedPath.split("/").filter { it.isNotEmpty() }.forEach { appendPathSegments(it) }
                }
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(signBody)
            }

            if (response.status.isSuccess()) {
                val responseBody: Map<String, String> = response.body()
                val signedUrlPath = responseBody["signedURL"] ?: return Result.failure(Exception("No signedURL in response"))
                
                val finalUrl = if (signedUrlPath.startsWith("http")) {
                    signedUrlPath
                } else {
                    var baseUrl = config.supabaseUrl.removeSuffix("/")
                    // Clean the path and ensure it's not empty
                    val cleanPath = signedUrlPath.removePrefix("/")
                    
                    // Supabase sometimes returns paths without /storage/v1/ prefix.
                    // We must ensure the final URL has it.
                    val prefixedPath = if (cleanPath.startsWith("storage/v1/")) {
                        cleanPath
                    } else if (cleanPath.startsWith("object/")) {
                        "storage/v1/$cleanPath"
                    } else {
                        // Fallback: if it's just bucket/file, we still need the prefix
                        "storage/v1/object/sign/$cleanPath"
                    }
                    
                    "$baseUrl/$prefixedPath"
                }
                
                Result.success(finalUrl)
            } else {
                var errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                android.util.Log.e("StorageService", "Sign FAILED: ${response.status} - $errorBody")
                Result.failure(
                    buildSupabaseHttpException(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        fallbackMessage = "No se pudo generar el enlace del archivo."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeStoragePath(path: String): String? {
        val normalized = path.trim().replace("\\", "/")
        if (normalized.isBlank()) return null
        val segments = normalized
            .split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == "." || it == ".." || it.contains('\u0000') }) return null
        return segments.joinToString("/")
    }

    private fun normalizeContentType(value: String): String {
        val raw = value.substringBefore(";").trim().lowercase()
        return when (raw) {
            "image/jpg" -> "image/jpeg"
            "audio/m4a", "audio/x-m4a" -> "audio/mp4"
            else -> raw.ifBlank { "application/octet-stream" }
        }
    }

    private suspend fun registerMediaObject(
        accessToken: String,
        bucketName: String,
        objectPath: String,
        contentType: String,
        sizeBytes: Long,
        sha256: String
    ): Result<Unit> {
        return try {
            val payload = buildJsonObject {
                put("p_bucket_name", bucketName)
                put("p_object_path", objectPath)
                put("p_content_type", contentType)
                put("p_size_bytes", sizeBytes)
                put("p_sha256", sha256)
            }

            var response = config.httpClient.post("${config.supabaseUrl}/rest/v1/rpc/register_media_object") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", config.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                var errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                mapSupabaseServiceRestrictionMessage(errorBody)?.let { friendlyMessage ->
                    return Result.failure(Exception(friendlyMessage))
                }
                if (isMissingRegisterRpc(response.status.value, errorBody)) {
                    Result.success(Unit)
                } else {
                    val errorHint = sanitizeRpcError(errorBody)
                    Result.failure(Exception("media_policy_blocked:${response.status.value}:$errorHint"))
                }
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 16) hex.append('0')
            hex.append(value.toString(16))
        }
        return hex.toString()
    }

    private fun isMissingRegisterRpc(statusCode: Int, body: String): Boolean {
        if (statusCode !in setOf(400, 404)) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains("register_media_object") &&
            (
                cleanBody.contains("does not exist") ||
                cleanBody.contains("undefined function") ||
                cleanBody.contains("could not find the function") ||
                cleanBody.contains("no matches were found") ||
                cleanBody.contains("schema cache") ||
                cleanBody.contains("pgrst202")
            )
    }

    private fun sanitizeRpcError(body: String): String {
        if (body.isBlank()) return "unknown"
        val compact = body.replace("\\s+".toRegex(), " ").trim()
        val lower = compact.lowercase()
        if ((lower.contains("\"code\":\"42702\"") || lower.contains("42702")) &&
            lower.contains("window_start") &&
            lower.contains("ambiguous")
        ) {
            return "guard_rate_limit_window_start_ambiguous"
        }
        if (lower.contains("pgrst202") || lower.contains("schema cache")) {
            return "register_media_object_not_available"
        }
        return compact.take(220)
    }
}



