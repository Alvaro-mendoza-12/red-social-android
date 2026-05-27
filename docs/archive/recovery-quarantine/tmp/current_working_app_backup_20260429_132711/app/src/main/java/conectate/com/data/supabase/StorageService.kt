package conectate.com.data.supabase

import conectate.com.core.media.MediaUploadGuard
import conectate.com.core.observability.AppMetrics
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

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
    private val mediaUploadGuard: MediaUploadGuard,
    private val appMetrics: AppMetrics
) {

    suspend fun uploadFile(
        bucketName: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
        allowOverwrite: Boolean = true
    ): Result<StorageResponse> {
        val startedAt = System.currentTimeMillis()
        var normalizedPath = normalizeStoragePath(fileName)
            ?: return Result.failure(Exception("Invalid storage path"))
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

            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

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

    suspend fun downloadFile(bucketName: String, fileName: String): Result<ByteArray> {
        return try {
            var normalizedPath = normalizeStoragePath(fileName)
                ?: return Result.failure(Exception("Invalid storage path"))
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

    suspend fun listFiles(bucketName: String, path: String = ""): Result<List<String>> {
        return try {
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

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
        var baseUrl = config.supabaseUrl.removeSuffix("/")
        var normalizedPath = normalizeStoragePath(fileName) ?: return ""
        // Join segments manually for simple GET URL
        val encodedFileName = normalizedPath
            .split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$baseUrl/storage/v1/object/public/$bucketName/$encodedFileName"
    }

    suspend fun createSignedUrl(bucketName: String, fileName: String, expiresIn: Int = 3600): Result<String> {
        return try {
            var normalizedPath = normalizeStoragePath(fileName)
                ?: return Result.failure(Exception("Invalid storage path"))
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
                
                android.util.Log.e("StorageService", "Sign SUCCESS raw: $signedUrlPath")
                
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
                
                android.util.Log.e("StorageService", "Sign SUCCESS final: $finalUrl")
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
        val raw = value.substringBefore("").trim().lowercase()
        return when (raw) {
            "image/jpg" -> "image/jpeg"
            "audio/m4a", "audio/x-m4a" -> "audio/mp4"
            else -> raw
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



