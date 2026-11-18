package conectate.com.data.groupvoice

import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GroupVoiceMember(val id: String, val name: String, val avatarUrl: String = "")
@Serializable
data class GroupVoiceRoster(val groupId: String, val groupName: String, val members: List<GroupVoiceMember>)
@Serializable
data class GroupVoiceIntent(val requestId: String, val mode: String, val recipientIds: List<String>)
@Serializable
data class GroupVoiceCreated(val roomId: String, val invitedCount: Int, val reused: Boolean = false)
class GroupVoiceApiException(val code: String, val status: Int) : IllegalStateException(code)

@Singleton
class GroupVoiceApi @Inject constructor(private val config: SupabaseConfig) {
    suspend fun roster(token: String, groupId: String): GroupVoiceRoster = request(token, groupId, null)
    suspend fun create(token: String, groupId: String, intent: GroupVoiceIntent): GroupVoiceCreated = request(token, groupId, intent)

    private suspend inline fun <reified T> request(token: String, groupId: String, intent: GroupVoiceIntent?): T {
        val base = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/') ?: error("El servidor no está disponible. Reintenta.")
        val group = URLEncoder.encode(groupId, "UTF-8")
        val path = "$base/v1/data/messages/groups/$group/" + if (intent == null) "voice-room-invitees" else "voice-rooms"
        val response = if (intent == null) config.httpClient.get(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("ngrok-skip-browser-warning", "1")
        } else config.httpClient.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("ngrok-skip-browser-warning", "1")
            contentType(ContentType.Application.Json)
            setBody(config.json.encodeToString(GroupVoiceIntent.serializer(), intent))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val code = runCatching { config.json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw GroupVoiceApiException(code ?: "GROUP_VOICE_UNAVAILABLE", response.status.value)
        }
        return config.json.decodeFromString<T>(body)
    }
}
