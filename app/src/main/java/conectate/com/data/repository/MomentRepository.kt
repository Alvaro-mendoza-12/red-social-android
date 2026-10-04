package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.music.isApprovedCommonsAssetUrl
import conectate.com.data.music.isApprovedCommonsPageUrl
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
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
import java.io.File
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
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        private const val MOMENTS_FALLBACK_POLL_INTERVAL_MS = 45_000L
        private const val MAX_MOMENT_VIDEO_DURATION_SECONDS = 25L
        private const val MAX_MOMENT_VIDEO_DURATION_MS = MAX_MOMENT_VIDEO_DURATION_SECONDS * 1_000L
        private const val MAX_MOMENT_MEDIA_BYTES = 35L * 1024 * 1024
        private const val MAX_MOMENT_MUSIC_BYTES = 80L * 1024 * 1024
        private const val IMAGE_MOMENT_DISPLAY_DURATION_MS = 6_000L
        private const val LOCAL_MOMENT_MUSIC_SOURCE = "novvin_moment_local"
    }

    private val _moments = MutableStateFlow<List<Moment>>(emptyList())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()
    
    // Coroutine scope for repository operations
    private val scope = newSafeScope("MomentRepository", Dispatchers.IO)
    
    // Store channels to allow explicit cleanup
    private var momentsChannel: RealtimeChannel? = null
    private var viewsChannel: RealtimeChannel? = null

    private var observationJob: kotlinx.coroutines.Job? = null
    private val locallyDeletedMomentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun canonicalMomentId(momentId: String): String =
        momentId.trim().lowercase()

    private fun shouldUseUbuntuBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())

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
        if (shouldUseUbuntuBackend()) {
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
                displayDurationMs = momentDisplayDurationMs(json),
                durationHours = json["duration_hours"]?.jsonPrimitive?.int ?: 24,
                durationType = json["duration_type"]?.jsonPrimitive?.content ?: "24h",
                visibility = json["visibility"]?.jsonPrimitive?.content ?: "all",
                visibilityType = visibilityType,
                excludedIds = excluded,
                audienceIds = json["audience_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                createdAt = json["created_at"]?.jsonPrimitive?.content,
                expiresAt = expiresAt,
                username = validUsername ?: "Usuario",
                userDisplayName = finalName,
                userAvatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.content,
                isVerified = profile?.get("is_verified")?.jsonPrimitive?.boolean ?: false,
                caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                isViewed = isViewed,
                allowReplies = json["allow_replies"]?.jsonPrimitive?.boolean ?: true,
                overlayText = json["overlay_text"]?.jsonPrimitive?.contentOrNull,
                overlayColor = json["overlay_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFFFF",
                overlayFont = json["overlay_font"]?.jsonPrimitive?.contentOrNull ?: "sans",
                overlayScale = json["overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                overlayX = json["overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.5f,
                overlayY = json["overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.38f,
                musicUrl = UbuntuStorageUrl.rewrite(json["music_url"]?.jsonPrimitive?.contentOrNull),
                musicAudioUrl = UbuntuStorageUrl.rewrite(json["music_audio_url"]?.jsonPrimitive?.contentOrNull),
                musicId = json["music_id"]?.jsonPrimitive?.contentOrNull,
                musicTitle = json["music_title"]?.jsonPrimitive?.contentOrNull,
                musicArtist = json["music_artist"]?.jsonPrimitive?.contentOrNull,
                musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(json["music_presentation"]?.jsonPrimitive?.contentOrNull).wireValue,
                musicArtworkUrl = UbuntuStorageUrl.rewrite(json["music_artwork_url"]?.jsonPrimitive?.contentOrNull),
                musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(json["music_overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f),
                musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(json["music_overlay_rotation"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f),
                musicLyrics = json["music_lyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 12_000 },
                musicSource = json["music_source"]?.jsonPrimitive?.contentOrNull,
                musicSourceUrl = json["music_source_url"]?.jsonPrimitive?.contentOrNull,
                musicLicense = json["music_license"]?.jsonPrimitive?.contentOrNull,
                musicLicenseUrl = json["music_license_url"]?.jsonPrimitive?.contentOrNull,
                musicStartMs = json["music_start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                musicDurationMs = json["music_duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 6_000L,
                musicEndMs = json["music_end_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                musicVolume = json["music_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                originalAudioVolume = json["original_audio_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                musicFadeInMs = json["music_fade_in_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                musicFadeOutMs = json["music_fade_out_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                duckOriginalAudio = json["duck_original_audio"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                musicEmbedded = json["music_embedded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            ).normalizedStorageUrls()

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

            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessToken()?.trim().orEmpty()
                if (token.isBlank()) return
                val feed = localFallbackApiClient.loadMomentsFeed(token).getOrElse { error ->
                    android.util.Log.e("MomentRepository", "Ubuntu moments feed failed", error)
                    return
                }
                val parsed = feed.items.mapNotNull { json ->
                    val momentId = json["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (locallyDeletedMomentIds.contains(canonicalMomentId(momentId))) return@mapNotNull null
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
                        displayDurationMs = momentDisplayDurationMs(json),
                        durationHours = json["duration_hours"]?.jsonPrimitive?.intOrNull ?: 24,
                        durationType = json["duration_type"]?.jsonPrimitive?.contentOrNull ?: "24h",
                        visibility = json["visibility"]?.jsonPrimitive?.contentOrNull ?: "all",
                        visibilityType = visibilityType,
                        excludedIds = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        audienceIds = json["audience_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        createdAt = json["created_at"]?.jsonPrimitive?.contentOrNull,
                        expiresAt = json["expires_at"]?.jsonPrimitive?.contentOrNull,
                        username = json["owner_name"]?.jsonPrimitive?.contentOrNull ?: "Usuario",
                        userDisplayName = json["owner_name"]?.jsonPrimitive?.contentOrNull ?: "Usuario",
                        userAvatarUrl = UbuntuStorageUrl.rewrite(json["owner_avatar_url"]?.jsonPrimitive?.contentOrNull),
                        isVerified = json["is_verified"]?.jsonPrimitive?.booleanOrNull ?: false,
                        caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                        isViewed = json["viewed_by_me"]?.jsonPrimitive?.booleanOrNull ?: false,
                        allowReplies = json["allow_replies"]?.jsonPrimitive?.booleanOrNull ?: true,
                        overlayText = json["overlay_text"]?.jsonPrimitive?.contentOrNull,
                        overlayColor = json["overlay_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFFFF",
                        overlayFont = json["overlay_font"]?.jsonPrimitive?.contentOrNull ?: "sans",
                        overlayScale = json["overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                        overlayX = json["overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.5f,
                        overlayY = json["overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.38f,
                        musicUrl = UbuntuStorageUrl.rewrite(json["music_url"]?.jsonPrimitive?.contentOrNull),
                        musicAudioUrl = UbuntuStorageUrl.rewrite(json["music_audio_url"]?.jsonPrimitive?.contentOrNull),
                        musicId = json["music_id"]?.jsonPrimitive?.contentOrNull,
                        musicTitle = json["music_title"]?.jsonPrimitive?.contentOrNull,
                        musicArtist = json["music_artist"]?.jsonPrimitive?.contentOrNull,
                musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(json["music_presentation"]?.jsonPrimitive?.contentOrNull).wireValue,
                musicArtworkUrl = UbuntuStorageUrl.rewrite(json["music_artwork_url"]?.jsonPrimitive?.contentOrNull),
                musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(json["music_overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f),
                musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(json["music_overlay_rotation"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f),
                musicLyrics = json["music_lyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 12_000 },
                        musicSource = json["music_source"]?.jsonPrimitive?.contentOrNull,
                        musicSourceUrl = json["music_source_url"]?.jsonPrimitive?.contentOrNull,
                        musicLicense = json["music_license"]?.jsonPrimitive?.contentOrNull,
                        musicLicenseUrl = json["music_license_url"]?.jsonPrimitive?.contentOrNull,
                        musicStartMs = json["music_start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        musicDurationMs = json["music_duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 6_000L,
                        musicEndMs = json["music_end_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        musicVolume = json["music_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                        originalAudioVolume = json["original_audio_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                        musicFadeInMs = json["music_fade_in_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        musicFadeOutMs = json["music_fade_out_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        duckOriginalAudio = json["duck_original_audio"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        musicEmbedded = json["music_embedded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        reactionCount = json["reaction_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        isLikedByMe = json["reacted_by_me"]?.jsonPrimitive?.booleanOrNull ?: false
                    ).normalizedStorageUrls()
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
                        if (locallyDeletedMomentIds.contains(canonicalMomentId(momentId))) return@mapNotNull null
                        
                        if (visibilityType == "silent" && userId != currentUserId) return@mapNotNull null
                        if (visibilityType == "private" && userId != currentUserId) return@mapNotNull null

                        var excluded = json["excluded_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        val audienceIds = json["audience_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        if (excluded.contains(currentUserId) && userId != currentUserId) return@mapNotNull null
                        val audience = json["visibility"]?.jsonPrimitive?.contentOrNull ?: "all"
                        if (audience == "only_selected" && userId != currentUserId && currentUserId !in audienceIds) return@mapNotNull null

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
                            displayDurationMs = momentDisplayDurationMs(json),
                            durationHours = json["duration_hours"]?.jsonPrimitive?.int ?: 24,
                            durationType = json["duration_type"]?.jsonPrimitive?.content ?: "24h",
                            visibility = json["visibility"]?.jsonPrimitive?.content ?: "all",
                            visibilityType = visibilityType,
                            excludedIds = excluded,
                            audienceIds = audienceIds,
                            createdAt = json["created_at"]?.jsonPrimitive?.content,
                            expiresAt = json["expires_at"]?.jsonPrimitive?.content,
                            username = validUsername ?: "Usuario",
                            userDisplayName = finalName,
                            userAvatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.content,
                            isVerified = profile?.get("is_verified")?.jsonPrimitive?.boolean ?: false,
                            caption = json["caption"]?.jsonPrimitive?.contentOrNull,
                            isViewed = viewedMomentIds.contains(momentId),
                            allowReplies = json["allow_replies"]?.jsonPrimitive?.boolean ?: true,
                            overlayText = json["overlay_text"]?.jsonPrimitive?.contentOrNull,
                            overlayColor = json["overlay_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFFFF",
                            overlayFont = json["overlay_font"]?.jsonPrimitive?.contentOrNull ?: "sans",
                            overlayScale = json["overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                            overlayX = json["overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.5f,
                            overlayY = json["overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.38f,
                            musicUrl = UbuntuStorageUrl.rewrite(json["music_url"]?.jsonPrimitive?.contentOrNull),
                            musicAudioUrl = UbuntuStorageUrl.rewrite(json["music_audio_url"]?.jsonPrimitive?.contentOrNull),
                            musicId = json["music_id"]?.jsonPrimitive?.contentOrNull,
                            musicTitle = json["music_title"]?.jsonPrimitive?.contentOrNull,
                            musicArtist = json["music_artist"]?.jsonPrimitive?.contentOrNull,
                musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(json["music_presentation"]?.jsonPrimitive?.contentOrNull).wireValue,
                musicArtworkUrl = UbuntuStorageUrl.rewrite(json["music_artwork_url"]?.jsonPrimitive?.contentOrNull),
                musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(json["music_overlay_y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: .5f),
                musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(json["music_overlay_scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f),
                musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(json["music_overlay_rotation"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f),
                musicLyrics = json["music_lyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 12_000 },
                            musicSource = json["music_source"]?.jsonPrimitive?.contentOrNull,
                            musicSourceUrl = json["music_source_url"]?.jsonPrimitive?.contentOrNull,
                            musicLicense = json["music_license"]?.jsonPrimitive?.contentOrNull,
                            musicLicenseUrl = json["music_license_url"]?.jsonPrimitive?.contentOrNull,
                            musicStartMs = json["music_start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            musicDurationMs = json["music_duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 6_000L,
                            musicEndMs = json["music_end_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            musicVolume = json["music_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                            originalAudioVolume = json["original_audio_volume"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f,
                            musicFadeInMs = json["music_fade_in_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            musicFadeOutMs = json["music_fade_out_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            duckOriginalAudio = json["duck_original_audio"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                            musicEmbedded = json["music_embedded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        ).normalizedStorageUrls()
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
        audienceIds: List<String> = emptyList(),
        caption: String? = null,
        allowReplies: Boolean = true,
        overlayText: String? = null,
        overlayColor: String = "#FFFFFFFF",
        overlayFont: String = "sans",
        overlayScale: Float = 1f,
        overlayX: Float = 0.5f,
        overlayY: Float = 0.38f,
        musicUri: Uri? = null,
        musicRemoteUrl: String? = null,
        musicTitle: String? = null,
        musicId: String? = null,
        musicArtist: String? = null,
        musicPresentation: String = "card",
        musicArtworkUrl: String? = null,
        musicSource: String? = null,
        musicSourceUrl: String? = null,
        musicLicense: String? = null,
        musicLicenseUrl: String? = null,
        musicStartMs: Long = 0L,
        musicDurationMs: Long = 6_000L,
        musicVolume: Float = 1f,
        originalAudioVolume: Float = 1f,
        musicFadeInMs: Long = 0L,
        musicFadeOutMs: Long = 0L,
        duckOriginalAudio: Boolean = false,
        musicEmbedded: Boolean = false,
        musicOverlayX: Float = .5f,
        musicOverlayY: Float = .5f,
        musicOverlayScale: Float = 1f,
        musicOverlayRotation: Float = 0f,
        musicLyrics: String? = null,
    ): Result<Moment> = withContext(Dispatchers.IO) {
        val uploadedObjects = mutableListOf<Pair<String, String>>()
        var metadataPersisted = false
        var metadataSubmissionAttempted = false
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user logged in"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            // 1. Upload media
            val sourceSizeBytes = queryUriSizeBytes(mediaUri)
            if (sourceSizeBytes > MAX_MOMENT_MEDIA_BYTES) {
                return@withContext Result.failure(Exception("El archivo supera el límite de 35 MB."))
            }
            val requestedVideo = mediaType.trim().lowercase() == "video"
            val rawBytes = if (requestedVideo) {
                readUriHeaderBytes(mediaUri)
            } else {
                context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
            }
                ?: return@withContext Result.failure(Exception("Failed to read media file"))

            val payload = resolveMomentMediaPayload(mediaUri = mediaUri, mediaType = mediaType, bytes = rawBytes)
                .getOrElse { return@withContext Result.failure(it) }
            val normalizedMediaType = if (payload.contentType.startsWith("video/")) "video" else "image"
            val displayDurationMs = if (normalizedMediaType == "video") {
                val durationMs = readVideoDurationMs(mediaUri)
                    ?: return@withContext Result.failure(Exception("No se pudo validar la duración del video."))
                if (durationMs > MAX_MOMENT_VIDEO_DURATION_MS) {
                    val durationSeconds = kotlin.math.ceil(durationMs / 1000.0).toLong()
                    return@withContext Result.failure(
                        Exception("El momento dura ${durationSeconds}s. Máximo permitido: ${MAX_MOMENT_VIDEO_DURATION_SECONDS}s.")
                    )
                }
                durationMs.coerceIn(1_000L, MAX_MOMENT_VIDEO_DURATION_MS)
            } else {
                // A still-image Moment always owns a six-second visual window.
                // Audio metadata must never shorten or extend that window.
                IMAGE_MOMENT_DISPLAY_DURATION_MS
            }

            val uuid = UUID.randomUUID().toString()
            var fileName = "${userId}/$uuid.${payload.extension}"
             
            val uploadResult = if (normalizedMediaType == "video") {
                val mediaSizeBytes = sourceSizeBytes
                if (mediaSizeBytes <= 0L) {
                    return@withContext Result.failure(Exception("No se pudo validar el tamaño del video."))
                }
                storageService.uploadFileFromUri(
                    bucketName = "moment-media",
                    fileName = fileName,
                    sourceUri = mediaUri,
                    sizeBytes = mediaSizeBytes,
                    contentType = payload.contentType,
                    allowOverwrite = true,
                    onProgress = {}
                )
            } else {
                storageService.uploadFile("moment-media", fileName, payload.bytes, payload.contentType)
            }
            if (uploadResult.isFailure) return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed"))

            val mediaRemoteKey = uploadResult.getOrThrow().Key ?: fileName
            uploadedObjects += "moment-media" to mediaRemoteKey
            val mediaUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("moment-media", mediaRemoteKey)).orEmpty()

            var effectiveMusicId = musicId
            var effectiveMusicTitle = musicTitle
            var effectiveMusicArtist = musicArtist
            var effectiveMusicSource = musicSource
            var effectiveMusicSourceUrl = musicSourceUrl
            var effectiveMusicLicense = musicLicense
            var effectiveMusicLicenseUrl = musicLicenseUrl
            var requestedMusicUrl = musicRemoteUrl?.trim()?.takeIf { it.isNotBlank() }

            if (musicUri != null) {
                val localMusicSize = queryUriSizeBytes(musicUri)
                if (localMusicSize !in 1..MAX_MOMENT_MUSIC_BYTES) {
                    return@withContext Result.failure(
                        Exception("La música seleccionada no es válida o supera el límite de 80 MB."),
                    )
                }
                val localMusicContentType = resolveMomentMusicContentType(musicUri)
                    ?: return@withContext Result.failure(Exception("Este formato de audio no es compatible."))
                val localMusicExtension = momentMusicExtension(localMusicContentType)
                val localMusicName = "$userId/moments/$uuid.$localMusicExtension"
                val localMusicUpload = storageService.uploadFileFromUri(
                    bucketName = "community-music",
                    fileName = localMusicName,
                    sourceUri = musicUri,
                    sizeBytes = localMusicSize,
                    contentType = localMusicContentType,
                    allowOverwrite = false,
                    onProgress = {},
                ).getOrElse { return@withContext Result.failure(it) }
                val localMusicRemoteKey = localMusicUpload.Key ?: localMusicName
                uploadedObjects += "community-music" to localMusicRemoteKey
                requestedMusicUrl = UbuntuStorageUrl.rewrite(
                    storageService.getPublicUrl("community-music", localMusicRemoteKey),
                )
                effectiveMusicId = musicId?.takeIf { it.startsWith("device:") } ?: "device:$uuid"
                effectiveMusicTitle = musicTitle?.trim()?.takeIf(String::isNotBlank) ?: "Audio del dispositivo"
                effectiveMusicArtist = musicArtist?.trim()?.takeIf(String::isNotBlank) ?: "Audio del dispositivo"
                effectiveMusicSource = LOCAL_MOMENT_MUSIC_SOURCE
                effectiveMusicSourceUrl = null
                effectiveMusicLicense = "Uso aportado por el usuario"
                effectiveMusicLicenseUrl = null
            }
            val hasMusic = requestedMusicUrl != null
            val normalizedMusicLicense = effectiveMusicLicense?.trim()?.lowercase().orEmpty()
            if (hasMusic) {
                val approvedCommonsLicense = normalizedMusicLicense == "cc0 1.0" ||
                    normalizedMusicLicense == "cc0" ||
                    normalizedMusicLicense == "public domain"
                val approvedCommons = effectiveMusicSource == "wikimedia_commons" &&
                    effectiveMusicId?.startsWith("commons:") == true &&
                    requestedMusicUrl!!.isApprovedCommonsAssetUrl() &&
                    effectiveMusicSourceUrl.orEmpty().isApprovedCommonsPageUrl() &&
                    approvedCommonsLicense
                val approvedCommunity = effectiveMusicSource == "novvin_community" &&
                    effectiveMusicId?.startsWith("community:") == true &&
                    requestedMusicUrl!!.startsWith("https://")
                val approvedExternal = effectiveMusicSource in setOf("openverse", "jamendo") &&
                    effectiveMusicId?.startsWith("${effectiveMusicSource}:") == true &&
                    requestedMusicUrl!!.startsWith("https://") &&
                    effectiveMusicSourceUrl.orEmpty().startsWith("https://") &&
                    approvedCommonsLicense
                val approvedOwnedLocal = effectiveMusicSource == LOCAL_MOMENT_MUSIC_SOURCE &&
                    effectiveMusicId?.startsWith("device:") == true &&
                    requestedMusicUrl!!.startsWith("http")
                if ((!approvedCommons && !approvedCommunity && !approvedExternal && !approvedOwnedLocal) ||
                    effectiveMusicTitle.isNullOrBlank() || effectiveMusicArtist.isNullOrBlank()
                ) {
                    return@withContext Result.failure(
                        Exception("La pista no está aprobada para reutilizarse en Momentos."),
                    )
                }
            }
            val musicUrl = requestedMusicUrl
            val safeMusicStartMs = musicStartMs.coerceAtLeast(0L)
            val safeMusicDurationMs = if (hasMusic) {
                if (normalizedMediaType == "image") {
                    IMAGE_MOMENT_DISPLAY_DURATION_MS
                } else {
                    val upper = displayDurationMs.coerceAtLeast(1_000L)
                    musicDurationMs.coerceIn(minOf(5_000L, upper), upper)
                }
            } else 6_000L
            val safeMusicEndMs = safeMusicStartMs + safeMusicDurationMs

            // 1.1 Generate and Upload Thumbnail
            var thumbnailUrl: String? = null
            try {
                val thumbBytes = if (normalizedMediaType == "video") {
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
                        val thumbRemoteKey = thumbUpload.getOrThrow().Key ?: thumbFileName
                        uploadedObjects += "moment-media" to thumbRemoteKey
                        thumbnailUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("moment-media", thumbRemoteKey))
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
                put("media_type", normalizedMediaType)
                if (thumbnailUrl != null) put("thumbnail_url", thumbnailUrl)
                put("duration_hours", durationHours)
                put("duration_type", durationType)
                put("visibility", visibility)
                put("visibility_type", visibilityType)
                put("excluded_ids", buildJsonArray { excludedIds.forEach { add(it) } })
                put("audience_ids", buildJsonArray { audienceIds.forEach { add(it) } })
                put("expires_at", expiresAt)
                if (caption != null) put("caption", caption)
                put("allow_replies", allowReplies)
                overlayText?.takeIf { it.isNotBlank() }?.let { put("overlay_text", it.take(120)) }
                put("overlay_color", overlayColor)
                put("overlay_font", overlayFont)
                put("overlay_scale", overlayScale.coerceIn(0.7f, 2.1f))
                put("overlay_x", overlayX.coerceIn(0f, 1f))
                put("overlay_y", overlayY.coerceIn(0f, 1f))
                musicUrl?.let { put("music_url", it) }
                musicUrl?.let { put("music_audio_url", it) }
                effectiveMusicId?.takeIf { hasMusic }?.let { put("music_id", it) }
                effectiveMusicTitle?.takeIf { it.isNotBlank() }?.let { put("music_title", it.take(100)) }
                effectiveMusicArtist?.takeIf { hasMusic && it.isNotBlank() }?.let { put("music_artist", it.take(120)) }
                effectiveMusicSource?.takeIf { hasMusic }?.let { put("music_source", it) }
                effectiveMusicSourceUrl?.takeIf { hasMusic && it.isNotBlank() }?.let { put("music_source_url", it) }
                effectiveMusicLicense?.takeIf { hasMusic }?.let { put("music_license", it) }
                effectiveMusicLicenseUrl?.takeIf { hasMusic && it.startsWith("https://") }?.let { put("music_license_url", it) }
                put("music_presentation", conectate.com.data.model.MomentMusicPresentation.fromWire(musicPresentation).wireValue)
                musicArtworkUrl?.takeIf { hasMusic && it.startsWith("https://") }?.let { put("music_artwork_url", it) }
                put("music_overlay_x", conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayX))
                put("music_overlay_y", conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayY))
                put("music_overlay_scale", conectate.com.data.model.normalizedMomentMusicScale(musicOverlayScale))
                put("music_overlay_rotation", conectate.com.data.model.normalizedMomentMusicRotation(musicOverlayRotation))
                musicLyrics?.takeIf { hasMusic && it.length <= 12_000 }?.let { put("music_lyrics", it) }
                put("music_start_ms", safeMusicStartMs)
                put("music_duration_ms", safeMusicDurationMs)
                put("music_end_ms", safeMusicEndMs)
                put("music_volume", musicVolume.coerceIn(0f, 1f))
                put("original_audio_volume", originalAudioVolume.coerceIn(0f, 1f))
                put("music_fade_in_ms", musicFadeInMs.coerceIn(0L, 5_000L))
                put("music_fade_out_ms", musicFadeOutMs.coerceIn(0L, 5_000L))
                put("duck_original_audio", duckOriginalAudio)
                put("music_embedded", musicEmbedded && normalizedMediaType == "video" && hasMusic)
            }

            if (shouldUseUbuntuBackend()) {
                // A timeout or a lost response cannot tell us whether the API
                // committed the moment. Keep its media until reconciliation so
                // a successfully published moment never loses its video/audio.
                metadataSubmissionAttempted = true
                val createdMoment = localFallbackApiClient.createMoment(
                    accessToken = accessToken,
                    mediaUrl = mediaUrl,
                    mediaType = normalizedMediaType,
                    thumbnailUrl = thumbnailUrl,
                    displayDurationMs = displayDurationMs,
                    durationHours = durationHours,
                    durationType = durationType,
                    visibility = visibility,
                    visibilityType = visibilityType,
                    excludedIds = excludedIds,
                    audienceIds = audienceIds,
                    caption = caption,
                    allowReplies = allowReplies,
                    overlayText = overlayText,
                    overlayColor = overlayColor,
                    overlayFont = overlayFont,
                    overlayScale = overlayScale,
                    overlayX = overlayX,
                    overlayY = overlayY,
                    musicUrl = musicUrl,
                    musicTitle = effectiveMusicTitle,
                    musicId = effectiveMusicId,
                    musicArtist = effectiveMusicArtist,
                    musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(musicPresentation).wireValue,
                    musicArtworkUrl = musicArtworkUrl,
                    musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayX),
                    musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayY),
                    musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(musicOverlayScale),
                    musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(musicOverlayRotation),
                    musicLyrics = musicLyrics?.takeIf { it.length <= 12_000 },
                    musicSource = effectiveMusicSource,
                    musicSourceUrl = effectiveMusicSourceUrl,
                    musicLicense = effectiveMusicLicense,
                    musicLicenseUrl = effectiveMusicLicenseUrl,
                    musicStartMs = safeMusicStartMs,
                    musicDurationMs = safeMusicDurationMs,
                    musicEndMs = safeMusicEndMs,
                    musicVolume = musicVolume.coerceIn(0f, 1f),
                    originalAudioVolume = originalAudioVolume.coerceIn(0f, 1f),
                    musicFadeInMs = musicFadeInMs.coerceIn(0L, 5_000L),
                    musicFadeOutMs = musicFadeOutMs.coerceIn(0L, 5_000L),
                    duckOriginalAudio = duckOriginalAudio,
                    musicEmbedded = musicEmbedded && normalizedMediaType == "video" && hasMusic,
                ).getOrElse { return@withContext Result.failure(it) }
                metadataPersisted = true
                refreshMoments()
                return@withContext Result.success(createdMoment.normalizedStorageUrls())
            }

            // A direct PostgREST insert cannot independently revalidate the current
            // Wikimedia metadata. Licensed music is therefore accepted only through
            // the Novvin API route that performs the authoritative Commons lookup.
            if (hasMusic) {
                return@withContext Result.failure(
                    Exception("No se pudo validar la licencia con el servidor de Novvin. Revisa la conexión e inténtalo de nuevo."),
                )
            }

            metadataSubmissionAttempted = true
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(momentData)
            }

            if (response.status.isSuccess()) {
                metadataPersisted = true
                val createdMoment: Moment = response.body<List<Moment>>().first()
                Result.success(createdMoment)
            } else {
                var errorBody = response.body<String>()
                Result.failure(Exception("Failed to create moment: $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Exception creating moment", e)
            Result.failure(e)
        } finally {
            if (!metadataPersisted && !metadataSubmissionAttempted) {
                cleanupUploadedMomentObjects(uploadedObjects)
            } else if (!metadataPersisted && uploadedObjects.isNotEmpty()) {
                android.util.Log.w(
                    "MomentRepository",
                    "La publicación del momento quedó sin confirmar; se conserva su contenido para reconciliación.",
                )
            }
        }
    }

    private suspend fun cleanupUploadedMomentObjects(objects: List<Pair<String, String>>) {
        objects.asReversed().forEach { (bucket, key) ->
            storageService.deleteFile(bucket, key).onFailure { error ->
                android.util.Log.w("MomentRepository", "No se pudo limpiar $bucket/$key", error)
            }
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

    private fun readUriHeaderBytes(uri: Uri, maxBytes: Int = 512): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            val read = stream.read(buffer)
            if (read > 0) buffer.copyOf(read) else null
        }
    }

    private fun queryUriSizeBytes(uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val fileLength = uri.path?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L
            if (fileLength > 0L) return fileLength
        }
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getLong(index)
                    if (value > 0L) return value
                }
            }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        return 0L
    }

    private fun resolveMomentMusicContentType(uri: Uri): String? {
        val resolverType = context.contentResolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        val supported = setOf(
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "application/ogg",
            "audio/opus",
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/webm",
        )
        if (resolverType in supported) return resolverType
        return when (uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            else -> null
        }
    }

    private fun momentMusicExtension(contentType: String): String = when (contentType) {
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/aac" -> "m4a"
        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/webm" -> "webm"
        else -> "wav"
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

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.recordMomentView(accessToken, momentId)
                return@withContext result
            }

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

    suspend fun setMomentLiked(momentId: String, liked: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val original = _moments.value
        _moments.value = original.map { moment ->
            if (moment.id == momentId) {
                val delta = if (liked && !moment.isLikedByMe) 1 else if (!liked && moment.isLikedByMe) -1 else 0
                moment.copy(
                    isLikedByMe = liked,
                    reactionCount = (moment.reactionCount + delta).coerceAtLeast(0)
                )
            } else moment
        }
        try {
            val accessToken = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No token"))
            val result = if (shouldUseUbuntuBackend()) {
                localFallbackApiClient.setMomentReaction(accessToken, momentId, liked)
            } else {
                val userId = authService.getCurrentUserId()
                    ?: return@withContext Result.failure(Exception("No user"))
                val response = if (liked) {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moment_reactions") {
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Authorization", "Bearer $accessToken")
                        header("Content-Type", "application/json")
                        header("Prefer", "resolution=merge-duplicates")
                        setBody(buildJsonObject {
                            put("moment_id", momentId)
                            put("user_id", userId)
                            put("reaction", "like")
                        })
                    }
                } else {
                    supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/moment_reactions") {
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Authorization", "Bearer $accessToken")
                        parameter("moment_id", "eq.$momentId")
                        parameter("user_id", "eq.$userId")
                    }
                }
                if (response.status.isSuccess()) Result.success(Unit)
                else Result.failure(Exception("No se pudo actualizar la reacción"))
            }
            if (result.isFailure) _moments.value = original
            result
        } catch (error: Exception) {
            _moments.value = original
            Result.failure(error)
        }
    }



    suspend fun getMomentViewers(momentId: String): Result<List<MomentView>> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            if (shouldUseUbuntuBackend()) {
                return@withContext localFallbackApiClient.loadMomentViewers(accessToken, momentId)
            }

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
                    ).normalizedStorageUrls()
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
        val cleanMomentId = canonicalMomentId(momentId)
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            if (cleanMomentId.isBlank()) return@withContext Result.failure(Exception("Moment invalido"))
            locallyDeletedMomentIds.add(cleanMomentId)
            _moments.value = _moments.value.filterNot { canonicalMomentId(it.id) == cleanMomentId }

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.deleteMoment(accessToken, momentId)
                if (result.isSuccess) {
                    _moments.value = _moments.value.filterNot { canonicalMomentId(it.id) == cleanMomentId }
                } else {
                    locallyDeletedMomentIds.remove(cleanMomentId)
                }
                return@withContext result
            }
            
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
                locallyDeletedMomentIds.remove(cleanMomentId)
                Result.failure(Exception("Failed to delete moment"))
            }
        } catch (e: Exception) {
            if (cleanMomentId.isNotBlank()) {
                locallyDeletedMomentIds.remove(cleanMomentId)
            }
            Result.failure(e)
        }
    }

    private fun readVideoDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
        } catch (e: Exception) {
            android.util.Log.e("MomentRepository", "Error reading moment video duration", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
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

    private fun momentDisplayDurationMs(json: JsonObject): Long? {
        val direct = json.longValue(
            "display_duration_ms",
            "displayDurationMs",
            "duration_ms",
            "durationMs",
            "duration_milliseconds"
        )
        if (direct > 0L) return direct

        val seconds = json.longValue("duration_seconds", "durationSeconds")
        if (seconds > 0L) return seconds * 1_000L

        return null
    }

    private fun JsonObject.longValue(vararg keys: String): Long {
        for (key in keys) {
            val primitive = this[key]?.jsonPrimitive ?: continue
            primitive.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return 0L
    }

    private fun Moment.normalizedStorageUrls(): Moment = copy(
        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
        thumbnailUrl = UbuntuStorageUrl.rewrite(thumbnailUrl),
        userAvatarUrl = UbuntuStorageUrl.rewrite(userAvatarUrl),
        musicUrl = UbuntuStorageUrl.rewrite(musicUrl)
    )

    private fun MomentView.normalizedStorageUrls(): MomentView = copy(
        userAvatarUrl = UbuntuStorageUrl.rewrite(userAvatarUrl)
    )
}





