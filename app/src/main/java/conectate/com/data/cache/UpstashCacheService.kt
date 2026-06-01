package conectate.com.data.cache

import android.util.Log
import conectate.com.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpstashCacheService @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "UpstashCache"
        private val restUrl = BuildConfig.UPSTASH_REST_URL.trim().trimEnd('/')
        private val token = BuildConfig.UPSTASH_TOKEN.trim()
        private val isConfigured = restUrl.isNotBlank() && token.isNotBlank()
    }

    /**
     * Obtiene un valor de cache (si existe).
     * Retorna null si no encontrado o error.
     */

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        try {
            val response = httpClient.get("$restUrl/get") {
                header("Authorization", "Bearer $token")
                parameter("key", key)
            }
            if (response.status.isSuccess()) {
                val body = response.body<UpstashGetResponse>()
                if (body.result == "OK") {
                    return@withContext body.value
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache key=$key", e)
            null
        }
    }

    /**
     * Guarda un valor en cache con TTL en segundos.
     */

    suspend fun set(key: String, value: String, exSeconds: Int = 300): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false

        try {
            val response = httpClient.post("$restUrl/set") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.FormUrlEncoded)
                buildParameters {
                    append("key", key)
                    append("value", value)
                    append("EX", exSeconds.toString())
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting cache key=$key", e)
            false
        }
    }

    /**
     * Elimina una clave de cache.
     */

    suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false

        try {
            val response = httpClient.post("$restUrl/del") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.FormUrlEncoded)
                buildParameters { append("key", key) }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting cache key=$key", e)
            false
        }
    }

    /**
     * Incrementa un contador (para stats).
     */

    suspend fun increment(key: String, amount: Int = 1): Long? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        try {
            val response = httpClient.post("$restUrl/incrby") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.FormUrlEncoded)
                buildParameters {
                    append("key", key)
                    append("amount", amount.toString())
                }
            }
            if (response.status.isSuccess()) {
                val body = response.body<UpstashIncrResponse>()
                return@withContext body.result?.toLongOrNull()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error incrementing key=$key", e)
            null
        }
    }

    private fun HttpRequestBuilder.buildParameters(block: ParametersBuilder.() -> Unit) {
        val formParameters = ParametersBuilder()
        block(formParameters)
        setBody(formParameters.build().formUrlEncode())
    }

    private data class UpstashGetResponse(
        val result: String,
        val value: String?
    )

    private data class UpstashIncrResponse(
        val result: String?
    )
}





