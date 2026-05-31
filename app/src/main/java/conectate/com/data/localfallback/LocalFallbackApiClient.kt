package conectate.com.data.localfallback

import conectate.com.BuildConfig
import conectate.com.data.model.Comment
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.model.Post
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
import kotlinx.serialization.json.JsonArray
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
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
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
    val messages: List<JsonObject> = emptyList(),
    val settings: LocalFallbackChatSettings = LocalFallbackChatSettings()
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
private data class LocalFallbackCompleteMigrationRequest(
    val migrationToken: String,
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

class LocalFallbackMigratedPasswordResetRequiredException(
    val migrationToken: String,
    val email: String,
    val displayName: String
) : Exception("Tu cuenta fue migrada al servidor local. Cambia tu contraseña para continuar.")

@Singleton
class LocalFallbackApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {
    val baseUrl: String = BuildConfig.FALLBACK_API_URL.trim().replace(Regex("/+$"), "")
    val isConfigured: Boolean = baseUrl.startsWith("http", ignoreCase = true)

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
                Result.success(json.decodeFromString(body))
            } else {
                val error = decodeError(body)
                if (
                    error.error == "MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED" &&
                    !error.migrationToken.isNullOrBlank() &&
                    password.length >= 8
                ) {
                    return completeMigratedPasswordReset(error.migrationToken, password)
                }
                Result.failure(toException(response, error))
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
                Result.success(json.decodeFromString(body))
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
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadMessagesInbox(accessToken: String, tab: String = "chats"): Result<LocalFallbackInboxResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/inbox?tab=${urlEncode(tab)}", accessToken)
    }

    suspend fun loadChat(accessToken: String, chatId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}", accessToken)
    }

    suspend fun loadGroupRoom(accessToken: String, groupId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/groups/${urlEncode(groupId)}", accessToken)
    }

    suspend fun loadCommunityRoom(accessToken: String, communityId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}", accessToken)
    }

    suspend fun loadCurrentProfile(accessToken: String): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/profile/me", accessToken)
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
                        avatarUrl = avatarUrl,
                        coverUrl = coverUrl,
                        isPrivate = isPrivate,
                        profileVisibility = profileVisibility,
                        lastSeenVisibility = lastSeenVisibility,
                        onlineStatusVisibility = onlineStatusVisibility
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
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
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreatePostRequest(
                        content = content,
                        mediaUrl = mediaUrls.firstOrNull(),
                        mediaUrls = mediaUrls,
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
                        mediaUrl = mediaUrl,
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
                        mediaUrl = mediaUrl ?: "",
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
                        mediaUrl = mediaUrl ?: "",
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
                        mediaUrl = mediaUrl ?: "",
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
            imageUrl = imageUrl,
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
            imageUrl = imageUrl,
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

    suspend fun leaveCollection(accessToken: String, collection: String, roomId: String): Result<Unit> =
        mutateCollectionMembership(accessToken, collection, roomId, "leave")

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
                setBody(LocalFallbackVoiceJoinRequest(userName, userAvatarUrl, socketId, isMuted))
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
                Result.success(json.decodeFromString<LocalFallbackStorageResponse>(body).`object`)
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
            val response = supabaseConfig.httpClient.get(url) {
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
        return requestJson<LocalFallbackStorageListResponse>(query, accessToken).map { it.objects }
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
        val encodedPath = objectPath.trim().replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { urlEncode(it) }
        return "$baseUrl/storage/v1/object/public/${urlEncode(bucketName)}/$encodedPath"
    }

    private suspend fun completeMigratedPasswordReset(
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
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend inline fun <reified T> requestJson(url: String, accessToken: String): Result<T> {
        return try {
            val response = supabaseConfig.httpClient.get(url) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.localHeaders(accessToken: String? = null) {
        headers {
            append("ngrok-skip-browser-warning", "true")
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
            "INVALID_CREDENTIALS" -> Exception("Correo o contraseña inválidos en el servidor Ubuntu.")
            "EMAIL_ALREADY_EXISTS" -> Exception("Ese correo ya existe en el servidor Ubuntu.")
            "EMAIL_AND_PASSWORD_REQUIRED" -> Exception("Escribe correo y contraseña.")
            "MIGRATION_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Tu cuenta migrada necesita una contraseña de al menos 8 caracteres.")
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
            value?.trim()?.takeIf { it.isNotBlank() }?.let { urls += it }
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

    private fun mapPost(row: JsonObject): Post {
        val mediaUrls = mediaUrlsOf(row)
        val reactionCounts = reactionCountsOf(row)
        val likeCount = intOf(
            row,
            "like_count",
            "likes_count",
            fallback = reactionCounts.values.sum().takeIf { it > 0 } ?: 0
        )
        return Post(
            id = textOf(row, "id").ifBlank { "post-${System.currentTimeMillis()}" },
            userId = textOf(row, "user_id", "owner_id", "author_id", "creator_id"),
            userName = textOf(row, "user_name", "display_name", "author_name").ifBlank { "Usuario" },
            userProfileImage = textOf(row, "user_profile_image", "avatar_url", "photo_url").ifBlank { null },
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
        val mediaUrl = textOf(row, "media_url", "audio_url", "file_url").ifBlank {
            content.removePrefix("[Audio]").trim().takeIf { content.startsWith("[Audio]") && it.startsWith("http") }
        }
        return Comment(
            id = textOf(row, "id").ifBlank { "comment-${System.currentTimeMillis()}" },
            postId = textOf(row, "post_id").ifBlank { fallbackPostId },
            userId = textOf(row, "user_id", "profile_id", "author_id"),
            content = if (content.startsWith("[Audio]") && mediaUrl != null) "[Audio]" else content,
            createdAt = textOf(row, "created_at", "updated_at"),
            userName = textOf(row, "user_name", "display_name", "author_name").ifBlank { "Usuario" },
            userAvatar = textOf(row, "user_avatar", "user_avatar_url", "avatar_url", "user_profile_image").ifBlank { null },
            isVerified = boolOf(row, "is_verified", "verified"),
            isMine = boolOf(row, "is_mine", "isMine"),
            parentId = textOf(row, "parent_id", "parentId").ifBlank { null },
            audioUrl = mediaUrl
        )
    }

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
            avatarUrl = textOf(row, "user_avatar_url", "avatar_url").ifBlank { null },
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
}
