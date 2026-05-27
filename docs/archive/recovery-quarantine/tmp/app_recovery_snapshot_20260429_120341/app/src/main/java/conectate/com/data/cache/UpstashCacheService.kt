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
        private const val UPSTASH_REST_URL = BuildConfig.UPSTASH_REST_URL // ej: https://red-social-123.upstash.io
        private const val UPSTASH_TOKEN = BuildConfig.UPSTASH_TOKEN
    }

    /**
     * Obtiene un valor de cache (si existe).
     * Retorna null si no encontrado o error.
     */
@Composable
@Composable
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        try {
            var response = httpClient.get("$UPSTASH_REST_URL/get") {
                header("Authorization", "Bearer $UPSTASH_TOKEN")
                parameter("key", key)
            }
            if (response.status.isSuccess()) {
                var body = response.body<UpstashGetResponse>()
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
@Composable
@Composable
    suspend fun set(key: String, value: String, exSeconds: Int = 300): Boolean = withContext(Dispatchers.IO) {
        try {
            var response = httpClient.post("$UPSTASH_REST_URL/set") {
                header("Authorization", "Bearer $UPSTASH_TOKEN")
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
@Composable
@Composable
    suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            var response = httpClient.post("$UPSTASH_REST_URL/del") {
                header("Authorization", "Bearer $UPSTASH_TOKEN")
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
@Composable
@Composable
    suspend fun increment(key: String, amount: Int = 1): Long? = withContext(Dispatchers.IO) {
        try {
            var response = httpClient.post("$UPSTASH_REST_URL/incrby") {
                header("Authorization", "Bearer $UPSTASH_TOKEN")
                contentType(ContentType.Application.FormUrlEncoded)
                buildParameters {
                    append("key", key)
                    append("amount", amount.toString())
                }
            }
            if (response.status.isSuccess()) {
                var body = response.body<UpstashIncrResponse>()
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

    private data class UpstashSetResponse(
        val result: String
    )

    private data class UpstashIncrResponse(
        val result: String?
    )
}





