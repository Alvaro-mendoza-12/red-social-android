package conectate.com.data.localfallback

import conectate.com.data.model.Comment
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.model.Notification
import conectate.com.data.model.Post
import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.Chat
import conectate.com.data.model.Challenge
import conectate.com.data.model.ChallengeSubmission
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LocalFallbackUser(
    val id: String = "",
    val email: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val supabaseUserId: String? = null,
    val syncSource: String? = null,
    val syncStatus: String? = null,
    val syncError: String? = null,
    val localPasswordSetAt: String? = null,
    val passwordChangedByUserAt: String? = null,
    val supabaseSyncedAt: String? = null,
    val accountCreatedAt: String? = null,
    val passwordChangeRequired: Boolean = false,
    val createdAt: String? = null,
    val lastLoginAt: String? = null
)

@Serializable
data class LocalFallbackAuthResponse(
    val user: LocalFallbackUser,
    val accessToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: String = "7d"
)

@Serializable
data class LocalFallbackInboxItem(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val updatedAt: String = "",
    val kind: String = "chat",
    val avatarUrl: String = "",
    val profileId: String? = null,
    val unreadCount: Int = 0,
    val lastMessageId: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageContentType: String? = null,
    val isMember: Boolean = true,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val isPublic: Boolean = true,
    val onlyAdminsCanTalk: Boolean = false,
    val folders: List<String> = emptyList(),
    val openPath: String? = null
)

@Serializable
data class LocalFallbackInboxResponse(
    val items: List<LocalFallbackInboxItem> = emptyList()
)

@Serializable
data class LocalFallbackParticipant(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val role: String = "member",
    val joinedAt: String = "",
    val lastReadAt: String = ""
)

@Serializable
data class LocalFallbackChatSettings(
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val translateEnabled: Boolean = false,
    val targetLang: String = "es"
)

@Serializable
data class LocalFallbackChatResponse(
    val chat: JsonObject? = null,
    val title: String = "",
    val otherParticipant: LocalFallbackParticipant? = null,
    val participants: List<LocalFallbackParticipant> = emptyList(),
    val messages: List<JsonObject> = emptyList(),
    val settings: LocalFallbackChatSettings = LocalFallbackChatSettings()
)

@Serializable
data class LocalFallbackChatPreferenceRequest(
    val isMuted: Boolean? = null,
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
    val isFavorite: Boolean? = null
)

@Serializable
private data class LocalFallbackRawChatResponse(
    val chat: JsonObject? = null
)

@Serializable
data class LocalFallbackProfileStats(
    val posts: Int = 0,
    val videos: Int = 0,
    val chats: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val reportsSent: Int = 0
)

@Serializable
data class LocalFallbackProfileResponse(
    val profile: JsonObject? = null,
    val stats: LocalFallbackProfileStats = LocalFallbackProfileStats()
)

@Serializable
private data class LocalFallbackProfilesResponse(
    val items: List<JsonObject> = emptyList()
)

data class LocalFallbackFeedResponse(
    val items: List<Post> = emptyList()
)

data class LocalFallbackPostDetailResponse(
    val post: Post,
    val comments: List<Comment> = emptyList()
)

@Serializable
data class LocalFallbackStorageObject(
    val id: String = "",
    val bucket: String = "",
    val path: String = "",
    val publicUrl: String = "",
    val url: String = "",
    val contentType: String = "application/octet-stream",
    val size: Long = 0L,
    val ownerId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
private data class LocalFallbackStorageResponse(
    val `object`: LocalFallbackStorageObject
)

@Serializable
private data class LocalFallbackRawStorageUploadResponse(
    val Key: String? = null,
    val path: String = "",
    val publicUrl: String = "",
    val public_url: String? = null
)

@Serializable
private data class LocalFallbackStorageListResponse(
    val objects: List<LocalFallbackStorageObject> = emptyList()
)

@Serializable
private data class LocalFallbackLoginRequest(
    val email: String,
    val password: String
)

@Serializable
private data class LocalFallbackRegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null
)

@Serializable
private data class LocalFallbackChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackPasswordResetRequest(
    val email: String
)

@Serializable
private data class LocalFallbackPasswordResetConfirmRequest(
    val resetToken: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackCompleteMigrationRequest(
    val migrationToken: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackDirectPasswordResetRequest(
    val email: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackSendMessageRequest(
    val id: String? = null,
    val content: String,
    val contentType: String,
    val mediaUrl: String = "",
    val mediaSize: Long = 0L,
    val replyToId: String = ""
)

@Serializable
private data class LocalFallbackCreateDirectChatRequest(
    val participantId: String
)

@Serializable
data class LocalFallbackFriendRequest(
    val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("receiver_id") val receiverId: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
private data class LocalFallbackFriendListResponse(
    val items: List<LocalFallbackFriendRequest> = emptyList()
)

@Serializable
private data class LocalFallbackFriendMutationResponse(
    val request: LocalFallbackFriendRequest? = null,
    val items: List<LocalFallbackFriendRequest> = emptyList(),
    val deleted: Int = 0
)

@Serializable
private data class LocalFallbackFriendCreateRequest(
    val receiverId: String
)

@Serializable
private data class LocalFallbackFriendStatusRequest(
    val status: String
)

@Serializable
private data class LocalFallbackTypingRequest(
    val conversationId: String,
    val targetType: String,
    val isTyping: Boolean,
    val displayName: String = ""
)

@Serializable
private data class LocalFallbackCreateCollectionRequest(
    val id: String? = null,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val isPublic: Boolean = true,
    val memberIds: List<String> = emptyList()
)

@Serializable
private data class LocalFallbackCreateCollectionResponse(
    val id: String = "",
    val room: JsonObject? = null
)

@Serializable
private data class LocalFallbackCreateVoiceRoomRequest(
    val id: String? = null,
    val name: String,
    val description: String? = null,
    val isPublic: Boolean = true,
    val maxParticipants: Int = 50,
    val agoraChannelName: String? = null,
    val readableId: String? = null
)

@Serializable
private data class LocalFallbackMembershipMutationRequest(
    val userId: String? = null,
    val role: String? = null
)

@Serializable
private data class LocalFallbackUpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val country: String? = null,
    val city: String? = null,
    val status: String? = null,
    val avatarUrl: String? = null,
    val coverUrl: String? = null,
    val isPrivate: Boolean? = null,
    val profileVisibility: String? = null,
    val lastSeenVisibility: String? = null,
    val onlineStatusVisibility: String? = null
)

@Serializable
private data class LocalFallbackPresenceRequest(
    val isOnline: Boolean
)

@Serializable
private data class LocalFallbackCreatePostRequest(
    val content: String,
    val mediaUrl: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val mediaType: String? = null,
    val visibility: String = "public"
)

@Serializable
private data class LocalFallbackPatchPostRequest(
    val content: String? = null,
    val visibility: String? = null
)

@Serializable
private data class LocalFallbackCreateCommentRequest(
    val content: String,
    val parentId: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String? = null
)

@Serializable
private data class LocalFallbackReactionRequest(
    val reactionType: String = "LIKE"
)

@Serializable
private data class LocalFallbackErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val migrationToken: String? = null,
    val email: String? = null,
    val displayName: String? = null
)

@Serializable
private data class LocalFallbackMessageResponse(
    val message: Message
)

@Serializable
private data class LocalFallbackGroupMessageResponse(
    val message: GroupMessage
)

@Serializable
private data class LocalFallbackCommunityMessageResponse(
    val message: CommunityMessage
)

@Serializable
data class LocalFallbackVoiceRoomState(
    val room: VoiceRoom,
    val participants: List<VoiceParticipant> = emptyList()
)

@Serializable
private data class LocalFallbackVoiceRoomsResponse(
    val rooms: List<JsonObject> = emptyList(),
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackVoiceRoomResponse(
    val room: JsonObject? = null,
    val participants: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackVoiceJoinRequest(
    val userName: String? = null,
    val userAvatarUrl: String? = null,
    val socketId: String? = null,
    val isMuted: Boolean = false
)

@Serializable
private data class LocalFallbackVoiceParticipantsResponse(
    val participants: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawFeedResponse(
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawPostDetailResponse(
    val post: JsonObject? = null,
    val comments: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawPostResponse(
    val post: JsonObject? = null
)

@Serializable
private data class LocalFallbackRawCommentResponse(
    val comment: JsonObject? = null
)

@Serializable
data class LocalFallbackNotificationsResponse(
    val items: List<JsonObject> = emptyList(),
    val unreadCount: Int = 0
)

data class LocalFallbackNotificationList(
    val items: List<Notification> = emptyList(),
    val unreadCount: Int = 0
)

@Serializable
private data class LocalFallbackNotificationResponse(
    val notification: JsonObject? = null
)

@Serializable
private data class LocalFallbackCreateNotificationRequest(
    val recipientId: String,
    val type: String,
    val targetId: String? = null,
    val targetType: String? = null,
    val content: String? = null,
    val extraData: JsonObject = buildJsonObject {}
)

@Serializable
private data class LocalFallbackFcmTokenRequest(
    val token: String,
    val platform: String = "android"
)

@Serializable
private data class LocalFallbackRawVideosResponse(
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawVideoResponse(
    val video: JsonObject? = null
)

@Serializable
private data class LocalFallbackRawVideoCommentsResponse(
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawVideoCommentResponse(
    val comment: JsonObject? = null
)

data class LocalFallbackVideoShareResult(
    val shareCount: Int?,
    val sharedByMe: Boolean
)

@Serializable
private data class LocalFallbackRawVideoShareResponse(
    @SerialName("share_count") val shareCountSnake: Int? = null,
    val shareCount: Int? = null,
    @SerialName("shared_by_me") val sharedByMeSnake: Boolean? = null,
    val sharedByMe: Boolean? = null
) {
    fun normalized(defaultSharedByMe: Boolean): LocalFallbackVideoShareResult =
        LocalFallbackVideoShareResult(
            shareCount = shareCountSnake ?: shareCount,
            sharedByMe = sharedByMeSnake ?: sharedByMe ?: defaultSharedByMe
        )
}

@Serializable
private data class LocalFallbackCreateVideoRequest(
    val title: String,
    val description: String,
    val videoUrl: String,
    val thumbnailUrl: String? = null,
    val coverUrl: String? = null,
    val durationSeconds: Long = 0,
    val isPublic: Boolean = true,
    val tags: List<String> = emptyList()
)

@Serializable
private data class LocalFallbackCreateVideoCommentRequest(
    val content: String,
    val contentType: String = "text",
    val mediaUrl: String? = null,
    val parentId: String? = null
)

@Serializable
private data class LocalFallbackRawActiveChallengeResponse(
    val activeChallenge: JsonObject? = null
)

@Serializable
private data class LocalFallbackRawChallengesResponse(
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawChallengeResponse(
    val challenge: JsonObject? = null
)

@Serializable
private data class LocalFallbackRawChallengeSubmissionsResponse(
    val items: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawChallengeSubmissionResponse(
    val submission: JsonObject? = null
)

@Serializable
private data class LocalFallbackChallengeSubmissionRequest(
    val challengeId: String,
    val contentUrl: String? = null,
    val textContent: String? = null
)

@Serializable
private data class LocalFallbackCreateChallengeRequest(
    val title: String,
    val description: String,
    val type: String,
    val difficulty: String
)

@Serializable
data class LocalFallbackVoteResponse(
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("voted_by_me") val votedByMe: Boolean = false
)

@Serializable
data class LocalFallbackParticipationResponse(
    val participating: Boolean = false
)

class LocalFallbackMigratedPasswordResetRequiredException(
    val migrationToken: String,
    val email: String,
    val displayName: String
) : Exception("Tu cuenta fue migrada a Ubuntu. Cambia tu contraseña para continuar.")

class LocalFallbackMigratedPasswordSetupRequiredException(
    val email: String,
    val displayName: String,
    val migrationToken: String
) : Exception("Por la migración a Ubuntu, esta cuenta necesita crear una contraseña una sola vez.")

@Serializable
data class LocalFallbackPasswordResetResponse(
    val ok: Boolean = true,
    val delivery: String? = null,
    val resetToken: String? = null,
    val expiresInMinutes: Int? = null
)

@Singleton
class LocalFallbackApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {
    val baseUrl: String
        get() = UbuntuStorageUrl.baseUrl
    val isConfigured: Boolean
        get() = UbuntuStorageUrl.isConfigured

    private val json = supabaseConfig.json

    suspend fun login(email: String, password: String): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val normalizedEmail = email.trim().lowercase()
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/login") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackLoginRequest(normalizedEmail, password))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String?
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/register") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackRegisterRequest(
                        email = email.trim().lowercase(),
                        password = password,
                        displayName = displayName?.trim()?.takeIf { it.isNotBlank() }
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/change-password") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackChangePasswordRequest(currentPassword, newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun requestPasswordReset(email: String): Result<LocalFallbackPasswordResetResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/password-reset/request") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPasswordResetRequest(email.trim().lowercase()))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackPasswordResetResponse>(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun confirmPasswordReset(
        resetToken: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/password-reset/confirm") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPasswordResetConfirmRequest(resetToken.trim(), newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun directPasswordReset(
        email: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val reset = requestPasswordReset(email).getOrElse { return Result.failure(it) }
        val resetToken = reset.resetToken?.trim().orEmpty()
        if (resetToken.isBlank()) {
            return Result.failure(
                Exception("Ubuntu generó el restablecimiento, pero no devolvió token en esta configuración. Usa el token entregado por el canal configurado.")
            )
        }
        return confirmPasswordReset(resetToken, newPassword)
    }

    suspend fun loadMessagesInbox(accessToken: String, tab: String = "chats"): Result<LocalFallbackInboxResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackInboxResponse>("$baseUrl/v1/data/messages/inbox?tab=${urlEncode(tab)}", accessToken)
            .map { it.normalized() }
    }

    suspend fun loadChat(accessToken: String, chatId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackChatResponse>("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}", accessToken)
            .map { it.normalized() }
    }

    suspend fun markChatAsRead(accessToken: String, chatId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/read") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun hideChat(accessToken: String, chatId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun clearChat(accessToken: String, chatId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/clear") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateChatPreferences(
        accessToken: String,
        chatId: String,
        isMuted: Boolean? = null,
        isPinned: Boolean? = null,
        isArchived: Boolean? = null,
        isFavorite: Boolean? = null
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/preferences") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackChatPreferenceRequest(
                        isMuted = isMuted,
                        isPinned = isPinned,
                        isArchived = isArchived,
                        isFavorite = isFavorite
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun markCollectionAsRead(accessToken: String, collection: String, roomId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanCollection = when (collection.trim().lowercase()) {
            "groups", "group" -> "groups"
            "communities", "community" -> "communities"
            else -> return Result.failure(Exception("Colección inválida."))
        }
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/$cleanCollection/${urlEncode(roomId)}/read") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createDirectChat(accessToken: String, participantId: String): Result<Chat> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCreateDirectChatRequest(participantId = participantId))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val chat = json.decodeFromString<LocalFallbackRawChatResponse>(body).chat
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el chat."))
                Result.success(mapChat(chat))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setNickname(accessToken: String, targetUserId: String, nickname: String?): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val cleanNickname = nickname?.trim().orEmpty()
            val response = if (cleanNickname.isBlank()) {
                supabaseConfig.httpClient.delete("$baseUrl/v1/data/messages/nicknames/${urlEncode(targetUserId)}") {
                    localHeaders(accessToken)
                }
            } else {
                supabaseConfig.httpClient.put("$baseUrl/v1/data/messages/nicknames/${urlEncode(targetUserId)}") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("nickname", JsonPrimitive(cleanNickname)) })
                }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadGroupRoom(accessToken: String, groupId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackChatResponse>("$baseUrl/v1/data/messages/groups/${urlEncode(groupId)}", accessToken)
            .map { it.normalized() }
    }

    suspend fun loadCommunityRoom(accessToken: String, communityId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackChatResponse>("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}", accessToken)
            .map { it.normalized() }
    }

    suspend fun loadCurrentProfile(accessToken: String): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackProfileResponse>("$baseUrl/v1/data/profile/me", accessToken)
    }

    suspend fun loadProfile(accessToken: String, profileId: String): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackProfileResponse>("$baseUrl/v1/data/profiles/${urlEncode(profileId)}", accessToken)
            .map { it.normalized() }
    }

    suspend fun loadProfileUser(accessToken: String, profileId: String): Result<User?> {
        return loadProfile(accessToken, profileId).map { response ->
            response.profile?.let(::mapProfileUser)
        }
    }

    suspend fun updatePresence(
        accessToken: String,
        isOnline: Boolean
    ): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/presence") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPresenceRequest(isOnline = isOnline))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackProfileResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun searchProfileUsers(
        accessToken: String,
        query: String,
        limit: Int = 20
    ): Result<List<User>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanLimit = limit.coerceIn(1, 100)
        return requestJson<LocalFallbackProfilesResponse>(
            "$baseUrl/v1/data/profiles/search?q=${urlEncode(query)}&limit=$cleanLimit",
            accessToken
        ).map { response ->
            response.items.map(::mapProfileUser)
        }
    }

    suspend fun loadFollowUsers(
        accessToken: String,
        profileId: String,
        tab: String
    ): Result<List<User>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanTab = if (tab == "followers") "followers" else "following"
        return requestJson<LocalFallbackProfilesResponse>(
            "$baseUrl/v1/data/follows?tab=${urlEncode(cleanTab)}&profileId=${urlEncode(profileId)}",
            accessToken
        ).map { response -> response.items.map(::mapProfileUser) }
    }

    suspend fun loadFriendRequests(accessToken: String, tab: String): Result<List<LocalFallbackFriendRequest>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackFriendListResponse>(
            "$baseUrl/v1/data/friends?tab=${urlEncode(tab)}",
            accessToken
        ).map { it.items }
    }

    suspend fun sendFriendRequest(accessToken: String, receiverId: String): Result<LocalFallbackFriendRequest?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/friends") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackFriendCreateRequest(receiverId = receiverId))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackFriendMutationResponse>(body).request)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateFriendRequest(
        accessToken: String,
        requestId: String,
        status: String
    ): Result<LocalFallbackFriendRequest?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/friends/${urlEncode(requestId)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackFriendStatusRequest(status = status))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackFriendMutationResponse>(body).request)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteFriendRequest(accessToken: String, requestId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/friends/${urlEncode(requestId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteFriendRelationship(accessToken: String, targetUserId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/friends/users/${urlEncode(targetUserId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun publishTyping(
        accessToken: String,
        conversationId: String,
        targetType: String,
        isTyping: Boolean,
        displayName: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/typing") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackTypingRequest(
                        conversationId = conversationId,
                        targetType = targetType,
                        isTyping = isTyping,
                        displayName = displayName?.trim().orEmpty()
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateCurrentProfile(
        accessToken: String,
        displayName: String? = null,
        bio: String? = null,
        country: String? = null,
        city: String? = null,
        status: String? = null,
        avatarUrl: String? = null,
        coverUrl: String? = null,
        isPrivate: Boolean? = null,
        profileVisibility: String? = null,
        lastSeenVisibility: String? = null,
        onlineStatusVisibility: String? = null
    ): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/profile/me") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackUpdateProfileRequest(
                        displayName = displayName,
                        bio = bio,
                        country = country,
                        city = city,
                        status = status,
                        avatarUrl = UbuntuStorageUrl.rewrite(avatarUrl),
                        coverUrl = UbuntuStorageUrl.rewrite(coverUrl),
                        isPrivate = isPrivate,
                        profileVisibility = profileVisibility,
                        lastSeenVisibility = lastSeenVisibility,
                        onlineStatusVisibility = onlineStatusVisibility
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackProfileResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    @Serializable
    data class LocalFallbackMomentsFeedResponse(
        val items: List<JsonObject> = emptyList()
    )

    @Serializable
    data class LocalFallbackMomentViewersResponse(
        val items: List<JsonObject> = emptyList()
    )

    @Serializable
    private data class LocalFallbackCreateMomentRequest(
        val mediaUrl: String,
        val mediaType: String,
        val thumbnailUrl: String? = null,
        val displayDurationMs: Long? = null,
        val durationHours: Int = 24,
        val durationType: String = "24h",
        val visibility: String = "all",
        val visibilityType: String = "public",
        val caption: String? = null,
        val allowReplies: Boolean = true
    )

    @Serializable
    private data class LocalFallbackRawMomentResponse(
        val moment: JsonObject? = null
    )

    suspend fun loadMomentsFeed(accessToken: String): Result<LocalFallbackMomentsFeedResponse> {
        if (!isConfigured) return Result.failure(IllegalStateException("FALLBACK_API_URL no configurada"))
        return requestJson("$baseUrl/v1/data/moments/feed", accessToken)
    }

    suspend fun createMoment(
        accessToken: String,
        mediaUrl: String,
        mediaType: String,
        thumbnailUrl: String?,
        displayDurationMs: Long?,
        durationHours: Int,
        durationType: String,
        visibility: String,
        visibilityType: String,
        caption: String?,
        allowReplies: Boolean
    ): Result<Moment> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/moments") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateMomentRequest(
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
                        mediaType = mediaType,
                        thumbnailUrl = UbuntuStorageUrl.rewrite(thumbnailUrl),
                        displayDurationMs = displayDurationMs,
                        durationHours = durationHours,
                        durationType = durationType,
                        visibility = visibility,
                        visibilityType = visibilityType,
                        caption = caption,
                        allowReplies = allowReplies
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val row = json.decodeFromString<LocalFallbackRawMomentResponse>(body).moment
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el momento."))
                Result.success(mapMoment(row))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadFeed(
        accessToken: String,
        limit: Int = 60,
        offset: Int = 0,
        userId: String? = null
    ): Result<LocalFallbackFeedResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val query = buildString {
            append("$baseUrl/v1/data/feed?limit=$limit&offset=$offset")
            userId?.trim()?.takeIf { it.isNotBlank() }?.let {
                append("&userId=${urlEncode(it)}")
            }
        }
        return requestJson<LocalFallbackRawFeedResponse>(query, accessToken)
            .map { response -> LocalFallbackFeedResponse(response.items.map { mapPost(it) }) }
    }

    suspend fun loadPostDetail(accessToken: String, postId: String): Result<LocalFallbackPostDetailResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawPostDetailResponse>(
            "$baseUrl/v1/data/feed/posts/${urlEncode(postId)}",
            accessToken
        ).mapCatching { response ->
            val post = response.post ?: throw IllegalStateException("Publicación no encontrada")
            LocalFallbackPostDetailResponse(
                post = mapPost(post),
                comments = response.comments.map { mapComment(it, postId) }
            )
        }
    }

    suspend fun createPost(
        accessToken: String,
        content: String,
        mediaUrls: List<String>,
        mediaType: String?,
        visibility: String
    ): Result<Post> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val normalizedMediaUrls = UbuntuStorageUrl.rewriteAll(mediaUrls)
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreatePostRequest(
                        content = content,
                        mediaUrl = normalizedMediaUrls.firstOrNull(),
                        mediaUrls = normalizedMediaUrls,
                        mediaType = mediaType,
                        visibility = visibility
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val post = json.decodeFromString<LocalFallbackRawPostResponse>(body).post
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió la publicación."))
                Result.success(mapPost(post))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updatePost(
        accessToken: String,
        postId: String,
        content: String? = null,
        visibility: String? = null
    ): Result<Post> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPatchPostRequest(content = content, visibility = visibility))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val post = json.decodeFromString<LocalFallbackRawPostResponse>(body).post
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió la publicación."))
                Result.success(mapPost(post))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deletePost(accessToken: String, postId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun addPostComment(
        accessToken: String,
        postId: String,
        content: String,
        parentId: String? = null,
        mediaUrl: String? = null,
        mediaType: String? = null
    ): Result<Comment> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/comments") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateCommentRequest(
                        content = content,
                        parentId = parentId,
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl),
                        mediaType = mediaType
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val comment = json.decodeFromString<LocalFallbackRawCommentResponse>(body).comment
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el comentario."))
                Result.success(mapComment(comment, postId))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deletePostComment(accessToken: String, commentId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/feed/comments/${urlEncode(commentId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updatePostComment(accessToken: String, commentId: String, content: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/feed/comments/${urlEncode(commentId)}") {
                localHeaders(accessToken)
                setBody(buildJsonObject { put("content", JsonPrimitive(content)) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setPostReaction(
        accessToken: String,
        postId: String,
        reactionType: String?
    ): Result<Post?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (reactionType.isNullOrBlank()) {
                supabaseConfig.httpClient.delete("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/reaction") {
                    localHeaders(accessToken)
                }
            } else {
                supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/reaction") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(LocalFallbackReactionRequest(reactionType))
                }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val post = runCatching { json.decodeFromString<LocalFallbackRawPostResponse>(body).post }
                    .getOrNull()
                    ?.let { mapPost(it) }
                Result.success(post)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadNotifications(
        accessToken: String,
        unreadOnly: Boolean = false,
        limit: Int = 200
    ): Result<LocalFallbackNotificationList> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val url = buildString {
            append("$baseUrl/v1/data/notifications?limit=$limit")
            if (unreadOnly) append("&unreadOnly=true")
        }
        return requestJson<LocalFallbackNotificationsResponse>(url, accessToken)
            .map { response ->
                LocalFallbackNotificationList(
                    items = response.items.map(::mapNotification),
                    unreadCount = response.unreadCount
                )
            }
    }

    suspend fun markNotificationRead(accessToken: String, notificationId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/notifications/${urlEncode(notificationId)}/read"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun markAllNotificationsRead(accessToken: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/notifications/read-all") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(response.bodyAsText())))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNotification(accessToken: String, notificationId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/notifications/${urlEncode(notificationId)}") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(response.bodyAsText())))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAllNotifications(accessToken: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/notifications") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(response.bodyAsText())))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createNotification(
        accessToken: String,
        recipientId: String,
        type: String,
        targetId: String? = null,
        targetType: String? = null,
        content: String? = null,
        extraData: JsonObject = buildJsonObject {}
    ): Result<Notification?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/notifications") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateNotificationRequest(
                        recipientId = recipientId,
                        type = type,
                        targetId = targetId,
                        targetType = targetType,
                        content = content,
                        extraData = extraData
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val notification = json.decodeFromString<LocalFallbackNotificationResponse>(body).notification
                Result.success(notification?.let(::mapNotification))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun registerFcmToken(accessToken: String, token: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/notifications/fcm-tokens") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackFcmTokenRequest(token = token))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun unregisterFcmToken(accessToken: String, token: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/notifications/fcm-tokens") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackFcmTokenRequest(token = token))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadVideos(
        accessToken: String,
        limit: Int = 60,
        offset: Int = 0,
        userId: String? = null,
        query: String? = null,
        sharedBy: String? = null
    ): Result<List<Video>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val url = buildString {
            append("$baseUrl/v1/data/videos?limit=$limit&offset=$offset")
            userId?.trim()?.takeIf { it.isNotBlank() }?.let { append("&userId=${urlEncode(it)}") }
            query?.trim()?.takeIf { it.isNotBlank() }?.let { append("&q=${urlEncode(it)}") }
            sharedBy?.trim()?.takeIf { it.isNotBlank() }?.let { append("&sharedBy=${urlEncode(it)}") }
        }
        return requestJson<LocalFallbackRawVideosResponse>(url, accessToken)
            .map { response -> response.items.map { mapVideo(it) } }
    }

    suspend fun loadVideo(accessToken: String, videoId: String): Result<Video> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawVideoResponse>(
            "$baseUrl/v1/data/videos/${urlEncode(videoId)}",
            accessToken
        ).mapCatching { response ->
            mapVideo(response.video ?: throw IllegalStateException("Video no encontrado"))
        }
    }

    suspend fun createVideo(
        accessToken: String,
        title: String,
        description: String,
        videoUrl: String,
        thumbnailUrl: String?,
        durationSeconds: Long,
        isPublic: Boolean,
        tags: List<String>
    ): Result<Video> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val normalizedVideoUrl = UbuntuStorageUrl.rewrite(videoUrl).orEmpty()
            val normalizedThumbUrl = UbuntuStorageUrl.rewrite(thumbnailUrl)
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/videos") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateVideoRequest(
                        title = title,
                        description = description,
                        videoUrl = normalizedVideoUrl,
                        thumbnailUrl = normalizedThumbUrl,
                        coverUrl = normalizedThumbUrl,
                        durationSeconds = durationSeconds,
                        isPublic = isPublic,
                        tags = tags
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val video = json.decodeFromString<LocalFallbackRawVideoResponse>(body).video
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el video."))
                Result.success(mapVideo(video))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteMoment(accessToken: String, momentId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/moments/${urlEncode(momentId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadMomentViewers(accessToken: String, momentId: String): Result<List<MomentView>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackMomentViewersResponse>(
            "$baseUrl/v1/data/moments/${urlEncode(momentId)}/viewers",
            accessToken
        ).map { response -> response.items.map { mapMomentView(it, momentId) } }
    }

    suspend fun recordMomentView(accessToken: String, momentId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/moments/${urlEncode(momentId)}/view") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setVideoReaction(accessToken: String, videoId: String, liked: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (liked) {
                supabaseConfig.httpClient.post("$baseUrl/v1/data/videos/${urlEncode(videoId)}/reaction") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(LocalFallbackReactionRequest("LIKE"))
                }
            } else {
                supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/${urlEncode(videoId)}/reaction") {
                    localHeaders(accessToken)
                }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun incrementVideoView(accessToken: String, videoId: String): Result<Int?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/videos/${urlEncode(videoId)}/view") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val payload = json.decodeFromString<JsonObject>(body)
                Result.success(intOf(payload, "view_count", "viewCount").takeIf { it >= 0 })
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun shareVideo(accessToken: String, videoId: String): Result<LocalFallbackVideoShareResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/videos/${urlEncode(videoId)}/share") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackRawVideoShareResponse>(body).normalized(defaultSharedByMe = true))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun unshareVideo(accessToken: String, videoId: String): Result<LocalFallbackVideoShareResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/${urlEncode(videoId)}/share") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackRawVideoShareResponse>(body).normalized(defaultSharedByMe = false))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteVideo(accessToken: String, videoId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/${urlEncode(videoId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadVideoComments(accessToken: String, videoId: String): Result<List<VideoComment>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawVideoCommentsResponse>(
            "$baseUrl/v1/data/videos/${urlEncode(videoId)}/comments",
            accessToken
        ).map { response -> response.items.map { mapVideoComment(it, videoId) } }
    }

    suspend fun addVideoComment(
        accessToken: String,
        videoId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        parentId: String? = null
    ): Result<VideoComment> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/videos/${urlEncode(videoId)}/comments") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateVideoCommentRequest(
                        content = content,
                        contentType = contentType,
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl),
                        parentId = parentId
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val comment = json.decodeFromString<LocalFallbackRawVideoCommentResponse>(body).comment
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el comentario."))
                Result.success(mapVideoComment(comment, videoId))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteVideoComment(accessToken: String, commentId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/comments/${urlEncode(commentId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadActiveChallenge(accessToken: String): Result<ActiveChallenge> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawActiveChallengeResponse>(
            "$baseUrl/v1/data/challenges/active",
            accessToken
        ).mapCatching { response ->
            mapActiveChallenge(response.activeChallenge ?: throw IllegalStateException("Reto activo no encontrado"))
        }
    }

    suspend fun createChallengeMaster(
        accessToken: String,
        title: String,
        description: String,
        type: String,
        difficulty: String
    ): Result<Challenge> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/challenges/master") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCreateChallengeRequest(title, description, type, difficulty))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val challenge = json.decodeFromString<LocalFallbackRawChallengeResponse>(body).challenge
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el reto."))
                Result.success(mapChallenge(challenge))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadMyChallengeMasters(accessToken: String): Result<List<Challenge>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawChallengesResponse>(
            "$baseUrl/v1/data/challenges/master/mine",
            accessToken
        ).map { response -> response.items.map { mapChallenge(it) } }
    }

    suspend fun deleteChallengeMaster(accessToken: String, challengeId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/challenges/master/${urlEncode(challengeId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun submitChallenge(
        accessToken: String,
        activeChallenge: ActiveChallenge,
        contentUrl: String? = null,
        textContent: String? = null
    ): Result<ChallengeSubmission> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/challenges/${urlEncode(activeChallenge.id)}/submissions"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackChallengeSubmissionRequest(
                        challengeId = activeChallenge.challengeId,
                        contentUrl = UbuntuStorageUrl.rewrite(contentUrl),
                        textContent = textContent
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val submission = json.decodeFromString<LocalFallbackRawChallengeSubmissionResponse>(body).submission
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió la participación."))
                Result.success(mapChallengeSubmission(submission))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadChallengeSubmissions(
        accessToken: String,
        activeChallengeId: String
    ): Result<List<ChallengeSubmission>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawChallengeSubmissionsResponse>(
            "$baseUrl/v1/data/challenges/${urlEncode(activeChallengeId)}/submissions",
            accessToken
        ).map { response -> response.items.map { mapChallengeSubmission(it) } }
    }

    suspend fun loadMyChallengeSubmission(
        accessToken: String,
        activeChallengeId: String
    ): Result<ChallengeSubmission?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackRawChallengeSubmissionResponse>(
            "$baseUrl/v1/data/challenges/${urlEncode(activeChallengeId)}/submissions/me",
            accessToken
        ).map { response -> response.submission?.let { mapChallengeSubmission(it) } }
    }

    suspend fun hasChallengeParticipation(accessToken: String, activeChallengeId: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackParticipationResponse>(
            "$baseUrl/v1/data/challenges/${urlEncode(activeChallengeId)}/participation",
            accessToken
        ).map { it.participating }
    }

    suspend fun toggleChallengeVote(accessToken: String, submissionId: String): Result<Pair<Int, Boolean>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/challenges/submissions/${urlEncode(submissionId)}/vote"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val payload = json.decodeFromString<LocalFallbackVoteResponse>(body)
                Result.success(payload.voteCount to payload.votedByMe)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun sendChatMessage(
        accessToken: String,
        chatId: String,
        content: String,
        contentType: String,
        mediaUrl: String?,
        mediaSize: Long,
        replyToId: String?,
        messageId: String? = null
    ): Result<Message> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackSendMessageRequest(
                        id = messageId,
                        content = content,
                        contentType = contentType,
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
                        mediaSize = mediaSize,
                        replyToId = replyToId ?: ""
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackMessageResponse>(body).message)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun sendGroupMessage(
        accessToken: String,
        groupId: String,
        content: String,
        contentType: String,
        mediaUrl: String?,
        mediaSize: Long,
        replyToId: String?,
        messageId: String? = null
    ): Result<GroupMessage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/groups/${urlEncode(groupId)}/messages") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackSendMessageRequest(
                        id = messageId,
                        content = content,
                        contentType = contentType,
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
                        mediaSize = mediaSize,
                        replyToId = replyToId ?: ""
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackGroupMessageResponse>(body).message)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun sendCommunityMessage(
        accessToken: String,
        communityId: String,
        content: String,
        contentType: String,
        mediaUrl: String?,
        mediaSize: Long,
        replyToId: String?,
        messageId: String? = null
    ): Result<CommunityMessage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/messages") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackSendMessageRequest(
                        id = messageId,
                        content = content,
                        contentType = contentType,
                        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
                        mediaSize = mediaSize,
                        replyToId = replyToId ?: ""
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackCommunityMessageResponse>(body).message)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createGroup(
        accessToken: String,
        id: String,
        name: String,
        description: String?,
        imageUrl: String?,
        memberIds: List<String>
    ): Result<String> = createCollection(
        accessToken = accessToken,
        collection = "groups",
        request = LocalFallbackCreateCollectionRequest(
            id = id,
            name = name,
            description = description,
            imageUrl = UbuntuStorageUrl.rewrite(imageUrl),
            isPublic = true,
            memberIds = memberIds
        )
    )

    suspend fun createCommunity(
        accessToken: String,
        id: String,
        name: String,
        description: String?,
        imageUrl: String?,
        category: String?,
        isPublic: Boolean
    ): Result<String> = createCollection(
        accessToken = accessToken,
        collection = "communities",
        request = LocalFallbackCreateCollectionRequest(
            id = id,
            name = name,
            description = description,
            imageUrl = UbuntuStorageUrl.rewrite(imageUrl),
            category = category,
            isPublic = isPublic
        )
    )

    private suspend fun createCollection(
        accessToken: String,
        collection: String,
        request: LocalFallbackCreateCollectionRequest
    ): Result<String> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/${urlEncode(collection)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val decoded = json.decodeFromString<LocalFallbackCreateCollectionResponse>(body)
                Result.success(decoded.id.ifBlank { decoded.room?.let { textOf(it, "id") }.orEmpty() })
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun joinCollection(
        accessToken: String,
        collection: String,
        roomId: String,
        userId: String? = null,
        role: String? = null
    ): Result<Unit> = mutateCollectionMembership(accessToken, collection, roomId, "join", userId, role)

    suspend fun leaveCollection(accessToken: String, collection: String, roomId: String, userId: String? = null): Result<Unit> =
        mutateCollectionMembership(accessToken, collection, roomId, "leave", userId)

    suspend fun deleteCollection(accessToken: String, collection: String, roomId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}"
            ) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun mutateCollectionMembership(
        accessToken: String,
        collection: String,
        roomId: String,
        action: String,
        userId: String? = null,
        role: String? = null
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}/${urlEncode(action)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackMembershipMutationRequest(userId = userId, role = role))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createVoiceRoom(
        accessToken: String,
        id: String,
        name: String,
        description: String?,
        isPublic: Boolean,
        maxParticipants: Int,
        agoraChannelName: String?
    ): Result<VoiceRoom> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/voice/rooms") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateVoiceRoomRequest(
                        id = id,
                        name = name,
                        description = description,
                        isPublic = isPublic,
                        maxParticipants = maxParticipants,
                        agoraChannelName = agoraChannelName
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val decoded = json.decodeFromString<LocalFallbackVoiceRoomResponse>(body)
                Result.success(decoded.room?.let(::mapVoiceRoom) ?: VoiceRoom(id = id, name = name))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadVoiceRooms(accessToken: String): Result<List<VoiceRoom>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackVoiceRoomsResponse>("$baseUrl/v1/data/voice/rooms", accessToken)
            .map { response ->
                val rows = if (response.rooms.isNotEmpty()) response.rooms else response.items
                rows.map { mapVoiceRoom(it) }
            }
    }

    suspend fun loadVoiceRoom(accessToken: String, roomId: String): Result<LocalFallbackVoiceRoomState> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackVoiceRoomResponse>("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}", accessToken)
            .map { response ->
                LocalFallbackVoiceRoomState(
                    room = response.room?.let(::mapVoiceRoom) ?: VoiceRoom(id = roomId, name = "Sala de voz"),
                    participants = response.participants.map(::mapVoiceParticipant)
                )
            }
    }

    suspend fun joinVoiceRoom(
        accessToken: String,
        roomId: String,
        userName: String?,
        userAvatarUrl: String?,
        socketId: String?,
        isMuted: Boolean
    ): Result<List<VoiceParticipant>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}/join") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackVoiceJoinRequest(userName, UbuntuStorageUrl.rewrite(userAvatarUrl), socketId, isMuted))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val payload = json.decodeFromString<LocalFallbackVoiceParticipantsResponse>(body)
                Result.success(payload.participants.map(::mapVoiceParticipant))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun leaveVoiceRoom(accessToken: String, roomId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}/leave") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteVoiceRoom(accessToken: String, roomId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun uploadFile(
        accessToken: String,
        bucketName: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String,
        fileName: String
    ): Result<LocalFallbackStorageObject> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val rawUpload = uploadFileRaw(
                accessToken = accessToken,
                bucketName = bucketName,
                objectPath = objectPath,
                bytes = bytes,
                contentType = contentType
            )
            if (rawUpload.isSuccess) {
                return rawUpload
            }

            rawUpload.exceptionOrNull()?.let { rawError ->
                android.util.Log.w(
                    "LocalFallbackApi",
                    "Raw Ubuntu upload failed for $bucketName/$objectPath; trying multipart fallback",
                    rawError
                )
            }

            uploadFileMultipart(
                accessToken = accessToken,
                bucketName = bucketName,
                objectPath = objectPath,
                bytes = bytes,
                contentType = contentType,
                fileName = fileName
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun uploadFileRaw(
        accessToken: String,
        bucketName: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String
    ): Result<LocalFallbackStorageObject> {
        val encodedPath = objectPath
            .split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { urlEncode(it) }
        if (encodedPath.isBlank()) return Result.failure(Exception("Ruta de archivo inválida."))
        return try {
            val parsedContentType = runCatching { ContentType.parse(contentType) }
                .getOrDefault(ContentType.Application.OctetStream)
            val response = supabaseConfig.httpClient.put(
                "$baseUrl/storage/v1/object/${urlEncode(bucketName)}/$encodedPath"
            ) {
                localHeaders(accessToken)
                contentType(parsedContentType)
                setBody(bytes)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val upload = json.decodeFromString<LocalFallbackRawStorageUploadResponse>(body)
                val cleanPath = upload.path.ifBlank { objectPath }
                val publicUrl = UbuntuStorageUrl.rewrite(upload.publicUrl.ifBlank { upload.public_url.orEmpty() }).orEmpty()
                Result.success(
                    LocalFallbackStorageObject(
                        id = upload.Key.orEmpty().ifBlank { "$bucketName/$cleanPath" },
                        bucket = bucketName,
                        path = cleanPath,
                        publicUrl = publicUrl,
                        url = publicUrl,
                        contentType = contentType,
                        size = bytes.size.toLong()
                    ).normalized()
                )
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun uploadFileMultipart(
        accessToken: String,
        bucketName: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String,
        fileName: String
    ): Result<LocalFallbackStorageObject> {
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/storage/upload") {
                localHeaders(accessToken)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("bucket", bucketName)
                            append("path", objectPath)
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, contentType)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "form-data; name=\"file\"; filename=\"${safeMultipartFileName(fileName)}\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackStorageResponse>(body).`object`.normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun downloadPublicFile(url: String): Result<ByteArray> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val targetUrl = UbuntuStorageUrl.rewrite(url) ?: url
            val response = supabaseConfig.httpClient.get(targetUrl) {
                localHeaders()
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(toException(response, decodeError(response.bodyAsText())))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun listStorageObjects(
        accessToken: String,
        bucketName: String,
        prefix: String = ""
    ): Result<List<LocalFallbackStorageObject>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val query = buildString {
            append("$baseUrl/v1/storage/objects?bucket=${urlEncode(bucketName)}")
            prefix.trim().takeIf { it.isNotBlank() }?.let {
                append("&prefix=${urlEncode(it)}")
            }
        }
        return requestJson<LocalFallbackStorageListResponse>(query, accessToken)
            .map { response -> response.objects.map { it.normalized() } }
    }

    suspend fun deleteFile(
        accessToken: String,
        bucketName: String,
        objectPath: String
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val encodedPath = objectPath.trim().replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { urlEncode(it) }
        if (encodedPath.isBlank()) return Result.failure(Exception("Ruta de archivo invalida."))
        return try {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/storage/objects/${urlEncode(bucketName)}/$encodedPath"
            ) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun publicObjectUrl(bucketName: String, objectPath: String): String {
        if (!isConfigured) return ""
        return UbuntuStorageUrl.publicObjectUrl(bucketName, objectPath)
    }

    suspend fun completeMigratedPasswordReset(
        migrationToken: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/complete-migrated-password-reset") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCompleteMigrationRequest(migrationToken, newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend inline fun <reified T> requestJson(url: String, accessToken: String): Result<T> {
        return try {
            requestJsonOnce<T>(url, accessToken).fold(
                onSuccess = { Result.success(it) },
                onFailure = { firstError ->
                    val retryUrl = refreshedRetryUrl(url)
                    if (retryUrl != null && retryUrl != url) {
                        requestJsonOnce<T>(retryUrl, accessToken)
                    } else {
                        Result.failure(firstError)
                    }
                }
            )
        } catch (error: Exception) {
            val retryUrl = refreshedRetryUrl(url)
            if (retryUrl != null && retryUrl != url) {
                requestJsonOnce<T>(retryUrl, accessToken)
            } else {
                Result.failure(error)
            }
        }
    }

    private suspend inline fun <reified T> requestJsonOnce(url: String, accessToken: String): Result<T> {
        val response = supabaseConfig.httpClient.get(url) {
            localHeaders(accessToken)
        }
        val body = response.bodyAsText()
        return if (response.status.isSuccess()) {
            Result.success(json.decodeFromString(body))
        } else {
            val error = toException(response, decodeError(body))
            if (response.status.value in setOf(502, 503, 504)) {
                Result.failure(Exception("Servidor temporalmente no disponible; refrescando endpoint.", error))
            } else {
                Result.failure(error)
            }
        }
    }

    private suspend fun refreshedRetryUrl(failedUrl: String): String? {
        val oldBase = baseUrl.trimEnd('/')
        if (oldBase.isBlank()) return null
        val refreshed = RuntimeBackendEndpoint.refreshAfterEndpointFailure(failedUrl)
        if (!refreshed) return null
        val newBase = baseUrl.trimEnd('/')
        if (newBase.isBlank() || newBase == oldBase) return null
        return if (failedUrl.startsWith(oldBase)) {
            newBase + failedUrl.removePrefix(oldBase)
        } else {
            null
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.localHeaders(accessToken: String? = null) {
        headers {
            append(UbuntuStorageUrl.NGROK_SKIP_BROWSER_WARNING, "1")
            accessToken?.trim()?.takeIf { it.isNotBlank() }?.let {
                append(HttpHeaders.Authorization, "Bearer $it")
            }
        }
    }

    private fun decodeError(body: String): LocalFallbackErrorResponse {
        if (body.isBlank()) return LocalFallbackErrorResponse()
        return runCatching { json.decodeFromString<LocalFallbackErrorResponse>(body) }
            .getOrElse {
                LocalFallbackErrorResponse(message = body.take(240))
            }
    }

    private fun toException(response: HttpResponse, error: LocalFallbackErrorResponse): Exception {
        val code = (error.error ?: error.message).orEmpty().trim()
        return when (code) {
            "INVALID_CREDENTIALS" -> Exception("Correo o contraseña incorrectos.")
            "ACCOUNT_NOT_FOUND" -> Exception("No existe una cuenta con ese correo. Regístrate para crear una cuenta.")
            "INVALID_PASSWORD" -> Exception("Contraseña incorrecta. Intenta otra vez o restablece tu contraseña.")
            "EMAIL_ALREADY_EXISTS" -> Exception("Ese correo ya existe en el servidor Ubuntu.")
            "EMAIL_AND_PASSWORD_REQUIRED" -> Exception("Escribe correo y contraseña.")
            "AUTH_REQUIRED" -> Exception("Tu sesión Ubuntu no llegó a la API. Cierra sesión e inicia sesión otra vez contra Ubuntu.")
            "INVALID_TOKEN" -> Exception("Tu sesión Ubuntu ya no es válida para este servidor. Inicia sesión otra vez; si cambiaste JWT_SECRET o reparaste la VM, los tokens anteriores quedan invalidados.")
            "MIGRATION_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Tu cuenta migrada necesita una contraseña de al menos 8 caracteres.")
            "RESET_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Ubuntu necesita crear una contraseña de al menos 8 caracteres.")
            "INVALID_OR_EXPIRED_RESET_TOKEN" -> Exception("El permiso seguro venció o no es válido. Vuelve a solicitar el cambio.")
            "MIGRATED_ACCOUNT_PASSWORD_SETUP_REQUIRED" -> LocalFallbackMigratedPasswordSetupRequiredException(
                email = error.email.orEmpty(),
                displayName = error.displayName.orEmpty(),
                migrationToken = error.migrationToken.orEmpty()
            )
            "MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED" -> LocalFallbackMigratedPasswordResetRequiredException(
                migrationToken = error.migrationToken.orEmpty(),
                email = error.email.orEmpty(),
                displayName = error.displayName.orEmpty()
            )
            else -> Exception(code.ifBlank { "Servidor Ubuntu respondió ${response.status.value}." })
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun LocalFallbackUser.normalized(): LocalFallbackUser =
        copy(avatarUrl = UbuntuStorageUrl.rewrite(avatarUrl))

    private fun LocalFallbackAuthResponse.normalized(): LocalFallbackAuthResponse =
        copy(user = user.normalized())

    private fun LocalFallbackInboxItem.normalized(): LocalFallbackInboxItem =
        copy(avatarUrl = UbuntuStorageUrl.rewriteOrEmpty(avatarUrl))

    private fun LocalFallbackInboxResponse.normalized(): LocalFallbackInboxResponse =
        copy(items = items.map { it.normalized() })

    private fun LocalFallbackParticipant.normalized(): LocalFallbackParticipant =
        copy(avatarUrl = UbuntuStorageUrl.rewriteOrEmpty(avatarUrl))

    private fun LocalFallbackChatResponse.normalized(): LocalFallbackChatResponse =
        copy(
            chat = chat?.normalizedStorageUrls(),
            otherParticipant = otherParticipant?.normalized(),
            participants = participants.map { it.normalized() },
            messages = messages.map { it.normalizedStorageUrls() }
        )

    private fun LocalFallbackProfileResponse.normalized(): LocalFallbackProfileResponse =
        copy(profile = profile?.normalizedStorageUrls())

    private fun mapProfileUser(row: JsonObject): User {
        return User(
            id = textOf(row, "supabase_user_id", "supabaseUserId", "id", "user_id", "profile_id"),
            email = textOf(row, "email"),
            username = textOf(row, "username").ifBlank { null },
            full_name = textOf(row, "full_name", "name").ifBlank { null },
            bio = textOf(row, "bio").ifBlank { null },
            avatar_url = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "avatar_url", "photo_url", "profile_image")),
            cover_url = UbuntuStorageUrl.rewrite(textOf(row, "cover_url", "cover_image")),
            country = textOf(row, "country").ifBlank { null },
            city = textOf(row, "city").ifBlank { null },
            followers_count = intOf(row, "followers_count", "followers"),
            following_count = intOf(row, "following_count", "following"),
            post_count = intOf(row, "post_count", "posts"),
            status = textOf(row, "status").ifBlank { null },
            display_name = textOf(row, "display_name", "displayName").ifBlank { null },
            is_private = boolOf(row, "is_private", "isPrivate"),
            profile_visibility = textOf(row, "profile_visibility", "profileVisibility").ifBlank { "everyone" },
            last_seen_visibility = textOf(row, "last_seen_visibility", "lastSeenVisibility").ifBlank { "everyone" },
            online_status_visibility = textOf(row, "online_status_visibility", "onlineStatusVisibility").ifBlank { "everyone" },
            is_admin = boolOf(row, "is_admin", "isAdmin"),
            voice_access_granted = boolOf(row, "voice_access_granted", "voiceAccessGranted"),
            is_verified = boolOf(row, "is_verified", "isVerified", "verified"),
            is_suspended = boolOf(row, "is_suspended", "isSuspended"),
            suspended_until = textOf(row, "suspended_until", "suspendedUntil").ifBlank { null },
            suspension_end_date = textOf(row, "suspension_end_date", "suspensionEndDate").ifBlank { null },
            suspension_reason = textOf(row, "suspension_reason", "suspensionReason").ifBlank { null },
            suspension_count = intOf(row, "suspension_count", "suspensionCount"),
            privacy_policy_accepted = boolOf(row, "privacy_policy_accepted", "privacyPolicyAccepted"),
            privacy_policy_accepted_at = textOf(row, "privacy_policy_accepted_at", "privacyPolicyAcceptedAt").ifBlank { null },
            is_online = boolOf(row, "is_online", "isOnline"),
            lastSeen = textOf(row, "last_seen", "lastSeen").ifBlank { null },
            created_at = textOf(row, "created_at", "createdAt"),
            updated_at = textOf(row, "updated_at", "updatedAt")
        )
    }

    private fun LocalFallbackStorageObject.normalized(): LocalFallbackStorageObject =
        copy(
            publicUrl = UbuntuStorageUrl.rewriteOrEmpty(publicUrl),
            url = UbuntuStorageUrl.rewriteOrEmpty(url)
        )

    private val storageUrlKeys = setOf(
        "avatar_url",
        "avatarUrl",
        "photo_url",
        "photoUrl",
        "cover_url",
        "coverUrl",
        "media_url",
        "mediaUrl",
        "media_urls",
        "mediaUrls",
        "image_url",
        "imageUrl",
        "video_url",
        "videoUrl",
        "thumbnail_url",
        "thumbnailUrl",
        "public_url",
        "publicUrl",
        "file_url",
        "fileUrl",
        "audio_url",
        "audioUrl",
        "user_profile_image",
        "userProfileImage",
        "user_avatar",
        "userAvatar",
        "user_avatar_url",
        "userAvatarUrl",
        "sender_image",
        "senderImage",
        "group_image",
        "groupImage",
        "community_image",
        "communityImage",
        "profile_image",
        "profileImage",
        "url"
    )

    private fun JsonObject.normalizedStorageUrls(): JsonObject =
        buildJsonObject {
            this@normalizedStorageUrls.forEach { (key, value) ->
                put(key, normalizeStorageElement(key, value))
            }
        }

    private fun normalizeStorageElement(key: String, element: JsonElement): JsonElement {
        if (key in storageUrlKeys && element is JsonPrimitive && element.contentOrNull != null && element.toString().startsWith("\"")) {
            return JsonPrimitive(UbuntuStorageUrl.rewriteOrEmpty(element.contentOrNull))
        }
        return when (element) {
            is JsonObject -> element.normalizedStorageUrls()
            is JsonArray -> buildJsonArray {
                element.forEach { item -> add(normalizeStorageElement(key, item)) }
            }
            else -> element
        }
    }

    private fun textOf(row: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = row[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun intOf(row: JsonObject, vararg keys: String, fallback: Int = 0): Int {
        for (key in keys) {
            val primitive = row[key]?.jsonPrimitive ?: continue
            primitive.intOrNull?.let { return it }
            primitive.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return fallback
    }

    private fun longOf(row: JsonObject, vararg keys: String, fallback: Long = 0L): Long {
        for (key in keys) {
            val primitive = row[key]?.jsonPrimitive ?: continue
            primitive.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return fallback
    }

    private fun boolOf(row: JsonObject, vararg keys: String, fallback: Boolean = false): Boolean {
        for (key in keys) {
            val primitive = row[key]?.jsonPrimitive ?: continue
            primitive.booleanOrNull?.let { return it }
            when (primitive.contentOrNull?.trim()?.lowercase()) {
                "true", "1", "yes" -> return true
                "false", "0", "no" -> return false
            }
        }
        return fallback
    }

    private fun mediaUrlsOf(row: JsonObject): List<String> {
        val urls = linkedSetOf<String>()

        fun addUrl(value: String?) {
            UbuntuStorageUrl.rewrite(value)?.takeIf { it.isNotBlank() }?.let { urls += it }
        }

        fun collect(element: kotlinx.serialization.json.JsonElement?) {
            when (element) {
                is JsonArray -> element.forEach { collect(it) }
                is JsonObject -> {
                    addUrl(textOf(element, "url", "media_url", "public_url", "path"))
                }
                else -> {
                    val text = element?.jsonPrimitive?.contentOrNull?.trim()
                    if (text.isNullOrBlank()) return
                    if (text.startsWith("[") || text.startsWith("{")) {
                        runCatching { json.parseToJsonElement(text) }
                            .onSuccess { collect(it) }
                            .onFailure { addUrl(text) }
                    } else {
                        addUrl(text)
                    }
                }
            }
        }

        collect(row["media_urls"])
        collect(row["mediaUrls"])
        collect(row["media"])
        addUrl(textOf(row, "media_url", "image_url", "video_url"))
        return urls.toList()
    }

    private fun reactionCountsOf(row: JsonObject): Map<String, Int> {
        val element = row["reaction_counts"] ?: row["reactionCounts"] ?: return emptyMap()
        val obj = when (element) {
            is JsonObject -> element
            else -> runCatching { json.parseToJsonElement(element.jsonPrimitive.content) as? JsonObject }
                .getOrNull()
        } ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            val count = value.jsonPrimitive.intOrNull
                ?: value.jsonPrimitive.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            key.uppercase() to count
        }.toMap()
    }

    private fun mapChat(row: JsonObject): Chat {
        val participants = when (val raw = row["participants"]) {
            is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        return Chat(
            id = textOf(row, "id").ifBlank { "chat-${System.currentTimeMillis()}" },
            participants = participants,
            created_at = textOf(row, "created_at", "createdAt"),
            updated_at = textOf(row, "updated_at", "updatedAt"),
            last_message = textOf(row, "last_message", "lastMessage").ifBlank { null },
            last_message_at = textOf(row, "last_message_at", "lastMessageAt").ifBlank { null },
            last_message_sender_id = textOf(row, "last_message_sender_id", "lastMessageSenderId").ifBlank { null },
            last_message_id = textOf(row, "last_message_id", "lastMessageId").ifBlank { null },
            is_group = boolOf(row, "is_group", "isGroup"),
            group_name = textOf(row, "group_name", "groupName").ifBlank { null },
            group_image = UbuntuStorageUrl.rewrite(textOf(row, "group_image", "groupImage")),
            nickname = textOf(row, "nickname").ifBlank { null },
            is_muted = boolOf(row, "is_muted", "isMuted"),
            is_pinned = boolOf(row, "is_pinned", "isPinned"),
            is_archived = boolOf(row, "is_archived", "isArchived"),
            is_favorite = boolOf(row, "is_favorite", "isFavorite"),
            cleared_at = textOf(row, "cleared_at", "clearedAt").ifBlank { null }
        )
    }

    private fun mapPost(row: JsonObject): Post {
        val mediaUrls = mediaUrlsOf(row)
        val rawReactionCounts = reactionCountsOf(row)
        val likeCount = intOf(
            row,
            "like_count",
            "likes_count",
            fallback = rawReactionCounts.values.sum().takeIf { it > 0 } ?: 0
        )
        val reactionCounts = rawReactionCounts.takeIf { it.isNotEmpty() }
            ?: if (likeCount > 0) mapOf("LIKE" to likeCount) else emptyMap()
        return Post(
            id = textOf(row, "id").ifBlank { "post-${System.currentTimeMillis()}" },
            userId = textOf(row, "user_id", "owner_id", "author_id", "creator_id"),
            userName = textOf(row, "user_name", "display_name", "author_name").ifBlank { "Usuario" },
            userProfileImage = UbuntuStorageUrl.rewrite(textOf(row, "user_profile_image", "avatar_url", "photo_url")),
            content = textOf(row, "content", "caption", "description", "text"),
            mediaUrls = mediaUrls,
            mediaUrlLegacy = mediaUrls.firstOrNull(),
            createdAt = textOf(row, "created_at", "updated_at"),
            likeCount = likeCount,
            commentCount = intOf(row, "comment_count", "comments_count"),
            shareCount = intOf(row, "share_count", "shares_count"),
            likedByMe = boolOf(row, "liked_by_me", "likedByMe"),
            sharedByMe = boolOf(row, "shared_by_me", "sharedByMe"),
            myReaction = textOf(row, "my_reaction", "myReaction").ifBlank { null },
            reactionCounts = reactionCounts,
            isVerified = boolOf(row, "is_verified", "verified"),
            visibility = textOf(row, "visibility").ifBlank { "public" }
        )
    }

    private fun mapComment(row: JsonObject, fallbackPostId: String): Comment {
        val content = textOf(row, "content", "body", "text")
        val mediaUrl = UbuntuStorageUrl.rewrite(textOf(row, "media_url", "audio_url", "file_url").ifBlank {
            content.removePrefix("[Audio]").trim().takeIf { content.startsWith("[Audio]") && it.startsWith("http") }
        })
        return Comment(
            id = textOf(row, "id").ifBlank { "comment-${System.currentTimeMillis()}" },
            postId = textOf(row, "post_id").ifBlank { fallbackPostId },
            userId = textOf(row, "user_id", "profile_id", "author_id"),
            content = if (content.startsWith("[Audio]") && mediaUrl != null) "[Audio]" else content,
            createdAt = textOf(row, "created_at", "updated_at"),
            userName = textOf(row, "user_name", "display_name", "author_name").ifBlank { "Usuario" },
            userAvatar = UbuntuStorageUrl.rewrite(textOf(row, "user_avatar", "user_avatar_url", "avatar_url", "user_profile_image")),
            isVerified = boolOf(row, "is_verified", "verified"),
            isMine = boolOf(row, "is_mine", "isMine"),
            parentId = textOf(row, "parent_id", "parentId").ifBlank { null },
            audioUrl = mediaUrl
        )
    }

    private fun mapNotification(row: JsonObject): Notification =
        Notification(
            id = textOf(row, "id").ifBlank { "notification-${System.currentTimeMillis()}" },
            recipientId = textOf(row, "recipient_id", "recipientId", "user_id"),
            actorId = textOf(row, "actor_id", "actorId").ifBlank { "" },
            type = textOf(row, "type", "notification_type", "notificationType").ifBlank { "activity" },
            targetId = textOf(row, "target_id", "targetId", "post_id", "video_id", "chat_id").ifBlank { null },
            targetType = textOf(row, "target_type", "targetType").ifBlank { null },
            content = textOf(row, "content", "message", "body", "title").ifBlank { null },
            isRead = boolOf(row, "is_read", "isRead", "read"),
            createdAt = textOf(row, "created_at", "createdAt", "updated_at"),
            actorName = textOf(row, "actor_name", "actorName", "sender_name").ifBlank { null },
            actorAvatarUrl = UbuntuStorageUrl.rewrite(
                textOf(row, "actor_avatar_url", "actorAvatarUrl", "sender_avatar_url", "avatar_url")
            ),
            extraData = (row["extra_data"] ?: row["extraData"]) as? JsonObject
        )

    private fun mapVideo(row: JsonObject): Video =
        Video(
            id = textOf(row, "id").ifBlank { "video-${System.currentTimeMillis()}" },
            userId = textOf(row, "user_id", "userId"),
            userName = textOf(row, "user_name", "userName", "display_name").ifBlank { "Usuario" },
            userProfileImage = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "user_profile_image", "userProfileImage", "avatar_url")),
            title = textOf(row, "title", "caption").ifBlank { "Video" },
            description = textOf(row, "description", "content"),
            videoUrl = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "video_url", "videoUrl", "media_url", "url")),
            thumbnailUrl = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "thumbnail_url", "thumbnailUrl", "cover_url", "coverUrl")),
            duration = intOf(row, "duration_seconds", "duration").toLong(),
            views = intOf(row, "views", "view_count"),
            likeCount = intOf(row, "like_count", "likeCount"),
            commentCount = intOf(row, "comment_count", "commentCount"),
            shareCount = intOf(row, "share_count", "shareCount"),
            country = textOf(row, "country"),
            city = textOf(row, "city"),
            created_at = textOf(row, "created_at", "createdAt"),
            updated_at = textOf(row, "updated_at", "updatedAt"),
            isPublic = boolOf(row, "is_public", "isPublic", fallback = true),
            tags = when (val tags = row["tags"]) {
                is JsonArray -> tags.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> emptyList()
            },
            likedByMe = boolOf(row, "liked_by_me", "likedByMe"),
            sharedByMe = boolOf(row, "shared_by_me", "sharedByMe"),
            isVerified = boolOf(row, "is_verified", "isVerified", "verified")
        )

    private fun mapMoment(row: JsonObject): Moment =
        Moment(
            id = textOf(row, "id").ifBlank { "moment-${System.currentTimeMillis()}" },
            userId = textOf(row, "user_id", "userId"),
            mediaUrl = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "media_url", "mediaUrl")),
            mediaType = textOf(row, "media_type", "mediaType").ifBlank { "image" },
            thumbnailUrl = UbuntuStorageUrl.rewrite(textOf(row, "thumbnail_url", "thumbnailUrl")),
            displayDurationMs = longOf(row, "display_duration_ms", "displayDurationMs", "duration_ms", "durationMs")
                .takeIf { it > 0L },
            durationHours = intOf(row, "duration_hours", "durationHours", fallback = 24),
            durationType = textOf(row, "duration_type", "durationType").ifBlank { "24h" },
            visibility = textOf(row, "visibility").ifBlank { "all" },
            visibilityType = textOf(row, "visibility_type", "visibilityType").ifBlank { "public" },
            excludedIds = when (val excluded = row["excluded_ids"] ?: row["excludedIds"]) {
                is JsonArray -> excluded.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> emptyList()
            },
            createdAt = textOf(row, "created_at", "createdAt").ifBlank { null },
            expiresAt = textOf(row, "expires_at", "expiresAt").ifBlank { null },
            caption = textOf(row, "caption").ifBlank { null },
            allowReplies = boolOf(row, "allow_replies", "allowReplies", fallback = true),
            username = textOf(row, "owner_name", "username", "user_name").ifBlank { null },
            userDisplayName = textOf(row, "owner_name", "display_name", "userDisplayName").ifBlank { null },
            userAvatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "owner_avatar_url", "avatar_url", "user_avatar_url")),
            isVerified = boolOf(row, "is_verified", "isVerified", "verified"),
            viewCount = intOf(row, "views_count", "viewCount"),
            isViewed = boolOf(row, "viewed_by_me", "isViewed")
        )

    private fun mapMomentView(row: JsonObject, fallbackMomentId: String): MomentView =
        MomentView(
            id = textOf(row, "id").ifBlank {
                "${textOf(row, "moment_id", "momentId").ifBlank { fallbackMomentId }}:${textOf(row, "user_id", "userId")}"
            },
            momentId = textOf(row, "moment_id", "momentId").ifBlank { fallbackMomentId },
            userId = textOf(row, "user_id", "userId", "profile_id"),
            viewedAt = textOf(row, "viewed_at", "viewedAt").ifBlank { null },
            username = textOf(row, "username", "user_name", "display_name").ifBlank { null },
            userDisplayName = textOf(row, "user_display_name", "display_name", "displayName", "username").ifBlank { null },
            userAvatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "user_avatar_url", "avatar_url", "userProfileImage")),
            isVerified = boolOf(row, "is_verified", "isVerified", "verified")
        )

    private fun mapVideoComment(row: JsonObject, fallbackVideoId: String): VideoComment =
        VideoComment(
            id = textOf(row, "id").ifBlank { "video-comment-${System.currentTimeMillis()}" },
            videoId = textOf(row, "video_id", "videoId").ifBlank { fallbackVideoId },
            userId = textOf(row, "user_id", "userId", "profile_id"),
            userName = textOf(row, "user_name", "userName", "display_name").ifBlank { "Usuario" },
            userProfileImage = UbuntuStorageUrl.rewrite(textOf(row, "user_profile_image", "avatar_url", "user_avatar_url")),
            content = textOf(row, "content", "comment", "comment_text", "body", "message", "text"),
            likes = intOf(row, "likes", "like_count"),
            created_at = textOf(row, "created_at", "createdAt"),
            parentId = normalizedParentCommentId(textOf(row, "parent_comment_id", "parent_id", "parentId", "reply_to_id", "replyToId")),
            contentType = textOf(row, "content_type", "contentType").ifBlank { "text" },
            mediaUrl = UbuntuStorageUrl.rewrite(textOf(row, "media_url", "audio_url", "file_url"))
        )

    private fun normalizedParentCommentId(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeUnless { it == "0" || it == "null" || it == "undefined" || it == "none" }
    }

    private fun mapChallenge(row: JsonObject): Challenge =
        Challenge(
            id = textOf(row, "id", "challenge_id").ifBlank { "challenge-${System.currentTimeMillis()}" },
            title = textOf(row, "title").ifBlank { "Reto" },
            description = textOf(row, "description"),
            challengeType = textOf(row, "challenge_type", "challengeType", "type").ifBlank { "mixed" },
            expiresAt = textOf(row, "expires_at", "expiresAt").ifBlank { null },
            creatorId = textOf(row, "creator_id", "creatorId").ifBlank { null },
            participantCount = intOf(row, "participant_count", "participantCount"),
            isCompleted = boolOf(row, "is_completed", "isCompleted")
        )

    private fun mapActiveChallenge(row: JsonObject): ActiveChallenge =
        ActiveChallenge(
            id = textOf(row, "id", "active_id").ifBlank { "active-${System.currentTimeMillis()}" },
            challengeId = textOf(row, "challenge_id", "challengeId"),
            challenge = (row["challenge"] as? JsonObject)?.let { mapChallenge(it) }
                ?: mapChallenge(row),
            expiresAt = textOf(row, "expires_at", "expiresAt"),
            votingExpiresAt = textOf(row, "voting_expires_at", "votingExpiresAt").ifBlank { null },
            status = textOf(row, "status").ifBlank { "active" },
            winnerIds = when (val winners = row["winner_ids"] ?: row["winnerIds"]) {
                is JsonArray -> winners.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> emptyList()
            }
        )

    private fun mapChallengeSubmission(row: JsonObject): ChallengeSubmission =
        ChallengeSubmission(
            id = textOf(row, "id").ifBlank { "submission-${System.currentTimeMillis()}" },
            challengeId = textOf(row, "challenge_id", "challengeId"),
            activeChallengeId = textOf(row, "active_challenge_id", "activeChallengeId"),
            userId = textOf(row, "user_id", "userId"),
            user = (row["user_data"] as? JsonObject)?.let {
                runCatching { json.decodeFromJsonElement<conectate.com.data.model.User>(it.normalizedStorageUrls()) }.getOrNull()
            },
            contentUrl = UbuntuStorageUrl.rewrite(textOf(row, "content_url", "contentUrl", "media_url")),
            textContent = textOf(row, "text_content", "textContent", "content").ifBlank { null },
            voteCount = intOf(row, "vote_count", "voteCount"),
            votedByMe = boolOf(row, "voted_by_me", "votedByMe"),
            createdAt = textOf(row, "created_at", "createdAt")
        )

    private fun mapVoiceRoom(row: JsonObject): VoiceRoom {
        return runCatching { json.decodeFromJsonElement<VoiceRoom>(row) }.getOrElse {
            VoiceRoom(
                id = textOf(row, "id", "room_id", "voice_room_id").ifBlank { "voice-${System.currentTimeMillis()}" },
                name = textOf(row, "name", "title", "readable_id").ifBlank { "Sala de voz" },
                description = textOf(row, "description", "topic"),
                creatorId = textOf(row, "creator_id", "owner_id", "user_id"),
                participantCount = intOf(row, "current_participants", "participant_count", "active_participants"),
                maxParticipants = intOf(row, "max_participants", "capacity", fallback = 50),
                isPublic = boolOf(row, "is_public", "public", fallback = true),
                isActive = boolOf(row, "is_active", "active", fallback = true),
                readableId = textOf(row, "readable_id").ifBlank { null },
            )
        }
    }

    private fun mapVoiceParticipant(row: JsonObject): VoiceParticipant {
        val userId = textOf(row, "user_id", "profile_id", "id")
        return VoiceParticipant(
            id = userId.ifBlank { textOf(row, "id").ifBlank { "voice-user-${System.currentTimeMillis()}" } },
            name = textOf(row, "user_name", "display_name", "name").ifBlank { "Usuario" },
            avatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "user_avatar_url", "avatar_url")),
            isSpeaking = boolOf(row, "is_speaking"),
            isMuted = boolOf(row, "is_muted"),
            socketId = textOf(row, "socket_id")
        )
    }

    private fun safeMultipartFileName(value: String): String =
        value.replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
            .ifBlank { "upload.bin" }

    // ─── Admin Moderation API ───────────────────────────────────

    suspend fun adminCheckAccess(accessToken: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/access") {
                localHeaders(accessToken)
            }
            if (response.status.value == 200) {
                Result.success(true)
            } else {
                Result.failure(Exception("Acceso denegado: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminGetSuspendedProfiles(accessToken: String): Result<List<conectate.com.presentation.profile.SuspendedProfile>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/suspended-profiles") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Error: ${response.status}"))
            }
            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["items"]?.jsonArray ?: return Result.success(emptyList())
            val profiles = items.map { element ->
                val obj = element.jsonObject
                conectate.com.presentation.profile.SuspendedProfile(
                    userId = textOf(obj, "id", "user_id"),
                    isSuspended = boolOf(obj, "is_suspended"),
                    suspendedUntil = textOf(obj, "suspended_until").ifBlank { null },
                    suspensionEndDate = textOf(obj, "suspension_end_date").ifBlank { null },
                    suspensionReason = textOf(obj, "suspension_reason").ifBlank { null },
                    suspensionCount = intOf(obj, "suspension_count"),
                    updatedAt = textOf(obj, "updated_at").ifBlank { null }
                )
            }
            Result.success(profiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminGetReports(accessToken: String): Result<List<conectate.com.data.model.Report>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/reports") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("Error: ${response.status}"))
            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["items"]?.jsonArray ?: return Result.success(emptyList())
            val reports = items.map { element ->
                val obj = element.jsonObject
                conectate.com.data.model.Report(
                    id = textOf(obj, "id"),
                    reporter_id = textOf(obj, "reporter_id"),
                    reporter_name = textOf(obj, "reporter_name"),
                    reported_user_id = textOf(obj, "reported_user_id"),
                    reported_user_name = textOf(obj, "reported_user_name"),
                    reason = textOf(obj, "reason"),
                    description = textOf(obj, "description"),
                    evidence_url = textOf(obj, "evidence_url").ifBlank { null },
                    status = textOf(obj, "status"),
                    created_at = textOf(obj, "created_at")
                )
            }
            Result.success(reports)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminGetVideoReports(accessToken: String): Result<List<conectate.com.data.model.VideoReport>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/video-reports") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("Error: ${response.status}"))
            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["items"]?.jsonArray ?: return Result.success(emptyList())
            val reports = items.map { element ->
                val obj = element.jsonObject
                conectate.com.data.model.VideoReport(
                    id = textOf(obj, "id"),
                    videoId = textOf(obj, "video_id"),
                    videoOwnerId = textOf(obj, "video_owner_id"),
                    videoOwnerName = textOf(obj, "video_owner_name"),
                    videoTitle = textOf(obj, "video_title").ifBlank { null },
                    reporterId = textOf(obj, "reporter_id"),
                    reporterName = textOf(obj, "reporter_name"),
                    reason = textOf(obj, "reason"),
                    details = textOf(obj, "details").ifBlank { null },
                    status = textOf(obj, "status"),
                    createdAt = textOf(obj, "created_at"),
                    updatedAt = textOf(obj, "updated_at")
                )
            }
            Result.success(reports)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminSuspendUser(accessToken: String, userId: String, reason: String, days: Int): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/admin/suspend") {
                localHeaders(accessToken)
                setBody(buildJsonObject {
                    put("user_id", kotlinx.serialization.json.JsonPrimitive(userId))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                    put("days", kotlinx.serialization.json.JsonPrimitive(days))
                })
            }
            if (response.status.isSuccess()) Result.success(true) else Result.failure(Exception("Error: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminUpdateReportStatus(accessToken: String, reportId: String, status: String, type: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/admin/reports/$reportId/status") {
                localHeaders(accessToken)
                setBody(buildJsonObject {
                    put("status", kotlinx.serialization.json.JsonPrimitive(status))
                    put("type", kotlinx.serialization.json.JsonPrimitive(type))
                })
            }
            if (response.status.isSuccess()) Result.success(true) else Result.failure(Exception("Error: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
