package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.StickerItem
import conectate.com.data.model.StickerPack
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService
) {
    companion object {
        private const val PREFS = "sticker_prefs"
        private const val KEY_LOCAL_ITEMS = "local_items_json"
        private const val KEY_LOCAL_PACKS = "local_packs_json"
        private const val KEY_FAVORITES = "favorite_ids_json"
        private const val KEY_DOWNLOADED_PACKS = "downloaded_pack_ids_json"
        private const val KEY_RECENT_STICKERS = "recent_sticker_ids_json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _catalog = MutableStateFlow<List<StickerItem>>(emptyList())
    val catalog: StateFlow<List<StickerItem>> = _catalog.asStateFlow()

    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _downloadedPacks = MutableStateFlow<Set<String>>(emptySet())
    val downloadedPacks: StateFlow<Set<String>> = _downloadedPacks.asStateFlow()

    private val _recentStickerIds = MutableStateFlow<List<String>>(emptyList())
    val recentStickerIds: StateFlow<List<String>> = _recentStickerIds.asStateFlow()



    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val localCatalog = loadLocalItems().ifEmpty { defaultCatalog() }
        val localPacks = loadLocalPacks().ifEmpty { defaultPacks() }
        val localFavs = loadLocalFavorites()
        val localDownloads = loadDownloadedPacks()
        val localRecents = loadRecentStickerIds()

        _catalog.value = localCatalog
        _packs.value = localPacks
        _favorites.value = localFavs
        _downloadedPacks.value = localDownloads
        _recentStickerIds.value = localRecents

        persistAll(localCatalog, localPacks, localFavs, localDownloads, localRecents)

        runCatching { refreshRemoteCatalog() }
        runCatching { refreshRemotePacks() }
    }



    suspend fun search(query: String): List<StickerItem> = withContext(Dispatchers.Default) {
        val q = query.trim().lowercase()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val base = _catalog.value
        if (tokens.isEmpty()) {
            return@withContext base.sortedWith(
                compareByDescending<StickerItem> { _favorites.value.contains(it.id) }
                    .thenByDescending { it.is_animated }
                    .thenBy { it.title.lowercase() }
            )
        }

        val scored = mutableListOf<Pair<StickerItem, Int>>()
        base.forEach { item ->
            var haystackTitle = item.title.lowercase()
            val haystackTags = item.tags.map { it.lowercase() }
            var score = 0
            tokens.forEach { token ->
                score += when {
                    haystackTitle == token -> 50
                    haystackTitle.contains(token) -> 20
                    haystackTags.any { it == token } -> 18
                    haystackTags.any { it.contains(token) } -> 10
                    item.id.lowercase().contains(token) -> 6
                    else -> 0
                }
            }
            if (score > 0) {
                val favBoost = if (_favorites.value.contains(item.id)) 25 else 0
                val animBoost = if (item.is_animated) 8 else 0
                scored += item to (score + favBoost + animBoost)
            }
        }

        scored.sortedByDescending { pair -> pair.second }
            .map { pair -> pair.first }
    }



    suspend fun toggleFavorite(stickerId: String): Boolean = withContext(Dispatchers.IO) {
        var clean = stickerId.trim()
        if (clean.isBlank()) return@withContext false

        var updated = _favorites.value.toMutableSet().apply {
            if (contains(clean)) remove(clean) else add(clean)
        }
        _favorites.value = updated
        persistFavorites(updated)

        // Best effort remote sync. If RPC is missing we keep local behavior.
        runCatching {
            var token = authService.getAccessToken() ?: return@runCatching
            var payload = buildJsonObject { put("p_sticker_id", JsonPrimitive(clean)) }
            supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_toggle_sticker_favorite") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
        }

        updated.contains(clean)
    }

    fun isFavoriteMediaUrl(mediaUrl: String?): Boolean {
        var cleanUrl = UbuntuStorageUrl.rewrite(mediaUrl)?.trim().orEmpty()
        if (cleanUrl.isBlank()) return false
        val stickerId = _catalog.value.firstOrNull {
            UbuntuStorageUrl.rewrite(it.media_url)?.trim()?.equals(cleanUrl, ignoreCase = true) == true
        }?.id ?: return false
        return _favorites.value.contains(stickerId)
    }



    suspend fun toggleFavoriteByMedia(mediaUrl: String, title: String = "Sticker"): Boolean = withContext(Dispatchers.IO) {
        var sticker = ensureStickerForMedia(mediaUrl, title) ?: return@withContext false
        registerRecentSticker(sticker.id)
        toggleFavorite(sticker.id)
    }



    suspend fun createPack(name: String, description: String? = null, isPublic: Boolean = false): Result<StickerPack> = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return@withContext Result.failure(Exception("El nombre del paquete es obligatorio"))
        }

        var local = StickerPack(
            name = trimmedName,
            description = description?.trim()?.ifBlank { null },
            is_public = isPublic
        )

        val updatedLocal = (_packs.value + local).distinctBy { it.id }
        _packs.value = updatedLocal
        persistPacks(updatedLocal)

        val remote = runCatching {
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            var payload = buildJsonObject {
                put("p_name", JsonPrimitive(trimmedName))
                put("p_description", JsonPrimitive(description?.trim()))
                put("p_is_public", JsonPrimitive(isPublic))
            }
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_create_sticker_pack") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
            if (!response.status.isSuccess()) throw IllegalStateException("No se pudo crear pack remoto")
            val body = response.bodyAsText()
            json.decodeFromString<StickerPack>(body)
        }.getOrNull()

        if (remote != null) {
            var merged = (_packs.value.filterNot { it.name.equals(trimmedName, ignoreCase = true) } + remote).distinctBy { it.id }
            _packs.value = merged
            persistPacks(merged)
            Result.success(remote)
        } else {
            Result.success(local)
        }
    }

    suspend fun addStickerToPack(
        packId: String,
        sticker: StickerItem
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = sticker.copy(pack_id = packId)
        val mergedItems = (_catalog.value + normalized).distinctBy { it.id }
        _catalog.value = mergedItems
        persistItems(mergedItems)

        runCatching {
            var token = authService.getAccessToken() ?: return@runCatching
            var payload = buildJsonObject {
                put("p_pack_id", JsonPrimitive(packId))
                put("p_sticker_id", JsonPrimitive(sticker.id))
                put("p_media_url", JsonPrimitive(sticker.media_url))
                put("p_label", JsonPrimitive(sticker.title))
                put("p_is_animated", JsonPrimitive(sticker.is_animated))
            }
            supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_add_sticker_to_pack") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
        }

        Result.success(Unit)
    }

    suspend fun createStickerFromImage(
        imageUri: Uri,
        packId: String? = null,
        title: String? = null,
        tags: List<String> = emptyList()
    ): Result<StickerItem> = withContext(Dispatchers.IO) {
        var bytes = readUriBytes(imageUri, maxBytes = 8 * 1024 * 1024)
            ?: return@withContext Result.failure(Exception("No se pudo leer la imagen"))
        if (bytes.isEmpty()) return@withContext Result.failure(Exception("Imagen vacía"))

        var mimeType = resolveStickerMimeType(imageUri, bytes)
        createStickerFromBytes(
            imageBytes = bytes,
            packId = packId,
            title = title,
            tags = tags,
            mimeType = mimeType
        )
    }

    suspend fun createStickerFromBytes(
        imageBytes: ByteArray,
        packId: String? = null,
        title: String? = null,
        tags: List<String> = emptyList(),
        mimeType: String = "image/png"
    ): Result<StickerItem> = withContext(Dispatchers.IO) {
        var uid = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("No hay sesión activa"))
        if (imageBytes.isEmpty()) return@withContext Result.failure(Exception("Imagen vacía"))

        val cleanMime = when {
            mimeType.isBlank() -> "image/png"
            mimeType.startsWith("image/", ignoreCase = true) -> mimeType.lowercase()
            else -> "image/png"
        }
        val extension = mimeToExtension(cleanMime)
        val remotePath = "stickers/$uid/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}$extension"

        val upload = storageService.uploadFile(
            bucketName = "chat-media",
            fileName = remotePath,
            bytes = imageBytes,
            contentType = cleanMime
        )
        if (upload.isFailure) {
            return@withContext Result.failure(upload.exceptionOrNull() ?: Exception("No se pudo subir sticker"))
        }

        val remoteKey = upload.getOrThrow().Key ?: remotePath
        val mediaUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("chat-media", remoteKey)).orEmpty()
        if (mediaUrl.isBlank()) {
            storageService.deleteFile("chat-media", remoteKey).onFailure { error ->
                android.util.Log.w("StickerRepository", "No se pudo limpiar el sticker sin URL", error)
            }
            return@withContext Result.failure(Exception("No se pudo generar URL del sticker"))
        }

        val cleanTitle = title?.trim()?.ifBlank { null } ?: "Sticker"
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().take(12)
        val selectedPack = packId?.trim()?.ifBlank { null } ?: _packs.value.firstOrNull()?.id
        var sticker = StickerItem(
            id = "custom_${UUID.randomUUID().toString().replace("-", "")}",
            title = cleanTitle,
            media_url = mediaUrl,
            thumbnail_url = mediaUrl,
            is_animated = isAnimatedSticker(imageBytes, cleanMime),
            tags = cleanTags,
            source = "custom",
            pack_id = selectedPack
        )

        var merged = (_catalog.value + sticker).distinctBy { it.id }
        _catalog.value = merged
        persistItems(merged)
        registerRecentSticker(sticker.id)

        if (!selectedPack.isNullOrBlank()) {
            addStickerToPack(selectedPack, sticker)
        }

        Result.success(sticker)
    }

    suspend fun copyStickerToPack(
        sticker: StickerItem,
        targetPackId: String
    ): Result<StickerItem> = withContext(Dispatchers.IO) {
        var cleanPack = targetPackId.trim()
        if (cleanPack.isBlank()) {
            return@withContext Result.failure(Exception("Selecciona un pack de destino"))
        }
        var pack = _packs.value.firstOrNull { it.id == cleanPack }
            ?: return@withContext Result.failure(Exception("Pack inválido"))

        val copied = sticker.copy(
            id = "copy_${UUID.randomUUID().toString().replace("-", "")}",
            title = if (sticker.title.endsWith("(copia)", ignoreCase = true)) {
                sticker.title
            } else {
                "${sticker.title} (copia)"
            },
            pack_id = cleanPack,
            pack_name = pack.name,
            is_favorite = false
        )

        var merged = (_catalog.value + copied).distinctBy { it.id }
        _catalog.value = merged
        persistItems(merged)

        addStickerToPack(cleanPack, copied)
        Result.success(copied)
    }



    suspend fun removeStickerFromPack(packId: String, stickerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanPack = packId.trim()
        var cleanSticker = stickerId.trim()
        if (cleanPack.isBlank() || cleanSticker.isBlank()) {
            return@withContext Result.failure(Exception("Datos inválidos"))
        }

        var removed = false
        var updated = _catalog.value.map { item ->
            if (item.id == cleanSticker && item.pack_id == cleanPack) {
                removed = true
                item.copy(pack_id = null, pack_name = null)
            } else {
                item
            }
        }

        if (!removed) {
            return@withContext Result.failure(Exception("El sticker no pertenece al pack seleccionado"))
        }

        _catalog.value = updated
        persistItems(updated)

        runCatching {
            var token = authService.getAccessToken() ?: return@runCatching
            supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/sticker_pack_items") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("pack_id", "eq.$cleanPack")
                parameter("sticker_id", "eq.$cleanSticker")
            }
        }

        Result.success(Unit)
    }



    suspend fun deleteSticker(stickerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanSticker = stickerId.trim()
        if (cleanSticker.isBlank()) {
            return@withContext Result.failure(Exception("stickerId inválido"))
        }

        val target = _catalog.value.firstOrNull { it.id == cleanSticker }
            ?: return@withContext Result.failure(Exception("Sticker no encontrado"))

        val updatedCatalog = _catalog.value.filterNot { it.id == cleanSticker }
        _catalog.value = updatedCatalog
        persistItems(updatedCatalog)

        val updatedFavs = _favorites.value.filterNot { it == cleanSticker }.toSet()
        _favorites.value = updatedFavs
        persistFavorites(updatedFavs)

        runCatching {
            var token = authService.getAccessToken() ?: return@runCatching
            var uid = authService.getCurrentUserId()?.trim()?.lowercase()

            supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/sticker_pack_items") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("sticker_id", "eq.$cleanSticker")
            }

            if (!uid.isNullOrBlank()) {
                supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/user_sticker_favorites") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("user_id", "eq.$uid")
                    parameter("sticker_id", "eq.$cleanSticker")
                }
            }
        }

        if (target.source.equals("custom", ignoreCase = true) && !target.media_url.isNullOrBlank()) {
            val objectPath = extractStorageObjectPathFromPublicUrl(
                urlOrPath = target.media_url,
                bucket = "chat-media"
            )
            if (!objectPath.isNullOrBlank()) {
                storageService.deleteFile("chat-media", objectPath)
            }
        }

        Result.success(Unit)
    }



    suspend fun downloadPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var clean = packId.trim()
        if (clean.isBlank()) return@withContext Result.failure(Exception("packId inválido"))

        var updated = _downloadedPacks.value.toMutableSet().apply { add(clean) }
        _downloadedPacks.value = updated
        persistDownloadedPacks(updated)

        // Prefetch lightweight remote catalog for selected pack (best effort).
        runCatching {
            var token = authService.getAccessToken() ?: return@runCatching
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_search_stickers") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("p_query", JsonPrimitive(""))
                    put("p_limit", JsonPrimitive(250))
                })
            }
            if (response.status.isSuccess()) {
                var parsed = runCatching {
                    json.decodeFromString<List<StickerItem>>(response.bodyAsText()).map { it.normalizedStorageUrls() }
                }.getOrDefault(emptyList())
                var merged = (parsed + _catalog.value).distinctBy { it.id }
                _catalog.value = merged
                persistItems(merged)
            }
        }

        Result.success(Unit)
    }

    fun favoritesAsItems(): List<StickerItem> {
        val ids = _favorites.value
        return _catalog.value.filter { ids.contains(it.id) }
    }

    fun recentAsItems(limit: Int = 48): List<StickerItem> {
        if (limit <= 0) return emptyList()
        val byId = _catalog.value.associateBy { it.id }
        return _recentStickerIds.value
            .asSequence()
            .mapNotNull { byId[it] }
            .take(limit)
            .toList()
    }

    fun registerRecentSticker(stickerId: String) {
        val cleanId = stickerId.trim()
        if (cleanId.isBlank()) return
        var updated = listOf(cleanId) + _recentStickerIds.value.filterNot { it == cleanId }
        val capped = updated.take(80)
        _recentStickerIds.value = capped
        persistRecentStickerIds(capped)
    }

    fun isPackDownloaded(packId: String): Boolean {
        return _downloadedPacks.value.contains(packId.trim())
    }

    private fun ensureStickerForMedia(mediaUrl: String, title: String): StickerItem? {
        var cleanUrl = UbuntuStorageUrl.rewrite(mediaUrl)?.trim().orEmpty()
        if (cleanUrl.isBlank()) return null

        _catalog.value.firstOrNull {
            UbuntuStorageUrl.rewrite(it.media_url)?.trim()?.equals(cleanUrl, ignoreCase = true) == true
        }?.let { return it }

        val generated = StickerItem(
            id = "message_${UUID.nameUUIDFromBytes(cleanUrl.toByteArray()).toString()}",
            title = title.trim().ifBlank { "Sticker" }.take(60),
            media_url = cleanUrl,
            thumbnail_url = cleanUrl,
            is_animated = cleanUrl.contains(".gif", ignoreCase = true),
            source = "message",
            pack_id = "local-default",
            pack_name = "Mis stickers"
        )

        var updated = (listOf(generated) + _catalog.value).distinctBy { it.id }
        _catalog.value = updated
        persistItems(updated)
        return generated
    }

    private suspend fun refreshRemoteCatalog() {
        var token = authService.getAccessToken() ?: return
        var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_search_stickers") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            setBody(buildJsonObject {
                put("p_query", JsonPrimitive(""))
                put("p_limit", JsonPrimitive(150))
            })
        }
        if (!response.status.isSuccess()) return

        var parsed = runCatching {
            json.decodeFromString<List<StickerItem>>(response.bodyAsText()).map { it.normalizedStorageUrls() }
        }.getOrNull() ?: return
        if (parsed.isEmpty()) return

        var merged = (parsed + _catalog.value).distinctBy { it.id }
        _catalog.value = merged
        persistItems(merged)
    }

    private suspend fun refreshRemotePacks() {
        runCatching {
            val rows = supabaseConfig.client.postgrest["sticker_packs"].select {
                order("updated_at", Order.DESCENDING)
            }.decodeList<StickerPack>()

            if (rows.isNotEmpty()) {
                var merged = (rows + _packs.value).distinctBy { it.id }
                _packs.value = merged
                persistPacks(merged)
            }
        }
    }

    private fun defaultPacks(): List<StickerPack> {
        return listOf(
            StickerPack(id = "local-default", name = "Mis stickers", is_public = false),
            StickerPack(id = "local-animated", name = "Animados", is_public = true)
        )
    }

    private fun defaultCatalog(): List<StickerItem> {
        return listOf(
            StickerItem(id = "emoji_happy", title = "Feliz", tags = listOf("feliz", "smile"), source = "local"),
            StickerItem(id = "emoji_love", title = "Amor", tags = listOf("love", "heart"), source = "local"),
            StickerItem(id = "emoji_fire", title = "Fuego", tags = listOf("fire", "top"), source = "local"),
            StickerItem(id = "emoji_party", title = "Fiesta", tags = listOf("party", "celebration"), source = "local"),
            StickerItem(id = "emoji_robot", title = "Robot", tags = listOf("robot", "fun"), source = "local"),
            StickerItem(id = "emoji_star", title = "Estrella", tags = listOf("star", "highlight"), source = "local")
        )
    }

    private fun persistAll(
        items: List<StickerItem>,
        packs: List<StickerPack>,
        favorites: Set<String>,
        downloadedPacks: Set<String>,
        recentStickerIds: List<String>
    ) {
        persistItems(items)
        persistPacks(packs)
        persistFavorites(favorites)
        persistDownloadedPacks(downloadedPacks)
        persistRecentStickerIds(recentStickerIds)
    }

    private fun persistItems(items: List<StickerItem>) {
        prefs.edit().putString(KEY_LOCAL_ITEMS, json.encodeToString(items)).apply()
    }

    private fun persistPacks(packs: List<StickerPack>) {
        prefs.edit().putString(KEY_LOCAL_PACKS, json.encodeToString(packs)).apply()
    }

    private fun persistFavorites(favorites: Set<String>) {
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(favorites.toList())).apply()
    }

    private fun persistDownloadedPacks(packIds: Set<String>) {
        prefs.edit().putString(KEY_DOWNLOADED_PACKS, json.encodeToString(packIds.toList())).apply()
    }

    private fun persistRecentStickerIds(stickerIds: List<String>) {
        prefs.edit().putString(KEY_RECENT_STICKERS, json.encodeToString(stickerIds)).apply()
    }

    private fun loadLocalItems(): List<StickerItem> {
        var raw = prefs.getString(KEY_LOCAL_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StickerItem>>(raw).map { it.normalizedStorageUrls() } }.getOrDefault(emptyList())
    }

    private fun loadLocalPacks(): List<StickerPack> {
        var raw = prefs.getString(KEY_LOCAL_PACKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StickerPack>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadLocalFavorites(): Set<String> {
        var raw = prefs.getString(KEY_FAVORITES, null) ?: return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }

    private fun loadDownloadedPacks(): Set<String> {
        var raw = prefs.getString(KEY_DOWNLOADED_PACKS, null) ?: return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }

    private fun loadRecentStickerIds(): List<String> {
        var raw = prefs.getString(KEY_RECENT_STICKERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveStickerMimeType(uri: Uri, bytes: ByteArray): String {
        return StickerFormatSupport.detectMimeType(bytes, context.contentResolver.getType(uri))
    }

    private fun isAnimatedSticker(bytes: ByteArray, mimeType: String): Boolean {
        return StickerFormatSupport.isAnimated(bytes, mimeType)
    }

    private fun mimeToExtension(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".png"
        }
    }

    private fun readUriBytes(uri: Uri, maxBytes: Int): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var bytes = input.readBytes()
                if (bytes.size > maxBytes) {
                    null
                } else {
                    bytes
                }
            }
        }.getOrNull()
    }

    private fun extractStorageObjectPathFromPublicUrl(urlOrPath: String?, bucket: String): String? {
        var raw = UbuntuStorageUrl.rewrite(urlOrPath)?.trim().orEmpty()
        if (raw.isBlank()) return null

        if (!raw.startsWith("http", ignoreCase = true)) {
            return raw.trimStart('/').ifBlank { null }
        }

        val marker = "/storage/v1/object/public/$bucket/"
        val idx = raw.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null

        val candidate = raw
            .substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimStart('/')
            .ifBlank { return null }

        return runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrDefault(candidate)
    }

    private fun StickerItem.normalizedStorageUrls(): StickerItem = copy(
        media_url = UbuntuStorageUrl.rewrite(media_url),
        thumbnail_url = UbuntuStorageUrl.rewrite(thumbnail_url)
    )
}





