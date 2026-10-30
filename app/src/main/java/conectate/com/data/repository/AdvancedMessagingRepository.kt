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
import conectate.com.data.repository.message.ScheduledMessageStore
import conectate.com.data.repository.message.ChatFolderStore
import conectate.com.data.repository.message.ScheduledMessagesKey
import conectate.com.data.repository.message.ScheduledMessagesReader
import conectate.com.data.repository.message.ScheduledMessagesSnapshot
import conectate.com.data.repository.message.bindScheduledJobToAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    @param:ApplicationContext private val context: Context,
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
        private const val KEY_AUTODESTRUCT = "autodestruct_json"
        private const val KEY_SUMMARY = "summary_json"
        // Production hardening: do not send user message content to public translation services.
        private const val SECURE_TRANSLATION_PROVIDER_ENABLED = true
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val folderStore by lazy { ChatFolderStore(prefs, authService::getCurrentUserId) }
    val folders: Flow<List<ChatFolder>> = folderStore.observe(authService.currentSession.map { it?.user?.id })
    private val translationCache = linkedMapOf<String, ChatTranslationSettings>()
    private val scheduledStore by lazy { ScheduledMessageStore(prefs) }
    private val scheduledMutations = Mutex()
    private val scheduledReader by lazy {
        ScheduledMessagesReader(scheduledStore, ::scheduledAccount, { authService.getAccessTokenAwait() }, fetch = { key, token ->
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_chat_scheduled_jobs") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("p_scope_type", key.scope)
                    put("p_target_id", key.targetId)
                })
            }
            check(response.status.isSuccess()) { "No se pudo actualizar la lista de mensajes programados." }
            json.decodeFromString<List<ScheduledMessageJob>>(response.bodyAsText())
        }, tokenOwner = ::decodeUserIdFromAccessToken)
    }

    private fun scheduledAccount(): String? = authService.getCurrentUserId()?.trim()?.lowercase(Locale.US)
    private fun requireScheduledAccount(account: String) {
        if (scheduledAccount() != account) throw CancellationException("La cuenta activa cambió")
    }

    fun isSecureTranslationAvailable(): Boolean = SECURE_TRANSLATION_PROVIDER_ENABLED



    suspend fun refreshFolders(): Result<List<ChatFolder>> = withFolderAccount { account ->
        val token = folderToken(account) ?: return@withFolderAccount folderStore.read(account)
        val fr = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
            header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("select", "id,name,color_hex")
        }
        requireFolderAccount(account)
        check(fr.status.isSuccess()) { "No se pudieron actualizar tus carpetas. Revisa tu conexión e inténtalo de nuevo." }
        val folders = json.decodeFromString<List<FolderRow>>(fr.bodyAsText())

        val lr = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
            header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("select", "folder_id,chat_id")
        }
        requireFolderAccount(account)
        check(lr.status.isSuccess()) { "No se pudieron actualizar los chats de tus carpetas." }
        val links = json.decodeFromString<List<FolderLinkRow>>(lr.bodyAsText())
        val byFolder = links.groupBy { it.folder_id }.mapValues { (_, v) -> v.map { it.chat_id.trim().lowercase() }.distinct() }
        val mapped = folders.map { ChatFolder(id = it.id, name = it.name, color_hex = it.color_hex, chat_ids = byFolder[it.id].orEmpty()) } +
            folderStore.read(account).filter { it.id.startsWith("local-") }
        folderStore.replace(account, mapped)
        mapped
    }



    suspend fun createFolder(name: String, colorHex: String? = null): Result<ChatFolder> = withFolderAccount { account ->
        val clean = name.trim().replace(Regex("\\s+"), " ").take(60)
        require(clean.isNotBlank()) { "Nombre requerido" }

        val normalizedName = clean.lowercase(Locale.US)
        val alreadyExists = folderStore.read(account).any { it.name.trim().lowercase(Locale.US) == normalizedName }
        if (alreadyExists) {
            error("Ya existe una carpeta con ese nombre.")
        }

        val cleanColor = colorHex?.trim()?.ifBlank { null }
        val token = folderToken(account)
        if (token.isNullOrBlank()) {
            val localFolder = ChatFolder(
                id = "local-${UUID.randomUUID()}",
                name = clean,
                color_hex = cleanColor
            )
            folderStore.replace(account, (folderStore.read(account) + localFolder).distinctBy { it.id })
            return@withFolderAccount localFolder
        }

        var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
            header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json"); header("Prefer", "return=representation")
            setBody(
                buildJsonObject {
                    put("name", clean)
                    put("user_id", account)
                    cleanColor?.let { put("color_hex", it) }
                }
            )
        }

        requireFolderAccount(account)
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            requireFolderAccount(account)
            val lowerBody = body.lowercase(Locale.US)
            if (
                lowerBody.contains("duplicate key") ||
                lowerBody.contains("23505") ||
                lowerBody.contains("chat_folders_user_id_name_key")
            ) {
                throw Exception("Ya existe una carpeta con ese nombre.")
            }
            if (response.status.value == 403 || lowerBody.contains("permission denied") || lowerBody.contains("policy")) {
                val rpcFolder = tryCreateFolderViaRpc(account, token, clean, cleanColor)
                requireFolderAccount(account)
                if (rpcFolder != null) {
                    folderStore.replace(account, (folderStore.read(account) + rpcFolder).distinctBy { it.id })
                    return@withFolderAccount rpcFolder
                }
                throw Exception("No tienes permiso para crear carpetas en este momento.")
            }
            throw Exception("No se pudo crear carpeta (${response.status.value}).")
        }

        val folder = parseFolderFromBody(response.bodyAsText(), clean, cleanColor)
            ?: error("No se pudo confirmar la creación de la carpeta. Actualiza la lista antes de volver a intentarlo.")
        folderStore.replace(account, (folderStore.read(account) + folder).distinctBy { it.id })
        folder
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
        account: String,
        token: String,
        folderName: String,
        colorHex: String?
    ): ChatFolder? {
        val rpcNames = listOf("secure_create_chat_folder", "secure_create_folder")
        rpcNames.forEach { rpcName ->
            requireFolderAccount(account)
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
            val body = response.bodyAsText()
            requireFolderAccount(account)
            if (response.status.isSuccess()) return parseFolderFromBody(body, folderName, colorHex)
            if (!isMissingRpcFunction(response.status.value, body, rpcName)) {
                return null
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
        val row = fromList ?: fromObject ?: return null
        if (row.id.isBlank()) return null
        return ChatFolder(
            id = row.id,
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



    suspend fun addChatToFolder(folderId: String, chatId: String): Result<Unit> = withFolderAccount { account ->
        val cleanFolder = folderId.trim()
        val cleanChat = chatId.trim().lowercase(Locale.US)
        require(cleanFolder.isNotBlank() && cleanChat.isNotBlank()) { "Datos inválidos" }
        val previous = folderStore.read(account)
        require(previous.any { it.id == cleanFolder }) { "La carpeta ya no está disponible. Actualiza la lista." }
        val token = if (cleanFolder.startsWith("local-")) null else folderToken(account)
        if (!cleanFolder.startsWith("local-")) {
            check(!token.isNullOrBlank()) { "No se pudo confirmar el cambio. Revisa tu conexión e inténtalo de nuevo." }
            val r = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json"); header("Prefer", "resolution=merge-duplicates,return=minimal")
                parameter("on_conflict", "folder_id,chat_id")
                setBody(buildJsonObject { put("folder_id", cleanFolder); put("chat_id", cleanChat) })
            }
            requireFolderAccount(account)
            check(r.status.isSuccess()) { "No se pudo añadir el chat a la carpeta. Revisa tu conexión e inténtalo de nuevo." }
        }
        folderStore.replace(account, previous.map {
            if (it.id == cleanFolder) it.copy(chat_ids = (it.chat_ids + cleanChat).distinct()) else it
        })
    }



    suspend fun removeChatFromFolder(folderId: String, chatId: String): Result<Unit> = withFolderAccount { account ->
        val cleanFolder = folderId.trim()
        val cleanChat = chatId.trim().lowercase(Locale.US)
        require(cleanFolder.isNotBlank() && cleanChat.isNotBlank()) { "Datos inválidos" }
        val previous = folderStore.read(account)
        require(previous.any { it.id == cleanFolder }) { "La carpeta ya no está disponible. Actualiza la lista." }
        val token = if (cleanFolder.startsWith("local-")) null else folderToken(account)
        if (!cleanFolder.startsWith("local-")) {
            check(!token.isNullOrBlank()) { "No se pudo confirmar el cambio. Revisa tu conexión e inténtalo de nuevo." }
            val r = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/chat_folder_chats") {
                header("Authorization", "Bearer $token"); header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("folder_id", "eq.$cleanFolder"); parameter("chat_id", "eq.$cleanChat")
            }
            requireFolderAccount(account)
            check(r.status.isSuccess()) { "No se pudo quitar el chat de la carpeta. Revisa tu conexión e inténtalo de nuevo." }
        }
        folderStore.replace(account, previous.map { if (it.id == cleanFolder) it.copy(chat_ids = it.chat_ids.filterNot { id -> id == cleanChat }) else it })
    }



    suspend fun deleteFolder(folderId: String): Result<Unit> = withFolderAccount { account ->
        val cleanFolder = folderId.trim()
        require(cleanFolder.isNotBlank()) { "Carpeta inválida" }
        val previous = folderStore.read(account)
        val token = if (cleanFolder.startsWith("local-")) null else folderToken(account)
        if (!cleanFolder.startsWith("local-")) {
            check(!token.isNullOrBlank()) { "No se pudo confirmar el cambio. Revisa tu conexión e inténtalo de nuevo." }
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/chat_folders") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$cleanFolder")
            }

            requireFolderAccount(account)
            check(response.status.isSuccess() || response.status.value == 404) {
                "No se pudo eliminar la carpeta. Revisa tu conexión e inténtalo de nuevo."
            }
        }
        folderStore.replace(account, previous.filterNot { it.id == cleanFolder })
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
        translateViaPrivateBackend(cleanText, sourceLang, targetLang)
    }



    private suspend fun translateToLanguage(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        var cleanText = text.trim()
        var cleanLang = normalizeLanguageCode(targetLang)
        if (cleanText.isBlank() || cleanLang == null) return@withContext cleanText
        if (!SECURE_TRANSLATION_PROVIDER_ENABLED) return@withContext cleanText
        var sourceLang = resolveSourceLanguageCode()
        if (sourceLang == cleanLang) return@withContext cleanText
        translateViaPrivateBackend(cleanText, sourceLang, cleanLang)
    }

    private suspend fun translateViaPrivateBackend(text: String, source: String, target: String): String {
        val token = authService.getAccessToken() ?: return text
        return runCatching {
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/v1/data/messages/translate") {
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("text", text.take(1200))
                    put("source", source)
                    put("target", target)
                })
            }
            if (!response.status.isSuccess()) return@runCatching text
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject["translatedText"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.ifBlank { text }
                ?: text
        }.getOrDefault(text)
    }



    suspend fun scheduleMessageJob(scopeType: String, targetId: String, content: String, contentType: String = "text", mediaUrl: String? = null, scheduledForEpochMs: Long, translateTo: String? = null, selfDestructSeconds: Int? = null): Result<ScheduledMessageJob> = withContext(Dispatchers.IO) {
        val key = ScheduledMessagesKey.create(scheduledAccount(), scopeType, targetId)
            ?: return@withContext Result.failure(IllegalStateException("Abre una conversación con tu cuenta para programar un mensaje."))
        val token = authService.getAccessTokenAwait() ?: return@withContext Result.failure(IllegalStateException("La sesión no está disponible."))
        requireScheduledAccount(key.accountId)
        val scheduledFor = Instant.ofEpochMilli(scheduledForEpochMs.coerceAtLeast(System.currentTimeMillis() + 30_000L)).toString()
        val idempotencyKey = UUID.randomUUID().toString()
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
                if (authService.isLocalFallbackSession()) put("p_idempotency_key", idempotencyKey)
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
                    ?: throw IllegalStateException("El servidor no confirmó el mensaje programado.")
            } else {
                check(r.status.value >= 500 || r.status.value in setOf(408, 425, 429)) {
                    "El servidor no aceptó el mensaje programado."
                }
                val local = ScheduledMessageJob(
                    id = "local-$idempotencyKey",
                    scope_type = key.scope,
                    target_id = key.targetId,
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
            requireScheduledAccount(key.accountId)
            val boundScheduled = bindScheduledJobToAccount(scheduled, key, decodeUserIdFromAccessToken(token))
            if (boundScheduled.id.startsWith("local-", ignoreCase = true)) {
                scheduledStore.putLocal(key.accountId, boundScheduled)
                enqueueScheduledSyncJob(boundScheduled.id, parseIso(boundScheduled.scheduled_for))
            } else {
                scheduledStore.rememberRemote(key, boundScheduled)
            }
            boundScheduled
        }.fold(onSuccess = { Result.success(it) }, onFailure = {
            if (it is CancellationException) throw it
            Result.failure(IllegalStateException("No se pudo confirmar el mensaje programado. Revisa tu conexión.", it))
        })
    }


    suspend fun getCachedScheduledJobs(key: ScheduledMessagesKey): ScheduledMessagesSnapshot = withContext(Dispatchers.IO) {
        requireScheduledAccount(key.accountId)
        scheduledStore.snapshot(key)
    }

    suspend fun getScheduledJobs(key: ScheduledMessagesKey): Result<ScheduledMessagesSnapshot> =
        withContext(Dispatchers.IO) { scheduledReader.refresh(key) }


    suspend fun cancelScheduledJob(jobId: String, expectedAccount: String): Result<Unit> = withContext(Dispatchers.IO) {
        scheduledMutations.withLock {
            val account = expectedAccount
            requireScheduledAccount(account)
            val cleanId = jobId.trim()
            if (cleanId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Mensaje programado inválido"))
            try {
                if (cleanId.startsWith("local-", ignoreCase = true)) {
                    // IDs from another account (or unowned legacy rows) must never be acted on.
                    check(scheduledStore.pending(account).any { it.id == cleanId }) { "Mensaje programado no disponible en esta cuenta" }
                } else {
                    val token = authService.getAccessTokenAwait() ?: error("Sesión no disponible")
                    requireScheduledAccount(account)
                    val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_cancel_scheduled_message_job") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_job_id", cleanId) })
                    }
                    check(response.status.isSuccess()) { "No se pudo confirmar la cancelación en el servidor." }
                }
                requireScheduledAccount(account)
                scheduledStore.remove(account, cleanId)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(IllegalStateException("No se pudo confirmar la cancelación. Revisa tu conexión y reintenta.", error))
            }
        }
    }

    suspend fun processDueScheduledJobs(maxBatch: Int = 20): Result<Int> = withContext(Dispatchers.IO) {
        scheduledMutations.withLock {
            val account = scheduledAccount() ?: return@withContext Result.success(0)
            val nowMs = System.currentTimeMillis()
            val localDue = scheduledStore.pending(account)
                .filter { it.status == "pending" && parseIso(it.scheduled_for) <= nowMs }
                .take(maxBatch.coerceAtLeast(0))
            if (localDue.isEmpty()) return@withContext Result.success(0)
            val token = authService.getAccessTokenAwait() ?: return@withContext Result.failure(IllegalStateException("Sesión no disponible"))
            requireScheduledAccount(account)
            val localFallback = authService.isLocalFallbackSession()
            var synced = 0
            for (job in localDue) {
                requireScheduledAccount(account)
                val confirmed = try {
                    val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_schedule_message_job") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject {
                            put("p_scope_type", job.scope_type)
                            put("p_target_id", job.target_id)
                            put("p_content", job.content)
                            put("p_content_type", job.content_type)
                            if (job.media_url.isNullOrBlank()) put("p_media_url", JsonNull) else put("p_media_url", job.media_url)
                            put("p_scheduled_for", Instant.ofEpochMilli(System.currentTimeMillis() + 10_000L).toString())
                            if (job.translate_to.isNullOrBlank()) put("p_translate_to", JsonNull) else put("p_translate_to", job.translate_to)
                            if (job.self_destruct_seconds == null) put("p_self_destruct_seconds", JsonNull) else put("p_self_destruct_seconds", job.self_destruct_seconds)
                            // Same key as the original attempt, including a response lost after commit.
                            if (localFallback) put("p_idempotency_key", job.id.removePrefix("local-"))
                        })
                    }
                    if (!response.status.isSuccess()) null else {
                        val body = response.bodyAsText()
                        runCatching { json.decodeFromString<List<ScheduledMessageJob>>(body).firstOrNull() }.getOrNull()
                            ?: runCatching { json.decodeFromString<ScheduledMessageJob>(body) }.getOrNull()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                requireScheduledAccount(account)
                val key = ScheduledMessagesKey.create(account, job.scope_type, job.target_id)!!
                val boundConfirmation = confirmed?.let {
                    runCatching { bindScheduledJobToAccount(it, key, decodeUserIdFromAccessToken(token)) }.getOrNull()
                }
                if (boundConfirmation != null) {
                    scheduledStore.rememberRemote(key, boundConfirmation)
                    scheduledStore.remove(account, job.id)
                    synced++
                } else {
                    scheduledStore.putLocal(account, job.copy(error_message = "Pendiente de sincronizar con el servidor"))
                    enqueueScheduledSyncJob(job.id, System.currentTimeMillis() + 60_000L)
                }
            }
            Result.success(synced)
        }
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

    private suspend fun requireFolderAccount(account: String) {
        currentCoroutineContext().ensureActive()
        if (authService.getCurrentUserId()?.trim()?.lowercase(Locale.US) != account) {
            throw CancellationException("Folder account changed")
        }
    }

    private suspend fun folderToken(account: String): String? {
        val token = authService.getAccessTokenAwait()
        requireFolderAccount(account)
        // Migrated Ubuntu accounts can use a public profile ID while JWT.sub is
        // their local ID. AuthService guards token refresh; bind this operation
        // to the captured active account instead of equating those two IDs.
        return token
    }

    private suspend fun <T> withFolderAccount(operation: suspend (String) -> T): Result<T> = folderStore.withAccount { account ->
        withContext(Dispatchers.IO) { operation(account) }
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










