package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.merge
import android.graphics.Bitmap
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
    private val storageService: StorageService,
    @ApplicationContext private val context: Context
) {

    private val _moments = MutableStateFlow<List<Moment>>(emptyList())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()
    
    // Coroutine scope for repository operations
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    
    // Store channels to allow explicit cleanup
    private var momentsChannel: RealtimeChannel? = null
    private var viewsChannel: RealtimeChannel? = null

    private var observationJob: kotlinx.coroutines.Job? = null

    init {
        // Observe auth session changes to restart Realtime with the correct token
        scope.launch {
            authService.currentSession.collect { session ->
                val userId = session?.user?.id
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
        // Initial fetch
        refreshMoments()
        
        // Real-time subscriptions
        try {
            // IMPORTANTE: Conectar el cliente de Realtime para habilitar WebSockets
            android.util.Log.d("MomentRepository", "⚡ Intentando conectar a Realtime...")
            
            // Monitor connection status
            internalScope.launch {
                supabaseConfig.client.realtime.status.collect { status ->
                    android.util.Log.d("MomentRepository", "⚡ Realtime Status: $status")
                }
            }

            supabaseConfig.client.realtime.connect()
            android.util.Log.d("MomentRepository", "⚡ Realtime.connect() invocado")

            // 0. AUTO-POLLING FALLBACK (Start this EARLY and INDEPENDENTLY)
            internalScope.launch {
                while (true) {
                    try {
                        android.util.Log.d("MomentRepository", "💓 Auto-Polling Refresh...")
                        refreshMoments()
                    } catch (e: Exception) {
                        android.util.Log.e("MomentRepository", "💓 Polling error", e)
                    }
                    kotlinx.coroutines.delay(15_000)
                }
            }

            // Cleanup Previous Channels
            if (momentsChannel != null) {
                android.util.Log.d("MomentRepository", "⚡ Limpiando canal anterior moments...")
                momentsChannel?.unsubscribe()
                supabaseConfig.client.realtime.removeChannel(momentsChannel!!)
                momentsChannel = null
            }
            if (viewsChannel != null) {
                android.util.Log.d("MomentRepository", "⚡ Limpiando canal anterior views...")
                viewsChannel?.unsubscribe()
                supabaseConfig.client.realtime.removeChannel(viewsChannel!!)
                viewsChannel = null
            }

            // 1. Moments Channel (Dynamic ID to guarantee fresh connection)
            val channelId = "moments_updates_${java.util.UUID.randomUUID()}"
            android.util.Log.d("MomentRepository", "⚡ Suscribiendo a canal DINÁMICO: $channelId")
            
            val newMomentsChannel = supabaseConfig.client.realtime.channel(channelId)
            val momentsChanges = newMomentsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "moments"
            }
            momentsChannel = newMomentsChannel

            // 2. Views Channel (Dynamic ID)
            val currentUserId = authService.getCurrentUserId()
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
                                val id = action.oldRecord["id"]?.jsonPrimitive?.content
                                if (id != null) {
                                    android.util.Log.d("MomentRepository", "⚡ DELETE detectado: $id - Eliminando localmente")
                                    _moments.value = _moments.value.filter { it.id != id }
                                } else {
                                    // FALLBACK NUCLEAR: Si no llega ID (por RLS), recargamos TODO.
                                    android.util.Log.w("MomentRepository", "⚡ DELETE sin ID (Bloqueado por RLS?), ejecutando RECARGA TOTAL...")
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
            momentsChannel?.subscribe(blockUntilSubscribed = false)
            viewsChannel?.subscribe(blockUntilSubscribed = false)
            android.util.Log.d("MomentRepository", "⚡ Suscripciones solicitadas (asíncronas)")
            
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "⚡ Error setting up realtime", e)
        }
    }

    private suspend fun fetchAndAddMoment(momentId: String) {
        try {
            val currentUserId = authService.getCurrentUserId() ?: return
            
            // Fetch the specific moment with profile join
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
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
            val visibilityType = json["visibility_type"]?.jsonPrimitive?.content ?: "public"
            val userId = json["user_id"]?.jsonPrimitive?.content ?: ""
            
            if (visibilityType == "silent" && userId != currentUserId) return
            if (visibilityType == "private" && userId != currentUserId) return

            val excluded = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            if (excluded.contains(currentUserId) && userId != currentUserId) return

            // Check if expired
            val expiresAt = json["expires_at"]?.jsonPrimitive?.content
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
            val viewsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("user_id", "eq.$currentUserId")
                parameter("moment_id", "eq.$momentId")
                parameter("select", "moment_id")
                parameter("limit", "1")
            }
            val isViewed = if (viewsResponse.status.isSuccess()) {
                val views: List<JsonObject> = viewsResponse.body()
                views.isNotEmpty()
            } else {
                false
            }

            val profileElement = json["user"]
            val profile = when {
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

            val rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
            val rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
            val rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull

            val validDisplayName = sanitize(rawDisplayName)
            val validFullName = sanitize(rawFullName)
            val validUsername = sanitize(rawUsername)
            
            val finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"

            if (expiresAt != null) {
                try {
                    val expires = java.time.OffsetDateTime.parse(expiresAt)
                    val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
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
            val currentList = _moments.value.toMutableList()
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

    suspend fun refreshMoments() {
        android.util.Log.d("MomentRepository", ">>> refreshMoments() called")
        try {
            val currentUserId = authService.getCurrentUserId() ?: return
            android.util.Log.d("MomentRepository", "Refreshing for user: $currentUserId")
            
            // Fetch moments joined with profiles
                val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
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
                val viewsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer ${authService.getAccessToken()}")
                    parameter("user_id", "eq.$currentUserId")
                    parameter("select", "moment_id")
                }
                val viewedMomentIds = if (viewsResponse.status.isSuccess()) {
                    val views: List<JsonObject> = viewsResponse.body()
                    val ids = views.mapNotNull { it["moment_id"]?.jsonPrimitive?.content }.toSet()
                    android.util.Log.d("MomentRepository", "Fetched ${ids.size} viewed moments for user $currentUserId: $ids")
                    ids
                } else {
                    android.util.Log.e("MomentRepository", "Failed to fetch views: ${viewsResponse.status}")
                    emptySet()
                }

                val parsedMoments = rawData.mapNotNull { json ->
                        val visibilityType = json["visibility_type"]?.jsonPrimitive?.content ?: "public"
                        val userId = json["user_id"]?.jsonPrimitive?.content ?: ""
                        val momentId = json["id"]?.jsonPrimitive?.content ?: ""
                        
                        if (visibilityType == "silent" && userId != currentUserId) return@mapNotNull null
                        if (visibilityType == "private" && userId != currentUserId) return@mapNotNull null

                        val excluded = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        if (excluded.contains(currentUserId) && userId != currentUserId) return@mapNotNull null

                        val profileElement = json["user"]
                        val profile = when {
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
                                val expiresAt = java.time.OffsetDateTime.parse(expiresAtString)
                                val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                                // Add a 5-minute grace period to prevent issues with minor clock desyncs
                                if (expiresAt.isBefore(now.minusMinutes(5))) {
                                    android.util.Log.d("MomentRepository", "🚫 Filatrando momento expirado: $momentId")
                                    return@mapNotNull null
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("MomentRepository", "Error parseando expires_at: $expiresAtString")
                            }
                        }

                        val rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
                        val rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
                        val rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull

                        val validDisplayName = sanitize(rawDisplayName)
                        val validFullName = sanitize(rawFullName)
                        val validUsername = sanitize(rawUsername)
                        
                        val finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"

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
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user logged in"))
            val accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            // 1. Upload media
            val bytes = context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Failed to read media file"))

            val uuid = UUID.randomUUID().toString()
            val ext = if (mediaType == "video") "mp4" else "jpg"
            val mime = if (mediaType == "video") "video/mp4" else "image/jpeg"
            val fileName = "${userId}/$uuid.$ext"
            
            val uploadResult = storageService.uploadFile("moment-media", fileName, bytes, mime)
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
            val expiresAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(durationHours.toLong()).toString()

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

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
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
                val errorBody = response.body<String>()
                Result.failure(Exception("Failed to create moment: $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Exception creating moment", e)
            Result.failure(e)
        }
    }

    suspend fun recordView(momentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MomentRepository", "========================================")
            android.util.Log.d("MomentRepository", "recordView CALLED for momentId: $momentId")
            
            // Optimistic Update: Update local state immediately
            val currentList = _moments.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == momentId }
            if (index != -1 && !currentList[index].isViewed) {
                currentList[index] = currentList[index].copy(isViewed = true)
                _moments.value = currentList.toList()
                android.util.Log.d("MomentRepository", "✓ Optimistic update: Marked $momentId as viewed")
            } else {
                android.util.Log.d("MomentRepository", "⚠ Moment not found or already viewed (index=$index)")
            }

            val userId = authService.getCurrentUserId()
            if (userId == null) {
                android.util.Log.e("MomentRepository", "✗ No user ID available!")
                return@withContext Result.failure(Exception("No user"))
            }
            android.util.Log.d("MomentRepository", "User ID: $userId")

            val accessToken = authService.getAccessToken()
            if (accessToken == null) {
                android.util.Log.e("MomentRepository", "✗ No access token!")
                return@withContext Result.failure(Exception("No token"))
            }
            android.util.Log.d("MomentRepository", "Access token obtained")

            android.util.Log.d("MomentRepository", "Sending POST to moment_views...")
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
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
                val errorBody = try { response.body<String>() } catch (e: Exception) { "Unable to read body" }
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
            val accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("moment_id", "eq.$momentId")
                parameter("select", "*,profiles(username,display_name,full_name,avatar_url,is_verified)")
                parameter("order", "viewed_at.desc")
            }

            if (response.status.isSuccess()) {
                val rawData: List<JsonObject> = response.body()
                val viewers = rawData.map { json ->
                    val profileElement = json["profiles"]
                    val profile = when {
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
                    
                    val rawUsername = profile?.get("username")?.jsonPrimitive?.contentOrNull
                    val rawDisplayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
                    val rawFullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull
                    
                    val validDisplayName = sanitize(rawDisplayName)
                    val validFullName = sanitize(rawFullName)
                    val validUsername = sanitize(rawUsername)
                    
                    // Priority: Display Name > Full Name > Username > "Usuario"
                    val finalName = validDisplayName ?: validFullName ?: validUsername ?: "Usuario"
                    
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
            val accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            
            // Note: We might want to also delete the file from storage
            // Fetch moment first to get storage path
            
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
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
            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
