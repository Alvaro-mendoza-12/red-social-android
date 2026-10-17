package conectate.com.data.community

import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.supabase.StorageService
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class QuestionAccess(val canCreate: Boolean = false, val isOwner: Boolean = false, val onlyCreator: Boolean = false, val canRespond: Boolean = false)
@Serializable data class CommunityAnswer(val id: String, val userId: String, val username: String = "", val name: String = "Usuario", val content: String, val createdAt: String, val likes: Int = 0, val dislikes: Int = 0, val myVote: Int = 0)
@Serializable data class CommunityQuestion(val id: String, val prompt: String, val maxLength: Int = 40, val closed: Boolean = false, val canRespond: Boolean = false, val total: Int = 0, val answers: List<CommunityAnswer> = emptyList(), val nextOffset: Int? = null, val myAnswer: CommunityAnswer? = null)
@Serializable data class PromotionCard(val title: String = "", val description: String = "", val imageUrl: String = "", val destination: String = "/app/messages", val cta: String = "Abrir")
@Serializable data class SocialPromotion(val id: String, val title: String, val description: String = "", val imageUrl: String = "", val destination: String, val cta: String = "Abrir", val active: Boolean = true, val cards: List<PromotionCard> = emptyList())
@Serializable data class SocialPromotions(val items: List<SocialPromotion> = emptyList(), val canManage: Boolean = false)

fun questionApiError(code: String) = when(code) {
    "QUESTION_OWNER_REQUIRED", "ONLY_CREATOR_CAN_TALK" -> "Solo el creador puede publicar aquí."
    "QUESTION_TEXT_LIMIT", "QUESTION_ANSWER_LIMIT" -> "La respuesta supera el límite de caracteres de esta pregunta."
    "QUESTION_CLOSED" -> "Esta pregunta está cerrada."
    "QUESTION_ACCESS_DENIED", "QUESTION_NOT_FOUND" -> "Esta pregunta ya no está disponible para tu cuenta."
    "QUESTION_CREATE_LIMIT" -> "Has publicado muchas preguntas. Inténtalo más tarde."
    else -> "No se pudo completar la acción. Inténtalo de nuevo."
}
@Singleton
class CommunityEngagementRepository @Inject constructor(private val config: SupabaseConfig, private val auth: AuthService, private val storage: StorageService, @ApplicationContext private val context: Context) {
    private fun base(kind: String, roomId: String): String {
        require(kind in setOf("group", "community")); require(roomId.matches(Regex("[a-fA-F0-9-]{36}")))
        return "/v1/data/questions/$kind/$roomId"
    }
    suspend fun access(kind: String, roomId: String) = request(base(kind,roomId)+"/access").map { config.json.decodeFromJsonElement<QuestionAccess>(it) }
    suspend fun question(kind: String, roomId: String, id: String, offset: Int = 0) = request(base(kind,roomId)+"/$id?offset=$offset").map { config.json.decodeFromJsonElement<CommunityQuestion>(it) }
    suspend fun create(kind: String, roomId: String, prompt: String, maxLength: Int) = request(base(kind,roomId), buildJsonObject { put("prompt",prompt); put("maxLength",maxLength) })
    suspend fun policy(kind: String, roomId: String, onlyCreator: Boolean) = request(base(kind,roomId)+"/policy",buildJsonObject { put("onlyCreator",onlyCreator) },true)
    suspend fun answer(kind: String, roomId: String, id: String, content: String) = request(base(kind,roomId)+"/$id/answers",buildJsonObject { put("content",content) })
    suspend fun vote(kind: String, roomId: String, id: String, answerId: String, value: Int) = request(base(kind,roomId)+"/$id/votes",buildJsonObject { put("answerId",answerId);put("value",value) })
    suspend fun promotions(manage: Boolean = false) = request("/v1/data/social/promotions" + if(manage) "?manage=1" else "").map { config.json.decodeFromJsonElement<SocialPromotions>(it) }
    suspend fun savePromotion(id: String?, title: String, description: String, cards: List<PromotionCard>) = request("/v1/data/social/promotions"+(id?.let{"/$it"} ?: ""),buildJsonObject {
        put("title",title);put("description",description);put("destination",cards.firstOrNull()?.destination ?: "/app/messages");put("cta",cards.firstOrNull()?.cta ?: "Abrir")
        put("cards",config.json.encodeToJsonElement(cards))
    },id!=null)
    suspend fun activatePromotion(id: String, active: Boolean) = request("/v1/data/social/promotions/$id",buildJsonObject{put("active",active)},true)
    suspend fun uploadPromotionImage(uri: Uri, accountId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mime=context.contentResolver.getType(uri).orEmpty()
            require(mime in setOf("image/jpeg","image/png","image/webp")){"Elige una imagen JPG, PNG o WebP."}
            val size=context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst())it.getLong(0)else 0L} ?: 0L
            require(size in 1..(16L*1024*1024)){"La imagen debe pesar hasta 16 MB."}
            val extension=when(mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
            val name="$accountId/promotions/${java.util.UUID.randomUUID()}.$extension"
            val uploaded=storage.uploadFileFromUri("post-media",name,uri,size,mime,false,{}).getOrThrow()
            val key=uploaded.Key?.removePrefix("post-media/")?.takeIf{it.isNotBlank()} ?: error("El servidor no devolvió la imagen subida.")
            Result.success(storage.getPublicUrl("post-media",key))
        }catch(cancelled: CancellationException){throw cancelled}catch(error: Exception){Result.failure(error)}
    }
    private suspend fun request(path: String, payload: JsonObject? = null, patch: Boolean = false): Result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val token=auth.ensureValidAccessToken().getOrThrow()
            val endpoint=RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/') ?: error("Servidor no disponible")
            val response=config.httpClient.request(endpoint+path) {
                method=if(payload==null) HttpMethod.Get else if(patch) HttpMethod.Patch else HttpMethod.Post
                header(HttpHeaders.Authorization,"Bearer ${token.trim()}");header("ngrok-skip-browser-warning","1")
                if(payload!=null){contentType(ContentType.Application.Json);setBody(payload)}
            }
            val parsed=config.json.parseToJsonElement(response.bodyAsText()).jsonObject
            if(!response.status.isSuccess()) error(questionApiError(parsed["error"]?.jsonPrimitive?.contentOrNull.orEmpty()))
            Result.success(parsed)
        } catch(error: CancellationException){throw error}
        catch(error: Exception){Result.failure(error)}
    }
}
