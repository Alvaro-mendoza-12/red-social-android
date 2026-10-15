package conectate.com.data.repository

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.Challenge
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.supabase.StorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import conectate.com.data.model.ActiveChallengeDto
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

@Singleton
class ChallengeRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val notificationRepository: NotificationRepository,
    private val localFallbackApiClient: LocalFallbackApiClient,
    @param:ApplicationContext private val context: Context
) {



    suspend fun submitMedia(activeChallenge: ActiveChallenge, bytes: ByteArray, mimeType: String, extension: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

        var storagePath = "challenge_submissions/${activeChallenge.id}/$userId.$extension"
        var bucketName = "challenges"
        var uploadResult = storageService.uploadFile(bucketName, storagePath, bytes, mimeType)

        if (uploadResult.isSuccess) {
            val remoteKey = uploadResult.getOrThrow().Key ?: storagePath
            var url = versionedChallengeUrl(
                UbuntuStorageUrl.rewrite(storageService.getPublicUrl(bucketName, remoteKey)).orEmpty()
            )

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.submitChallenge(
                    accessToken = accessToken,
                    activeChallenge = activeChallenge,
                    contentUrl = url
                ).map { Unit }
            }

            var submissionData = mapOf(
                "challenge_id" to activeChallenge.challengeId,
                "active_challenge_id" to activeChallenge.id,
                "user_id" to userId,
                "content_url" to url
            )

            var dbResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/challenge_submissions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(submissionData)
            }

            if (dbResponse.status.isSuccess()) {
                Result.success(Unit)
            } else {
                var errorBody = dbResponse.bodyAsText()
                Result.failure(Exception("DB Error: ${dbResponse.status} - $errorBody"))
            }
        } else {
            var error = uploadResult.exceptionOrNull()?.message ?: "Unknown upload error"
            Result.failure(Exception("Upload Error: ${humanizeUploadError(error)}"))
        }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun getSubmissions(activeChallengeId: String, minCreatedAt: String? = null): Result<List<conectate.com.data.model.ChallengeSubmission>> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ChallengeRepository", "=== getSubmissions START for $activeChallengeId ===")
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            
            var userId = authService.getCurrentUserId() ?: ""
            android.util.Log.d("ChallengeRepository", "Current UserId: $userId")

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.loadChallengeSubmissions(accessToken, activeChallengeId)
            }
            
            // Using RPC for reliability and bypassing RLS for user names
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/rpc/get_challenge_submissions_voted") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Cache-Control", "no-cache")
                header("Pragma", "no-cache")
                parameter("p_active_challenge_id", activeChallengeId) // Use the Instance ID (Session ID)
            }
 
            android.util.Log.d("ChallengeRepository", "HTTP Response status: ${response.status}")
            
            if (response.status.isSuccess()) {
                var responseText = response.bodyAsText()
                android.util.Log.d("ChallengeRepository", "Response length: ${responseText.length} chars")
                android.util.Log.d("ChallengeRepository", "Response preview: ${responseText.take(200)}")
                
                val jsonConfig = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val rawList = jsonConfig.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(responseText)
                android.util.Log.d("ChallengeRepository", "Parsed ${rawList.size} raw JSON objects")
                
                val submissions = rawList.mapNotNull { json ->
                    try {
                        // Manual parsing for robust User extraction
                        val userJsonElement = json["user_data"]
                        var user = if (userJsonElement != null && userJsonElement !is kotlinx.serialization.json.JsonNull) {
                             try {
                                 val userObj = userJsonElement.jsonObject
                                 var id = userObj["id"]?.jsonPrimitive?.content ?: ""
                                 // Try multiple fields for name to be safe (camelCase and snake_case variants)
                                 fun getSafe(key: String): String? {
                                     var c = userObj[key]?.jsonPrimitive?.content
                                     return if (c == null || c.isEmpty() || c.equals("null", ignoreCase = true)) null else c
                                 }
                                 
                                 val displayName = getSafe("display_name")
                                                 ?: getSafe("displayName")
                                                 ?: getSafe("full_name")
                                                 ?: getSafe("fullName")
                                                 ?: getSafe("username")
                                                 ?: getSafe("name")
                                                 ?: "Usuario"
                                 val avatarUrl = UbuntuStorageUrl.rewrite(getSafe("avatar_url") ?: getSafe("avatarUrl"))
                                 
                                 conectate.com.data.model.User(
                                     id = id,
                                     display_name = displayName,
                                     avatar_url = avatarUrl
                                 )
                             } catch (e: Exception) {
                                 android.util.Log.e("ChallengeRepository", "Error parsing user manually: $userJsonElement", e)
                                 null
                             }
                        } else null
                        
                        // Check if I voted: Try direct boolean property first (Optimized RPC), then scan votes array
                        val votedByMeDirect = json["voted_by_me"]?.jsonPrimitive?.booleanOrNull
                        var votedByMe = if (votedByMeDirect != null) {
                            votedByMeDirect
                        } else {
                            val votesArray = json["votes"]?.jsonArray
                            votesArray?.any { 
                                val voteObj = it.jsonObject
                                var vid = voteObj["voter_id"]?.jsonPrimitive?.content 
                                       ?: voteObj["user_id"]?.jsonPrimitive?.content
                                       ?: voteObj["userId"]?.jsonPrimitive?.content
                                vid == userId 
                            } ?: false
                        }
                        
                        conectate.com.data.model.ChallengeSubmission(
                            id = json["id"]?.toString()?.removeSurrounding("\"") ?: "",
                            challengeId = json["challenge_id"]?.toString()?.removeSurrounding("\"") ?: "",
                            activeChallengeId = json["active_challenge_id"]?.toString()?.removeSurrounding("\"") ?: "",
                            userId = json["user_id"]?.toString()?.removeSurrounding("\"") ?: "",
                            contentUrl = json["content_url"]?.toString()?.let { if (it == "null") null else UbuntuStorageUrl.rewrite(it.removeSurrounding("\"")) },
                            textContent = json["text_content"]?.toString()?.let { if (it == "null") null else it.removeSurrounding("\"") },
                            voteCount = json["vote_count"]?.toString()?.toIntOrNull() ?: 0,
                            votedByMe = votedByMe,
                            createdAt = json["created_at"]?.toString()?.removeSurrounding("\"") ?: "",
                            user = user
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("ChallengeRepository", "Error parsing submission: ${e.message}", e)
                        null
                    }
                }
                android.util.Log.d("ChallengeRepository", "Final submissions count: ${submissions.size}")
                Result.success(submissions)
            } else {
                var error = response.bodyAsText()
                android.util.Log.e("ChallengeRepository", "getSubmissions HTTP ERROR: ${response.status} - $error")
                Result.failure(Exception("Error fetching submissions"))
            }
        } catch (e: Exception) { 
            android.util.Log.e("ChallengeRepository", "getSubmissions EXCEPTION", e)
            Result.failure(e) 
        }
    }



    suspend fun toggleVote(activeChallengeId: String, submissionId: String): Result<Pair<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("Not authenticated"))
            
            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.toggleChallengeVote(accessToken, submissionId)
            }

            android.util.Log.d("ChallengeRepository", "Calling v2026_challenge_vote RPC for submission: $submissionId")
            
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/v2026_challenge_vote") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                contentType(ContentType.Application.Json)
                setBody(mapOf("p_id" to submissionId))
            }

            if (response.status.isSuccess()) {
                val json: JsonObject = response.body()
                val newCount = json["vote_count"]?.jsonPrimitive?.int ?: 0
                var votedByMe = json["voted_by_me"]?.jsonPrimitive?.boolean ?: false
                android.util.Log.d("ChallengeRepository", "Toggle vote SUCCESS - submission: $submissionId, newCount: $newCount, votedByMe: $votedByMe")
                Result.success(Pair(newCount, votedByMe))
            } else {
                var error = response.bodyAsText()
                android.util.Log.e("ChallengeRepository", "Toggle vote RPC failed: $error")
                // Extract useful message if possible, or use full error
                Result.failure(Exception("Error: $error"))
            }
        } catch (e: Exception) { 
            android.util.Log.e("ChallengeRepository", "Exception in toggleVote", e)
            Result.failure(e) 
        }
    }



    suspend fun getMySubmission(activeChallengeId: String, userId: String): Result<conectate.com.data.model.ChallengeSubmission?> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No token"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.loadMyChallengeSubmission(token, activeChallengeId)
            }

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/challenge_submissions") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("active_challenge_id", "eq.$activeChallengeId")
                parameter("user_id", "eq.$userId")
                parameter("select", "*")
                parameter("limit", "1")
            }
            
            if (response.status.isSuccess()) {
                var list = response.body<List<kotlinx.serialization.json.JsonObject>>()
                if (list.isNotEmpty()) {
                    val json = list.first()
                    // Mapping simple (ignoring user object expansion and vote counts as this is just for ID resolution)
                    val sub = conectate.com.data.model.ChallengeSubmission(
                        id = json["id"]?.toString()?.removeSurrounding("\"") ?: "",
                        challengeId = json["challenge_id"]?.toString()?.removeSurrounding("\"") ?: "",
                        activeChallengeId = json["active_challenge_id"]?.toString()?.removeSurrounding("\"") ?: "",
                        userId = json["user_id"]?.toString()?.removeSurrounding("\"") ?: "",
                        contentUrl = json["content_url"]?.toString()?.let { if (it == "null") null else UbuntuStorageUrl.rewrite(it.removeSurrounding("\"")) },
                        textContent = json["text_content"]?.toString()?.let { if (it == "null") null else it.removeSurrounding("\"") },
                        voteCount = 0, // Placeholder
                        votedByMe = false, // Placeholder
                        createdAt = json["created_at"]?.toString()?.removeSurrounding("\"") ?: ""
                    )
                    Result.success(sub)
                } else {
                    Result.success(null)
                }
            } else {
                Result.failure(Exception("Failed to fetch my submission"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun submitText(activeChallenge: ActiveChallenge, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.submitChallenge(
                    accessToken = accessToken,
                    activeChallenge = activeChallenge,
                    textContent = text
                ).map { Unit }
            }

            var submissionData = mapOf(
                "challenge_id" to activeChallenge.challengeId,
                "active_challenge_id" to activeChallenge.id,
                "user_id" to userId,
                "text_content" to text
            )

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/challenge_submissions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                contentType(ContentType.Application.Json)
                setBody(submissionData)
            }

            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception("Post failed"))
        } catch (e: Exception) { Result.failure(e) }
    }



    suspend fun getActiveChallenge(): Result<ActiveChallenge> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("Not authenticated"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.loadActiveChallenge(accessToken)
            }
            var url = supabaseConfig.supabaseUrl
            val key = supabaseConfig.supabaseAnonKey
            
            // 1. Llamar a la función RPC (retorna DTO plano)
            var response = supabaseConfig.httpClient.post("$url/rest/v1/rpc/get_or_generate_active_challenge") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", key)
            }

            if (response.status.isSuccess()) {
                val list: List<ActiveChallengeDto> = response.body()
                if (list.isNotEmpty()) {
                    val dto = list[0]
                    
                    // 2. Obtener detalles del Challenge maestro
                    val challengeResponse = supabaseConfig.httpClient.get("$url/rest/v1/challenges_master") {
                        header("Authorization", "Bearer $accessToken")
                        header("apikey", key)
                        parameter("id", "eq.${dto.challengeId}")
                        parameter("select", "*")
                    }
                    
                    if (challengeResponse.status.isSuccess()) {
                         val challenges: List<Challenge> = challengeResponse.body()
                         if (challenges.isNotEmpty()) {
                             val fullChallenge = ActiveChallenge(
                                 id = dto.activeId,
                                 challengeId = dto.challengeId,
                                 challenge = challenges[0],
                                 expiresAt = dto.expiresAt,
                                 votingExpiresAt = dto.votingExpiresAt,
                                 status = dto.status,
                                 winnerIds = dto.winnerIds
                             )
                             Result.success(fullChallenge)
                         } else {
                             Result.failure(Exception("Challenge master not found for id: ${dto.challengeId}"))
                         }
                    } else {
                        Result.failure(Exception("Failed to fetch challenge details: ${challengeResponse.status}"))
                    }
                } else {
                    Result.failure(Exception("No active challenge returned from RPC"))
                }
            } else {
                var errorBody = response.bodyAsText()
                Result.failure(Exception("RPC failed: ${response.status} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("ChallengeRepository", "Error fetching challenge", e)
            Result.failure(e)
        }
    }



    suspend fun createChallengeMaster(title: String, description: String, type: String, difficulty: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.createChallengeMaster(
                    accessToken,
                    title,
                    description,
                    type,
                    difficulty
                ).map { Unit }
            }
            
            var data = mapOf(
                "title" to title,
                "description" to description,
                "challenge_type" to type,
                "difficulty" to difficulty,
                "creator_id" to userId
            )

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/challenges_master") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                contentType(ContentType.Application.Json)
                setBody(data)
            }

            if (response.status.isSuccess()) Result.success(Unit)
            else {
                var error = response.bodyAsText()
                android.util.Log.e("ChallengeRepository", "Create master failed: $error")
                Result.failure(Exception("Failed to create master challenge"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }



    suspend fun getUserChallenges(): Result<List<Challenge>> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.loadMyChallengeMasters(accessToken)
            }
            
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/challenges_master") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("creator_id", "eq.$userId")
                parameter("order", "created_at.desc")
            }
            
            if (response.status.isSuccess()) {
                val list: List<Challenge> = response.body()
                Result.success(list)
            } else Result.failure(Exception("Fetch failed"))
        } catch (e: Exception) { Result.failure(e) }
    }



    suspend fun deleteChallengeMaster(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("Not authenticated"))

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.deleteChallengeMaster(accessToken, id)
            }
            
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/challenges_master") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$id")
            }
            
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception("Delete failed"))
        } catch (e: Exception) { Result.failure(e) }
    }



    suspend fun submitDrawing(activeChallenge: ActiveChallenge, lines: List<conectate.com.presentation.components.Line>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            val drawableLines = lines.filter { it.points.isNotEmpty() }
            if (drawableLines.isEmpty()) {
                return@withContext Result.failure(Exception("Dibuja algo antes de enviarlo."))
            }

            // 1. Convertir dibujo a imagen
            var bytes = drawingToBytes(drawableLines)
            
            // 2. Subir a Storage
            var storagePath = "challenge_submissions/${activeChallenge.id}/$userId.png"
            var bucketName = "challenges"
            var uploadResult = storageService.uploadFile(bucketName, storagePath, bytes, "image/png")
            
            if (uploadResult.isSuccess) {
                val remoteKey = uploadResult.getOrThrow().Key ?: storagePath
                var url = versionedChallengeUrl(
                    UbuntuStorageUrl.rewrite(storageService.getPublicUrl(bucketName, remoteKey)).orEmpty()
                )

                if (supabaseConfig.isLocalFallbackPrimary) {
                    return@withContext localFallbackApiClient.submitChallenge(
                        accessToken = accessToken,
                        activeChallenge = activeChallenge,
                        contentUrl = url
                    ).map { Unit }
                }
                
                // 3. Guardar en DB
                var submissionData = mapOf(
                    "challenge_id" to activeChallenge.challengeId,
                    "active_challenge_id" to activeChallenge.id,
                    "user_id" to userId,
                    "content_url" to url
                )

                var dbResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/challenge_submissions") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    setBody(submissionData)
                }

                if (dbResponse.status.isSuccess()) {
                    Result.success(Unit)
                } else {
                    var errorBody = dbResponse.bodyAsText()
                    Result.failure(Exception("DB Error: ${dbResponse.status} - $errorBody"))
                }
            } else {
                var error = uploadResult.exceptionOrNull()?.message ?: "Unknown upload error"
                Result.failure(Exception("Upload Error: ${humanizeUploadError(error)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun submitMedia(activeChallenge: ActiveChallenge, uri: android.net.Uri, mimeType: String, extension: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) 
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            
            var bytes = inputStream.use { it.readMediaBytesWithLimit(30L * 1024 * 1024) }
            
            submitMedia(activeChallenge, bytes, mimeType, extension)
        } catch (e: Exception) { 
            Result.failure(e) 
        }
    }



    suspend fun submitAudio(activeChallenge: ActiveChallenge, file: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var bytes = file.inputStream().use { it.readMediaBytesWithLimit(30L * 1024 * 1024) }
            submitMedia(activeChallenge, bytes, "audio/mp4", "mp4")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun checkParticipation(activeChallengeId: String, minCreatedAt: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.hasChallengeParticipation(accessToken, activeChallengeId)
            }

            // FIXED: Filter by Master Challenge ID to allow participation in new contents even if active_id is reused
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/challenge_submissions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("challenge_id", "eq.$activeChallengeId") // Filter by Master ID
                if (minCreatedAt != null) {
                    parameter("created_at", "gte.$minCreatedAt")
                }
                parameter("user_id", "eq.$userId")
                parameter("select", "id")
                parameter("limit", "1")
            }
            
            android.util.Log.d("ChallengeRepository", "checkParticipation for $activeChallengeId: status ${response.status}")

            if (response.status.isSuccess()) {
                var responseText = response.bodyAsText()
                var list = kotlinx.serialization.json.Json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(responseText)
                val exists = list.isNotEmpty()
                android.util.Log.d("ChallengeRepository", "checkParticipation RESULT: $exists (records: ${list.size})")
                Result.success(exists)
            } else {
                var error = response.bodyAsText()
                android.util.Log.e("ChallengeRepository", "checkParticipation FAILED: $error")
                Result.failure(Exception("Check failed"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun passTorch(challengeTitle: String, friendId: String): Result<Unit> {
        return notificationRepository.sendNotification(
            recipientId = friendId,
            type = "challenge_invite",
            content = "Te pasaron una Ronda Novvin: $challengeTitle. ¡Tienes 10 minutos!",
            extraData = buildJsonObject {
                put("challenge_title", challengeTitle)
            }
        )
    }

    fun observeActiveChallenge(): Flow<ActiveChallenge> = flow {
        try {
            if (supabaseConfig.isLocalFallbackPrimary) {
                while (true) {
                    getActiveChallenge().onSuccess { emit(it) }
                    delay(15_000)
                }
            }

            var channel = supabaseConfig.client.realtime.channel("active-challenges-realtime")
            var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "active_challenges"
            }
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "active-challenges"
            )
            
            changes.collect { action ->
                if (action is PostgresAction.Update || action is PostgresAction.Insert) {
                    // Necesitamos volver a pedir el reto completo porque record no trae el join con challenge_master
                    getActiveChallenge().onSuccess { emit(it) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChallengeRepository", "Error observing active challenge", e)
        }
    }

    fun observeSubmissions(activeChallengeId: String, minCreatedAt: String? = null): Flow<List<conectate.com.data.model.ChallengeSubmission>> = flow {
        if (supabaseConfig.isLocalFallbackPrimary) {
            while (true) {
                emit(getSubmissions(activeChallengeId, minCreatedAt).getOrNull().orEmpty())
                delay(5_000)
            }
        }

        val channelId = "subs-${activeChallengeId}-${System.currentTimeMillis()}"
        var channel = supabaseConfig.client.realtime.channel(channelId)
        
        try {
            // 1. Carga inicial inmediata
            val initial = getSubmissions(activeChallengeId, minCreatedAt).getOrNull() ?: emptyList()
            emit(initial)

            // 2. Configurar listener de cambios (INSERT, UPDATE, DELETE)
            var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "challenge_submissions"
                filter = "active_challenge_id=eq.$activeChallengeId"
            }

            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "challenge-submissions-$activeChallengeId"
            )
            android.util.Log.d("ChallengeRepository", "📡 Realtime Subscribed: $channelId")

            // 3. Loop infinito escuchando cambios
            changes.collect { action ->
                android.util.Log.d("ChallengeRepository", "🔔 Realtime Event: ${action::class.simpleName}")
                delay(500) // Debounce para evitar recargas excesivas si entran muchos votos juntos
                
                val updatedList = getSubmissions(activeChallengeId, minCreatedAt).getOrNull()
                if (updatedList != null) {
                    emit(updatedList)
                    android.util.Log.d("ChallengeRepository", "🔄 List refreshed: ${updatedList.size} items")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChallengeRepository", "❌ Error in observeSubmissions flow", e)
        } finally {
            android.util.Log.d("ChallengeRepository", "🔌 Unsubscribing channel: $channelId")
            channel.unsubscribe()
        }
    }

    private fun humanizeUploadError(rawError: String): String {
        val clean = rawError.trim()
        val lower = clean.lowercase()
        return when {
            lower.contains("media_size_not_allowed") ->
                "El archivo supera el tamano permitido para este reto."
            lower.contains("unsupported content type") ->
                "Formato de archivo no permitido para retos."
            lower.contains("unsafe_media_path") ->
                "Ruta de archivo invalida."
            lower.contains("rate_limit_media_register") ->
                "Demasiadas subidas en poco tiempo. Intenta en unos segundos."
            lower.contains("media_duplicate_spam") ->
                "Detectamos demasiados envios duplicados. Intenta con otro archivo."
            lower.contains("guard_rate_limit_window_start_ambiguous") ->
                "Backend desactualizado: aplica el hotfix SQL de guard_rate_limit y vuelve a intentar."
            lower.contains("register_media_object_not_available") ->
                "Falta la funcion register_media_object en Supabase. Ejecuta la migracion fase 2."
            lower.contains("media_policy_blocked") &&
                (lower.contains("pgrst202") || lower.contains("schema cache") || lower.contains("could not find the function")) ->
                "Falta aplicar la migracion de backend de media en Supabase. Ejecuta el script de fase 2."
            else -> clean
        }
    }

    private fun drawingToBytes(lines: List<conectate.com.presentation.components.Line>): ByteArray {
        // Find drawing bounds so the exported PNG matches what the user drew.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        lines.forEach { line ->
            line.points.forEach { (x, y) ->
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }

        val padding = 72f
        val drawingWidth = (maxX - minX).coerceAtLeast(1f)
        val drawingHeight = (maxY - minY).coerceAtLeast(1f)
        val targetWidth = 1080f
        val targetHeight = 1080f

        val bitmap = Bitmap.createBitmap(targetWidth.toInt(), targetHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val scale = minOf(
            (targetWidth - padding * 2) / drawingWidth,
            (targetHeight - padding * 2) / drawingHeight
        ).coerceAtLeast(0.01f)
        val contentWidth = drawingWidth * scale
        val contentHeight = drawingHeight * scale
        val offsetX = (targetWidth - contentWidth) / 2f
        val offsetY = (targetHeight - contentHeight) / 2f

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            style = android.graphics.Paint.Style.STROKE
        }

        lines.forEach { line ->
            paint.color = line.color
            paint.strokeWidth = (line.strokeWidth * scale).coerceIn(2f, 96f)
            
            val path = android.graphics.Path()
            if (line.points.isNotEmpty()) {
                val startX = (line.points[0].first - minX) * scale + offsetX
                val startY = (line.points[0].second - minY) * scale + offsetY
                path.moveTo(startX, startY)

                line.points.drop(1).forEach { (px, py) ->
                    val x = (px - minX) * scale + offsetX
                    val y = (py - minY) * scale + offsetY
                    path.lineTo(x, y)
                }
            }
            if (line.points.size == 1) {
                canvas.drawCircle(targetWidth / 2f, targetHeight / 2f, paint.strokeWidth / 2f, paint)
            } else {
                canvas.drawPath(path, paint)
            }
        }

        return try {
            val out = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "No se pudo exportar el dibujo."
            }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun versionedChallengeUrl(url: String): String {
        if (url.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}v=${System.currentTimeMillis()}"
    }
}





