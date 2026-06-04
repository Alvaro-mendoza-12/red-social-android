package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.merge
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MomentRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val ubuntuRealtimeClient: UbuntuRealtimeClient,
    private val storageService: StorageService,
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val MOMENTS_FALLBACK_POLL_INTERVAL_MS = 45_000L
    }

    private val _moments = MutableStateFlow<List<Moment>>(emptyList())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()
    
    // Coroutine scope for repository operations
    private val scope = newSafeScope("MomentRepository", Dispatchers.IO)
    
    // Store channels to allow explicit cleanup
    private var momentsChannel: RealtimeChannel? = null
    private var viewsChannel: RealtimeChannel? = null

    private var observationJob: kotlinx.coroutines.Job? = null

    init {
        // Observe auth session changes to restart Realtime with the correct token
        scope.launch {
            authService.currentSession.collect { session ->
                var userId = session?.user?.id
                android.util.Log.d("MomentRepository", "⚡ Auth session changed. Restarting Realtime... User: $userId")
                
                observationJob?.cancel()
                observationJob = scope.launch {
                    val currentSession = session
                    if (currentSession != null) {
                        try {
                            startObservingReactive(this)
                        } catch (e: Exception) {
                            android.util.Log.e("MomentRepository", "⚡ Error in reactive observation", e)
                        }
                    } else {
                        android.util.Log.d("MomentRepository", "⚡ No active session, clearing moments and waiting...")
                        _moments.value = emptyList()
                    }
                }
            }
        }
    }

    private suspend fun startObservingReactive(internalScope: kotlinx.coroutines.CoroutineScope) {
        refreshMoments()
        if (supabaseConfig.isLocalFallbackPrimary) {
            startUbuntuMomentsObservation(internalScope)
            return
        }

        // Real-time subscriptions (Supabase)
        try {
            // IMPORTANTE: Conectar el cliente de Realtime para habilitar WebSockets
            android.util.Log.d("MomentRepository", "⚡ Intentando conectar a Realtime...")
            
            // Monitor connection status
            internalScope.launch {
                supabaseConfig.client.realtime.status.collect { status ->
                    android.util.Log.d("MomentRepository", "⚡ Realtime Status: $status")
                }
            }

            supabaseConfig.ensureRealtimeConnected("moments-observer")
            android.util.Log.d("MomentRepository", "⚡ Realtime asegurado")

            // 0. AUTO-POLLING FALLBACK (Start this EARLY and INDEPENDENTLY)
            internalScope.launch {
                while (internalScope.isActive) {
                    try {
                        android.util.Log.d("MomentRepository", "💓 Auto-Polling Refresh...")
                        refreshMoments()
                    } catch (e: Exception) {
                        android.util.Log.e("MomentRepository", "💓 Polling error", e)
                    }
                    kotlinx.coroutines.delay(MOMENTS_FALLBACK_POLL_INTERVAL_MS)
                }
            }

            // Cleanup Previous Channels
            if (momentsChannel != null) {
                android.util.Log.d("MomentRepository", "⚡ Limpiando canal anterior moments...")
                momentsChannel?.unsubscribe()
                momentsChannel?.let { supabaseConfig.client.realtime.removeChannel(it) }
                momentsChannel = null
            }
            if (viewsChannel != null) {
                android.util.Log.d("MomentRepository", "⚡ Limpiando canal anterior views...")
                viewsChannel?.unsubscribe()
                viewsChannel?.let { supabaseConfig.client.realtime.removeChannel(it) }
                viewsChannel = null
            }

            // 1. Moments Channel (Dynamic ID to guarantee fresh connection)
            var channelId = "moments_updates_${java.util.UUID.randomUUID()}"
            android.util.Log.d("MomentRepository", "⚡ Suscribiendo a canal DINÁMICO: $channelId")
            
            val newMomentsChannel = supabaseConfig.client.realtime.channel(channelId)
            val momentsChanges = newMomentsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "moments"
            }
            momentsChannel = newMomentsChannel

            // 2. Views Channel (Dynamic ID)
            var currentUserId = authService.getCurrentUserId()
            val viewsChannelId = "views_updates_${java.util.UUID.randomUUID()}"
            val newViewsChannel = supabaseConfig.client.realtime.channel(viewsChannelId)
            val viewsChanges = if (currentUserId != null) {
                newViewsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "moment_views"
                    filter = "user_id=eq.$currentUserId"
                }
            } else {
                emptyFlow()
            }
            viewsChannel = newViewsChannel

            // Launch concurrent collectors
            internalScope.launch {
                momentsChanges.collect { action ->
                    android.util.Log.d("MomentRepository", "⚡ Realtime event received: ${action::class.simpleName}")
                    android.util.Log.d("MomentRepository", "⚡ Full Action Payload: $action")

                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val newId = action.record["id"]?.jsonPrimitive?.content
                                if (newId != null) {
                                    fetchAndAddMoment(newId)
                                } else {
                                    android.util.Log.w("MomentRepository", "⚡ INSERT sin ID, forzando recarga total...")
                                    refreshMoments()
                                }
                            }
                            is PostgresAction.Update -> {
                                val updatedId = action.record["id"]?.jsonPrimitive?.content
                                if (updatedId != null) {
                                    fetchAndAddMoment(updatedId)
                                } else {
                                    android.util.Log.w("MomentRepository", "⚡ UPDATE sin ID, forzando recarga total...")
                                    refreshMoments()
                                }
                            }
                            is PostgresAction.Delete -> {
                                var id = action.oldRecord["id"]?.jsonPrimitive?.content
                                if (id != null) {
                                    android.util.Log.d("MomentRepository", "⚡ DELETE detectado: $id - Eliminando localmente")
                                    _moments.value = _moments.value.filter { it.id != id }
                                } else {
                                    // Si RLS no entrega el ID anterior, sincronizamos de nuevo para mantener consistencia.
                                    android.util.Log.w("MomentRepository", "DELETE sin ID en realtime; sincronizando momentos")
                                    refreshMoments()
                                }
                            }
                            else -> {
                                android.util.Log.d("MomentRepository", "⚡ Evento desconocido, por seguridad recargando...")
                                refreshMoments()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MomentRepository", "⚡ Error procesando evento, fallback a refresh", e)
                        refreshMoments()
                    }
                }
            }

            internalScope.launch {
                viewsChanges.collect { 
                    android.util.Log.d("MomentRepository", "⚡ Views changed, refreshing...")
                    refreshMoments() 
                }
            }

            android.util.Log.d("MomentRepository", "⚡ Intentando suscribir canales...")
            momentsChannel?.let {
                supabaseConfig.subscribeRealtimeChannel(
                    channel = it,
                    reason = "moments-updates",
                    blockUntilSubscribed = false
                )
            }
            viewsChannel?.let {
                supabaseConfig.subscribeRealtimeChannel(
                    channel = it,
                    reason = "moment-views-updates",
                    blockUntilSubscribed = false
                )
            }
            android.util.Log.d("MomentRepository", "⚡ Suscripciones solicitadas (asíncronas)")
            
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "⚡ Error setting up realtime", e)
        }
    }

    private suspend fun fetchAndAddMoment(momentId: String) {
        try {
            var currentUserId = authService.getCurrentUserId() ?: return // Fetch the specific moment with profile join
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                header("Cache-Control", "no-cache")
                parameter("select", "*,user:profiles!user_id(username,display_name,full_name,avatar_url,is_verified)")
                parameter("id", "eq.$momentId")
                parameter("limit", "1")
            }

            if (!response.status.isSuccess()) {
                android.util.Log.e("MomentRepository", "Failed to fetch moment $momentId: ${response.status}")
                return
            }

            val rawData: List<JsonObject> = response.body()
            if (rawData.isEmpty()) {
                android.util.Log.w("MomentRepository", "Moment $momentId not found or filtered out")
                return
            }

            val json = rawData.first()
            
            // Check visibility filters (same logic as refreshMoments)
            var visibilityType = json["visibility_type"]?.jsonPrimitive?.content ?: "public"
            var userId = json["user_id"]?.jsonPrimitive?.content ?: ""
            
            if (visibilityType == "silent" && userId != currentUserId) return
            if (visibilityType == "private" && userId != currentUserId) return

            var excluded = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            if (excluded.contains(currentUserId) && userId != currentUserId) return

            // Check if expired
            var expiresAt = json["expires_at"]?.jsonPrimitive?.content
            if (expiresAt != null) {
                try {
                    val expiry = OffsetDateTime.parse(expiresAt)
                    if (OffsetDateTime.now().isAfter(expiry)) {
                        android.util.Log.d("MomentRepository", "Moment $momentId is expired, ignoring")
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MomentRepository", "Error parsing expiry date", e)
                }
            }

            // Check if already viewed
            var viewsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("user_id", "eq.$currentUserId")
                parameter("moment_id", "eq.$momentId")
                parameter("select", "moment_id")
                parameter("limit", "1")
            }
            var isViewed = if (viewsResponse.status.isSuccess()) {
                val views: List<JsonObject> = viewsResponse.body()
                views.isNotEmpty()
            } else {
                false
            }

            var profileElement = json["user"]
            var profile = when {
                profileElement is JsonObject -> profileElement
                profileElement is JsonArray -> profileElement.firstOrNull()?.jsonObject
                else -> null
            }
            
            fun sanitize(input: String?): String? {
                if (input == null) return null
                if (input.equals("null", ignoreCase = true)) return null
                if (input.isBlank()) return null
                return input
            }

            var rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
            var rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
            var rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull

            var validDisplayName = sanitize(rawDisplayName)
            var validFullName = sanitize(rawFullName)
            var validUsername = sanitize(rawUsername)
            
            var finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"

            if (expiresAt != null) {
                try {
                    val expires = java.time.OffsetDateTime.parse(expiresAt)
                    var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    if (expires.isBefore(now.minusMinutes(5))) {
                        android.util.Log.d("MomentRepository", "⚡ Evitando agregar momento recién recibido pero ya expirado: $momentId")
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MomentRepository", "Error validando expiración en sync individual", e)
                }
            }

            // Create object
            val newMoment = Moment(
                id = momentId,
                userId = userId,
                mediaUrl = json["media_url"]?.jsonPrimitive?.content ?: "",
                mediaType = json["media_type"]?.jsonPrimitive?.content ?: "image",
                thumbnailUrl = json["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                durationHours = json["duration_hours"]?.jsonPrimitive?.int ?: 24,
                durationType = json["duration_type"]?.jsonPrimitive?.content ?: "24h",
                visibility = json["visibility"]?.jsonPrimitive?.content ?: "all",
                visibilityType = visibilityType,
                excludedIds = excluded,
                createdAt = json["created_at"]?.jsonPrimitive?.content,
                expiresAt = expiresAt,
                username = validUsername ?: "Usuario",
                userDisplayName = finalName,
                userAvatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.content,
                isVerified = profile?.get("is_verified")?.jsonPrimitive?.boolean ?: false,
                caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                isViewed = isViewed,
                allowReplies = json["allow_replies"]?.jsonPrimitive?.boolean ?: true
            )

            // Add or update in list
            var currentList = _moments.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == momentId }
            
            if (existingIndex != -1) {
                // Update existing
                currentList[existingIndex] = newMoment
                android.util.Log.d("MomentRepository", "⚡ Updated moment $momentId in list")
            } else {
                // Add new at the beginning (most recent first)
                currentList.add(0, newMoment)
                android.util.Log.d("MomentRepository", "⚡ Added new moment $momentId to list")
            }
            
            _moments.value = currentList
            
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Error in fetchAndAddMoment", e)
            throw e
        }
    }

    private fun startUbuntuMomentsObservation(internalScope: kotlinx.coroutines.CoroutineScope) {
        val token = authService.getAccessToken()?.trim().orEmpty()
        if (token.isNotBlank()) {
            ubuntuRealtimeClient.updateAccessToken(token)
            ubuntuRealtimeClient.subscribe("moments:live")
        }
        internalScope.launch {
            ubuntuRealtimeClient.events.collect {
                if (it.channel == "moments:live") {
                    refreshMoments()
                }
            }
        }
        internalScope.launch {
            while (internalScope.isActive) {
                kotlinx.coroutines.delay(MOMENTS_FALLBACK_POLL_INTERVAL_MS)
                refreshMoments()
            }
        }
    }

    suspend fun refreshMoments() {
        android.util.Log.d("MomentRepository", ">>> refreshMoments() called")
        try {
            var currentUserId = authService.getCurrentUserId() ?: return
            android.util.Log.d("MomentRepository", "Refreshing for user: $currentUserId")

            if (supabaseConfig.isLocalFallbackPrimary) {
                val token = authService.getAccessToken()?.trim().orEmpty()
                if (token.isBlank()) return
                val feed = localFallbackApiClient.loadMomentsFeed(token).getOrElse { error ->
                    android.util.Log.e("MomentRepository", "Ubuntu moments feed failed", error)
                    return
                }
                val parsed = feed.items.mapNotNull { json ->
                    val momentId = json["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val userId = json["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val visibilityType = json["visibility_type"]?.jsonPrimitive?.contentOrNull ?: "public"
                    if (visibilityType == "silent" && userId != currentUserId) return@mapNotNull null
                    if (visibilityType == "private" && userId != currentUserId) return@mapNotNull null
                    Moment(
                        id = momentId,
                        userId = userId,
                        mediaUrl = json["media_url"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaType = json["media_type"]?.jsonPrimitive?.contentOrNull ?: "image",
                        thumbnailUrl = json["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                        durationHours = json["duration_hours"]?.jsonPrimitive?.intOrNull ?: 24,
                        durationType = json["duration_type"]?.jsonPrimitive?.contentOrNull ?: "24h",
                        visibility = json["visibility"]?.jsonPrimitive?.contentOrNull ?: "all",
                        visibilityType = visibilityType,
                        excludedIds = emptyList(),
                        createdAt = json["created_at"]?.jsonPrimitive?.contentOrNull,
                        expiresAt = json["expires_at"]?.jsonPrimitive?.contentOrNull,
                        username = json["owner_name"]?.jsonPrimitive?.contentOrNull ?: "Usuario",
                        userDisplayName = json["owner_name"]?.jsonPrimitive?.contentOrNull ?: "Usuario",
                        userAvatarUrl = null,
                        isVerified = false,
                        caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                        isViewed = json["viewed_by_me"]?.jsonPrimitive?.booleanOrNull ?: false,
                        allowReplies = json["allow_replies"]?.jsonPrimitive?.booleanOrNull ?: true
                    )
                }
                _moments.value = parsed
                return
            }
            
            // Fetch moments joined with profiles
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                header("Cache-Control", "no-cache") // Standard HTTP cache control
                parameter("select", "*,user:profiles!user_id(username,display_name,full_name,avatar_url,is_verified)")
                // VISIBILITY FIX: Remove ALL filters to guarantee data shows up.
                // parameter("expires_at", "gt.now()")
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                val rawData: List<JsonObject> = response.body()
                
                // Fetch views for isViewed
                var viewsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer ${authService.getAccessToken()}")
                    parameter("user_id", "eq.$currentUserId")
                    parameter("select", "moment_id")
                }
                val viewedMomentIds = if (viewsResponse.status.isSuccess()) {
                    val views: List<JsonObject> = viewsResponse.body()
                    var ids = views.mapNotNull { it["moment_id"]?.jsonPrimitive?.content }.toSet()
                    android.util.Log.d("MomentRepository", "Fetched ${ids.size} viewed moments for user $currentUserId: $ids")
                    ids
                } else {
                    android.util.Log.e("MomentRepository", "Failed to fetch views: ${viewsResponse.status}")
                    emptySet()
                }

                val parsedMoments = rawData.mapNotNull { json ->
                        var visibilityType = json["visibility_type"]?.jsonPrimitive?.content ?: "public"
                        var userId = json["user_id"]?.jsonPrimitive?.content ?: ""
                        var momentId = json["id"]?.jsonPrimitive?.content ?: ""
                        
                        if (visibilityType == "silent" && userId != currentUserId) return@mapNotNull null
                        if (visibilityType == "private" && userId != currentUserId) return@mapNotNull null

                        var excluded = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        if (excluded.contains(currentUserId) && userId != currentUserId) return@mapNotNull null

                        var profileElement = json["user"]
                        var profile = when {
                            profileElement is JsonObject -> profileElement
                            profileElement is JsonArray -> profileElement.firstOrNull()?.jsonObject
                            else -> null
                        }
                        
                        fun sanitize(input: String?): String? {
                             if (input == null) return null
                             if (input.equals("null", ignoreCase = true)) return null
                             if (input.isBlank()) return null
                             return input
                        }

                        val expiresAtString = json["expires_at"]?.jsonPrimitive?.content
                        if (expiresAtString != null) {
                            try {
                                var expiresAt = java.time.OffsetDateTime.parse(expiresAtString)
                                var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                                // Add a 5-minute grace period to prevent issues with minor clock desyncs
                                if (expiresAt.isBefore(now.minusMinutes(5))) {
                                    android.util.Log.d("MomentRepository", "🚫 Filatrando momento expirado: $momentId")
                                    return@mapNotNull null
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("MomentRepository", "Error parseando expires_at: $expiresAtString")
                            }
                        }

                        var rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
                        var rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
                        var rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull

                        var validDisplayName = sanitize(rawDisplayName)
                        var validFullName = sanitize(rawFullName)
                        var validUsername = sanitize(rawUsername)
                        
                        var finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"

                        Moment(
                            id = momentId,
                            userId = userId,
                            mediaUrl = json["media_url"]?.jsonPrimitive?.content ?: "",
                            mediaType = json["media_type"]?.jsonPrimitive?.content ?: "image",
                            thumbnailUrl = json["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                            durationHours = json["duration_hours"]?.jsonPrimitive?.int ?: 24,
                            durationType = json["duration_type"]?.jsonPrimitive?.content ?: "24h",
                            visibility = json["visibility"]?.jsonPrimitive?.content ?: "all",
                            visibilityType = visibilityType,
                            excludedIds = excluded,
                            createdAt = json["created_at"]?.jsonPrimitive?.content,
                            expiresAt = json["expires_at"]?.jsonPrimitive?.content,
                            username = validUsername ?: "Usuario",
                            userDisplayName = finalName,
                            userAvatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.content,
                            isVerified = profile?.get("is_verified")?.jsonPrimitive?.boolean ?: false,
                            caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                            isViewed = viewedMomentIds.contains(momentId),
                            allowReplies = json["allow_replies"]?.jsonPrimitive?.boolean ?: true
                        )
                }
                _moments.value = parsedMoments
            }
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Error refreshing moments", e)
        }
    }

    // Keep this for compatibility if needed, but it just emits the state flow
    fun getMoments(): Flow<List<Moment>> = moments

    suspend fun createMoment(
        mediaUri: Uri,
        mediaType: String,
        durationHours: Int,
        durationType: String,
        visibility: String,
        visibilityType: String,
        excludedIds: List<String>,
        caption: String? = null,
        allowReplies: Boolean = true
    ): Result<Moment> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user logged in"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            // 1. Upload media
            val rawBytes = context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Failed to read media file"))

            val payload = resolveMomentMediaPayload(mediaUri = mediaUri, mediaType = mediaType, bytes = rawBytes)
                .getOrElse { return@withContext Result.failure(it) }

            val uuid = UUID.randomUUID().toString()
            var fileName = "${userId}/$uuid.${payload.extension}"
             
            val uploadResult = storageService.uploadFile("moment-media", fileName, payload.bytes, payload.contentType)
            if (uploadResult.isFailure) return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed"))

            val mediaUrl = storageService.getPublicUrl("moment-media", fileName)

            // 1.1 Generate and Upload Thumbnail
            var thumbnailUrl: String? = null
            try {
                val thumbBytes = if (mediaType == "video") {
                    generateVideoThumbnail(mediaUri)
                } else {
                    // For images, we can either use the original or generate a smaller one.
                    // For now, let's just use the original to be safe and fast.
                    null
                }
                
                if (thumbBytes != null) {
                    val thumbFileName = "${userId}/${uuid}_thumb.jpg"
                    val thumbUpload = storageService.uploadFile("moment-media", thumbFileName, thumbBytes, "image/jpeg")
                    if (thumbUpload.isSuccess) {
                        thumbnailUrl = storageService.getPublicUrl("moment-media", thumbFileName)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MomentRepository", "Thumbnail generation failed", e)
                // Fallback: thumbnailUrl remains null
            }

            // 2. Calculate expiry
            // 2. Calculate expiry (Use UTC to ensure server compatibility)
            var expiresAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(durationHours.toLong()).toString()

            // 3. Insert into DB
            val momentData = buildJsonObject {
                put("user_id", userId)
                put("media_url", mediaUrl)
                put("media_type", mediaType)
                if (thumbnailUrl != null) put("thumbnail_url", thumbnailUrl)
                put("duration_hours", durationHours)
                put("duration_type", durationType)
                put("visibility", visibility)
                put("visibility_type", visibilityType)
                put("excluded_ids", buildJsonArray { excludedIds.forEach { add(it) } })
                put("expires_at", expiresAt)
                if (caption != null) put("caption", caption)
                put("allow_replies", allowReplies)
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(momentData)
            }

            if (response.status.isSuccess()) {
                val createdMoment: Moment = response.body<List<Moment>>().first()
                Result.success(createdMoment)
            } else {
                var errorBody = response.body<String>()
                Result.failure(Exception("Failed to create moment: $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Exception creating moment", e)
            Result.failure(e)
        }
    }

    private data class MomentMediaPayload(
        val bytes: ByteArray,
        val contentType: String,
        val extension: String
    )

    private fun resolveMomentMediaPayload(
        mediaUri: Uri,
        mediaType: String,
        bytes: ByteArray
    ): Result<MomentMediaPayload> {
        if (bytes.isEmpty()) return Result.failure(Exception("Archivo vacío"))

        val declared = canonicalizeContentType(context.contentResolver.getType(mediaUri))
        val guessed = canonicalizeContentType(guessMimeFromUri(mediaUri))
        val inferred = canonicalizeContentType(inferMimeType(bytes))

        val candidates = listOfNotNull(declared, guessed, inferred)

        return when (mediaType.trim().lowercase()) {
            "video" -> {
                var contentType = if (candidates.contains("video/mp4") || candidates.contains("audio/mp4")) {
                    "video/mp4"
                } else {
                    return Result.failure(Exception("Formato de video no soportado. Usa MP4."))
                }
                Result.success(MomentMediaPayload(bytes = bytes, contentType = contentType, extension = "mp4"))
            }
            else -> {
                val supportedImageTypes = setOf("image/jpeg", "image/png", "image/webp")

                var contentType = candidates.firstOrNull { supportedImageTypes.contains(it) }
                if (contentType != null) {
                    var extension = preferredExtensionForMime(contentType) ?: "jpg"
                    return Result.success(MomentMediaPayload(bytes = bytes, contentType = contentType, extension = extension))
                }

                // HEIC/HEIF or other image formats: try to transcode to JPEG.
                val isSomeImage = candidates.any { it.startsWith("image/") }
                if (!isSomeImage) {
                    return Result.failure(Exception("Formato de imagen no soportado. Usa JPG/PNG/WebP."))
                }

                val jpegBytes = transcodeImageToJpeg(mediaUri) ?: return Result.failure(
                    Exception("No se pudo convertir la imagen. Usa JPG/PNG/WebP.")
                )

                Result.success(MomentMediaPayload(bytes = jpegBytes, contentType = "image/jpeg", extension = "jpg"))
            }
        }
    }

    private fun canonicalizeContentType(contentType: String?): String? {
        val clean = contentType?.trim()?.lowercase()?.substringBefore(";")?.trim().orEmpty()
        if (clean.isBlank()) return null
        return when (clean) {
            "image/jpg" -> "image/jpeg"
            else -> clean
        }
    }

    private fun preferredExtensionForMime(contentType: String): String? {
        return when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            else -> null
        }
    }

    private fun guessMimeFromUri(uri: Uri): String? {
        var extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?.lowercase()
            ?.trim()
            ?.ifBlank { null }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun inferMimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null

        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes.getOrNull(8) == 0x57.toByte() &&
            bytes.getOrNull(9) == 0x45.toByte() &&
            bytes.getOrNull(10) == 0x42.toByte() &&
            bytes.getOrNull(11) == 0x50.toByte()
        ) {
            return "image/webp"
        }
        if (
            bytes.getOrNull(4) == 0x66.toByte() &&
            bytes.getOrNull(5) == 0x74.toByte() &&
            bytes.getOrNull(6) == 0x79.toByte() &&
            bytes.getOrNull(7) == 0x70.toByte()
        ) {
            val brand = bytes.copyOfRange(8, 12).decodeToString().lowercase()
            return if (brand.contains("m4a")) "audio/mp4" else "video/mp4"
        }
        return null
    }

    private fun transcodeImageToJpeg(mediaUri: Uri, quality: Int = 92): ByteArray? {
        return try {
            var bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, mediaUri)
                ImageDecoder.decodeBitmap(source)
            } else {
                context.contentResolver.openInputStream(mediaUri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } ?: return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
            out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Image transcode to JPEG failed", e)
            null
        }
    }



    suspend fun recordView(momentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MomentRepository", "========================================")
            android.util.Log.d("MomentRepository", "recordView CALLED for momentId: $momentId")
            
            // Optimistic Update: Update local state immediately
            var currentList = _moments.value.toMutableList()
            var index = currentList.indexOfFirst { it.id == momentId }
            if (index != -1 && !currentList[index].isViewed) {
                currentList[index] = currentList[index].copy(isViewed = true)
                _moments.value = currentList.toList()
                android.util.Log.d("MomentRepository", "✓ Optimistic update: Marked $momentId as viewed")
            } else {
                android.util.Log.d("MomentRepository", "⚠ Moment not found or already viewed (index=$index)")
            }

            var userId = authService.getCurrentUserId()
            if (userId == null) {
                android.util.Log.e("MomentRepository", "✗ No user ID available!")
                return@withContext Result.failure(Exception("No user"))
            }
            android.util.Log.d("MomentRepository", "User ID: $userId")

            var accessToken = authService.getAccessToken()
            if (accessToken == null) {
                android.util.Log.e("MomentRepository", "✗ No access token!")
                return@withContext Result.failure(Exception("No token"))
            }
            android.util.Log.d("MomentRepository", "Access token obtained")

            android.util.Log.d("MomentRepository", "Sending POST to moment_views...")
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("moment_id", momentId)
                    put("user_id", userId)
                })
            }

            android.util.Log.d("MomentRepository", "Response status: ${response.status}")
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.Conflict) {
                android.util.Log.d("MomentRepository", "✓ View recorded successfully (status=${response.status})")
                android.util.Log.d("MomentRepository", "========================================")
                Result.success(Unit)
            } else {
                var errorBody = try { response.body<String>() } catch (e: Exception) { "Unable to read body" }
                android.util.Log.e("MomentRepository", "✗ Failed to record view: ${response.status}, body=$errorBody")
                android.util.Log.d("MomentRepository", "========================================")
                Result.failure(Exception("Failed to record view: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "✗ Exception in recordView: ${e.message}", e)
            android.util.Log.d("MomentRepository", "========================================")
            Result.failure(e)
        }
    }



    suspend fun getMomentViewers(momentId: String): Result<List<MomentView>> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("moment_id", "eq.$momentId")
                parameter("select", "*,profiles(username,display_name,full_name,avatar_url,is_verified)")
                parameter("order", "viewed_at.desc")
            }

            if (response.status.isSuccess()) {
                val rawData: List<JsonObject> = response.body()
                val viewers = rawData.map { json ->
                    var profileElement = json["profiles"]
                    var profile = when {
                        profileElement is JsonObject -> profileElement
                        profileElement is JsonArray -> profileElement.firstOrNull()?.jsonObject
                        else -> null
                    }
                    
                    // Sanitize profile strings (same logic as moments)
                    fun sanitize(input: String?): String? {
                        if (input == null) return null
                        if (input.equals("null", ignoreCase = true)) return null
                        if (input.isBlank()) return null
                        return input
                    }
                    
                    var rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
                    var rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
                    var rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull
                    
                    var validDisplayName = sanitize(rawDisplayName)
                    var validFullName = sanitize(rawFullName)
                    var validUsername = sanitize(rawUsername)
                    
                    // Priority: Display Name > Full Name > Username > "Usuario"
                    var finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"
                    
                    MomentView(
                        id = json["id"]?.jsonPrimitive?.content ?: "",
                        momentId = json["moment_id"]?.jsonPrimitive?.content ?: "",
                        userId = json["user_id"]?.jsonPrimitive?.content ?: "",
                        viewedAt = json["viewed_at"]?.jsonPrimitive?.content,
                        username = validUsername,
                        userDisplayName = finalName,
                        userAvatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.content,
                        isVerified = profile?.get("is_verified")?.jsonPrimitive?.boolean ?: false
                    )
                }
                Result.success(viewers)
            } else {
                Result.failure(Exception("Failed to fetch viewers"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun deleteMoment(momentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            
            // Note: We might want to also delete the file from storage
            // Fetch moment first to get storage path
            
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("id", "eq.$momentId")
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete moment"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    private fun generateVideoThumbnail(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            var bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            
            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                stream.toByteArray()
            } else null
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Error generating thumbnail", e)
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }
}





