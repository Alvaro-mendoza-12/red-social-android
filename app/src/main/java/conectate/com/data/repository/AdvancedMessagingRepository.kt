package conectate.com.data.repository

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatSummary
import conectate.com.data.model.ChatTranslationSettings
import conectate.com.data.model.Message
import conectate.com.data.model.ScheduledMessageJob
import conectate.com.data.sync.SyncManager
import conectate.com.data.sync.SyncWorker
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdvancedMessagingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository
) {
    @Serializable private data class FolderRow(val id: String, val name: String, val color_hex: String? = null)
    @Serializable private data class FolderLinkRow(val folder_id: String, val chat_id: String)
    @Serializable private data class SummaryRow(val chat_id: String, val summary: String, val source_message_count: Int = 0, val generated_at: String? = null)
    @Serializable private data class AutoDestructTask(val owner_id: String, val scope_type: String, val message_id: String, val execute_at_epoch_ms: Long, val retry_count: Int = 0)

    companion object {
        private const val PREFS = "advanced_messaging_prefs"
        private const val KEY_FOLDERS = "folders_json"
        private const val KEY_SCHEDULED_LOCAL = "scheduled_local_json"
        private const val KEY_AUTODESTRUCT = "autodestruct_json"
        private const val KEY_SUMMARY = "summary_json"
        // Production hardening: do not send user message content to public translation services.
        private const val SECURE_TRANSLATION_PROVIDER_ENABLED = false
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _folders = MutableStateFlow(loadFoldersLocal())
    val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()
    private val translationCache = linkedMapOf<String, ChatTranslationSettings>()

    fun isSecureTranslationAvailable(): Boolean = SECURE_TRANSLATION_PROVIDER_ENABLED



    suspend fun refreshFolders(): Result<List<ChatFolder>> = withContext(Dispatchers.IO) {
        var token = authService.getAccessToken() ?: return@withContext Result.success(_folders.value)
        runCatching {
            val fr = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id,name,color_hex")
            }
            if (!fr.status.isSuccess()) return@runCatching _folders.value
            var folders = runCatching { json.decodeFromString<List<FolderRow>>(fr.bodyAsText()) }.getOrDefault(emptyList())

            val lr = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "folder_id,chat_id")
            }
            val links = if (lr.status.isSuccess()) runCatching { json.decodeFromString<List<FolderLinkRow>>(lr.bodyAsText()) }.getOrDefault(emptyList()) else emptyList()
            val byFolder = links.groupBy { it.folder_id }.mapValues { (_, v) -> v.map { it.chat_id.trim().lowercase() }.distinct() }
            val mapped = folders.map { ChatFolder(id = it.id, name = it.name, color_hex = it.color_hex, chat_ids = byFolder[it.id].orEmpty()) }
            _folders.value = mapped
            prefs.edit().putString(KEY_FOLDERS, json.encodeToString(mapped)).apply()
            mapped
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudieron cargar carpetas")) }
        )
    }



    suspend fun createFolder(name: String, colorHex: String? = null): Result<ChatFolder> = withContext(Dispatchers.IO) {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(60)
        if (clean.isBlank()) return@withContext Result.failure(Exception("Nombre requerido"))

        val normalizedName = clean.lowercase(Locale.US)
        val alreadyExists = _folders.value.any { it.name.trim().lowercase(Locale.US) == normalizedName }
        if (alreadyExists) {
            return@withContext Result.failure(Exception("Ya existe una carpeta con ese nombre."))
        }

        val cleanColor = colorHex?.trim()?.ifBlank { null }
        var token = authService.getAccessToken()
        var userId = authService.getCurrentUserId()
            ?.trim()
            ?.lowercase()
            ?.ifBlank { null }
            ?: decodeUserIdFromAccessToken(token)
        runCatching {
            if (token.isNullOrBlank()) {
                val localFolder = ChatFolder(
                    id = "local-${UUID.randomUUID()}",
                    name = clean,
                    color_hex = cleanColor
                )
                var merged = (_folders.value + localFolder).distinctBy { it.id }
                _folders.value = merged
                persistFolders(merged)
                return@runCatching localFolder
            }
            if (userId.isNullOrBlank()) {
                throw Exception("No se pudo identificar el usuario actual. Vuelve a iniciar sesión.")
            }
            val resolvedUserId = userId

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json"); header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("name", clean)
                        put("user_id", resolvedUserId)
                        cleanColor?.let { put("color_hex", it) }
                    }
                )
            }

            if (!response.status.isSuccess()) {
                var body = runCatching { response.bodyAsText() }.getOrDefault("")
                val lowerBody = body.lowercase(Locale.US)
                if (
                    lowerBody.contains("duplicate key") ||
                    lowerBody.contains("23505") ||
                    lowerBody.contains("chat_folders_user_id_name_key")
                ) {
                    throw Exception("Ya existe una carpeta con ese nombre.")
                }
                if (response.status.value == 403 || lowerBody.contains("permission denied") || lowerBody.contains("policy")) {
                    val rpcFolder = tryCreateFolderViaRpc(token, clean, cleanColor)
                    if (rpcFolder != null) {
                        var merged = (_folders.value + rpcFolder).distinctBy { it.id }
                        _folders.value = merged
                        persistFolders(merged)
                        return@runCatching rpcFolder
                    }
                    throw Exception("No tienes permiso para crear carpetas en este momento.")
                }
                throw Exception("No se pudo crear carpeta (${response.status.value}).")
            }

            var row = runCatching { json.decodeFromString<List<FolderRow>>(response.bodyAsText()).firstOrNull() }.getOrNull()
            var folder = ChatFolder(
                id = row?.id ?: UUID.randomUUID().toString(),
                name = row?.name ?: clean,
                color_hex = row?.color_hex ?: cleanColor
            )
            var merged = (_folders.value + folder).distinctBy { it.id }
            _folders.value = merged
            persistFolders(merged)
            folder
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo crear carpeta")) }
        )
    }

    private fun decodeUserIdFromAccessToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return runCatching {
            var payload = token.split('.').getOrNull(1)?.trim().orEmpty()
            if (payload.isBlank()) return@runCatching null
            val decoded = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            json.parseToJsonElement(decoded)
                .jsonObject["sub"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
        }.getOrNull()
    }

    private suspend fun tryCreateFolderViaRpc(
        token: String,
        folderName: String,
        colorHex: String?
    ): ChatFolder? {
        val rpcNames = listOf("secure_create_chat_folder", "secure_create_folder")
        rpcNames.forEach { rpcName ->
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/$rpcName") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("p_name", folderName)
                        colorHex?.let { put("p_color_hex", it) }
                    }
                )
            }
            if (response.status.isSuccess()) {
                var body = runCatching { response.bodyAsText() }.getOrDefault("")
                parseFolderFromBody(body, folderName, colorHex)?.let { return it }
                return ChatFolder(
                    id = UUID.randomUUID().toString(),
                    name = folderName,
                    color_hex = colorHex
                )
            }
            var body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(response.status.value, body, rpcName)) {
                android.util.Log.w(
                    "AdvancedMessagingRepository",
                    "RPC $rpcName failed (${response.status.value}): $body"
                )
            }
        }
        return null
    }

    private fun parseFolderFromBody(
        body: String,
        fallbackName: String,
        fallbackColor: String?
    ): ChatFolder? {
        if (body.isBlank()) return null
        var fromList = runCatching {
            json.decodeFromString<List<FolderRow>>(body).firstOrNull()
        }.getOrNull()
        val fromObject = if (fromList == null) {
            runCatching { json.decodeFromString<FolderRow>(body) }.getOrNull()
        } else {
            null
        }
        var row = fromList ?: fromObject ?: return null
        return ChatFolder(
            id = row.id.ifBlank { UUID.randomUUID().toString() },
            name = row.name.ifBlank { fallbackName },
            color_hex = row.color_hex ?: fallbackColor
        )
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val normalized = body.lowercase(Locale.US)
        return normalized.contains("pgrst202") ||
            normalized.contains("could not find the function") ||
            (normalized.contains(functionName.lowercase(Locale.US)) && normalized.contains("does not exist"))
    }



    suspend fun addChatToFolder(folderId: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanFolder = folderId.trim()
        var cleanChat = chatId.trim().lowercase()
        if (cleanFolder.isBlank() || cleanChat.isBlank()) return@withContext Result.failure(Exception("Datos inválidos"))
        runCatching {
            _folders.value = _folders.value.map {
                if (it.id == cleanFolder) it.copy(chat_ids = (it.chat_ids + cleanChat).distinct()) else it
            }
            persistFolders(_folders.value)

            var token = authService.getAccessToken()
            if (token.isNullOrBlank()) return@runCatching

            var r = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json"); header("Prefer", "resolution=merge-duplicates,return=minimal")
                parameter("on_conflict", "folder_id,chat_id")
                setBody(buildJsonObject { put("folder_id", cleanFolder); put("chat_id", cleanChat) })
            }
            if (!r.status.isSuccess()) {
                android.util.Log.w("AdvancedMessagingRepository", "addChatToFolder remote sync failed: ${r.status}")
            }
        }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo asignar carpeta")) })
    }



    suspend fun removeChatFromFolder(folderId: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanFolder = folderId.trim()
        var cleanChat = chatId.trim().lowercase()
        if (cleanFolder.isBlank() || cleanChat.isBlank()) return@withContext Result.failure(Exception("Datos inválidos"))
        runCatching {
            _folders.value = _folders.value.map { if (it.id == cleanFolder) it.copy(chat_ids = it.chat_ids.filterNot { id -> id == cleanChat }) else it }
            persistFolders(_folders.value)

            var token = authService.getAccessToken()
            if (token.isNullOrBlank()) return@runCatching

            var r = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("folder_id", "eq.$cleanFolder"); parameter("chat_id", "eq.$cleanChat")
            }
            if (!r.status.isSuccess()) {
                android.util.Log.w("AdvancedMessagingRepository", "removeChatFromFolder remote sync failed: ${r.status}")
            }
        }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo quitar carpeta")) })
    }



    suspend fun deleteFolder(folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanFolder = folderId.trim()
        if (cleanFolder.isBlank()) return@withContext Result.failure(Exception("Carpeta inválida"))

        val previousFolders = _folders.value
        runCatching {
            _folders.value = previousFolders.filterNot { it.id == cleanFolder }
            persistFolders(_folders.value)

            var token = authService.getAccessToken()
            if (token.isNullOrBlank() || cleanFolder.startsWith("local-")) return@runCatching

            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$cleanFolder")
            }

            if (!response.status.isSuccess() && response.status.value != 404) {
                var body = runCatching { response.bodyAsText() }.getOrDefault("")
                throw Exception("No se pudo eliminar carpeta (${response.status.value}). $body")
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                _folders.value = previousFolders
                persistFolders(previousFolders)
                Result.failure(it as? Exception ?: Exception("No se pudo eliminar carpeta"))
            }
        )
    }



    suspend fun setChatTranslation(chatId: String, enabled: Boolean, targetLang: String?): Result<ChatTranslationSettings> = withContext(Dispatchers.IO) {
        var cleanChat = chatId.trim().lowercase()
        var normalizedTarget = normalizeLanguageCode(targetLang)
        if (enabled && !SECURE_TRANSLATION_PROVIDER_ENABLED) {
            return@withContext Result.failure(
                IllegalStateException("La traducción automática no está disponible hasta configurar un proveedor privado y seguro.")
            )
        }
        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        runCatching {
            var payload = buildJsonObject {
                put("p_chat_id", cleanChat)
                put("p_enabled", enabled)
                if (normalizedTarget == null) {
                    put("p_target_lang", JsonNull)
                } else {
                    put("p_target_lang", normalizedTarget)
                }
            }
            var r = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_set_chat_translation_settings") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey); header("Content-Type", "application/json")
                setBody(payload)
            }
            if (!r.status.isSuccess()) throw IllegalStateException("No se pudo guardar traducción")
            var setting = runCatching { json.decodeFromString<List<ChatTranslationSettings>>(r.bodyAsText()).firstOrNull() }.getOrNull()
                ?: ChatTranslationSettings(chat_id = cleanChat, enabled = enabled, target_lang = normalizedTarget)
            translationCache[cleanChat] = setting
            setting
        }.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo guardar traducción")) })
    }



    suspend fun getChatTranslation(chatId: String): ChatTranslationSettings? = withContext(Dispatchers.IO) {
        var cleanChat = chatId.trim().lowercase()
        if (cleanChat.isBlank()) return@withContext null
        translationCache[cleanChat]?.let { return@withContext it }

        var token = authService.getAccessToken() ?: return@withContext null
        runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_translation_settings") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "chat_id,enabled,target_lang,updated_at")
                parameter("chat_id", "eq.$cleanChat")
                parameter("limit", "1")
            }
            if (!response.status.isSuccess()) return@runCatching null
            val parsed = runCatching { json.decodeFromString<List<ChatTranslationSettings>>(response.bodyAsText()).firstOrNull() }.getOrNull()
            if (parsed != null) {
                translationCache[cleanChat] = parsed
            }
            parsed
        }.getOrNull()
    }



    suspend fun applyAutoTranslation(chatId: String, text: String): String = withContext(Dispatchers.IO) {
        var cleanText = text.trim()
        if (cleanText.isBlank()) return@withContext cleanText
        if (!SECURE_TRANSLATION_PROVIDER_ENABLED) return@withContext cleanText
        var cleanChat = chatId.trim().lowercase()
        var setting = translationCache[cleanChat]
        var targetLang = normalizeLanguageCode(setting?.target_lang)
        if (setting == null || !setting.enabled || targetLang == null) return@withContext cleanText
        var sourceLang = resolveSourceLanguageCode()
        if (sourceLang == targetLang) return@withContext cleanText
        runCatching {
            var r = supabaseConfig.httpClient.get("https://api.mymemory.translated.net/get") {
                parameter("q", cleanText.take(1200))
                parameter("langpair", "$sourceLang|$targetLang")
            }
            if (!r.status.isSuccess()) return@runCatching cleanText
            parseTranslationResponse(
                body = r.bodyAsText(),
                originalText = cleanText
            )
        }.getOrDefault(cleanText)
    }



    private suspend fun translateToLanguage(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        var cleanText = text.trim()
        var cleanLang = normalizeLanguageCode(targetLang)
        if (cleanText.isBlank() || cleanLang == null) return@withContext cleanText
        if (!SECURE_TRANSLATION_PROVIDER_ENABLED) return@withContext cleanText
        var sourceLang = resolveSourceLanguageCode()
        if (sourceLang == cleanLang) return@withContext cleanText
        runCatching {
            var r = supabaseConfig.httpClient.get("https://api.mymemory.translated.net/get") {
                parameter("q", cleanText.take(1200))
                parameter("langpair", "$sourceLang|$cleanLang")
            }
            if (!r.status.isSuccess()) return@runCatching cleanText
            parseTranslationResponse(
                body = r.bodyAsText(),
                originalText = cleanText
            )
        }.getOrDefault(cleanText)
    }



    suspend fun scheduleMessageJob(scopeType: String, targetId: String, content: String, contentType: String = "text", mediaUrl: String? = null, scheduledForEpochMs: Long, translateTo: String? = null, selfDestructSeconds: Int? = null): Result<ScheduledMessageJob> = withContext(Dispatchers.IO) {
        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        val scheduledFor = Instant.ofEpochMilli(scheduledForEpochMs.coerceAtLeast(System.currentTimeMillis() + 30_000L)).toString()
        var cleanTranslateTo = normalizeLanguageCode(translateTo)
        if (cleanTranslateTo != null && !SECURE_TRANSLATION_PROVIDER_ENABLED) {
            return@withContext Result.failure(
                IllegalStateException("La traducción programada está deshabilitada hasta configurar un proveedor privado y seguro.")
            )
        }
        runCatching {
            var payload = buildJsonObject {
                put("p_scope_type", scopeType.trim().lowercase()); put("p_target_id", targetId.trim().lowercase())
                put("p_content", content.trim().take(4000)); put("p_content_type", contentType.ifBlank { "text" })
                var cleanMediaUrl = mediaUrl?.trim()?.ifBlank { null }
                if (cleanMediaUrl == null) put("p_media_url", JsonNull) else put("p_media_url", cleanMediaUrl)
                put("p_scheduled_for", scheduledFor)
                if (cleanTranslateTo == null) put("p_translate_to", JsonNull) else put("p_translate_to", cleanTranslateTo)
                if (selfDestructSeconds == null) put("p_self_destruct_seconds", JsonNull) else put("p_self_destruct_seconds", selfDestructSeconds)
            }
            var r = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_schedule_message_job") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey); header("Content-Type", "application/json")
                setBody(payload)
            }
            val scheduled = if (r.status.isSuccess()) {
                var body = r.bodyAsText()
                runCatching { json.decodeFromString<List<ScheduledMessageJob>>(body).firstOrNull() }.getOrNull()
                    ?: runCatching { json.decodeFromString<ScheduledMessageJob>(body) }.getOrNull()
                    ?: ScheduledMessageJob(
                        id = UUID.randomUUID().toString(),
                        scope_type = scopeType,
                        target_id = targetId,
                        content = content,
                        content_type = contentType,
                        media_url = mediaUrl,
                        translate_to = cleanTranslateTo,
                        self_destruct_seconds = selfDestructSeconds,
                        scheduled_for = scheduledFor,
                        status = "pending"
                    )
            } else {
                val local = ScheduledMessageJob(
                    id = "local-${UUID.randomUUID()}",
                    scope_type = scopeType,
                    target_id = targetId,
                    content = content,
                    content_type = contentType,
                    media_url = mediaUrl,
                    translate_to = cleanTranslateTo,
                    self_destruct_seconds = selfDestructSeconds,
                    scheduled_for = scheduledFor,
                    status = "pending"
                )
                local
            }
            persistLocalScheduled((loadLocalScheduled() + scheduled).distinctBy { it.id })
            enqueueScheduledSyncJob(scheduled.id, parseIso(scheduled.scheduled_for))
            scheduled
        }.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo programar")) })
    }


    suspend fun getScheduledJobs(scopeType: String, targetId: String): Result<List<ScheduledMessageJob>> = withContext(Dispatchers.IO) {
        val cleanScope = scopeType.trim().lowercase(Locale.US)
        val cleanTarget = targetId.trim().lowercase(Locale.US)
        if (cleanScope.isBlank() || cleanTarget.isBlank()) return@withContext Result.success(emptyList())
        runCatching {
            loadLocalScheduled()
                .filter {
                    it.scope_type.trim().lowercase(Locale.US) == cleanScope &&
                        it.target_id.trim().lowercase(Locale.US) == cleanTarget &&
                        it.status.trim().lowercase(Locale.US) in setOf("pending", "failed")
                }
                .sortedBy { parseIso(it.scheduled_for) }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudieron cargar mensajes programados")) }
        )
    }


    suspend fun cancelScheduledJob(jobId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = jobId.trim()
        if (cleanId.isBlank()) return@withContext Result.failure(Exception("Mensaje programado inválido"))
        runCatching {
            persistLocalScheduled(loadLocalScheduled().filterNot { it.id == cleanId })
            if (!cleanId.startsWith("local-", ignoreCase = true)) {
                patchRemoteScheduledStatus(cleanId, "cancelled", null)
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo cancelar mensaje programado")) }
        )
    }



    suspend fun processDueScheduledJobs(maxBatch: Int = 20): Result<Int> = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val localDue = loadLocalScheduled()
            .filter { it.status == "pending" && parseIso(it.scheduled_for) <= nowMs }
            .take(maxBatch)
        val remoteDue = loadRemoteDueScheduled(maxBatch)
        var due = (localDue + remoteDue).distinctBy { it.id }.take(maxBatch)
        if (due.isEmpty()) return@withContext Result.success(0)
        var sent = 0
        var keep = loadLocalScheduled().toMutableList()
        due.forEach { job ->
            val translated = if (!job.translate_to.isNullOrBlank()) {
                translateToLanguage(job.content, job.translate_to.orEmpty())
            } else {
                applyAutoTranslation(job.target_id, job.content)
            }
            val mid = UUID.randomUUID().toString().lowercase()
            val result = when (job.scope_type.trim().lowercase()) {
                "dm" -> messageRepository.sendMessage(chatId = job.target_id, content = translated, mediaType = job.content_type, existingMediaUrl = job.media_url, messageId = mid).map { it.id }
                "group" -> groupRepository.sendGroupMessage(groupId = job.target_id, content = translated, contentType = job.content_type, mediaUrl = job.media_url).map { null }
                "community" -> communityRepository.sendCommunityMessage(communityId = job.target_id, content = translated, contentType = job.content_type, mediaUrl = job.media_url).map { null }
                else -> Result.failure(Exception("scope inválido"))
            }
            keep.removeAll { it.id == job.id }
            if (result.isSuccess) {
                sent += 1
                val sentMessageId = result.getOrNull()
                if (!sentMessageId.isNullOrBlank() && (job.self_destruct_seconds ?: 0) > 0) {
                    val owner = authService.getCurrentUserId()?.trim()?.lowercase() ?: ""
                    if (owner.isNotBlank()) persistAutoDestruct(loadAutoDestruct() + AutoDestructTask(owner, job.scope_type, sentMessageId, System.currentTimeMillis() + ((job.self_destruct_seconds ?: 0) * 1000L)))
                }
                if (!job.id.startsWith("local-")) {
                    patchRemoteScheduledStatus(job.id, "sent", null)
                }
            } else {
                keep.add(job.copy(status = "failed", error_message = result.exceptionOrNull()?.message?.take(200)))
                if (!job.id.startsWith("local-")) {
                    patchRemoteScheduledStatus(job.id, "failed", result.exceptionOrNull()?.message?.take(200))
                }
            }
        }
        persistLocalScheduled(keep)
        Result.success(sent)
    }



    suspend fun processDueAutoDestructTasks(maxBatch: Int = 30): Result<Int> = withContext(Dispatchers.IO) {
        val uid = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext Result.success(0)
        val all = loadAutoDestruct()
        var due = all.filter { it.owner_id == uid && it.execute_at_epoch_ms <= System.currentTimeMillis() }.take(maxBatch)
        if (due.isEmpty()) return@withContext Result.success(0)
        var keep = all.toMutableList()
        var deleted = 0
        due.forEach { task ->
            val res = when (task.scope_type.trim().lowercase()) {
                "dm" -> messageRepository.deleteMessage(task.message_id, true)
                "group" -> groupRepository.deleteGroupMessage(task.message_id, true)
                "community" -> communityRepository.deleteCommunityMessage(task.message_id, true)
                else -> Result.failure(Exception("scope inválido"))
            }
            keep.remove(task)
            if (res.isSuccess) deleted += 1 else if (task.retry_count < 5) keep.add(task.copy(retry_count = task.retry_count + 1, execute_at_epoch_ms = System.currentTimeMillis() + 60_000L))
        }
        persistAutoDestruct(keep)
        Result.success(deleted)
    }



    suspend fun summarizeChat(chatId: String, messages: List<Message>, expectedParticipants: Int? = null): Result<ChatSummary> = withContext(Dispatchers.Default) {
        var cleanChat = chatId.trim().lowercase()
        if (cleanChat.isBlank()) return@withContext Result.failure(Exception("chat inválido"))
        val visible = messages.filterNot { it.deleted_for_all }.takeLast(120)
        val textCount = visible.count { it.content_type == "text" }
        val mediaCount = visible.count { it.content_type != "text" }
        val peopleObserved = visible.map { it.sender_id.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size
        val people = expectedParticipants?.coerceAtLeast(1) ?: peopleObserved.coerceAtLeast(1)
        val summaryText = "Mensajes recientes: ${visible.size}. Texto: $textCount. Multimedia: $mediaCount. Participantes activos: ${people.coerceAtLeast(1)}."
        val summary = ChatSummary(chat_id = cleanChat, summary = summaryText, source_message_count = visible.size, generated_at = Instant.now().toString())
        val map = loadSummaryLocal().toMutableMap(); map[cleanChat] = summary
        prefs.edit().putString(KEY_SUMMARY, json.encodeToString(map.values.toList())).apply()
        runCatching { upsertSummaryRemote(summary) }
        Result.success(summary)
    }



    suspend fun getCachedSummary(chatId: String): ChatSummary? = withContext(Dispatchers.IO) { loadSummaryLocal()[chatId.trim().lowercase()] }

    private suspend fun upsertSummaryRemote(summary: ChatSummary) {
        val token = authService.getAccessToken() ?: return
        runCatching {
            supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_summary_cache") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json"); header("Prefer", "resolution=merge-duplicates,return=minimal")
                parameter("on_conflict", "chat_id")
                setBody(buildJsonObject {
                    put("chat_id", summary.chat_id)
                    put("summary", summary.summary.take(1200))
                    put("source_message_count", summary.source_message_count)
                    put("generated_at", summary.generated_at ?: Instant.now().toString())
                })
            }
        }
    }

    private suspend fun loadRemoteDueScheduled(maxBatch: Int): List<ScheduledMessageJob> {
        var token = authService.getAccessToken() ?: return emptyList()
        return runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/scheduled_message_jobs") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id,scope_type,target_id,content,content_type,media_url,translate_to,self_destruct_seconds,scheduled_for,status,error_message,created_at,updated_at")
                parameter("status", "eq.pending")
                parameter("scheduled_for", "lte.${Instant.now()}")
                parameter("order", "scheduled_for.asc")
                parameter("limit", maxBatch.coerceIn(1, 100))
            }
            if (!response.status.isSuccess()) return@runCatching emptyList()
            runCatching { json.decodeFromString<List<ScheduledMessageJob>>(response.bodyAsText()) }.getOrDefault(emptyList())
        }.getOrDefault(emptyList())
    }

    private suspend fun patchRemoteScheduledStatus(jobId: String, status: String, error: String?) {
        val token = authService.getAccessToken() ?: return
        runCatching {
            supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/scheduled_message_jobs") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$jobId")
                setBody(
                    buildJsonObject {
                        put("status", status)
                        if (error.isNullOrBlank()) {
                            put("error_message", JsonNull)
                        } else {
                            put("error_message", error.take(200))
                        }
                        put("updated_at", Instant.now().toString())
                    }
                )
            }
        }
    }

    private fun persistFolders(items: List<ChatFolder>) {
        prefs.edit().putString(KEY_FOLDERS, json.encodeToString(items)).apply()
    }

    private fun normalizeLanguageCode(raw: String?): String? {
        var cleaned = raw
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            ?.ifBlank { null }
            ?: return null

        if (cleaned == "auto") return null
        val valid = cleaned.matches(Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$"))
        if (!valid) return null
        return cleaned
    }

    private fun resolveSourceLanguageCode(): String {
        val localeTag = Locale.getDefault().toLanguageTag().lowercase(Locale.US).replace('_', '-')
        return normalizeLanguageCode(localeTag)
            ?: normalizeLanguageCode(Locale.getDefault().language)
            ?: "es"
    }

    private fun parseTranslationResponse(body: String, originalText: String): String {
        if (body.isBlank()) return originalText
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val status = root["responseStatus"]?.jsonPrimitive?.intOrNull ?: 200
            val details = root["responseDetails"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val translatedText = root["responseData"]
                ?.jsonObject
                ?.get("translatedText")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()

            val detailsLower = details.lowercase(Locale.US)
            val translatedLower = translatedText.lowercase(Locale.US)
            val invalidPayload =
                status !in 200..299 ||
                    detailsLower.contains("invalid source language") ||
                    detailsLower.contains("langpair") ||
                    translatedLower.contains("invalid source language")

            if (invalidPayload || translatedText.isBlank()) {
                originalText
            } else {
                translatedText
            }
        }.getOrDefault(originalText)
    }

    private fun enqueueScheduledSyncJob(jobId: String, executeAtEpochMs: Long) {
        runCatching {
            val delayMs = (executeAtEpochMs - System.currentTimeMillis()).coerceAtLeast(5_000L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        SyncManager.SYNC_REASON_KEY to "scheduled_message_due",
                        "scheduled_job_id" to jobId
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    private fun loadFoldersLocal(): List<ChatFolder> {
        var raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ChatFolder>>(raw) }.getOrDefault(emptyList())
    }
    private fun loadLocalScheduled(): List<ScheduledMessageJob> {
        var raw = prefs.getString(KEY_SCHEDULED_LOCAL, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ScheduledMessageJob>>(raw) }.getOrDefault(emptyList())
    }
    private fun persistLocalScheduled(items: List<ScheduledMessageJob>) { prefs.edit().putString(KEY_SCHEDULED_LOCAL, json.encodeToString(items)).apply() }
    private fun loadAutoDestruct(): List<AutoDestructTask> {
        var raw = prefs.getString(KEY_AUTODESTRUCT, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AutoDestructTask>>(raw) }.getOrDefault(emptyList())
    }
    private fun persistAutoDestruct(items: List<AutoDestructTask>) { prefs.edit().putString(KEY_AUTODESTRUCT, json.encodeToString(items)).apply() }
    private fun loadSummaryLocal(): Map<String, ChatSummary> {
        var raw = prefs.getString(KEY_SUMMARY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<List<SummaryRow>>(raw).associate { it.chat_id to ChatSummary(it.chat_id, it.summary, it.source_message_count, it.generated_at) } }.getOrDefault(emptyMap())
    }
    private fun parseIso(iso: String?): Long = runCatching { Instant.parse(iso ?: "").toEpochMilli() }.getOrDefault(0L)
}










