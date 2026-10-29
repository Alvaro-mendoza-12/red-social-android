package conectate.com.data.localfallback

import conectate.com.BuildConfig
import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.Comment
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.CommunityChannel
import conectate.com.data.model.CommunityChannelCatalog
import conectate.com.data.model.CommunityEvent
import conectate.com.data.model.CommunityEventCatalog
import conectate.com.data.model.CommunityMemberProfile
import conectate.com.data.model.CommunityMemberProfileCatalog
import conectate.com.data.model.CommunityRoleCatalog
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.model.Notification
import conectate.com.data.model.Post
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveChapter
import conectate.com.data.model.LiveStream
import conectate.com.data.model.LiveBattle
import conectate.com.data.model.LiveBattleCandidate
import conectate.com.data.model.LiveCollaborationCapabilities
import conectate.com.data.model.LiveGuestInvitation
import conectate.com.data.model.LiveParticipantState
import conectate.com.data.model.LiveDrawGuessGuessResult
import conectate.com.data.model.LiveDrawGuessState
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.model.LiveDrawPacketReceipt
import conectate.com.data.model.LiveSfuSession
import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.Chat
import conectate.com.data.model.Challenge
import conectate.com.data.model.ChallengeSubmission
import conectate.com.data.model.User
import conectate.com.data.model.Badge
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.UUID
import java.net.URI
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

data class AdminDashboardStats(
    val totalUsers: Int = 0,
    val suspendedUsers: Int = 0,
    val pendingUserReports: Int = 0,
    val pendingVideoReports: Int = 0,
    val totalVideos: Int = 0
)

data class AdminAccessInfo(
    val isAdmin: Boolean = false,
    val accessLevel: String = "none",
    val isOwner: Boolean = false,
    val canWrite: Boolean = false,
    val canDeleteUsers: Boolean = false,
    val capabilities: Set<String> = emptySet()
)

data class AdminBadgeOption(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "",
    val color: String = "",
    val createdBy: String = "",
    val canDelete: Boolean = false
)

data class AdminFrameOption(val id: String, val name: String)

data class AdminCatalog(
    val badges: List<AdminBadgeOption> = emptyList(),
    val frames: List<AdminFrameOption> = emptyList()
)

data class AdminUserContent(
    val posts: Int = 0,
    val videos: Int = 0,
    val moments: Int = 0,
    val reports: Int = 0,
    val storageObjects: Int = 0,
    val storageBytes: Long = 0
)

data class AdminUser(
    val id: String,
    val localId: String,
    val email: String,
    val displayName: String,
    val username: String = "",
    val avatarUrl: String? = null,
    val createdAt: String? = null,
    val lastLoginAt: String? = null,
    val syncSource: String = "local",
    val isAdmin: Boolean = false,
    val adminAccessLevel: String = "none",
    val adminIsOwner: Boolean = false,
    val isTestAccount: Boolean = false,
    val isVerified: Boolean = false,
    val isSuspended: Boolean = false,
    val suspendedUntil: String? = null,
    val suspensionReason: String? = null,
    val badges: List<AdminBadgeOption> = emptyList(),
    val profileFrame: String = "",
    val content: AdminUserContent? = null
)

data class AdminUserPage(
    val items: List<AdminUser> = emptyList(),
    val total: Int = 0
)

data class RecoveryUploadEvidence(val name: String, val mime: String, val bytes: ByteArray)

data class AdminRecoveryEvidence(val id: String, val name: String, val mime: String, val size: Int)

data class AdminRecoveryCase(
    val id: String,
    val identifier: String,
    val alternateContact: String,
    val contactType: String,
    val profileHint: String,
    val description: String,
    val status: String,
    val candidateUserId: String,
    val candidateName: String,
    val candidateUsername: String,
    val candidateEmail: String,
    val contactVerified: Boolean,
    val createdAt: String,
    val reviewedAt: String = "",
    val reviewedBy: String = "",
    val evidence: List<AdminRecoveryEvidence> = emptyList(),
)

data class AdminRecoveryPage(
    val items: List<AdminRecoveryCase>, val total: Int, val offset: Int, val limit: Int,
    val statusCounts: Map<String, Int>
)

data class AdminRecoveryAccount(
    val id: String,
    val email: String,
    val displayName: String,
    val username: String,
)

data class NearbyProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val city: String = "",
    val country: String = "",
    val interests: List<String> = emptyList(),
    val distanceKm: Double = 0.5
)

data class AnonymousPeer(
    val alias: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val interests: List<String> = emptyList(),
    val hobbies: List<String> = emptyList(),
    val userId: String? = null
)

data class AnonymousChatSession(
    val id: String,
    val status: String,
    val ownRevealLevel: Int,
    val peerRevealLevel: Int,
    val peer: AnonymousPeer,
    val lastMessage: String? = null,
    val lastMessageSender: String? = null,
    val unreadCount: Int = 0,
    val messageCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    val friendshipPercent: Int
        get() = (((ownRevealLevel + peerRevealLevel).coerceIn(0, 6) * 100f) / 6f)
            .roundToInt()
            .coerceIn(0, 100)
}

data class AnonymousChatMessage(
    val isRead: Boolean = false,
    val id: String,
    val clientMessageId: String,
    val sender: String,
    val content: String,
    val contentType: String = "text",
    val mediaUrl: String? = null,
    val createdAt: String
)

data class AnonymousMatchResult(
    val status: String,
    val session: AnonymousChatSession? = null
)

data class AnonymousBottle(
    val id: String,
    val content: String,
    val status: String,
    val perspective: String,
    val attemptCount: Int = 0,
    val offeredAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

data class AnonymousBottleResult(
    val status: String,
    val bottle: AnonymousBottle? = null,
    val session: AnonymousChatSession? = null,
)

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
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: String = "7d"
)

@Serializable
data class LocalFallbackInboxItem(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val category: String = "",
    val updatedAt: String = "",
    val kind: String = "chat",
    val avatarUrl: String = "",
    val profileId: String? = null,
    val unreadCount: Int = 0,
    val unreadMessageIds: List<String>? = null,
    val lastMessageId: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageContentType: String? = null,
    val clearedAt: String? = null,
    @SerialName("cleared_at")
    val clearedAtSnake: String? = null,
    val isMember: Boolean = true,
    val historyAccess: CollectionHistoryAccess? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val isPublic: Boolean = true,
    val onlyAdminsCanTalk: Boolean = false,
    val dailyMessageLimit: Int = 0,
    val weeklyMessageLimit: Int = 0,
    val restUntil: String? = null,
    @SerialName("creator_id")
    val creatorId: String? = null,
    @SerialName("current_user_role")
    val currentUserRole: String? = null,
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
    val lastReadAt: String = "",
    val restUntil: String? = null
)

@Serializable
data class LocalFallbackAiChatRequest(
    val message: String,
    val context: Map<String, String> = emptyMap()
)

@Serializable
data class LocalFallbackAiChatResponse(
    val answer: String = "",
    val intent: String = "fallback",
    val confidence: Double = 0.0,
    val suggestions: List<String> = emptyList(),
    val source: String = "",
    val learnedFromUsers: Boolean = false
)

private const val EXPECTED_NOVVIN_SOURCE = "conectate-first-party-hybrid-v5"

private class LocalFallbackAiTransientException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

@Serializable
data class LocalFallbackCollectionPreviewMember(
    val id: String = "",
    val name: String = "Usuario",
    val avatarUrl: String = "",
    val role: String = "member"
)

@Serializable
data class LocalFallbackCollectionPreview(
    val kind: String = "",
    val roomId: String = "",
    val name: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val memberCount: Int = 0,
    val participants: List<LocalFallbackCollectionPreviewMember> = emptyList(),
    val isMember: Boolean = false
)

@Serializable
private data class LocalFallbackGroupInviteResponse(
    val token: String = "",
    val roomId: String = "",
    val expiresAt: String? = null,
    val maxUses: Int = 0
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
    val settings: LocalFallbackChatSettings = LocalFallbackChatSettings(),
    val historyAccess: CollectionHistoryAccess? = null
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
private data class LocalFallbackSupportAdminResponse(
    val admin: JsonObject? = null
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
private data class LocalFallbackLiveStreamsResponse(
    val items: List<LiveStream> = emptyList()
)

@Serializable
private data class LocalFallbackLiveStreamResponse(
    val stream: LiveStream? = null
)

@Serializable
private data class LocalFallbackLiveCommentsResponse(
    val items: List<LiveComment> = emptyList()
)

@Serializable
private data class LocalFallbackLiveCommentResponse(
    val comment: LiveComment? = null
)

@Serializable
private data class LocalFallbackLiveReplayResponse(
    val replay: LiveStream? = null
)

@Serializable
private data class LocalFallbackLiveChapterResponse(
    val chapter: LiveChapter? = null
)

@Serializable
private data class LocalFallbackLiveCapabilitiesResponse(
    val guestsBattlesEnabled: Boolean = false,
    val sfuAvailable: Boolean = false,
    val channelPointsEnabled: Boolean = false,
    val drawGuessEnabled: Boolean = false,
    val maxGuests: Int = 0,
    val scoringSources: List<String> = emptyList()
)

@Serializable
private data class LocalFallbackLiveSfuTokenResponse(
    val wsUrl: String = "",
    val roomName: String = "",
    val token: String = "",
    val expiresAt: Long = 0L,
    val role: String = "viewer",
    val maxGuests: Int = 0
)

@Serializable
private data class LocalFallbackLiveInvitationResponse(
    val invitation: LiveGuestInvitation? = null
)

@Serializable
private data class LocalFallbackLiveInvitationsResponse(
    val invitations: List<LiveGuestInvitation> = emptyList()
)

@Serializable
private data class LocalFallbackLiveParticipantResponse(
    val participant: LiveParticipantState? = null
)

@Serializable
private data class LocalFallbackLiveGuestActionResponse(
    val removed: Boolean = false,
    val muted: Boolean = false,
    val userId: String = "",
    val mutedTracks: Int = 0
)

@Serializable
private data class LocalFallbackLiveBattleResponse(
    val battle: LiveBattle? = null
)

@Serializable
private data class LocalFallbackLiveBattleCandidatesResponse(
    val items: List<LiveBattleCandidate> = emptyList()
)

@Serializable
private data class LocalFallbackLiveSupportersResponse(
    val items: List<conectate.com.data.model.LiveSupporter> = emptyList()
)

@Serializable
private data class LocalFallbackLiveDrawCurrentResponse(
    val state: LiveDrawGuessState? = null
)

@Serializable
private data class LocalFallbackLiveDrawAdvanceResponse(
    val advanced: Boolean = false,
    val state: LiveDrawGuessState = LiveDrawGuessState()
)

@Serializable
private data class LocalFallbackCreateLiveRequest(
    val id: String,
    val title: String,
    val source: String,
    val hashtags: List<String>,
    val coverUrl: String? = null,
    val gameName: String? = null,
    val category: String? = null,
    val visibility: String = "public",
    val giftsEnabled: Boolean = true,
    val battlesEnabled: Boolean = true
)

@Serializable
private data class LocalFallbackCreateLiveCommentRequest(
    val clientCommentId: String? = null,
    val content: String
)

@Serializable
private data class LocalFallbackLoginEmailsResponse(
    val items: List<String> = emptyList()
)

@Serializable
private data class LocalFallbackProfilesResponse(
    val items: List<JsonObject> = emptyList()
)

data class LocalFallbackFeedResponse(
    val items: List<Post> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
)

@Serializable
data class LocalFallbackSemanticSearchResponse(
    val items: List<conectate.com.data.model.SemanticSearchResult> = emptyList(),
    val model: String = "",
    val publicOnly: Boolean = true,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
)

data class LocalFallbackPostDetailResponse(
    val post: Post,
    val comments: List<Comment> = emptyList()
)

@Serializable
private data class LocalFallbackPostThreadSummaryRequest(
    val maxBullets: Int = 5
)

@Serializable
private data class LocalFallbackPostThreadSummaryResponse(
    val summary: conectate.com.data.model.PostThreadSummary
)

data class AdminModerationAppeal(
    val id: String,
    val decisionId: String,
    val userId: String,
    val displayName: String,
    val email: String,
    val statement: String,
    val status: String,
    val ruleCode: String,
    val ruleTitle: String,
    val explanation: String,
    val severity: String,
    val reviewerNote: String? = null,
    val createdAt: String? = null,
    val reviewedAt: String? = null
)

@Serializable
data class SavedCollection(
    val id: String = "",
    val name: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class SavedPostSummary(
    val id: String = "",
    @SerialName("author_id") val authorId: String = "",
    @SerialName("author_name") val authorName: String = "Usuario",
    val content: String = "",
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val available: Boolean = true
)

@Serializable
data class SavedPostItem(
    val id: String = "",
    @SerialName("collection_id") val collectionId: String = "",
    @SerialName("post_id") val postId: String = "",
    val tags: List<String> = emptyList(),
    val note: String = "",
    @SerialName("saved_at") val savedAt: String = "",
    val post: SavedPostSummary = SavedPostSummary()
)

@Serializable
data class SavedLibraryResponse(
    val collections: List<SavedCollection> = emptyList(),
    val items: List<SavedPostItem> = emptyList()
)

@Serializable
data class SystemHealthComponent(
    val ok: Boolean = false,
    val status: String = "unknown",
    val latencyMs: Long = 0,
    val message: String? = null
)

@Serializable
data class SystemReleaseInfo(
    val release: String = "unknown",
    val commit: String = "unknown",
    val deployedAtUtc: String? = null,
    val environment: String = "unknown"
)

@Serializable
data class SystemHealthResponse(
    val ok: Boolean = false,
    val status: String = "unknown",
    val service: String = "",
    val version: String = "",
    val generatedAt: String = "",
    val release: SystemReleaseInfo = SystemReleaseInfo(),
    val components: Map<String, SystemHealthComponent> = emptyMap()
)

data class LocalFallbackPostReactionsResponse(
    val items: List<conectate.com.data.model.PostReactionUser> = emptyList(),
    val total: Int = 0,
    val reactionCounts: Map<String, Int> = emptyMap()
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
    val password: String,
    val totpCode: String? = null,
    val backupCode: String? = null
)

@Serializable
data class ServerTwoFactorStatus(
    val enabled: Boolean = false,
    val recoveryCodesRemaining: Int = 0
)

@Serializable
data class ServerTwoFactorSetup(
    val secret: String,
    val provisioningUri: String
)

data class ServerTwoFactorEnabled(
    val session: LocalFallbackAuthResponse,
    val recoveryCodes: List<String>
)

@Serializable
data class PasswordRecoveryCodeStatus(
    val recoveryCodesRemaining: Int = 0
)

@Serializable
private data class PasswordRecoveryCodeRotation(
    val recoveryCodes: List<String> = emptyList()
)

@Serializable
private data class LocalFallbackRefreshRequest(
    val refreshToken: String,
    val accessToken: String? = null
)

@Serializable
private data class LocalFallbackRegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val username: String,
)

@Serializable
data class UsernameAvailabilityResponse(
    val username: String = "",
    val available: Boolean = false,
    val reason: String? = null,
    val suggestions: List<String> = emptyList(),
)

@Serializable
private data class LocalFallbackChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackPasswordResetRequest(
    val email: String,
    val codeFormat: String = "numeric"
)

@Serializable
private data class LocalFallbackEmailCodeVerifyRequest(val email: String, val code: String)

@Serializable
private data class LocalFallbackAssistedRecoveryRequest(
    val identifier: String,
    val alternateContact: String,
    val description: String,
    val contactType: String = "other",
    val profileHint: String = "",
)

@Serializable
private data class LocalFallbackContactCodeRequest(val code: String)

@Serializable
data class LocalFallbackAssistedRecoveryResponse(
    val ok: Boolean = false,
    val caseId: String = ""
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
    val thumbnailUrl: String = "",
    val mediaSize: Long = 0L,
    val replyToId: String = "",
    val channelId: String? = null,
    val isForwarded: Boolean = false,
    val forwardedFromMessageId: String? = null,
    val forwardedFromUserId: String? = null
)

@Serializable
private data class LocalFallbackEditMessageRequest(
    val content: String
)

@Serializable
private data class LocalFallbackDeleteMessageRequest(
    val forEveryone: Boolean = false
)

@Serializable
private data class LocalFallbackReactionMessageRequest(
    val emoji: String
)

@Serializable
data class LocalFallbackBlockRelationshipResponse(
    val blockedByMe: Boolean = false,
    val blockedMe: Boolean = false,
    val isBlocked: Boolean = false
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
    val readableId: String? = null,
    val roomMode: String = "casual",
    val password: String? = null
)

@Serializable
private data class LocalFallbackMembershipMutationRequest(
    val userId: String? = null,
    val role: String? = null
)

@Serializable
internal data class LocalFallbackUpdateProfileRequest(
    val displayName: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val country: String? = null,
    val city: String? = null,
    val gender: String? = null,
    val birthDate: String? = null,
    val status: String? = null,
    val interests: List<String>? = null,
    val hobbies: List<String>? = null,
    val linkedGames: List<String>? = null,
    val gameLogoUrls: Map<String, String>? = null,
    val favoriteMusicUrl: String? = null,
    val favoriteBook: String? = null,
    val favoriteSport: String? = null,
    val career: String? = null,
    val education: String? = null,
    val workplace: String? = null,
    val availabilitySchedule: String? = null,
    val profileSectionOrder: List<String>? = null,
    val profileSectionVisibility: Map<String, String>? = null,
    val socialLinks: Map<String, String>? = null,
    val avatarUrl: String? = null,
    val avatarMediaType: String? = null,
    val avatarVideoMuted: Boolean? = null,
    val avatarDurationMs: Long? = null,
    val coverUrl: String? = null,
    val removeAvatar: Boolean = false,
    val removeCover: Boolean = false,
    val profileTheme: String? = null,
    val profileEffect: String? = null,
    val profileWelcome: String? = null,
    val isPrivate: Boolean? = null,
    val favoritesPublic: Boolean? = null,
    val breakMode: String? = null,
    val profileVisibility: String? = null,
    val profileViewHistoryEnabled: Boolean? = null,
    val lastSeenVisibility: String? = null,
    val onlineStatusVisibility: String? = null
)

/**
 * PATCH must only contain fields the caller intentionally supplied. The shared
 * JSON configuration enables default/null serialization for legacy endpoints;
 * sending this DTO directly would therefore turn every omitted profile field
 * into an explicit null and the server could interpret those nulls as clears.
 */
internal fun LocalFallbackUpdateProfileRequest.toSparseJson(): JsonObject = buildJsonObject {
    displayName?.let { put("displayName", JsonPrimitive(it)) }
    username?.let { put("username", JsonPrimitive(it)) }
    bio?.let { put("bio", JsonPrimitive(it)) }
    country?.let { put("country", JsonPrimitive(it)) }
    city?.let { put("city", JsonPrimitive(it)) }
    gender?.let { put("gender", JsonPrimitive(it)) }
    birthDate?.let { put("birthDate", JsonPrimitive(it)) }
    status?.let { put("status", JsonPrimitive(it)) }
    interests?.let { values -> put("interests", JsonArray(values.map(::JsonPrimitive))) }
    hobbies?.let { values -> put("hobbies", JsonArray(values.map(::JsonPrimitive))) }
    linkedGames?.let { values -> put("linkedGames", JsonArray(values.map(::JsonPrimitive))) }
    gameLogoUrls?.let { values ->
        put("gameLogoUrls", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
    }
    favoriteMusicUrl?.let { put("favoriteMusicUrl", JsonPrimitive(it)) }
    favoriteBook?.let { put("favoriteBook", JsonPrimitive(it)) }
    favoriteSport?.let { put("favoriteSport", JsonPrimitive(it)) }
    education?.let { put("education", JsonPrimitive(it)) }
    workplace?.let { put("workplace", JsonPrimitive(it)) }
    career?.let { put("career", JsonPrimitive(it)) }
    availabilitySchedule?.let { put("availabilitySchedule", JsonPrimitive(it)) }
    profileSectionOrder?.let { values ->
        put("profileSectionOrder", JsonArray(values.map(::JsonPrimitive)))
    }
    profileSectionVisibility?.let { values ->
        put("profileSectionVisibility", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
    }
    socialLinks?.let { values ->
        put("socialLinks", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
    }
    avatarUrl?.let { put("avatarUrl", JsonPrimitive(it)) }
    avatarMediaType?.let { put("avatarMediaType", JsonPrimitive(it)) }
    avatarVideoMuted?.let { put("avatarVideoMuted", JsonPrimitive(it)) }
    avatarDurationMs?.let { put("avatarDurationMs", JsonPrimitive(it)) }
    coverUrl?.let { put("coverUrl", JsonPrimitive(it)) }
    if (removeAvatar) put("removeAvatar", JsonPrimitive(true))
    if (removeCover) put("removeCover", JsonPrimitive(true))
    profileTheme?.let { put("profileTheme", JsonPrimitive(it)) }
    profileEffect?.let { put("profileEffect", JsonPrimitive(it)) }
    profileWelcome?.let { put("profileWelcome", JsonPrimitive(it)) }
    isPrivate?.let { put("isPrivate", JsonPrimitive(it)) }
    favoritesPublic?.let { put("favoritesPublic", JsonPrimitive(it)) }
    breakMode?.let { put("breakMode", JsonPrimitive(it)) }
    profileVisibility?.let { put("profileVisibility", JsonPrimitive(it)) }
    profileViewHistoryEnabled?.let { put("profileViewHistoryEnabled", JsonPrimitive(it)) }
    lastSeenVisibility?.let { put("lastSeenVisibility", JsonPrimitive(it)) }
    onlineStatusVisibility?.let { put("onlineStatusVisibility", JsonPrimitive(it)) }
}

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
    val visibility: String = "public",
    val isSensitive: Boolean = false,
    val remixAllowed: Boolean = false,
    val remixAudience: String = "public"
)

@Serializable
private data class LocalFallbackPatchPostRequest(
    val content: String? = null,
    val visibility: String? = null
)

@Serializable
private data class LocalFallbackRemixPermissionRequest(
    val allowed: Boolean,
    val audience: String = "public"
)

@Serializable
private data class LocalFallbackCreateRemixRequest(
    val content: String = "",
    val visibility: String = "public"
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
private data class LocalFallbackModerationEvidence(
    val source: String = "",
    val label: String = ""
)

@Serializable
private data class LocalFallbackCommunityChannelMutationRequest(
    val name: String,
    val description: String? = null,
    val type: String,
    val position: Int = 100
)

@Serializable
private data class LocalFallbackCommunityChannelPauseRequest(
    val paused: Boolean,
    val minutes: Int = 60,
    val reason: String? = null,
    val pausedUntil: String? = null
)

@Serializable
private data class LocalFallbackCommunityChannelResponse(
    val channel: CommunityChannel
)

@Serializable
private data class LocalFallbackCommunityProfileMutationRequest(
    val alias: String? = null,
    val bio: String? = null,
    val distinctive: String? = null
)

@Serializable
private data class LocalFallbackCommunityProfileResponse(
    val profile: CommunityMemberProfile
)

@Serializable
private data class LocalFallbackCommunityEventMutationRequest(
    val title: String,
    val description: String? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val location: String? = null
)

@Serializable
private data class LocalFallbackCommunityEventAttendanceRequest(
    val status: String,
    val reminderMinutes: Int? = null
)

@Serializable
private data class LocalFallbackCommunityEventResponse(
    val event: CommunityEvent
)

@Serializable
private data class LocalFallbackErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val migrationToken: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val reason: String? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
    val until: String? = null,
    @SerialName("suspended_until") val suspendedUntil: String? = null,
    @SerialName("decision_id") val decisionId: String? = null,
    @SerialName("rule_title") val ruleTitle: String? = null,
    val explanation: String? = null,
    val evidence: List<LocalFallbackModerationEvidence> = emptyList(),
    @SerialName("appeal_deadline") val appealDeadline: String? = null,
    @SerialName("appeal_status") val appealStatus: String? = null,
    @SerialName("appeal_reviewer_note") val appealReviewerNote: String? = null,
    val appealToken: String? = null
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
    val isMuted: Boolean = false,
    val password: String? = null
)

@Serializable
private data class LocalFallbackVoiceStageRequest(
    val handRaised: Boolean
)

@Serializable
private data class LocalFallbackVoiceStageModerationRequest(
    val role: String? = null,
    val isMuted: Boolean? = null
)

@Serializable
private data class LocalFallbackVoiceParticipantsResponse(
    val participants: List<JsonObject> = emptyList()
)

@Serializable
private data class LocalFallbackRawFeedResponse(
    val items: List<JsonObject> = emptyList(),
    val hasMore: Boolean? = null,
    val nextOffset: Int? = null,
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
private data class SavedCollectionResponse(
    val collection: SavedCollection
)

@Serializable
private data class SavedCollectionRequest(
    val name: String
)

@Serializable
private data class SavePostRequest(
    val postId: String,
    val tags: List<String> = emptyList(),
    val note: String = ""
)

@Serializable
private data class LocalFallbackRawPostReactionsResponse(
    val items: List<JsonObject> = emptyList(),
    val total: Int = 0,
    @SerialName("reaction_counts") val reactionCounts: Map<String, Int> = emptyMap()
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
data class NotificationDigestPreferences(
    val enabled: Boolean = false,
    val frequency: String = "daily",
    val deliveryHour: Int = 18,
    val deliveryWeekday: Int = 1,
    val timezoneOffsetMinutes: Int = 0,
    val includeMentions: Boolean = true,
    val includeDirectMessages: Boolean = false,
    val includeCommunityActivity: Boolean = true,
    val includeUpcomingLives: Boolean = true,
    val lastDeliveredAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class NotificationDigestCategoryCounts(
    val mentions: Int = 0,
    val directMessages: Int = 0,
    val communityActivity: Int = 0,
    val upcomingLives: Int = 0
)

@Serializable
data class NotificationDigestItem(
    val id: String = "",
    val actorName: String = "Novvin",
    val actorAvatarUrl: String? = null,
    val type: String = "activity",
    val targetId: String? = null,
    val targetType: String? = null,
    val content: String? = null,
    val category: String = "",
    val createdAt: String = ""
)

@Serializable
data class NotificationDigestSummary(
    val frequency: String = "daily",
    val periodStart: String = "",
    val periodEnd: String = "",
    val total: Int = 0,
    val unreadCount: Int = 0,
    val categories: NotificationDigestCategoryCounts = NotificationDigestCategoryCounts(),
    val items: List<NotificationDigestItem> = emptyList()
)

data class NotificationDigestOverview(
    val preferences: NotificationDigestPreferences = NotificationDigestPreferences(),
    val summary: NotificationDigestSummary = NotificationDigestSummary()
)

@Serializable
private data class NotificationDigestPreferencesResponse(
    val preferences: NotificationDigestPreferences = NotificationDigestPreferences()
)

@Serializable
private data class NotificationDigestOverviewResponse(
    val preferences: NotificationDigestPreferences = NotificationDigestPreferences(),
    val summary: NotificationDigestSummary = NotificationDigestSummary()
)

@Serializable
private data class UpdateNotificationDigestPreferencesRequest(
    val enabled: Boolean,
    val frequency: String,
    val deliveryHour: Int,
    val deliveryWeekday: Int,
    val timezoneOffsetMinutes: Int,
    val includeMentions: Boolean,
    val includeDirectMessages: Boolean,
    val includeCommunityActivity: Boolean,
    val includeUpcomingLives: Boolean
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
    val isSensitive: Boolean = false,
    val tags: List<String> = emptyList(),
    val soundId: String? = null,
    val soundSourceVideoId: String? = null,
    val clientPublishId: String? = null,
    val chapters: List<conectate.com.data.model.VideoChapter> = emptyList(),
    val allowClipSharing: Boolean = true,
)

@Serializable
private data class LocalFallbackUpdateVideoRequest(
    val title: String,
    val description: String,
    val isPublic: Boolean,
    val allowClipSharing: Boolean,
)

@Serializable
data class MomentNotificationPreferences(val enabled: Boolean = false)

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

class LocalFallbackSessionRenewalRequiredException :
    Exception("Tu sesión ya no se puede renovar. Inicia sesión nuevamente.")

class LocalFallbackTwoFactorRequiredException(val reason: String) : Exception(
    when (reason) {
        "TWO_FACTOR_LOCKED" -> "Demasiados intentos. Espera 15 minutos antes de probar otro código."
        "TWO_FACTOR_INVALID" -> "El código de verificación no coincide o ya se usó."
        else -> "Escribe el código de tu aplicación autenticadora o un código de respaldo."
    }
)

class LocalFallbackPasswordChangedNeedsTwoFactorException : Exception(
    "Contraseña actualizada. Entra con tu nueva contraseña y el código de tu autenticador."
)

@Serializable
data class LocalFallbackPasswordResetResponse(
    val codeLength: Int? = null,
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
        get() = RuntimeBackendEndpoint.isConfigured

    private val json = supabaseConfig.json

    suspend fun askNovvin(
        accessToken: String,
        message: String,
        context: Map<String, String> = emptyMap()
    ): Result<LocalFallbackAiChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val initialUrl = "$baseUrl/v1/ai/chat"
        val firstAttempt = askNovvinOnce(initialUrl, accessToken, message, context)
        return firstAttempt.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error !is LocalFallbackAiTransientException) {
                    Result.failure(error)
                } else {
                    val retryUrl = refreshedRetryUrl(initialUrl)
                    if (retryUrl != null && retryUrl != initialUrl) {
                        askNovvinOnce(retryUrl, accessToken, message, context)
                    } else {
                        Result.failure(error)
                    }
                }
            }
        )
    }

    private suspend fun askNovvinOnce(
        url: String,
        accessToken: String,
        message: String,
        context: Map<String, String>
    ): Result<LocalFallbackAiChatResponse> {
        return try {
            val response = supabaseConfig.httpClient.post(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackAiChatRequest(message.trim(), context))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val decoded = json.decodeFromString<LocalFallbackAiChatResponse>(body)
                if (decoded.source != EXPECTED_NOVVIN_SOURCE) {
                    Result.failure(Exception("La versión segura de Novvin v5 no está activa en el servidor."))
                } else if (decoded.answer.isBlank()) {
                    Result.failure(Exception("Novvin devolvió una respuesta vacía."))
                } else {
                    Result.success(decoded)
                }
            } else {
                val error = toException(response, decodeError(body))
                if (response.status.value in setOf(502, 503, 504)) {
                    Result.failure(
                        LocalFallbackAiTransientException(
                            "Novvin no está disponible en este momento.",
                            error
                        )
                    )
                } else {
                    Result.failure(error)
                }
            }
        } catch (error: Exception) {
            Result.failure(
                LocalFallbackAiTransientException(
                    "No fue posible conectar con Novvin. Revisa Internet e intenta nuevamente.",
                    error
                )
            )
        }
    }

    suspend fun login(
        email: String,
        password: String,
        secondFactorCode: String? = null
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        val normalizedEmail = email.trim().lowercase()
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/login") {
                localHeaders()
                contentType(ContentType.Application.Json)
                val code = secondFactorCode?.trim()?.takeIf(String::isNotBlank)
                setBody(LocalFallbackLoginRequest(
                    normalizedEmail,
                    password,
                    totpCode = code?.takeIf { it.matches(Regex("[0-9]{6}")) },
                    backupCode = code?.takeUnless { it.matches(Regex("[0-9]{6}")) }
                ))
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
        displayName: String?,
        username: String,
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/register") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackRegisterRequest(
                        email = email.trim().lowercase(),
                        password = password,
                        displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
                        username = username.trim().lowercase(),
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
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/change-password") {
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
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/password-reset/request") {
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

    suspend fun verifyEmailPasswordCode(email: String, code: String): Result<LocalFallbackPasswordResetResponse> {
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible. Inténtalo de nuevo."))
        return runCatching {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/password-reset/verify") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackEmailCodeVerifyRequest(email.trim().lowercase(), code.trim()))
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
            json.decodeFromString<LocalFallbackPasswordResetResponse>(body).also {
                check(it.ok && it.resetToken?.matches(Regex("^[0-9a-f]{64}$")) == true) { "El servidor no pudo verificar el código." }
            }
        }
    }

    suspend fun requestAssistedRecovery(
        identifier: String,
        alternateContact: String,
        description: String,
        contactType: String = "other",
        profileHint: String = "",
        evidence: List<RecoveryUploadEvidence> = emptyList(),
    ): Result<LocalFallbackAssistedRecoveryResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Novvin no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/account-recovery/cases") {
                localHeaders()
                if (evidence.isEmpty()) {
                    contentType(ContentType.Application.Json)
                    setBody(LocalFallbackAssistedRecoveryRequest(identifier, alternateContact, description, contactType, profileHint))
                } else {
                    timeout { requestTimeoutMillis = 90_000; socketTimeoutMillis = 30_000 }
                    setBody(MultiPartFormDataContent(formData {
                        append("identifier", identifier); append("alternateContact", alternateContact)
                        append("description", description); append("contactType", contactType); append("profileHint", profileHint)
                        evidence.forEach { file -> append("evidence", file.bytes, Headers.build {
                            append(HttpHeaders.ContentType, file.mime)
                            val safeName = file.name.replace(Regex("[\\\"\r\n]"), "_")
                            append(HttpHeaders.ContentDisposition, "filename=\"$safeName\"")
                        }) }
                    }))
                }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackAssistedRecoveryResponse>(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun confirmAssistedRecoveryContact(caseId: String, code: String): Result<Unit> = runCatching {
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: error("El servidor de Novvin no está disponible.")
        val id = URLEncoder.encode(caseId, Charsets.UTF_8.name())
        val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/account-recovery/cases/$id/confirm-contact") {
            localHeaders()
            contentType(ContentType.Application.Json)
            setBody(LocalFallbackContactCodeRequest(code))
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
    }

    suspend fun confirmPasswordReset(
        resetToken: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/password-reset/confirm") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPasswordResetConfirmRequest(resetToken.trim(), newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                if (json.parseToJsonElement(body).jsonObject["requiresTwoFactor"]?.jsonPrimitive?.content == "true") {
                    return Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException())
                }
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
        return runReadWithDeadline(InteractiveReadDeadlines.InboxMillis) {
            requestJson<LocalFallbackInboxResponse>(
                "$baseUrl/v1/data/messages/inbox?tab=${urlEncode(tab)}",
                accessToken,
            ).getOrThrow().normalized()
        }
    }

    suspend fun checkUsernameAvailability(username: String, displayName: String): Result<UsernameAvailabilityResponse> {
        return conectate.com.core.network.runReadWithDeadline(8_000L) {
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: error("No pudimos conectar con el servidor para comprobar el nombre de usuario.")
            val response = supabaseConfig.httpClient.get("$endpoint/v1/auth/username/availability") {
                parameter("username", username.trim())
                parameter("name", displayName.trim())
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                json.decodeFromString<UsernameAvailabilityResponse>(body)
            } else {
                throw toException(response, decodeError(body))
            }
        }
    }

    suspend fun twoFactorStatus(accessToken: String): Result<ServerTwoFactorStatus> = try {
        val response = supabaseConfig.httpClient.get("$baseUrl/v1/auth/2fa/status") {
            localHeaders(accessToken)
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun passwordRecoveryCodeStatus(accessToken: String): Result<PasswordRecoveryCodeStatus> = try {
        val response = supabaseConfig.httpClient.get("$baseUrl/v1/auth/password-recovery-codes/status") {
            localHeaders(accessToken)
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun rotatePasswordRecoveryCodes(
        accessToken: String,
        password: String,
        secondFactorCode: String?
    ): Result<List<String>> = try {
        val code = secondFactorCode?.trim().orEmpty()
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/password-recovery-codes/rotate") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "password" to password,
                "totpCode" to code.takeIf { it.matches(Regex("[0-9]{6}")) },
                "backupCode" to code.takeUnless { it.matches(Regex("[0-9]{6}")) }
            ))
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(
            json.decodeFromString<PasswordRecoveryCodeRotation>(body).recoveryCodes
        ) else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun confirmSavedPasswordRecoveryCode(
        email: String,
        recoveryCode: String,
        newPassword: String,
        secondFactorCode: String?
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val code = secondFactorCode?.trim().orEmpty()
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/password-recovery-codes/confirm") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "email" to email.trim().lowercase(),
                    "recoveryCode" to recoveryCode.trim(),
                    "newPassword" to newPassword,
                    "totpCode" to code.takeIf { it.matches(Regex("[0-9]{6}")) },
                    "backupCode" to code.takeUnless { it.matches(Regex("[0-9]{6}")) }
                ))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(
                json.decodeFromString<LocalFallbackAuthResponse>(body).normalized()
            ) else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun setupTwoFactor(accessToken: String, password: String): Result<ServerTwoFactorSetup> = try {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/2fa/setup") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("password" to password))
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun enableTwoFactor(
        accessToken: String,
        password: String,
        totpCode: String
    ): Result<ServerTwoFactorEnabled> = try {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/2fa/enable") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("password" to password, "totpCode" to totpCode))
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val payload = json.parseToJsonElement(body).jsonObject
            val codes = payload["recoveryCodes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            Result.success(ServerTwoFactorEnabled(
                session = json.decodeFromString<LocalFallbackAuthResponse>(body).normalized(),
                recoveryCodes = codes
            ))
        } else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun disableTwoFactor(
        accessToken: String,
        password: String,
        code: String
    ): Result<Unit> = try {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/2fa/disable") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "password" to password,
                "totpCode" to code.takeIf { it.matches(Regex("[0-9]{6}")) },
                "backupCode" to code.takeUnless { it.matches(Regex("[0-9]{6}")) }
            ))
        }
        if (response.status.isSuccess()) Result.success(Unit)
        else Result.failure(toException(response, decodeError(response.bodyAsText())))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun loadDirectMessageRequest(accessToken: String, chatId: String?, targetUserId: String): Result<conectate.com.data.model.DirectMessageRequestState> =
        requestJson(
            if (chatId == null) "$baseUrl/v1/data/messages/requests/eligibility/${urlEncode(targetUserId)}"
            else "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/request", accessToken,
        )

    suspend fun decideDirectMessageRequest(accessToken: String, chatId: String, accept: Boolean): Result<conectate.com.data.model.DirectMessageRequestState> {
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/request") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(mapOf("action" to if (accept) "accept" else "reject"))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun loadChat(accessToken: String, chatId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runReadWithDeadline(InteractiveReadDeadlines.ConversationHistoryMillis) {
            requestJson<LocalFallbackChatResponse>(
                "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}",
                accessToken,
            ).getOrThrow().normalized()
        }
    }

    suspend fun loadChatMetadata(accessToken: String, chatId: String): Result<Chat> =
        loadChat(accessToken, chatId).mapCatching { response ->
            mapChat(response.chat ?: throw IllegalStateException("Chat no encontrado"))
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

    /** Distinct endpoint so older servers cannot silently turn a viewport receipt into read-all. */
    suspend fun markVisibleMessagesAsRead(accessToken: String, chatId: String, messageIds: List<String>): Result<Set<String>> {
        return markVisibleConversationMessagesAsRead(accessToken, "chat", chatId, messageIds)
    }

    suspend fun markVisibleConversationMessagesAsRead(accessToken: String, kind: String, roomId: String, messageIds: List<String>): Result<Set<String>> {
        val collection = when (kind) { "chat" -> "chats"; "group" -> "groups"; "community" -> "communities"; else -> return Result.failure(IllegalArgumentException("Unknown conversation kind")) }
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        if (messageIds.isEmpty()) return Result.success(emptySet())
        require(messageIds.size <= 200)
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/$collection/${urlEncode(roomId)}/read-visible") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("messageIds", JsonArray(messageIds.map(::JsonPrimitive))) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val payload = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val confirmed = payload["messageIds"]?.jsonArray
                    ?: return Result.failure(Exception("El servidor no confirmó los mensajes leídos."))
                Result.success(confirmed.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .map { it.trim().lowercase() }.filter { it in messageIds }.toSet())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: CancellationException) {
            throw error
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

    suspend fun resolveSupportAdminId(accessToken: String, alias: String): Result<String> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackSupportAdminResponse>(
            "$baseUrl/v1/data/support/admins/${urlEncode(alias.trim().lowercase())}",
            accessToken
        ).mapCatching { response ->
            val admin = response.admin
                ?: throw IllegalStateException("No encontramos ese administrador de soporte.")
            textOf(admin, "id", "user_id", "profile_id")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("La cuenta de soporte no está disponible.")
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

    suspend fun refreshSession(
        refreshToken: String,
        accessToken: String? = null
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val token = refreshToken.trim()
        if (token.isBlank()) return Result.failure(Exception("No hay una sesión que renovar."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/refresh") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackRefreshRequest(
                        refreshToken = token,
                        accessToken = accessToken?.trim()?.takeIf { it.isNotBlank() }
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

    /** Revokes only the mobile session represented by this opaque refresh credential. */
    suspend fun revokeSession(refreshToken: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val token = refreshToken.trim()
        if (token.isBlank()) return Result.failure(Exception("No hay sesión que cerrar."))
        RuntimeBackendEndpoint.ensureFreshEndpoint()
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/logout") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(mapOf("refreshToken" to token))
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(response.bodyAsText())))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadCommunityRoleCatalog(
        accessToken: String,
        communityId: String
    ): Result<CommunityRoleCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/roles",
            accessToken
        )
    }

    suspend fun loadCommunityChannels(
        accessToken: String,
        communityId: String
    ): Result<CommunityChannelCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels",
            accessToken
        )
    }

    suspend fun createCommunityChannel(
        accessToken: String,
        communityId: String,
        name: String,
        description: String?,
        type: String,
        position: Int = 100
    ): Result<CommunityChannel> = communityChannelMutation {
        post("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(LocalFallbackCommunityChannelMutationRequest(name, description, type, position))
        }
    }

    suspend fun updateCommunityChannel(
        accessToken: String,
        communityId: String,
        channelId: String,
        name: String,
        description: String?,
        type: String,
        position: Int
    ): Result<CommunityChannel> = communityChannelMutation {
        patch("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels/${urlEncode(channelId)}") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(LocalFallbackCommunityChannelMutationRequest(name, description, type, position))
        }
    }

    suspend fun pauseCommunityChannel(
        accessToken: String,
        communityId: String,
        channelId: String,
        paused: Boolean,
        minutes: Int,
        reason: String?
    ): Result<CommunityChannel> = communityChannelMutation {
        post("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels/${urlEncode(channelId)}/pause") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(LocalFallbackCommunityChannelPauseRequest(paused, minutes, reason))
        }
    }

    suspend fun deleteCommunityChannel(
        accessToken: String,
        communityId: String,
        channelId: String
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels/${urlEncode(channelId)}"
            ) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun openCommunityVoiceChannel(
        accessToken: String,
        communityId: String,
        channelId: String
    ): Result<VoiceRoom> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/channels/${urlEncode(channelId)}/voice-room"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val decoded = json.decodeFromString<LocalFallbackVoiceRoomResponse>(body)
                decoded.room?.let { Result.success(mapVoiceRoom(it)) }
                    ?: Result.failure(IllegalStateException("El canal de voz no devolvió una sala válida."))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadCommunityMemberProfiles(
        accessToken: String,
        communityId: String
    ): Result<CommunityMemberProfileCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/member-profiles",
            accessToken
        )
    }

    suspend fun updateCommunityMemberProfile(
        accessToken: String,
        communityId: String,
        alias: String?,
        bio: String?,
        distinctive: String?
    ): Result<CommunityMemberProfile> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/member-profile"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCommunityProfileMutationRequest(alias, bio, distinctive))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackCommunityProfileResponse>(body).profile)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadCommunityEvents(
        accessToken: String,
        communityId: String
    ): Result<CommunityEventCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/events",
            accessToken
        )
    }

    suspend fun createCommunityEvent(
        accessToken: String,
        communityId: String,
        title: String,
        description: String?,
        startsAt: String,
        endsAt: String?,
        location: String?
    ): Result<CommunityEvent> = communityEventMutation {
        post("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/events") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                LocalFallbackCommunityEventMutationRequest(
                    title = title,
                    description = description,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    location = location
                )
            )
        }
    }

    suspend fun cancelCommunityEvent(
        accessToken: String,
        communityId: String,
        eventId: String
    ): Result<CommunityEvent> = communityEventMutation {
        delete(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/events/${urlEncode(eventId)}"
        ) {
            localHeaders(accessToken)
        }
    }

    suspend fun updateCommunityEventAttendance(
        accessToken: String,
        communityId: String,
        eventId: String,
        status: String,
        reminderMinutes: Int?
    ): Result<CommunityEvent> = communityEventMutation {
        put(
            "$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/events/${urlEncode(eventId)}/attendance"
        ) {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(LocalFallbackCommunityEventAttendanceRequest(status, reminderMinutes))
        }
    }

    private suspend fun communityEventMutation(
        request: suspend HttpClient.() -> HttpResponse
    ): Result<CommunityEvent> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.request()
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackCommunityEventResponse>(body).event)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun communityChannelMutation(
        request: suspend HttpClient.() -> HttpResponse
    ): Result<CommunityChannel> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.request()
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackCommunityChannelResponse>(body).channel)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadCurrentProfile(accessToken: String): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runReadWithDeadline(InteractiveReadDeadlines.ProfileMillis) {
            requestJson<LocalFallbackProfileResponse>("$baseUrl/v1/data/profile/me", accessToken)
                .getOrThrow()
        }
    }

    suspend fun loadProfile(accessToken: String, profileId: String): Result<LocalFallbackProfileResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runReadWithDeadline(InteractiveReadDeadlines.ProfileMillis) {
            requestJson<LocalFallbackProfileResponse>(
                "$baseUrl/v1/data/profiles/${urlEncode(profileId)}",
                accessToken,
            ).getOrThrow().normalized()
        }
    }

    suspend fun loadProfileMusic(accessToken: String, profileId: String): Result<conectate.com.data.model.ProfileMusicSummary> =
        runReadWithDeadline(InteractiveReadDeadlines.ProfileMillis) {
            requestJson<conectate.com.data.model.ProfileMusicSummary>(
                "$baseUrl/v1/data/profiles/${urlEncode(profileId)}/music/reactions", accessToken,
            ).getOrThrow()
        }

    suspend fun updateProfileMusic(
        accessToken: String, profileId: String, musicKey: String,
        reaction: String? = null, presentation: String? = null,
    ): Result<conectate.com.data.model.ProfileMusicSummary> = try {
        val part = if (presentation == null) "reactions" else "presentation"
        val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/profiles/${urlEncode(profileId)}/music/$part") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("musicKey", JsonPrimitive(musicKey))
                if (presentation != null) put("presentation", JsonPrimitive(presentation))
                else put("reaction", reaction?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    suspend fun getBlockRelationship(
        accessToken: String,
        profileId: String
    ): Result<LocalFallbackBlockRelationshipResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackBlockRelationshipResponse>("$baseUrl/v1/data/profiles/${urlEncode(profileId)}/block", accessToken)
    }

    suspend fun blockUser(
        accessToken: String,
        profileId: String
    ): Result<LocalFallbackBlockRelationshipResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/profiles/${urlEncode(profileId)}/block") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackBlockRelationshipResponse>(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun unblockUser(
        accessToken: String,
        profileId: String
    ): Result<LocalFallbackBlockRelationshipResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/profiles/${urlEncode(profileId)}/block") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackBlockRelationshipResponse>(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadProfileUser(accessToken: String, profileId: String): Result<User?> {
        return loadProfile(accessToken, profileId).map { response ->
            response.profile?.let(::mapProfileUser)?.withComputedFollowCounts(response.stats)
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

    suspend fun setProfileFollow(accessToken: String, profileId: String, following: Boolean): Result<Unit> = try {
        val response = supabaseConfig.httpClient.request("$baseUrl/v1/data/profiles/${urlEncode(profileId)}/follow") {
            method = if (following) HttpMethod.Post else HttpMethod.Delete
            localHeaders(accessToken)
        }
        if (response.status.isSuccess()) Result.success(Unit)
        else Result.failure(toException(response, decodeError(response.bodyAsText())))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

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
        username: String? = null,
        bio: String? = null,
        country: String? = null,
        city: String? = null,
        gender: String? = null,
        birthDate: String? = null,
        status: String? = null,
        interests: List<String>? = null,
        hobbies: List<String>? = null,
        linkedGames: List<String>? = null,
        gameLogoUrls: Map<String, String>? = null,
        favoriteMusicUrl: String? = null,
        favoriteBook: String? = null,
        favoriteSport: String? = null,
        career: String? = null,
        education: String? = null,
        workplace: String? = null,
        availabilitySchedule: String? = null,
        profileSectionOrder: List<String>? = null,
        profileSectionVisibility: Map<String, String>? = null,
        socialLinks: Map<String, String>? = null,
        avatarUrl: String? = null,
        avatarMediaType: String? = null,
        avatarVideoMuted: Boolean? = null,
        avatarDurationMs: Long? = null,
        coverUrl: String? = null,
        removeAvatar: Boolean = false,
        removeCover: Boolean = false,
        profileTheme: String? = null,
        profileEffect: String? = null,
        profileWelcome: String? = null,
        isPrivate: Boolean? = null,
        favoritesPublic: Boolean? = null,
        breakMode: String? = null,
        profileVisibility: String? = null,
        profileViewHistoryEnabled: Boolean? = null,
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
                        username = username,
                        bio = bio,
                        country = country,
                        city = city,
                        gender = gender,
                        birthDate = birthDate,
                        status = status,
                        interests = interests,
                        hobbies = hobbies,
                        linkedGames = linkedGames,
                        gameLogoUrls = gameLogoUrls,
                        favoriteMusicUrl = UbuntuStorageUrl.rewrite(favoriteMusicUrl),
                        favoriteBook = favoriteBook,
                        favoriteSport = favoriteSport,
                        career = career,
                        education = education,
                        workplace = workplace,
                        availabilitySchedule = availabilitySchedule,
                        profileSectionOrder = profileSectionOrder,
                        profileSectionVisibility = profileSectionVisibility,
                        socialLinks = socialLinks,
                        avatarUrl = UbuntuStorageUrl.rewrite(avatarUrl),
                        avatarMediaType = avatarMediaType,
                        avatarVideoMuted = avatarVideoMuted,
                        avatarDurationMs = avatarDurationMs,
                        coverUrl = UbuntuStorageUrl.rewrite(coverUrl),
                        removeAvatar = removeAvatar,
                        removeCover = removeCover,
                        profileTheme = profileTheme,
                        profileEffect = profileEffect,
                        profileWelcome = profileWelcome,
                        isPrivate = isPrivate,
                        favoritesPublic = favoritesPublic,
                        breakMode = breakMode,
                        profileVisibility = profileVisibility,
                        profileViewHistoryEnabled = profileViewHistoryEnabled,
                        lastSeenVisibility = lastSeenVisibility,
                        onlineStatusVisibility = onlineStatusVisibility
                    ).toSparseJson()
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
    internal data class LocalFallbackCreateMomentRequest(
        val mediaUrl: String,
        val mediaType: String,
        val thumbnailUrl: String? = null,
        val displayDurationMs: Long? = null,
        val durationHours: Int = 24,
        val durationType: String = "24h",
        val visibility: String = "all",
        val visibilityType: String = "public",
        val excludedIds: List<String> = emptyList(),
        val audienceIds: List<String> = emptyList(),
        val caption: String? = null,
        val allowReplies: Boolean = true,
        val overlayText: String? = null,
        val overlayColor: String = "#FFFFFFFF",
        val overlayFont: String = "sans",
        val overlayScale: Float = 1f,
        val overlayX: Float = 0.5f,
        val overlayY: Float = 0.38f,
        val musicUrl: String? = null,
        val musicTitle: String? = null,
        val musicId: String? = null,
        val musicArtist: String? = null,
        val musicPresentation: String = "card",
        val musicArtworkUrl: String? = null,
        val musicSource: String? = null,
        val musicSourceUrl: String? = null,
        val musicLicense: String? = null,
        val musicLicenseUrl: String? = null,
        val musicStartMs: Long = 0L,
        val musicDurationMs: Long = 6_000L,
        val musicEndMs: Long? = null,
        val musicVolume: Float = 1f,
        val originalAudioVolume: Float = 1f,
        val musicFadeInMs: Long = 0L,
        val musicFadeOutMs: Long = 0L,
        val duckOriginalAudio: Boolean = false,
        val musicEmbedded: Boolean = false,
        val musicOverlayX: Float = .5f,
        val musicOverlayY: Float = .5f,
        val musicOverlayScale: Float = 1f,
        val musicOverlayRotation: Float = 0f,
        val musicLyrics: String? = null,
    )

    @Serializable
    private data class LocalFallbackRawMomentResponse(
        val moment: JsonObject? = null
    )

    suspend fun loadMomentsFeed(
        accessToken: String,
        ownerIds: List<String> = emptyList()
    ): Result<LocalFallbackMomentsFeedResponse> {
        if (!isConfigured) return Result.failure(IllegalStateException("FALLBACK_API_URL no configurada"))
        val owners = conectate.com.data.model.momentAvatarOwnerIds(ownerIds)
        val query = if (owners.isEmpty()) "" else "?owner_ids=" + owners.joinToString(",").encodeURLParameter()
        return requestJson("$baseUrl/v1/data/moments/feed$query", accessToken)
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
        excludedIds: List<String>,
        audienceIds: List<String>,
        caption: String?,
        allowReplies: Boolean,
        overlayText: String?,
        overlayColor: String,
        overlayFont: String,
        overlayScale: Float,
        overlayX: Float,
        overlayY: Float,
        musicUrl: String?,
        musicTitle: String?,
        musicId: String?,
        musicArtist: String?,
        musicPresentation: String,
        musicArtworkUrl: String?,
        musicSource: String?,
        musicSourceUrl: String?,
        musicLicense: String?,
        musicLicenseUrl: String?,
        musicStartMs: Long,
        musicDurationMs: Long,
        musicEndMs: Long?,
        musicVolume: Float,
        originalAudioVolume: Float,
        musicFadeInMs: Long,
        musicFadeOutMs: Long,
        duckOriginalAudio: Boolean,
        musicEmbedded: Boolean,
        musicOverlayX: Float = .5f,
        musicOverlayY: Float = .5f,
        musicOverlayScale: Float = 1f,
        musicOverlayRotation: Float = 0f,
        musicLyrics: String? = null,
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
                        musicUrl = UbuntuStorageUrl.rewrite(musicUrl),
                        musicTitle = musicTitle,
                        musicId = musicId,
                        musicArtist = musicArtist,
                        musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(musicPresentation).wireValue,
                        musicArtworkUrl = UbuntuStorageUrl.rewrite(musicArtworkUrl),
                        musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayX),
                        musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(musicOverlayY),
                        musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(musicOverlayScale),
                        musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(musicOverlayRotation),
                        musicLyrics = musicLyrics?.takeIf { it.length <= 12_000 },
                        musicSource = musicSource,
                        musicSourceUrl = musicSourceUrl,
                        musicLicense = musicLicense,
                        musicLicenseUrl = musicLicenseUrl,
                        musicStartMs = musicStartMs,
                        musicDurationMs = musicDurationMs,
                        musicEndMs = musicEndMs,
                        musicVolume = musicVolume,
                        originalAudioVolume = originalAudioVolume,
                        musicFadeInMs = musicFadeInMs,
                        musicFadeOutMs = musicFadeOutMs,
                        duckOriginalAudio = duckOriginalAudio,
                        musicEmbedded = musicEmbedded,
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
        userId: String? = null,
        mode: String? = null
    ): Result<LocalFallbackFeedResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val query = buildString {
            append("$baseUrl/v1/data/feed?limit=$limit&offset=$offset")
            userId?.trim()?.takeIf { it.isNotBlank() }?.let {
                append("&userId=${urlEncode(it)}")
            }
            mode?.trim()?.lowercase()?.takeIf { it in setOf("discover", "following") }?.let {
                append("&mode=${urlEncode(it)}")
            }
        }
        return runReadWithDeadline(InteractiveReadDeadlines.FirstPageMillis) {
            requestJson<LocalFallbackRawFeedResponse>(query, accessToken).getOrThrow()
        }
            .map { response ->
                val nextOffset = publicFeedNextOffset(offset, limit, response.items.size,
                    response.hasMore, response.nextOffset)
                LocalFallbackFeedResponse(response.items.map { mapPost(it) }, nextOffset != null, nextOffset)
            }
    }

    suspend fun searchPublicContent(
        accessToken: String,
        query: String,
        limit: Int = 24
    ): Result<LocalFallbackSemanticSearchResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) {
            return Result.failure(IllegalArgumentException("Escribe al menos dos caracteres."))
        }
        val cleanLimit = limit.coerceIn(1, 50)
        return requestJson(
            "$baseUrl/v1/data/search/semantic?q=${urlEncode(cleanQuery)}&limit=$cleanLimit",
            accessToken
        )
    }

    suspend fun searchPublicContentPage(
        accessToken: String,
        query: String,
        limit: Int = 20,
        offset: Int = 0,
    ): Result<LocalFallbackSemanticSearchResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanQuery = query.trim().take(120)
        if (cleanQuery.length < 2) return Result.failure(IllegalArgumentException("Escribe al menos dos caracteres."))
        val cleanLimit = limit.coerceIn(1, 50)
        val cleanOffset = offset.coerceAtLeast(0)
        return requestJson(
            "$baseUrl/v1/data/search/semantic?q=${urlEncode(cleanQuery)}&limit=$cleanLimit&offset=$cleanOffset",
            accessToken,
        )
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
        visibility: String,
        remixAllowed: Boolean = false,
        remixAudience: String = "public",
        isSensitive: Boolean = false
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
                        visibility = visibility,
                        isSensitive = isSensitive,
                        remixAllowed = remixAllowed,
                        remixAudience = remixAudience
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
                contentType(ContentType.Application.Json)
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
                    parameter("response", "minimal")
                }
            } else {
                supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/reaction") {
                    localHeaders(accessToken)
                    parameter("response", "minimal")
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

    suspend fun summarizePostThread(
        accessToken: String,
        postId: String,
        maxBullets: Int = 5
    ): Result<conectate.com.data.model.PostThreadSummary> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/thread-summary"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackPostThreadSummaryRequest(maxBullets.coerceIn(2, 6)))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(
                    json.decodeFromString<LocalFallbackPostThreadSummaryResponse>(body).summary
                )
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createRemix(
        accessToken: String,
        postId: String,
        content: String,
        visibility: String = "public"
    ): Result<Post> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/remix"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCreateRemixRequest(content = content, visibility = visibility))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val post = json.decodeFromString<LocalFallbackRawPostResponse>(body).post
                    ?: return Result.failure(Exception("El servidor Ubuntu no devolvió el remix."))
                Result.success(mapPost(post))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateRemixPermission(
        accessToken: String,
        postId: String,
        allowed: Boolean,
        audience: String = "public"
    ): Result<Post> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/remix-permission"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackRemixPermissionRequest(allowed = allowed, audience = audience))
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

    suspend fun submitModerationAppeal(
        appealToken: String,
        statement: String
    ): Result<String> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        if (appealToken.isBlank()) return Result.failure(Exception("Vuelve a iniciar sesión para apelar."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/moderation/appeals") {
                localHeaders(appealToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("statement", JsonPrimitive(statement.trim()))
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val appeal = json.parseToJsonElement(body).jsonObject["appeal"]?.jsonObject
                Result.success(textOf(appeal ?: JsonObject(emptyMap()), "status").ifBlank { "pending" })
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadSavedLibrary(
        accessToken: String,
        query: String = ""
    ): Result<SavedLibraryResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val url = buildString {
            append("$baseUrl/v1/data/saved-collections")
            query.trim().takeIf(String::isNotBlank)?.let { append("?q=${urlEncode(it)}") }
        }
        return requestJson(url, accessToken)
    }

    suspend fun createSavedCollection(
        accessToken: String,
        name: String
    ): Result<SavedCollection> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/saved-collections") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(SavedCollectionRequest(name.trim()))
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
            json.decodeFromString<SavedCollectionResponse>(body).collection
        }
    }

    suspend fun savePostToCollection(
        accessToken: String,
        collectionId: String,
        postId: String,
        tags: List<String> = emptyList(),
        note: String = ""
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/saved-collections/${urlEncode(collectionId)}/items"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(SavePostRequest(postId = postId, tags = tags, note = note))
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
        }
    }

    suspend fun removeSavedPost(
        accessToken: String,
        collectionId: String,
        postId: String
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/data/saved-collections/${urlEncode(collectionId)}/items/${urlEncode(postId)}"
            ) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
        }
    }

    suspend fun loadPostReactions(
        accessToken: String,
        postId: String,
        reactionType: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): Result<LocalFallbackPostReactionsResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val normalizedType = reactionType?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val query = buildString {
            append("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/reactions")
            append("?limit=${limit.coerceIn(1, 200)}&offset=${offset.coerceAtLeast(0)}")
            normalizedType?.let { append("&type=${urlEncode(it)}") }
        }
        return requestJson<LocalFallbackRawPostReactionsResponse>(query, accessToken)
            .map { response ->
                LocalFallbackPostReactionsResponse(
                    items = response.items.map { row ->
                        conectate.com.data.model.PostReactionUser(
                            userId = textOf(row, "user_id", "userId"),
                            userName = textOf(row, "user_name", "userName").ifBlank { "Usuario" },
                            userAvatarUrl = UbuntuStorageUrl.rewrite(
                                textOf(row, "user_avatar_url", "userAvatarUrl", "avatar_url")
                            ),
                            isVerified = boolOf(row, "is_verified", "isVerified", "verified"),
                            reactionType = textOf(row, "reaction_type", "reactionType").uppercase().ifBlank { "LIKE" },
                            reactedAt = textOf(row, "reacted_at", "reactedAt", "updated_at", "created_at")
                                .ifBlank { null }
                        )
                    },
                    total = response.total,
                    reactionCounts = response.reactionCounts.mapKeys { it.key.uppercase() }
                )
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

    suspend fun loadNotificationDigest(accessToken: String): Result<NotificationDigestOverview> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<NotificationDigestOverviewResponse>(
            "$baseUrl/v1/data/notification-digest",
            accessToken
        ).map { response ->
            NotificationDigestOverview(
                preferences = response.preferences,
                summary = response.summary
            )
        }
    }

    suspend fun updateNotificationDigestPreferences(
        accessToken: String,
        preferences: NotificationDigestPreferences
    ): Result<NotificationDigestPreferences> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put(
                "$baseUrl/v1/data/notification-digest/preferences"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    UpdateNotificationDigestPreferencesRequest(
                        enabled = preferences.enabled,
                        frequency = preferences.frequency,
                        deliveryHour = preferences.deliveryHour,
                        deliveryWeekday = preferences.deliveryWeekday,
                        timezoneOffsetMinutes = preferences.timezoneOffsetMinutes,
                        includeMentions = preferences.includeMentions,
                        includeDirectMessages = preferences.includeDirectMessages,
                        includeCommunityActivity = preferences.includeCommunityActivity,
                        includeUpcomingLives = preferences.includeUpcomingLives
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(
                    json.decodeFromString<NotificationDigestPreferencesResponse>(body).preferences
                )
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
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

    suspend fun loadLiveStreams(accessToken: String, limit: Int = 60): Result<List<LiveStream>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanLimit = limit.coerceIn(1, 100)
        return requestJson<LocalFallbackLiveStreamsResponse>(
            "$baseUrl/v1/data/live?limit=$cleanLimit",
            accessToken
        ).map { it.items }
    }

    suspend fun loadMomentNotificationPreferences(accessToken: String): Result<MomentNotificationPreferences> =
        requestJson("$baseUrl/v1/data/moments/notification-preferences", accessToken)

    suspend fun updateMomentNotificationPreferences(
        accessToken: String,
        enabled: Boolean
    ): Result<MomentNotificationPreferences> = try {
        val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/moments/notification-preferences") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to enabled))
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) { Result.failure(error) }

    suspend fun loadLiveCollaborationCapabilities(
        accessToken: String
    ): Result<LiveCollaborationCapabilities> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveCapabilitiesResponse>(
            "$baseUrl/v1/data/live/capabilities",
            accessToken
        ).map { response ->
            LiveCollaborationCapabilities(
                guestsBattlesEnabled = response.guestsBattlesEnabled,
                sfuAvailable = response.sfuAvailable,
                channelPointsEnabled = response.channelPointsEnabled,
                drawGuessEnabled = response.drawGuessEnabled,
                maxGuests = response.maxGuests,
                scoringSources = response.scoringSources
            )
        }
    }

    suspend fun issueLiveSfuSession(
        accessToken: String,
        liveId: String,
        role: String
    ): Result<LiveSfuSession> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/sfu-token"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("role", role.trim().lowercase()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val payload = json.decodeFromString<LocalFallbackLiveSfuTokenResponse>(body)
                Result.success(
                    LiveSfuSession(
                        wsUrl = payload.wsUrl,
                        roomName = payload.roomName,
                        token = payload.token,
                        expiresAt = payload.expiresAt,
                        role = payload.role,
                        maxGuests = payload.maxGuests
                    )
                )
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun inviteLiveGuest(
        accessToken: String,
        liveId: String,
        inviteeUserId: String,
        requestNonce: String = UUID.randomUUID().toString()
    ): Result<LiveGuestInvitation> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/invitations"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("inviteeUserId", inviteeUserId.trim())
                    put("requestNonce", requestNonce.trim())
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val invitation = json.decodeFromString<LocalFallbackLiveInvitationResponse>(body).invitation
                    ?: return Result.failure(Exception("El servidor no devolvió la invitación."))
                Result.success(invitation)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun requestToJoinLive(
        accessToken: String,
        liveId: String,
        requestNonce: String = UUID.randomUUID().toString()
    ): Result<LiveGuestInvitation> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/requests"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("requestNonce", requestNonce.trim()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val invitation = json.decodeFromString<LocalFallbackLiveInvitationResponse>(body).invitation
                    ?: return Result.failure(Exception("El servidor no devolvió la solicitud."))
                Result.success(invitation)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadLiveGuestInvitations(
        accessToken: String,
        liveId: String
    ): Result<List<LiveGuestInvitation>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveInvitationsResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/invitations",
            accessToken
        ).map { it.invitations }
    }

    suspend fun respondToLiveGuestInvitation(
        accessToken: String,
        liveId: String,
        invitationId: String,
        status: String
    ): Result<LiveGuestInvitation> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/invitations/${urlEncode(invitationId)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("status", status.trim().lowercase()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val invitation = json.decodeFromString<LocalFallbackLiveInvitationResponse>(body).invitation
                    ?: return Result.failure(Exception("El servidor no devolvió la invitación."))
                Result.success(invitation)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateLiveParticipantState(
        accessToken: String,
        liveId: String,
        state: String
    ): Result<LiveParticipantState> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/participants/state"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("state", state.trim().lowercase()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val participant = json.decodeFromString<LocalFallbackLiveParticipantResponse>(body).participant
                    ?: return Result.failure(Exception("El servidor no devolvió el participante."))
                Result.success(participant)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun leaveLiveGuest(accessToken: String, liveId: String): Result<Unit> = runCatching {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/leave") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
    }

    suspend fun removeLiveGuest(
        accessToken: String,
        liveId: String,
        guestUserId: String,
        reason: String = ""
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/${urlEncode(guestUserId)}/remove"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("reason", reason.trim()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess() &&
                json.decodeFromString<LocalFallbackLiveGuestActionResponse>(body).removed
            ) Result.success(Unit) else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun muteLiveGuest(
        accessToken: String,
        liveId: String,
        guestUserId: String
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/guests/${urlEncode(guestUserId)}/mute"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("muted", true) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess() &&
                json.decodeFromString<LocalFallbackLiveGuestActionResponse>(body).muted
            ) Result.success(Unit) else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun startLiveBattle(
        accessToken: String,
        liveId: String,
        opponentUserId: String,
        opponentLiveId: String? = null,
    ): Result<LiveBattle> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("opponentUserId", opponentUserId.trim())
                    opponentLiveId?.trim()?.takeIf(String::isNotBlank)?.let { put("opponentLiveId", it) }
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val battle = json.decodeFromString<LocalFallbackLiveBattleResponse>(body).battle
                    ?: return Result.failure(Exception("El servidor no devolvió la batalla."))
                Result.success(battle)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadLiveBattleCandidates(
        accessToken: String,
        liveId: String,
    ): Result<List<LiveBattleCandidate>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveBattleCandidatesResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles/candidates",
            accessToken,
        ).map { it.items }
    }

    suspend fun respondToLiveBattle(
        accessToken: String,
        liveId: String,
        battleId: String,
        accepted: Boolean,
    ): Result<LiveBattle> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles/${urlEncode(battleId)}/respond",
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("accepted", accepted) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val battle = json.decodeFromString<LocalFallbackLiveBattleResponse>(body).battle
                    ?: return Result.failure(Exception("El servidor no devolvió la batalla."))
                Result.success(battle)
            } else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadActiveLiveBattle(
        accessToken: String,
        liveId: String
    ): Result<LiveBattle?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveBattleResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles/current",
            accessToken
        ).map { it.battle }
    }

    suspend fun loadLiveSupporters(
        accessToken: String,
        liveId: String
    ): Result<List<conectate.com.data.model.LiveSupporter>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveSupportersResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/supporters",
            accessToken
        ).map { it.items }
    }

    suspend fun loadLiveGiftEvents(accessToken: String, liveId: String): Result<List<conectate.com.data.model.LiveGiftEvent>> =
        requestJson<LiveGiftEventsResponse>("$baseUrl/v1/data/live/${urlEncode(liveId)}/gifts", accessToken)
            .map { it.items }

    suspend fun loadCurrentLiveDrawGuess(
        accessToken: String,
        liveId: String
    ): Result<LiveDrawGuessState?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveDrawCurrentResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/current",
            accessToken
        ).map { it.state }
    }

    suspend fun createLiveDrawGuess(
        accessToken: String,
        liveId: String,
        participantIds: List<String>,
        totalRounds: Int = 3,
        roundSeconds: Int = 75,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<LiveDrawGuessState> = postLiveDrawState(
        accessToken = accessToken,
        url = "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess",
        body = buildJsonObject {
            put("participantIds", buildJsonArray {
                participantIds.map(String::trim).filter(String::isNotBlank).distinct().take(7)
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("totalRounds", totalRounds.coerceIn(1, 12))
            put("roundSeconds", roundSeconds.coerceIn(30, 180))
            put("maxPlayers", (participantIds.distinct().size + 1).coerceIn(2, 8))
            put("idempotencyKey", idempotencyKey)
        }
    )

    suspend fun acceptLiveDrawGuess(
        accessToken: String,
        liveId: String,
        gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<LiveDrawGuessState> = postLiveDrawState(
        accessToken,
        "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/accept",
        buildJsonObject { put("idempotencyKey", idempotencyKey) }
    )

    suspend fun startLiveDrawGuess(
        accessToken: String,
        liveId: String,
        gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<LiveDrawGuessState> = postLiveDrawState(
        accessToken,
        "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/start",
        buildJsonObject { put("idempotencyKey", idempotencyKey) }
    )

    suspend fun cancelLiveDrawGuess(
        accessToken: String,
        liveId: String,
        gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<LiveDrawGuessState> = postLiveDrawState(
        accessToken,
        "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/cancel",
        buildJsonObject { put("idempotencyKey", idempotencyKey) }
    )

    suspend fun advanceLiveDrawGuess(
        accessToken: String,
        liveId: String,
        gameId: String
    ): Result<LiveDrawGuessState> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/advance"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
            json.decodeFromString<LocalFallbackLiveDrawAdvanceResponse>(body).state
        }
    }

    suspend fun sendLiveDrawPacket(
        accessToken: String,
        liveId: String,
        gameId: String,
        packet: LiveDrawPacket
    ): Result<LiveDrawPacketReceipt> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/draw"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(packet)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
            json.decodeFromString<LiveDrawPacketReceipt>(body)
        }
    }

    suspend fun submitLiveDrawGuess(
        accessToken: String,
        liveId: String,
        gameId: String,
        guess: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<LiveDrawGuessGuessResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/games/draw-guess/${urlEncode(gameId)}/guesses"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("guess", guess.trim())
                    put("idempotencyKey", idempotencyKey)
                })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(body))
            json.decodeFromString<LiveDrawGuessGuessResult>(body)
        }
    }

    private suspend fun postLiveDrawState(
        accessToken: String,
        url: String,
        body: JsonObject
    ): Result<LiveDrawGuessState> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) throw toException(response, decodeError(responseBody))
            json.decodeFromString<LiveDrawGuessState>(responseBody)
        }
    }

    suspend fun loadLiveBattle(
        accessToken: String,
        liveId: String,
        battleId: String
    ): Result<LiveBattle> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveBattleResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles/${urlEncode(battleId)}",
            accessToken
        ).mapCatching { response ->
            response.battle ?: throw IllegalStateException("Batalla no encontrada")
        }
    }

    suspend fun endLiveBattle(
        accessToken: String,
        liveId: String,
        battleId: String
    ): Result<LiveBattle> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/battles/${urlEncode(battleId)}/end"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {})
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val battle = json.decodeFromString<LocalFallbackLiveBattleResponse>(body).battle
                    ?: return Result.failure(Exception("El servidor no devolvió la batalla."))
                Result.success(battle)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadLiveStream(accessToken: String, liveId: String): Result<LiveStream> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveStreamResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}",
            accessToken
        ).mapCatching { response ->
            response.stream ?: throw IllegalStateException("Live no encontrado")
        }
    }

    suspend fun createLiveStream(
        accessToken: String,
        id: String,
        title: String,
        source: String,
        hashtags: List<String>,
        coverUrl: String? = null,
        gameName: String? = null,
        category: String? = null,
        visibility: String = "public",
        giftsEnabled: Boolean = true,
        battlesEnabled: Boolean = true
    ): Result<LiveStream> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/live") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackCreateLiveRequest(
                        id = id,
                        title = title,
                        source = source,
                        hashtags = hashtags,
                        coverUrl = coverUrl,
                        gameName = gameName,
                        category = category,
                        visibility = visibility,
                        giftsEnabled = giftsEnabled,
                        battlesEnabled = battlesEnabled
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val stream = json.decodeFromString<LocalFallbackLiveStreamResponse>(body).stream
                    ?: return Result.failure(Exception("El servidor no devolvió el live."))
                Result.success(stream)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateLiveState(
        accessToken: String,
        liveId: String,
        isActive: Boolean? = null,
        source: String? = null,
        viewerCount: Int? = null,
        peakViewerCount: Int? = null,
        totalComments: Int? = null,
        totalReactions: Int? = null,
        allowGuestRequests: Boolean? = null
    ): Result<LiveStream> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val payload = buildJsonObject {
                isActive?.let { put("isActive", it) }
                source?.trim()?.lowercase()?.takeIf { it == "camera" || it == "screen" || it == "audio" }?.let {
                    put("source", it)
                }
                viewerCount?.let { put("viewerCount", it) }
                peakViewerCount?.let { put("peakViewerCount", it) }
                totalComments?.let { put("totalComments", it) }
                totalReactions?.let { put("totalReactions", it) }
                allowGuestRequests?.let { put("allowGuestRequests", it) }
            }
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/state"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val stream = json.decodeFromString<LocalFallbackLiveStreamResponse>(body).stream
                    ?: return Result.failure(Exception("El servidor no devolvió el live."))
                Result.success(stream)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadLiveComments(
        accessToken: String,
        liveId: String,
        limit: Int = 80
    ): Result<List<LiveComment>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanLimit = limit.coerceIn(1, 200)
        return requestJson<LocalFallbackLiveCommentsResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/comments?limit=$cleanLimit",
            accessToken
        ).map { it.items }
    }

    suspend fun createLiveComment(
        accessToken: String,
        liveId: String,
        content: String,
        clientCommentId: String? = null
    ): Result<LiveComment> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/comments"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCreateLiveCommentRequest(content = content, clientCommentId = clientCommentId))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val comment = json.decodeFromString<LocalFallbackLiveCommentResponse>(body).comment
                    ?: return Result.failure(Exception("El servidor no devolvió el comentario."))
                Result.success(comment)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
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
        sharedBy: String? = null,
        favoritedBy: String? = null,
        soundId: String? = null,
        recommended: Boolean = false,
        feedSessionId: String? = null,
        followingOnly: Boolean = false,
    ): Result<List<Video>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val url = buildString {
            append("$baseUrl/v1/data/videos?limit=$limit&offset=$offset")
            userId?.trim()?.takeIf { it.isNotBlank() }?.let { append("&userId=${urlEncode(it)}") }
            query?.trim()?.takeIf { it.isNotBlank() }?.let { append("&q=${urlEncode(it)}") }
            sharedBy?.trim()?.takeIf { it.isNotBlank() }?.let { append("&sharedBy=${urlEncode(it)}") }
            favoritedBy?.trim()?.takeIf { it.isNotBlank() }?.let { append("&favoritedBy=${urlEncode(it)}") }
            soundId?.trim()?.takeIf { it.isNotBlank() }?.let { append("&soundId=${urlEncode(it)}") }
            if (recommended) append("&recommended=true")
            if (followingOnly) append("&followingOnly=true")
            feedSessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                append("&feedSessionId=${urlEncode(it.take(80))}")
            }
        }
        return runReadWithDeadline(InteractiveReadDeadlines.FirstPageMillis) {
            requestJson<LocalFallbackRawVideosResponse>(url, accessToken).getOrThrow()
        }
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
        isSensitive: Boolean = false,
        tags: List<String>,
        soundId: String? = null,
        soundSourceVideoId: String? = null,
        clientPublishId: String? = null,
        chapters: List<conectate.com.data.model.VideoChapter> = emptyList(),
        allowClipSharing: Boolean = true,
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
                        isSensitive = isSensitive,
                        tags = tags,
                        soundId = soundId,
                        soundSourceVideoId = soundSourceVideoId,
                        clientPublishId = clientPublishId,
                        chapters = chapters,
                        allowClipSharing = allowClipSharing,
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

    suspend fun updateVideo(accessToken: String, videoId: String, title: String,
                            description: String, isPublic: Boolean,
                            allowClipSharing: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/videos/${urlEncode(videoId)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackUpdateVideoRequest(title, description, isPublic, allowClipSharing))
            }
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(response.bodyAsText())))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun getVideoByPublishId(accessToken: String, attemptId: String): Result<Video?> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get(
                "$baseUrl/v1/data/videos/publish-attempt/${urlEncode(attemptId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.value == 404) Result.success(null)
            else if (response.status.isSuccess()) {
                val video = json.decodeFromString<LocalFallbackRawVideoResponse>(body).video
                    ?: return Result.failure(Exception("El servidor no devolvió el video publicado."))
                Result.success(mapVideo(video))
            } else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun setMomentReaction(accessToken: String, momentId: String, liked: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (liked) {
                supabaseConfig.httpClient.post("$baseUrl/v1/data/moments/${urlEncode(momentId)}/reaction") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                }
            } else {
                supabaseConfig.httpClient.delete("$baseUrl/v1/data/moments/${urlEncode(momentId)}/reaction") {
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

    suspend fun setVideoFavorite(accessToken: String, videoId: String, favorite: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (favorite) {
                supabaseConfig.httpClient.post("$baseUrl/v1/data/videos/${urlEncode(videoId)}/favorite") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                }
            } else {
                supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/${urlEncode(videoId)}/favorite") {
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

    suspend fun setPostCommentReaction(
        accessToken: String,
        commentId: String,
        reactionType: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (reactionType == null) {
                supabaseConfig.httpClient.delete(
                    "$baseUrl/v1/data/feed/comments/${urlEncode(commentId)}/reaction"
                ) { localHeaders(accessToken) }
            } else {
                supabaseConfig.httpClient.put(
                    "$baseUrl/v1/data/feed/comments/${urlEncode(commentId)}/reaction"
                ) {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("reactionType", reactionType.uppercase()) })
                }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadAdditionalLoginEmails(accessToken: String): Result<List<String>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLoginEmailsResponse>(
            "$baseUrl/v1/data/profile/login-emails",
            accessToken
        ).map { it.items.map(String::trim).filter(String::isNotBlank).distinct() }
    }

    suspend fun addAdditionalLoginEmail(accessToken: String, email: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/profile/login-emails") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email", email.trim().lowercase()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun removeAdditionalLoginEmail(accessToken: String, email: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/data/profile/login-emails/${urlEncode(email.trim().lowercase())}"
            ) {
                localHeaders(accessToken)
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

    suspend fun getVideoWatchLater(accessToken: String, cursor: String = "", sort: String = "newest"): Result<VideoWatchLaterPage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val suffix = "?sort=${urlEncode(sort)}" +
                cursor.takeIf(String::isNotBlank)?.let { "&cursor=${urlEncode(it)}" }.orEmpty()
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/videos/watch-later/list$suffix") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<VideoWatchLaterPage>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun setVideoWatchLater(accessToken: String, videoId: String, saved: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val url = "$baseUrl/v1/data/videos/watch-later/${urlEncode(videoId)}"
            val response = if (saved) supabaseConfig.httpClient.put(url) { localHeaders(accessToken) }
            else supabaseConfig.httpClient.delete(url) { localHeaders(accessToken) }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun getVideoHistory(accessToken: String, cursor: String = ""): Result<VideoHistoryPage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val suffix = cursor.takeIf(String::isNotBlank)?.let { "?cursor=${urlEncode(it)}" }.orEmpty()
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/videos/history/list$suffix") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<VideoHistoryPage>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun recordVideoHistory(accessToken: String, videoId: String,
                                   positionMs: Long, durationMs: Long): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/videos/history/${urlEncode(videoId)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(VideoHistoryProgress(positionMs, durationMs))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun deleteVideoHistory(accessToken: String, videoId: String? = null): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val suffix = videoId?.let { "/${urlEncode(it)}" }.orEmpty()
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/videos/history$suffix") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun getVideoCollections(accessToken: String): Result<VideoCollectionCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/videos/collections") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<VideoCollectionCatalog>(body))
            // Older servers interpret /videos/collections as /videos/:videoId.
            // A VIDEO_NOT_FOUND on this catalog route cannot refer to a selected video.
            else {
                val error = decodeError(body)
                if (response.status.value == 404 && error.error in setOf("NOT_FOUND", "VIDEO_NOT_FOUND")) {
                    Result.failure(BackendRouteUnavailableException())
                } else {
                    Result.failure(toException(response, error))
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun saveVideoCollection(accessToken: String, name: String,
                                    collectionId: String? = null): Result<VideoCollection> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val url = "$baseUrl/v1/data/videos/collections" +
                collectionId?.let { "/${urlEncode(it)}" }.orEmpty()
            val response = if (collectionId == null) supabaseConfig.httpClient.post(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(VideoCollectionNameRequest(name))
            } else supabaseConfig.httpClient.patch(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(VideoCollectionNameRequest(name))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<VideoCollection>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun deleteVideoCollection(accessToken: String, collectionId: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete(
                "$baseUrl/v1/data/videos/collections/${urlEncode(collectionId)}") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun getVideoCollectionItems(accessToken: String, collectionId: String,
                                        search: String = "", offset: Int = 0): Result<VideoCollectionPage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val suffix = "?offset=$offset" + (search.takeIf(String::isNotBlank)
                ?.let { "&search=${urlEncode(it)}" }.orEmpty())
            val response = supabaseConfig.httpClient.get(
                "$baseUrl/v1/data/videos/collections/${urlEncode(collectionId)}/items$suffix") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<VideoCollectionPage>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun setVideoCollectionItem(accessToken: String, collectionId: String,
                                       videoId: String, add: Boolean): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val url = "$baseUrl/v1/data/videos/collections/${urlEncode(collectionId)}/items/${urlEncode(videoId)}"
            val response = if (add) supabaseConfig.httpClient.put(url) { localHeaders(accessToken) }
            else supabaseConfig.httpClient.delete(url) { localHeaders(accessToken) }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun moveVideoCollectionItem(accessToken: String, collectionId: String,
                                        videoId: String, direction: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/videos/collections/${urlEncode(collectionId)}/items/${urlEncode(videoId)}/move") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(VideoCollectionMoveRequest(direction))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun sharePost(accessToken: String, postId: String): Result<LocalFallbackVideoShareResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/share") {
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

    suspend fun unsharePost(accessToken: String, postId: String): Result<LocalFallbackVideoShareResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/feed/posts/${urlEncode(postId)}/share") {
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

    suspend fun editVideoComment(accessToken: String, commentId: String, content: String): Result<Unit> =
        mutateVideoComment(
            accessToken = accessToken,
            method = "PATCH",
            path = "/v1/data/videos/comments/${urlEncode(commentId)}",
            body = buildJsonObject { put("content", content) }
        )

    suspend fun setVideoCommentReaction(
        accessToken: String,
        commentId: String,
        reactionType: String?
    ): Result<Unit> = mutateVideoComment(
        accessToken = accessToken,
        method = if (reactionType == null) "DELETE" else "PUT",
        path = "/v1/data/videos/comments/${urlEncode(commentId)}/reaction",
        body = reactionType?.let { buildJsonObject { put("reactionType", it) } }
    )

    suspend fun reportVideoComment(
        accessToken: String,
        commentId: String,
        reason: String,
        details: String = ""
    ): Result<Unit> = mutateVideoComment(
        accessToken = accessToken,
        method = "POST",
        path = "/v1/data/videos/comments/${urlEncode(commentId)}/report",
        body = buildJsonObject {
            put("reason", reason)
            put("details", details)
        }
    )

    private suspend fun mutateVideoComment(
        accessToken: String,
        method: String,
        path: String,
        body: JsonObject? = null
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = when (method) {
                "PATCH" -> supabaseConfig.httpClient.patch("$baseUrl$path") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    body?.let { setBody(it) }
                }
                "PUT" -> supabaseConfig.httpClient.put("$baseUrl$path") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    body?.let { setBody(it) }
                }
                "DELETE" -> supabaseConfig.httpClient.delete("$baseUrl$path") { localHeaders(accessToken) }
                else -> supabaseConfig.httpClient.post("$baseUrl$path") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    body?.let { setBody(it) }
                }
            }
            val responseBody = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(responseBody)))
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

    suspend fun updateCommunityInfo(
        accessToken: String,
        communityId: String,
        name: String,
        description: String,
        isPublic: Boolean,
        onlyAdminsCanTalk: Boolean,
        weeklyMessageLimit: Int,
        imageUrl: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/communities/${urlEncode(communityId)}/update") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("is_public", isPublic)
                    put("only_admins_can_talk", onlyAdminsCanTalk)
                    put("daily_message_limit", 0)
                    put("weekly_message_limit", weeklyMessageLimit.coerceIn(0, 10000))
                    if (imageUrl != null) put("image", imageUrl)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateGroupInfo(
        accessToken: String,
        groupId: String,
        name: String,
        description: String,
        onlyAdminsCanTalk: Boolean,
        imageUrl: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/groups/${urlEncode(groupId)}/update") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("only_admins_can_talk", onlyAdminsCanTalk)
                    if (imageUrl != null) put("image", imageUrl)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
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
        thumbnailUrl: String?,
        mediaSize: Long,
        replyToId: String?,
        messageId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
    ): Result<Message> {
        if (RuntimeBackendEndpoint.ensureFreshEndpoint() == null) {
            return Result.failure(Exception("Servidor temporalmente no disponible. El mensaje se enviará al recuperar la conexión."))
        }
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val request = LocalFallbackSendMessageRequest(
            id = messageId,
            content = content,
            contentType = contentType,
            mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl).orEmpty(),
            thumbnailUrl = UbuntuStorageUrl.rewrite(thumbnailUrl).orEmpty(),
            mediaSize = mediaSize,
            replyToId = replyToId ?: "",
            isForwarded = isForwarded,
            forwardedFromMessageId = forwardedFromMessageId,
            forwardedFromUserId = forwardedFromUserId
        )
        val initialUrl = "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages"
        return try {
            sendChatMessageOnce(initialUrl, accessToken, request).fold(
                onSuccess = { Result.success(it) },
                onFailure = { firstError ->
                    if (firstError is CancellationException) throw firstError
                    if (firstError is MessageSendHttpException && !firstError.isRetryable) {
                        return@fold Result.failure(firstError)
                    }
                    val retryUrl = refreshedRetryUrl(initialUrl)
                    if (retryUrl != null && retryUrl != initialUrl) {
                        sendChatMessageOnce(retryUrl, accessToken, request)
                    } else {
                        Result.failure(firstError)
                    }
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retryUrl = refreshedRetryUrl(initialUrl)
            if (retryUrl != null && retryUrl != initialUrl) {
                sendChatMessageOnce(retryUrl, accessToken, request)
            } else {
                Result.failure(error)
            }
        }
    }

    suspend fun loadLiveReplays(
        accessToken: String,
        mine: Boolean = false,
        limit: Int = 40
    ): Result<List<LiveStream>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveStreamsResponse>(
            "$baseUrl/v1/data/live/replays?mine=$mine&limit=${limit.coerceIn(1, 100)}",
            accessToken
        ).map { it.items }
    }

    suspend fun loadLiveReplay(accessToken: String, liveId: String): Result<LiveStream> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson<LocalFallbackLiveReplayResponse>(
            "$baseUrl/v1/data/live/${urlEncode(liveId)}/replay",
            accessToken
        ).mapCatching { response ->
            response.replay ?: throw IllegalStateException("Repetición no encontrada")
        }
    }

    suspend fun publishLiveReplay(
        accessToken: String,
        liveId: String,
        replayUrl: String,
        thumbnailUrl: String?,
        durationMs: Long
    ): Result<LiveStream> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/replay"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("replayUrl", replayUrl)
                    thumbnailUrl?.let { put("thumbnailUrl", it) }
                    put("durationMs", durationMs)
                    put("published", true)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val replay = json.decodeFromString<LocalFallbackLiveReplayResponse>(body).replay
                    ?: return Result.failure(Exception("El servidor no devolvió la repetición."))
                Result.success(replay)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createLiveChapter(
        accessToken: String,
        liveId: String,
        title: String,
        offsetMs: Long
    ): Result<LiveChapter> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/live/${urlEncode(liveId)}/chapters"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("title", title)
                    put("offsetMs", offsetMs)
                    put("isHighlight", true)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val chapter = json.decodeFromString<LocalFallbackLiveChapterResponse>(body).chapter
                    ?: return Result.failure(Exception("El servidor no devolvió el capítulo."))
                Result.success(chapter)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun sendChatMessageOnce(
        url: String,
        accessToken: String,
        request: LocalFallbackSendMessageRequest
    ): Result<Message> {
        return try {
            val response = supabaseConfig.httpClient.post(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackMessageResponse>(body).message)
            } else {
                Result.failure(toMessageSendException(response, decodeError(body)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun editChatMessage(
        accessToken: String,
        chatId: String,
        messageId: String,
        content: String
    ): Result<Message> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages/${urlEncode(messageId)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackEditMessageRequest(content = content))
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

    suspend fun getMessageMarkers(accessToken: String, kind: String, roomId: String): Result<MessageMarkersResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get(
                "$baseUrl/v1/data/messages/markers/${urlEncode(kind)}/${urlEncode(roomId)}"
            ) { localHeaders(accessToken) }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<MessageMarkersResponse>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun getGlobalSavedMessages(
        accessToken: String,
        kind: String = "",
        roomId: String = "",
        type: String = "",
        from: String = "",
        to: String = "",
        cursor: String = "",
        search: String = "",
    ): Result<GlobalSavedMessagesResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val filters = listOf("kind" to kind, "roomId" to roomId, "type" to type,
                "from" to from, "to" to to, "cursor" to cursor, "search" to search)
                .filter { it.second.isNotBlank() }
                .joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
            val url = "$baseUrl/v1/data/messages/saved" + if (filters.isEmpty()) "" else "?$filters"
            val response = supabaseConfig.httpClient.get(url) { localHeaders(accessToken) }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<GlobalSavedMessagesResponse>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun updateMessageMarker(
        accessToken: String,
        kind: String,
        roomId: String,
        messageId: String,
        marker: String,
        enabled: Boolean,
        label: String = "",
    ): Result<MessageMarkersResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        if (marker !in setOf("pin", "saved")) return Result.failure(IllegalArgumentException("Marcador no válido."))
        return try {
            val url = "$baseUrl/v1/data/messages/markers/${urlEncode(kind)}/${urlEncode(roomId)}/${urlEncode(messageId)}/$marker"
            val response = if (enabled) supabaseConfig.httpClient.put(url) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("label" to label))
            } else supabaseConfig.httpClient.delete(url) { localHeaders(accessToken) }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(json.decodeFromString<MessageMarkersResponse>(body))
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun deleteChatMessage(
        accessToken: String,
        chatId: String,
        messageId: String,
        forEveryone: Boolean
    ): Result<Message> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages/${urlEncode(messageId)}/delete"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackDeleteMessageRequest(forEveryone = forEveryone))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackMessageResponse>(body).message)
            } else {
                val error = decodeError(body)
                Result.failure(ChatMessageDeleteException(response.status.value, error.error.orEmpty()))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun toggleChatMessageReaction(
        accessToken: String,
        chatId: String,
        messageId: String,
        emoji: String
    ): Result<Message> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages/${urlEncode(messageId)}/reactions"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackReactionMessageRequest(emoji = emoji))
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
        messageId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
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
                        replyToId = replyToId ?: "",
                        isForwarded = isForwarded,
                        forwardedFromMessageId = forwardedFromMessageId,
                        forwardedFromUserId = forwardedFromUserId
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackGroupMessageResponse>(body).message)
            } else {
                Result.failure(toMessageSendException(response, decodeError(body)))
            }
        } catch (error: CancellationException) {
            throw error
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
        channelId: String? = null,
        messageId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
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
                        replyToId = replyToId ?: "",
                        channelId = channelId,
                        isForwarded = isForwarded,
                        forwardedFromMessageId = forwardedFromMessageId,
                        forwardedFromUserId = forwardedFromUserId
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackCommunityMessageResponse>(body).message)
            } else {
                Result.failure(toMessageSendException(response, decodeError(body)))
            }
        } catch (error: CancellationException) {
            throw error
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

    suspend fun loadCollectionMembershipPolicy(
        accessToken: String, collection: String, roomId: String
    ): Result<CollectionMembershipPolicy> = requestJson(
        "$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}/membership-policy", accessToken
    )

    suspend fun setCollectionMembersCanAdd(
        accessToken: String, collection: String, roomId: String, enabled: Boolean
    ): Result<CollectionMembershipPolicy> = try {
        val response = supabaseConfig.httpClient.patch(
            "$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}/membership-policy"
        ) {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("membersCanAdd", enabled) })
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString<CollectionMembershipPolicy>(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.failure(error)
    }

    suspend fun addCollectionMember(
        accessToken: String, collection: String, roomId: String, userId: String,
        historyFrom: String? = null, historyUntil: String? = null
    ): Result<Unit> = try {
        val response = supabaseConfig.httpClient.post(
            "$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}/join"
        ) {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("userId", userId)
                historyFrom?.let { put("historyFrom", it) }
                historyUntil?.let { put("historyUntil", it) }
            })
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(Unit)
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.failure(error)
    }

    suspend fun previewCollection(
        accessToken: String,
        kind: String,
        roomId: String,
        inviteToken: String? = null
    ): Result<LocalFallbackCollectionPreview> {
        val collection = when (kind.trim().lowercase()) {
            "group" -> "groups"
            "community" -> "communities"
            else -> return Result.failure(Exception("El QR no corresponde a un grupo o comunidad."))
        }
        val tokenQuery = runCatching { collectionInviteQuery(collection, inviteToken) }.getOrElse { return Result.failure(it) }
        return requestJson<LocalFallbackCollectionPreview>(
            "$baseUrl/v1/data/messages/$collection/${urlEncode(roomId)}/preview$tokenQuery",
            accessToken
        ).map { preview ->
            preview.copy(
                imageUrl = UbuntuStorageUrl.rewriteOrEmpty(preview.imageUrl),
                participants = preview.participants.map { participant ->
                    participant.copy(avatarUrl = UbuntuStorageUrl.rewriteOrEmpty(participant.avatarUrl))
                }
            )
        }
    }

    suspend fun editCollectionMessage(
        accessToken: String,
        collection: String,
        roomId: String,
        messageId: String,
        content: String
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanCollection = collection.trim().lowercase().takeIf { it == "groups" || it == "communities" }
            ?: return Result.failure(Exception("Colección de mensajes inválida."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/messages/$cleanCollection/${urlEncode(roomId)}/messages/${urlEncode(messageId)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackEditMessageRequest(content = content))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteCollectionMessage(
        accessToken: String,
        collection: String,
        roomId: String,
        messageId: String,
        forEveryone: Boolean
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val cleanCollection = collection.trim().lowercase().takeIf { it == "groups" || it == "communities" }
            ?: return Result.failure(Exception("Colección de mensajes inválida."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/$cleanCollection/${urlEncode(roomId)}/messages/${urlEncode(messageId)}/delete"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackDeleteMessageRequest(forEveryone = forEveryone))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createGroupInvite(accessToken: String, groupId: String): Result<String> =
        createCollectionInvite(accessToken, "groups", groupId)

    suspend fun createCollectionInvite(accessToken: String, collection: String, roomId: String): Result<String> {
        require(collection in setOf("groups", "communities"))
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/$collection/${urlEncode(roomId)}/invites"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("expiresInHours", 72)
                    put("maxUses", 100)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val invite = json.decodeFromString<LocalFallbackGroupInviteResponse>(body)
                if (invite.token.isBlank()) Result.failure(Exception("El servidor no devolvió una invitación válida."))
                else Result.success(invite.token)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun joinGroupWithInvite(accessToken: String, groupId: String, token: String): Result<Unit> =
        joinCollectionWithInvite(accessToken, "groups", groupId, token)

    suspend fun joinCollectionWithInvite(accessToken: String, collection: String, roomId: String, token: String): Result<Unit> {
        require(collection in setOf("groups", "communities"))
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post(
                "$baseUrl/v1/data/messages/$collection/${urlEncode(roomId)}/join-invite"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("token", token.trim()) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun revokeCollectionInvites(accessToken: String, collection: String, roomId: String): Result<Unit> = runCatching {
        require(collection in setOf("groups", "communities"))
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/$collection/${urlEncode(roomId)}/invites/revoke") {
            localHeaders(accessToken)
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
    }

    suspend fun setGroupRestUntil(accessToken: String, groupId: String, restUntil: String?): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/messages/groups/${urlEncode(groupId)}/member-settings"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    if (restUntil.isNullOrBlank()) put("restUntil", JsonNull) else put("restUntil", restUntil)
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun leaveCollectionWithHistory(accessToken: String, collection: String, roomId: String): Result<CollectionMembershipExit> = try {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/${urlEncode(collection)}/${urlEncode(roomId)}/leave") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {})
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) Result.success(json.decodeFromString<CollectionMembershipExit>(body))
        else Result.failure(toException(response, decodeError(body)))
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.failure(error)
    }

    suspend fun hideCollectionHistory(accessToken: String, collection: String, roomId: String): Result<Unit> =
        mutateCollectionMembership(accessToken, collection, roomId, "hide", null, null)

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
        agoraChannelName: String?,
        roomMode: String,
        password: String?
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
                        agoraChannelName = agoraChannelName,
                        roomMode = roomMode,
                        password = password
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

    suspend fun updateVoiceRoom(accessToken: String, roomId: String, name: String, isPublic: Boolean, password: String?): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("name", name.trim()); put("isPublic", isPublic); if (password != null) put("password", password) })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(toException(response, decodeError(body)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
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
        isMuted: Boolean,
        password: String? = null
    ): Result<List<VoiceParticipant>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}/join") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackVoiceJoinRequest(
                        userName = userName,
                        userAvatarUrl = UbuntuStorageUrl.rewrite(userAvatarUrl),
                        socketId = socketId,
                        isMuted = isMuted,
                        password = password
                    )
                )
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

    suspend fun setVoiceStageHandRaised(
        accessToken: String,
        roomId: String,
        handRaised: Boolean
    ): Result<List<VoiceParticipant>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}/stage/request"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackVoiceStageRequest(handRaised))
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

    suspend fun moderateVoiceStageParticipant(
        accessToken: String,
        roomId: String,
        userId: String,
        role: String? = null,
        isMuted: Boolean? = null
    ): Result<List<VoiceParticipant>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.patch(
                "$baseUrl/v1/data/voice/rooms/${urlEncode(roomId)}/stage/participants/${urlEncode(userId)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackVoiceStageModerationRequest(role = role, isMuted = isMuted))
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
            StorageUploadPolicy.upload(
                raw = { uploadFileRaw(accessToken, bucketName, objectPath, bytes, contentType) },
                multipart = { uploadFileMultipart(accessToken, bucketName, objectPath, bytes, contentType, fileName) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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
                timeout {
                    connectTimeoutMillis = StorageUploadPolicy.CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = StorageUploadPolicy.RESPONSE_IDLE_TIMEOUT_MILLIS
                    requestTimeoutMillis = StorageUploadPolicy.REQUEST_TIMEOUT_MILLIS
                }
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
                val error = decodeError(body)
                val reportedPath = runCatching {
                    json.parseToJsonElement(body).jsonObject["path"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val requestedPath = "/storage/v1/object/${urlEncode(bucketName)}/$encodedPath"
                if (StorageUploadPolicy.isUnsupportedRawRoute(
                        response.status.value, error.error, reportedPath, requestedPath
                    )) Result.failure(StorageRawRouteUnavailableException())
                else Result.failure(toException(response, error))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
                timeout {
                    connectTimeoutMillis = StorageUploadPolicy.CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = StorageUploadPolicy.RESPONSE_IDLE_TIMEOUT_MILLIS
                    requestTimeoutMillis = StorageUploadPolicy.REQUEST_TIMEOUT_MILLIS
                }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val endpoint = RuntimeBackendEndpoint.authenticationEndpoint()
            ?: return Result.failure(Exception("El servidor de Novvin no está disponible en este momento. Inténtalo de nuevo más tarde."))
        return try {
            val response = supabaseConfig.httpClient.post("$endpoint/v1/auth/complete-migrated-password-reset") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCompleteMigrationRequest(migrationToken, newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                if (json.parseToJsonElement(body).jsonObject["requiresTwoFactor"]?.jsonPrimitive?.content == "true") {
                    return Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException())
                }
                Result.success(json.decodeFromString<LocalFallbackAuthResponse>(body).normalized())
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend inline fun <reified T> requestJson(url: String, accessToken: String): Result<T> {
        if (RuntimeBackendEndpoint.ensureFreshEndpoint() == null) {
            return Result.failure(Exception("Servidor temporalmente no disponible. Inténtalo de nuevo cuando vuelva la conexión."))
        }
        return try {
            // The URL is assembled by callers before the suspending read. A
            // rotating Quick Tunnel can change during that small window, so
            // validate discovery first and rebuild the same path/query on the
            // current trusted origin before spending the read deadline.
            val initialUrl = rebaseToCurrentEndpoint(url)
            requestJsonOnce<T>(initialUrl, accessToken).fold(
                onSuccess = { Result.success(it) },
                onFailure = { firstError ->
                    val retryUrl = refreshedRetryUrl(initialUrl)
                    if (retryUrl != null && retryUrl != initialUrl) {
                        requestJsonOnce<T>(retryUrl, accessToken)
                    } else {
                        Result.failure(firstError)
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            // A tab/account change cancels obsolete reads. Retrying after that
            // cancellation races the new request and can resurrect stale UI.
            throw cancelled
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
            RuntimeBackendEndpoint.noteSuccessfulRead(url)
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
            append("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
            append("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
            append("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
            append("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
            append("X-Request-ID", UUID.randomUUID().toString())
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

    private fun toMessageSendException(response: HttpResponse, error: LocalFallbackErrorResponse): MessageSendHttpException =
        MessageSendHttpException(
            statusCode = response.status.value,
            errorCode = (error.error ?: error.message).orEmpty().trim(),
            cause = toException(response, error),
        )

    private fun toException(response: HttpResponse, error: LocalFallbackErrorResponse): Exception {
        val code = (error.error ?: error.message).orEmpty().trim()
        return when (code) {
            "PROFILE_MUSIC_UNAVAILABLE" -> conectate.com.data.model.ProfileMusicUnavailableException()
            "PROFILE_MUSIC_CHANGED" -> conectate.com.data.model.ProfileMusicChangedException()
            "COMMUNITY_WEEKLY_MESSAGE_LIMIT_REACHED" -> Exception("Alcanzaste el cupo semanal de esta comunidad. Se renueva el lunes a las 00:00 UTC.")
            "COMMUNITY_DAILY_MESSAGE_LIMIT_REACHED" -> Exception("Alcanzaste el cupo diario de esta comunidad.")
            "ONLY_ADMINS_CAN_TALK" -> Exception("Solo los administradores pueden escribir en esta comunidad.")
            "COMMUNITY_OWNER_ROLE_REQUIRED" -> Exception("Solo quien creó la comunidad puede asignar o quitar administradores.")
            "NOT_FOUND" -> BackendRouteUnavailableException()
            "VIDEO_NOT_FOUND", "VIDEOS_NOT_FOUND" ->
                Exception("Este video ya no está disponible o no tienes acceso.")
            "INVALID_RECOVERY_CODE" ->
                Exception("El código de recuperación no coincide o ya fue utilizado.")
            "RECOVERY_CASE_DETAILS_REQUIRED" ->
                Exception("Completa el dato de la cuenta, un contacto válido y una explicación de al menos 20 caracteres.")
            "RECOVERY_CONTACT_INVALID" ->
                Exception("Revisa el correo o número de WhatsApp nuevo e inténtalo otra vez.")
            "INVALID_OR_EXPIRED_CONTACT_CODE" ->
                Exception("El código de contacto no coincide, venció o agotó los intentos. Pide otro al administrador.")
            "ACCOUNT_RECOVERY_RATE_LIMITED", "ACCOUNT_RECOVERY_CONTACT_RATE_LIMITED" ->
                Exception("Demasiados intentos de recuperación. Espera un momento antes de volver a intentarlo.")
            "RECOVERY_CONTACT_NOT_VERIFIED" ->
                Exception("El solicitante aún no confirmó el contacto nuevo.")
            "RECOVERY_CASE_ACCOUNT_MISMATCH" ->
                Exception("El caso ya está asociado a otra cuenta. Revisa la selección.")
            "RECOVERY_CODE_AND_PASSWORD_REQUIRED" ->
                Exception("Escribe un código de recuperación válido y una contraseña de al menos 8 caracteres.")
            "TWO_FACTOR_REQUIRED", "TWO_FACTOR_INVALID", "TWO_FACTOR_LOCKED" ->
                LocalFallbackTwoFactorRequiredException(code)
            "ACCOUNT_SUSPENDED" -> AccountSuspendedException(
                suspensionEndDate = error.suspendedUntil ?: error.until,
                reason = error.suspensionReason ?: error.reason,
                message = error.message
                    ?.removePrefix("ACCOUNT_SUSPENDED:")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Tu cuenta está suspendida.",
                appealToken = error.appealToken,
                decisionId = error.decisionId,
                ruleTitle = error.ruleTitle,
                explanation = error.explanation,
                evidence = error.evidence.mapNotNull { item ->
                    item.label.trim().takeIf { it.isNotBlank() }
                },
                appealDeadline = error.appealDeadline,
                appealStatus = error.appealStatus,
                appealReviewerNote = error.appealReviewerNote
            )
            "INVALID_CREDENTIALS" -> Exception("Correo o contraseña incorrectos.")
            "ACCOUNT_NOT_FOUND" -> Exception("No existe una cuenta con ese correo. Regístrate para crear una cuenta.")
            "INVALID_PASSWORD" -> Exception("Contraseña incorrecta. Intenta otra vez o restablece tu contraseña.")
            "EMAIL_ALREADY_EXISTS" -> Exception("Ese correo ya existe en el servidor Ubuntu.")
            "USERNAME_TAKEN" -> Exception("Ese @usuario ya está en uso. Elige una de las sugerencias u otro nombre.")
            "USERNAME_INVALID" -> Exception("El @usuario debe tener entre 3 y 24 letras, números, puntos, guiones o guiones bajos.")
            "USERNAME_RESERVED" -> Exception("Ese @usuario está reservado o no está permitido. Elige otro.")
            "USERNAME_MATCHES_EMAIL" -> Exception("Por privacidad, elige un @usuario distinto a la parte inicial de tu correo.")
            "USERNAME_IMMUTABLE" -> Exception("El @usuario de esta cuenta es permanente y no puede modificarse.")
            "USERNAME_CHECK_RATE_LIMITED" -> Exception("Has comprobado muchos nombres seguidos. Espera un minuto e inténtalo de nuevo.")
            "EMAIL_AND_PASSWORD_REQUIRED" -> Exception("Escribe correo y contraseña.")
            "INVALID_EMAIL" -> Exception("Escribe un correo electrónico válido.")
            "PASSWORD_TOO_SHORT", "EMAIL_AND_STRONG_PASSWORD_REQUIRED" ->
                Exception("La contraseña debe tener al menos 8 caracteres.")
            "CURRENT_AND_NEW_PASSWORD_REQUIRED" ->
                Exception("Escribe tu contraseña actual y una contraseña nueva de al menos 8 caracteres.")
            "NEW_PASSWORD_MUST_BE_DIFFERENT" ->
                Exception("La contraseña nueva debe ser diferente de la actual.")
            "REMIX_NOT_ALLOWED" ->
                Exception("El autor ya no permite crear remixes de esta publicación.")
            "REMIX_FOLLOWERS_ONLY" ->
                Exception("Solo los seguidores del autor pueden crear un remix de esta publicación.")
            "REMIX_SOURCE_NOT_VISIBLE" ->
                Exception("Ya no tienes acceso a la publicación original.")
            "REMIX_CHAIN_LIMIT_REACHED" ->
                Exception("Esta cadena de remixes alcanzó su límite.")
            "REMIX_PERMISSION_REQUIRES_OWNER" ->
                Exception("Solo el autor puede cambiar el permiso de remix.")
            "INVALID_REMIX_AUDIENCE" ->
                Exception("Elige quién puede crear remixes: todos o solo seguidores.")
            "AUTH_REQUIRED" -> Exception("Tu sesión necesita renovarse. Vuelve a intentarlo.")
            "INVALID_TOKEN" -> Exception("Tu sesión venció y no pudo renovarse. Inicia sesión nuevamente.")
            "INVALID_REFRESH_TOKEN" -> LocalFallbackSessionRenewalRequiredException()
            "ACCOUNT_DELETED" -> {
                AccountDeletedEvents.notifyAccountDeleted()
                AccountDeletedException()
            }
            "AI_SERVICE_TIMEOUT" -> Exception("Novvin tardó demasiado en responder. Intenta nuevamente.")
            "AI_SERVICE_UNAVAILABLE" -> Exception("Novvin no está disponible en este momento. Intenta nuevamente.")
            "MESSAGE_REQUIRED" -> Exception("Escribe un mensaje para Novvin.")
            "MESSAGE_TOO_LONG" -> Exception("El mensaje para Novvin supera los 1.000 caracteres.")
            "AI_RATE_LIMITED" -> Exception("Has enviado muchos mensajes seguidos. Espera un momento antes de reintentar.")
            "APPEAL_STATEMENT_TOO_SHORT" ->
                Exception("Explica tu apelación con al menos 20 caracteres.")
            "APPEAL_ALREADY_OPEN" ->
                Exception("Ya existe una apelación pendiente de revisión humana para esta decisión.")
            "APPEAL_DEADLINE_EXPIRED" ->
                Exception("El plazo para apelar esta decisión ya terminó.")
            "APPEAL_TOKEN_EXPIRED", "APPEAL_TOKEN_INVALID", "APPEAL_AUTH_REQUIRED" ->
                Exception("El permiso para apelar venció. Inicia sesión otra vez para solicitar uno nuevo.")
            "APPEAL_REVIEW_NOTE_REQUIRED" ->
                Exception("Escribe el fundamento de la revisión antes de guardar.")
            "APPEAL_ALREADY_REVIEWED" ->
                Exception("Esta apelación ya fue revisada. Actualiza el panel para ver el resultado.")
            "APPEAL_NOT_FOUND" ->
                Exception("La apelación ya no está disponible.")
            "MODERATION_RULE_INVALID" ->
                Exception("Selecciona una norma válida para registrar la decisión.")
            "SUSPENSION_DAYS_INVALID" ->
                Exception("La duración de la suspensión no es válida.")
            "ADMIN_REQUIRED" ->
                Exception("Tu cuenta no tiene permiso de administrador para realizar esta acción.")
            "MIGRATION_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Tu cuenta migrada necesita una contraseña de al menos 8 caracteres.")
            "INVALID_MIGRATION_TOKEN" -> Exception("El permiso para cambiar la contraseña venció. Verifica otra vez tu contraseña actual.")
            "RESET_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Ubuntu necesita crear una contraseña de al menos 8 caracteres.")
            "RECOVERY_EVIDENCE_LIMIT" -> Exception("Adjunta como máximo cinco archivos, de hasta 8 MB cada uno.")
            "RECOVERY_EVIDENCE_TYPE" -> Exception("Usa imágenes, PDF, texto, audio o video compatibles. No se aceptan archivos vacíos ni ejecutables.")
            "RECOVERY_EVIDENCE_NOT_FOUND" -> Exception("La evidencia ya no está disponible. Se conserva durante 30 días.")
            "INVALID_OR_EXPIRED_EMAIL_CODE" -> Exception("El código es incorrecto, venció o se agotaron los intentos. Revisa el último correo o pide otro código.")
            "EMAIL_AND_SIX_DIGIT_CODE_REQUIRED" -> Exception("Escribe el correo de tu cuenta y el código de seis dígitos.")
            "INVALID_OR_EXPIRED_RESET_TOKEN" -> Exception("El permiso seguro venció o no es válido. Vuelve a solicitar el cambio.")
            "GROUP_INVITE_INVALID" -> Exception("La invitación del grupo está incompleta o no es válida.")
            "GROUP_INVITE_CREATE_DENIED" -> Exception("La administración no permite añadir integrantes mediante una invitación en este momento.")
            "GROUP_INVITE_EXPIRED_OR_USED" -> Exception("La invitación del grupo venció o alcanzó su límite de usos.")
            "ROOM_NOT_FOUND" -> Exception("El grupo o la comunidad ya no existe o no está disponible.")
            "ROLE_INVALID" -> Exception("Selecciona un rol válido para la comunidad.")
            "ROLE_PREVIEW_DENIED", "ADD_MEMBER_DENIED" ->
                Exception("No tienes permiso para gestionar roles en esta comunidad.")
            "CREATOR_ROLE_IMMUTABLE" ->
                Exception("El rol de la persona propietaria no se puede cambiar.")
            "CHANNEL_MANAGE_DENIED" ->
                Exception("No tienes permiso para administrar los canales de esta comunidad.")
            "CHANNEL_PAUSE_DENIED" ->
                Exception("No tienes permiso para pausar este canal.")
            "CHANNEL_NAME_REQUIRED" ->
                Exception("Escribe un nombre para el canal.")
            "CHANNEL_TYPE_INVALID" ->
                Exception("Selecciona un tipo de canal válido.")
            "CHANNEL_NOT_FOUND" ->
                Exception("Este canal ya no está disponible.")
            "CHANNEL_PAUSE_REASON_REQUIRED" ->
                Exception("Escribe el motivo de la pausa.")
            "CHANNEL_PAUSE_UNTIL_INVALID" ->
                Exception("La duración de la pausa no es válida.")
            "CHANNEL_PAUSED" ->
                Exception(
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "Este canal está pausado temporalmente."
                )
            "VOICE_CHANNEL_DOES_NOT_ACCEPT_MESSAGES" ->
                Exception("Este canal es de voz. Ábrelo para conversar en una sala.")
            "DEFAULT_CHANNEL_CANNOT_BE_DELETED" ->
                Exception("El canal General no se puede eliminar.")
            "EVENT_MANAGE_DENIED" ->
                Exception("No tienes permiso para administrar eventos en esta comunidad.")
            "EVENT_TITLE_REQUIRED" ->
                Exception("Escribe un título para el evento.")
            "EVENT_START_INVALID" ->
                Exception("Selecciona una fecha futura válida para el evento.")
            "EVENT_END_INVALID" ->
                Exception("La fecha de finalización debe ser posterior al inicio.")
            "EVENT_ID_REQUIRED", "EVENT_NOT_FOUND" ->
                Exception("Este evento ya no está disponible.")
            "EVENT_REMINDER_INVALID" ->
                Exception("Selecciona un recordatorio válido.")
            "EVENT_REMINDER_TOO_LATE" ->
                Exception("Ese recordatorio ya habría ocurrido. Elige uno más cercano o ninguno.")
            "VOICE_ROOM_MODE_INVALID" ->
                Exception("Selecciona una modalidad válida para la sala.")
            "VOICE_STAGE_REQUIRED" ->
                Exception("Esta acción solo está disponible en una sala de escenario.")
            "VOICE_STAGE_JOIN_REQUIRED" ->
                Exception("Entra primero al escenario para solicitar la palabra.")
            "VOICE_STAGE_ALREADY_SPEAKER" ->
                Exception("Ya puedes hablar en este escenario.")
            "VOICE_STAGE_MODERATION_DENIED" ->
                Exception("No tienes permiso para moderar este escenario.")
            "VOICE_STAGE_MODERATOR_IMMUTABLE" ->
                Exception("No puedes cambiar el rol de esa persona moderadora.")
            "VOICE_STAGE_ROLE_INVALID" ->
                Exception("Selecciona un rol válido para el escenario.")
            "VOICE_STAGE_PARTICIPANT_REQUIRED", "VOICE_STAGE_PARTICIPANT_NOT_FOUND" ->
                Exception("Esa persona ya no está en el escenario.")
            "VOICE_STAGE_UPDATE_REQUIRED" ->
                Exception("Selecciona una acción para esta persona.")
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
            "PASSWORD_TOO_LONG" -> Exception("La contraseña es demasiado larga. Usa una más corta.")
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

    internal fun mapProfileUser(row: JsonObject): User {
        val badges = runCatching {
            row["badges"]?.let { json.decodeFromJsonElement<List<Badge>>(it) }
        }.getOrNull().orEmpty()
        return User(
            id = textOf(row, "supabase_user_id", "supabaseUserId", "id", "user_id", "profile_id"),
            email = textOf(row, "email"),
            username = textOf(row, "username").ifBlank { null },
            username_origin = textOf(row, "username_origin").ifBlank { null },
            full_name = textOf(row, "full_name", "name").ifBlank { null },
            bio = textOf(row, "bio").ifBlank { null },
            avatar_url = UbuntuStorageUrl.rewriteOrEmpty(textOf(row, "avatar_url", "photo_url", "profile_image")),
            cover_url = UbuntuStorageUrl.rewrite(textOf(row, "cover_url", "cover_image")),
            profile_theme = textOf(row, "profile_theme", "profileTheme").ifBlank { "conectate" },
            profile_effect = textOf(row, "profile_effect", "profileEffect").ifBlank { "none" },
            profile_welcome = textOf(row, "profile_welcome", "profileWelcome").ifBlank { null },
            country = textOf(row, "country").ifBlank { null },
            city = textOf(row, "city").ifBlank { null },
            birth_date = textOf(row, "birth_date", "birthDate").ifBlank { null },
            interests = stringListOf(row, "interests"),
            hobbies = stringListOf(row, "hobbies"),
            linked_games = stringListOf(row, "linked_games", "linkedGames"),
            game_logo_urls = stringMapOf(row, "game_logo_urls", "gameLogoUrls"),
            favorite_music_url = UbuntuStorageUrl.rewrite(textOf(row, "favorite_music_url", "favoriteMusicUrl")),
            favorite_book = textOf(row, "favorite_book", "favoriteBook").ifBlank { null },
            favorite_sport = textOf(row, "favorite_sport", "favoriteSport").ifBlank { null },
            career = textOf(row, "career", "profession").ifBlank { null },
            education = textOf(row, "education").ifBlank { null },
            workplace = textOf(row, "workplace").ifBlank { null },
            availability_schedule = textOf(row, "availability_schedule", "availabilitySchedule").ifBlank { null },
            profile_section_order = stringListOf(row, "profile_section_order", "profileSectionOrder"),
            profile_section_visibility = stringMapOf(row, "profile_section_visibility", "profileSectionVisibility"),
            social_links = stringMapOf(row, "social_links", "socialLinks"),
            followers_count = intOf(row, "followers_count", "followers"),
            following_count = intOf(row, "following_count", "following"),
            post_count = intOf(row, "post_count", "posts"),
            status = textOf(row, "status").ifBlank { null },
            display_name = textOf(row, "display_name", "displayName").ifBlank { null },
            is_private = boolOf(row, "is_private", "isPrivate"),
            favorites_public = boolOf(row, "favorites_public", "favoritesPublic"),
            break_mode = textOf(row, "break_mode", "breakMode").ifBlank { "off" },
            profile_visibility = textOf(row, "profile_visibility", "profileVisibility").ifBlank { "everyone" },
            profile_view_history_enabled = boolOf(
                row,
                "profile_view_history_enabled",
                "profileViewHistoryEnabled",
                fallback = true
            ),
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
            badges = badges,
            profile_frame = textOf(row, "profile_frame", "profileFrame"),
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

    private fun stringListOf(row: JsonObject, vararg keys: String): List<String> {
        for (key in keys) {
            val values = (row[key] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            if (values != null) return values
        }
        return emptyList()
    }

    private fun stringMapOf(row: JsonObject, vararg keys: String): Map<String, String> {
        for (key in keys) {
            val values = (row[key] as? JsonObject)?.mapNotNull { (entryKey, element) ->
                val value = element.jsonPrimitive.contentOrNull?.trim()
                if (entryKey.isBlank() || value.isNullOrBlank()) null else entryKey to value
            }?.toMap()
            if (values != null) return values
        }
        return emptyMap()
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
            visibility = textOf(row, "visibility").ifBlank { "public" },
            remixAllowed = boolOf(row, "remix_allowed", "remixAllowed"),
            remixAudience = textOf(row, "remix_audience", "remixAudience").ifBlank { "public" },
            remixRevokedAt = textOf(row, "remix_revoked_at", "remixRevokedAt").ifBlank { null },
            remixSourcePostId = textOf(row, "remix_source_post_id", "remixSourcePostId").ifBlank { null },
            remixRootPostId = textOf(row, "remix_root_post_id", "remixRootPostId").ifBlank { null },
            remixSourceUserId = textOf(row, "remix_source_user_id", "remixSourceUserId").ifBlank { null },
            remixSourceUserName = textOf(row, "remix_source_user_name", "remixSourceUserName").ifBlank { null },
            remixDepth = intOf(row, "remix_depth", "remixDepth"),
            surveyId = textOf(row, "survey_id").ifBlank { null },
            surveyResponseCount = intOf(row, "survey_response_count")
        ).also { it.isSensitive = boolOf(row, "is_sensitive", "isSensitive") }
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
            audioUrl = mediaUrl,
            mediaType = textOf(row, "media_type", "mediaType").ifBlank { null },
            reactionCounts = (row["reaction_counts"] as? JsonObject)
                ?.mapValues { (_, value) -> value.jsonPrimitive.intOrNull ?: 0 }
                .orEmpty(),
            myReaction = textOf(row, "my_reaction", "myReaction").ifBlank { null }
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
            avatarMediaType = textOf(row, "avatar_media_type", "avatarMediaType").ifBlank { null },
            avatarVideoMuted = boolOf(row, "avatar_video_muted", "avatarVideoMuted", fallback = true),
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
            favoriteCount = intOf(row, "favorite_count", "favoriteCount"),
            favoritedByMe = boolOf(row, "favorited_by_me", "favoritedByMe"),
            isVerified = boolOf(row, "is_verified", "isVerified", "verified"),
            soundId = textOf(row, "sound_id", "soundId").ifBlank { null },
            soundSourceVideoId = textOf(row, "sound_source_video_id", "soundSourceVideoId").ifBlank { null },
            soundTitle = textOf(row, "sound_title", "soundTitle").ifBlank { null },
            soundArtist = textOf(row, "sound_artist", "soundArtist").ifBlank { null },
            soundUrl = UbuntuStorageUrl.rewrite(textOf(row, "sound_url", "soundUrl")),
            chapters = runCatching {
                json.decodeFromJsonElement<List<conectate.com.data.model.VideoChapter>>(
                    row["chapters"] ?: JsonArray(emptyList())
                )
            }.getOrDefault(emptyList()),
            allowClipSharing = boolOf(row, "allow_clip_sharing", "allowClipSharing", fallback = true),
            ownedByMe = boolOf(row, "owned_by_me", "ownedByMe")
        ).also { it.isSensitive = boolOf(row, "is_sensitive", "isSensitive") }

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
            audienceIds = when (val audience = row["audience_ids"] ?: row["audienceIds"]) {
                is JsonArray -> audience.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> emptyList()
            },
            createdAt = textOf(row, "created_at", "createdAt").ifBlank { null },
            expiresAt = textOf(row, "expires_at", "expiresAt").ifBlank { null },
            caption = textOf(row, "caption").ifBlank { null },
            allowReplies = boolOf(row, "allow_replies", "allowReplies", fallback = true),
            overlayText = textOf(row, "overlay_text", "overlayText").ifBlank { null },
            overlayColor = textOf(row, "overlay_color", "overlayColor").ifBlank { "#FFFFFFFF" },
            overlayFont = textOf(row, "overlay_font", "overlayFont").ifBlank { "sans" },
            overlayScale = textOf(row, "overlay_scale", "overlayScale").toFloatOrNull() ?: 1f,
            overlayX = textOf(row, "overlay_x", "overlayX").toFloatOrNull() ?: 0.5f,
            overlayY = textOf(row, "overlay_y", "overlayY").toFloatOrNull() ?: 0.38f,
            musicUrl = UbuntuStorageUrl.rewrite(textOf(row, "music_url", "musicUrl").ifBlank { null }),
            musicAudioUrl = UbuntuStorageUrl.rewrite(textOf(row, "music_audio_url", "musicAudioUrl").ifBlank { null }),
            musicId = textOf(row, "music_id", "musicId").ifBlank { null },
            musicTitle = textOf(row, "music_title", "musicTitle").ifBlank { null },
            musicArtist = textOf(row, "music_artist", "musicArtist").ifBlank { null },
            musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(
                textOf(row, "music_presentation", "musicPresentation")
            ).wireValue,
            musicArtworkUrl = UbuntuStorageUrl.rewrite(textOf(row, "music_artwork_url", "musicArtworkUrl").ifBlank { null }),
            musicOverlayX = conectate.com.data.model.normalizedMomentMusicPosition(textOf(row, "music_overlay_x", "musicOverlayX").toFloatOrNull() ?: .5f),
            musicOverlayY = conectate.com.data.model.normalizedMomentMusicPosition(textOf(row, "music_overlay_y", "musicOverlayY").toFloatOrNull() ?: .5f),
            musicOverlayScale = conectate.com.data.model.normalizedMomentMusicScale(textOf(row, "music_overlay_scale", "musicOverlayScale").toFloatOrNull() ?: 1f),
            musicOverlayRotation = conectate.com.data.model.normalizedMomentMusicRotation(textOf(row, "music_overlay_rotation", "musicOverlayRotation").toFloatOrNull() ?: 0f),
            musicLyrics = textOf(row, "music_lyrics", "musicLyrics").takeIf { it.isNotBlank() && it.length <= 12_000 },
            musicSource = textOf(row, "music_source", "musicSource").ifBlank { null },
            musicSourceUrl = textOf(row, "music_source_url", "musicSourceUrl").ifBlank { null },
            musicLicense = textOf(row, "music_license", "musicLicense").ifBlank { null },
            musicLicenseUrl = textOf(row, "music_license_url", "musicLicenseUrl").ifBlank { null },
            musicStartMs = longOf(row, "music_start_ms", "musicStartMs"),
            musicDurationMs = longOf(row, "music_duration_ms", "musicDurationMs").takeIf { it > 0L } ?: 6_000L,
            musicEndMs = longOf(row, "music_end_ms", "musicEndMs").takeIf { it > 0L },
            musicVolume = textOf(row, "music_volume", "musicVolume").toFloatOrNull() ?: 1f,
            originalAudioVolume = textOf(row, "original_audio_volume", "originalAudioVolume").toFloatOrNull() ?: 1f,
            musicFadeInMs = longOf(row, "music_fade_in_ms", "musicFadeInMs").coerceIn(0L, 5_000L),
            musicFadeOutMs = longOf(row, "music_fade_out_ms", "musicFadeOutMs").coerceIn(0L, 5_000L),
            duckOriginalAudio = boolOf(row, "duck_original_audio", "duckOriginalAudio"),
            musicEmbedded = boolOf(row, "music_embedded", "musicEmbedded"),
            username = textOf(row, "owner_name", "username", "user_name").ifBlank { null },
            userDisplayName = textOf(row, "owner_name", "display_name", "userDisplayName").ifBlank { null },
            userAvatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "owner_avatar_url", "avatar_url", "user_avatar_url")),
            isVerified = boolOf(row, "is_verified", "isVerified", "verified"),
            viewCount = intOf(row, "views_count", "viewCount"),
            isViewed = boolOf(row, "viewed_by_me", "isViewed"),
            reactionCount = intOf(row, "reaction_count", "reactionCount"),
            isLikedByMe = boolOf(row, "reacted_by_me", "isLikedByMe")
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
            mediaUrl = UbuntuStorageUrl.rewrite(textOf(row, "media_url", "audio_url", "file_url")),
            reactions = (row["reactions"] as? JsonObject)?.mapValues { (_, value) ->
                value.jsonPrimitive.intOrNull ?: 0
            }.orEmpty(),
            myReaction = textOf(row, "my_reaction", "myReaction").ifBlank { null },
            updatedAt = textOf(row, "updated_at", "updatedAt")
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
                passwordRequired = boolOf(row, "password_required", "passwordRequired"),
                isActive = boolOf(row, "is_active", "active", fallback = true),
                readableId = textOf(row, "readable_id").ifBlank { null },
                roomMode = textOf(row, "room_mode", "roomMode").ifBlank { "casual" },
                speakerIds = stringListOf(row, "speaker_ids", "speakerIds"),
                raisedHandCount = intOf(row, "raised_hand_count", "raisedHandCount"),
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
            role = textOf(row, "role", "stage_role").ifBlank { "speaker" },
            handRaised = boolOf(row, "hand_raised", "handRaised"),
            socketId = textOf(row, "socket_id")
        )
    }

    private fun safeMultipartFileName(value: String): String =
        value.replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
            .ifBlank { "upload.bin" }

    // Proximity and anonymous chat

    suspend fun updateProximityPresence(
        accessToken: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        gender: String,
        lookingFor: String,
        randomEnabled: Boolean
    ): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/proximity/presence") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("radiusKm", radiusKm)
                    put("gender", gender)
                    put("lookingFor", lookingFor)
                    put("randomEnabled", randomEnabled)
                })
            }
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception("No se pudo activar proximidad: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disableProximityPresence(accessToken: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/proximity/presence") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception("No se pudo desactivar proximidad: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadNearbyProfiles(accessToken: String, radiusKm: Double): Result<List<NearbyProfile>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/proximity/nearby") {
                localHeaders(accessToken)
                parameter("radiusKm", radiusKm)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudieron cargar personas cercanas: ${response.status}"))
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val items = root["items"]?.jsonArray.orEmpty().mapNotNull { element ->
                val row = element.jsonObject
                val userId = textOf(row, "user_id")
                if (userId.isBlank()) return@mapNotNull null
                NearbyProfile(
                    userId = userId,
                    displayName = textOf(row, "display_name").ifBlank { "Usuario" },
                    avatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "avatar_url"))?.takeIf { it.isNotBlank() },
                    city = textOf(row, "city"),
                    country = textOf(row, "country"),
                    interests = stringListOf(row, "interests"),
                    distanceKm = textOf(row, "distance_km").toDoubleOrNull() ?: 0.5
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startAnonymousMatch(
        accessToken: String,
        gender: String,
        lookingFor: String
    ): Result<AnonymousMatchResult> = anonymousMatchRequest(
        accessToken = accessToken,
        path = "/v1/data/proximity/random/match",
        method = "POST",
        body = buildJsonObject {
            put("gender", gender)
            put("lookingFor", lookingFor)
        }
    )

    suspend fun refreshAnonymousBottleAvailability(
        accessToken: String,
    ): Result<AnonymousBottleResult> = anonymousBottleRequest(
        accessToken = accessToken,
        path = "/v1/data/proximity/random/availability",
        method = "POST",
        body = buildJsonObject {},
    )

    suspend fun sendAnonymousBottle(
        accessToken: String,
        content: String,
    ): Result<AnonymousBottleResult> = anonymousBottleRequest(
        accessToken = accessToken,
        path = "/v1/data/proximity/random/bottles",
        method = "POST",
        body = buildJsonObject {
            put("content", content)
        },
    )

    suspend fun loadOutgoingAnonymousBottle(accessToken: String): Result<AnonymousBottleResult> =
        anonymousBottleRequest(
            accessToken = accessToken,
            path = "/v1/data/proximity/random/bottles/outgoing",
            method = "GET",
        )

    suspend fun loadIncomingAnonymousBottle(accessToken: String): Result<AnonymousBottleResult> =
        anonymousBottleRequest(
            accessToken = accessToken,
            path = "/v1/data/proximity/random/bottles/incoming",
            method = "GET",
        )

    suspend fun disableAnonymousBottleAvailability(accessToken: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/proximity/random/availability") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception(apiErrorCode(body, "No se pudo cerrar la recepción de botellas.")))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun rebaseToCurrentEndpoint(url: String): String {
        val currentBase = RuntimeBackendEndpoint.ensureFreshEndpoint()
            ?.trimEnd('/')
            ?.takeIf(String::isNotBlank)
            ?: return url
        return runCatching {
            val parsed = URI(url.trim())
            if (parsed.host.isNullOrBlank()) return@runCatching url
            val path = parsed.rawPath.orEmpty().ifBlank { "/" }
            val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
            "$currentBase$path$query$fragment"
        }.getOrDefault(url)
    }

    suspend fun deleteOwnAccount(accessToken: String): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/rest/v1/rpc/delete_own_account") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {})
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun respondToAnonymousBottle(
        accessToken: String,
        bottleId: String,
        accept: Boolean,
    ): Result<AnonymousBottleResult> = anonymousBottleRequest(
        accessToken = accessToken,
        path = "/v1/data/proximity/random/bottles/$bottleId/respond",
        method = "POST",
        body = buildJsonObject { put("action", if (accept) "accept" else "decline") },
    )

    suspend fun cancelAnonymousBottle(accessToken: String, bottleId: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/proximity/random/bottles/$bottleId") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception(apiErrorCode(body, "No se pudo cancelar la botella.")))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun anonymousBottleRequest(
        accessToken: String,
        path: String,
        method: String,
        body: JsonObject? = null,
    ): Result<AnonymousBottleResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = when (method) {
                "POST" -> supabaseConfig.httpClient.post("$baseUrl$path") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    body?.let { setBody(it) }
                }
                else -> supabaseConfig.httpClient.get("$baseUrl$path") { localHeaders(accessToken) }
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return Result.failure(Exception(apiErrorCode(responseBody, "No se pudo actualizar la botella anónima.")))
            }
            val root = json.parseToJsonElement(responseBody).jsonObject
            Result.success(
                AnonymousBottleResult(
                    status = textOf(root, "status").ifBlank { "idle" },
                    bottle = root["bottle"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonObject
                        ?.let(::mapAnonymousBottle),
                    session = root["session"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonObject
                        ?.let(::mapAnonymousSession),
                ),
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun apiErrorCode(body: String, fallback: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank) ?: fallback

    suspend fun loadAnonymousMatchStatus(accessToken: String): Result<AnonymousMatchResult> =
        anonymousMatchRequest(
            accessToken = accessToken,
            path = "/v1/data/proximity/random/status",
            method = "GET"
        )

    suspend fun loadAnonymousSessions(accessToken: String): Result<List<AnonymousChatSession>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/proximity/random/sessions") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) {
                return Result.failure(Exception("No se pudieron cargar tus chats anónimos: ${response.status}"))
            }
            val items = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["items"]
                ?.jsonArray
                .orEmpty()
            Result.success(items.map { mapAnonymousSession(it.jsonObject) })
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun anonymousMatchRequest(
        accessToken: String,
        path: String,
        method: String,
        body: JsonObject? = null
    ): Result<AnonymousMatchResult> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = if (method == "POST") {
                supabaseConfig.httpClient.post("$baseUrl$path") {
                    localHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    if (body != null) setBody(body)
                }
            } else {
                supabaseConfig.httpClient.get("$baseUrl$path") { localHeaders(accessToken) }
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudo buscar chat: ${response.status}"))
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Result.success(
                AnonymousMatchResult(
                    status = textOf(root, "status").ifBlank { "idle" },
                    session = root["session"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::mapAnonymousSession)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelAnonymousMatch(accessToken: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/proximity/random/queue") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception("No se pudo cancelar la búsqueda: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadAnonymousSession(accessToken: String, sessionId: String): Result<AnonymousChatSession> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/proximity/random/sessions/$sessionId") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudo actualizar el chat: ${response.status}"))
            val session = json.parseToJsonElement(response.bodyAsText()).jsonObject["session"]?.jsonObject
                ?: return Result.failure(Exception("La sesión anónima no está disponible."))
            Result.success(mapAnonymousSession(session))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadAnonymousMessages(accessToken: String, sessionId: String): Result<List<AnonymousChatMessage>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/proximity/random/sessions/$sessionId/messages") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudieron cargar los mensajes: ${response.status}"))
            val items = json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]?.jsonArray.orEmpty()
            Result.success(items.map { mapAnonymousMessage(it.jsonObject) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendAnonymousMessage(
        accessToken: String,
        sessionId: String,
        clientMessageId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null
    ): Result<AnonymousChatMessage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/proximity/random/sessions/$sessionId/messages") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("clientMessageId", clientMessageId)
                    put("content", content)
                    put("contentType", contentType)
                    mediaUrl?.let { put("mediaUrl", it) }
                })
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudo enviar el mensaje: ${response.status}"))
            val message = json.parseToJsonElement(response.bodyAsText()).jsonObject["message"]?.jsonObject
                ?: return Result.failure(Exception("El servidor no confirmó el mensaje."))
            Result.success(mapAnonymousMessage(message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revealAnonymousProfile(
        accessToken: String,
        sessionId: String,
        level: Int
    ): Result<AnonymousChatSession> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/proximity/random/sessions/$sessionId/reveal") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("level", level) })
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("No se pudo cambiar la revelación: ${response.status}"))
            val session = json.parseToJsonElement(response.bodyAsText()).jsonObject["session"]?.jsonObject
                ?: return Result.failure(Exception("El servidor no confirmó la revelación."))
            Result.success(mapAnonymousSession(session))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun endAnonymousSession(accessToken: String, sessionId: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/proximity/random/sessions/$sessionId") {
                localHeaders(accessToken)
            }
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(Exception("No se pudo finalizar el chat: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapAnonymousSession(row: JsonObject): AnonymousChatSession {
        val peer = row["peer"]?.jsonObject ?: JsonObject(emptyMap())
        return AnonymousChatSession(
            id = textOf(row, "id"),
            status = textOf(row, "status").ifBlank { "active" },
            ownRevealLevel = intOf(row, "own_reveal_level"),
            peerRevealLevel = intOf(row, "peer_reveal_level"),
            peer = AnonymousPeer(
                alias = textOf(peer, "alias").ifBlank { "Persona" },
                displayName = textOf(peer, "display_name").ifBlank { "Persona" },
                avatarUrl = UbuntuStorageUrl.rewrite(textOf(peer, "avatar_url"))?.takeIf { it.isNotBlank() },
                interests = stringListOf(peer, "interests"),
                hobbies = stringListOf(peer, "hobbies"),
                userId = textOf(peer, "user_id").ifBlank { null }
            ),
            lastMessage = textOf(row, "last_message").ifBlank { null },
            lastMessageSender = textOf(row, "last_message_sender").ifBlank { null },
            unreadCount = intOf(row, "unread_count"),
            messageCount = intOf(row, "message_count"),
            createdAt = textOf(row, "created_at"),
            updatedAt = textOf(row, "updated_at")
        )
    }

    private fun mapAnonymousBottle(row: JsonObject): AnonymousBottle = AnonymousBottle(
        id = textOf(row, "id"),
        content = textOf(row, "content"),
        status = textOf(row, "status").ifBlank { "searching" },
        perspective = textOf(row, "perspective").ifBlank { "sender" },
        attemptCount = intOf(row, "attempt_count"),
        offeredAt = textOf(row, "offered_at").ifBlank { null },
        expiresAt = textOf(row, "expires_at").ifBlank { null },
        createdAt = textOf(row, "created_at"),
        updatedAt = textOf(row, "updated_at"),
    )

    private fun mapAnonymousMessage(row: JsonObject): AnonymousChatMessage = AnonymousChatMessage(
        isRead = boolOf(row, "is_read", "isRead"),
        id = textOf(row, "id"),
        clientMessageId = textOf(row, "client_message_id"),
        sender = textOf(row, "sender").ifBlank { "peer" },
        content = textOf(row, "content"),
        contentType = textOf(row, "content_type", "contentType").ifBlank { "text" },
        mediaUrl = UbuntuStorageUrl.rewrite(textOf(row, "media_url", "mediaUrl"))?.takeIf { it.isNotBlank() },
        createdAt = textOf(row, "created_at")
    )

    // ─── Admin Moderation API ───────────────────────────────────

    private fun mapAdminBadge(row: JsonObject): AdminBadgeOption = AdminBadgeOption(
        id = textOf(row, "id"),
        name = textOf(row, "name"),
        description = textOf(row, "description"),
        icon = textOf(row, "icon"),
        color = textOf(row, "color"),
        createdBy = textOf(row, "created_by"),
        canDelete = boolOf(row, "can_delete")
    )

    private fun mapAdminUser(row: JsonObject): AdminUser {
        val content = row["content"]?.jsonObject
        return AdminUser(
            id = textOf(row, "id"),
            localId = textOf(row, "local_id"),
            email = textOf(row, "email"),
            displayName = textOf(row, "display_name").ifBlank { textOf(row, "email") },
            username = textOf(row, "username"),
            avatarUrl = UbuntuStorageUrl.rewrite(textOf(row, "avatar_url"))?.takeIf { it.isNotBlank() },
            createdAt = textOf(row, "created_at").ifBlank { null },
            lastLoginAt = textOf(row, "last_login_at").ifBlank { null },
            syncSource = textOf(row, "sync_source").ifBlank { "local" },
            isAdmin = boolOf(row, "is_admin"),
            adminAccessLevel = textOf(row, "admin_access_level").ifBlank {
                if (boolOf(row, "is_admin")) "full" else "none"
            },
            adminIsOwner = boolOf(row, "admin_is_owner"),
            isTestAccount = boolOf(row, "is_test_account"),
            isVerified = boolOf(row, "is_verified"),
            isSuspended = boolOf(row, "is_suspended"),
            suspendedUntil = textOf(row, "suspended_until").ifBlank { null },
            suspensionReason = textOf(row, "suspension_reason").ifBlank { null },
            badges = row["badges"]?.jsonArray?.map { mapAdminBadge(it.jsonObject) }.orEmpty(),
            profileFrame = textOf(row, "profile_frame"),
            content = content?.let {
                AdminUserContent(
                    posts = intOf(it, "posts"),
                    videos = intOf(it, "videos"),
                    moments = intOf(it, "moments"),
                    reports = intOf(it, "reports"),
                    storageObjects = intOf(it, "storage_objects"),
                    storageBytes = it["storage_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                )
            }
        )
    }

    suspend fun loadSystemHealth(accessToken: String): Result<SystemHealthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/system/health") {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw toException(response, decodeError(body))
            }
            json.decodeFromString<SystemHealthResponse>(body)
        }
    }

    suspend fun loadSystemAnnouncements(accessToken: String? = null): Result<List<SystemAnnouncement>> = runCatching {
        check(isConfigured) { "Servidor no configurado." }
        val response = supabaseConfig.httpClient.get("$baseUrl${if (accessToken == null) "/v1/system/announcements" else "/v1/admin/system/announcements"}") {
            if (accessToken != null) localHeaders(accessToken)
            parameter("versionCode", BuildConfig.VERSION_CODE)
            timeout { requestTimeoutMillis = 2_500; connectTimeoutMillis = 2_000; socketTimeoutMillis = 2_500 }
        }
        check(response.status.isSuccess()) { "No se pudieron cargar los avisos: HTTP ${response.status.value}" }
        json.decodeFromString(SystemAnnouncementEnvelope.serializer(), response.bodyAsText()).items
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun publishSystemAnnouncement(accessToken: String, notice: PublishSystemAnnouncement): Result<Unit> = runCatching {
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/admin/system/announcements") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PublishSystemAnnouncement.serializer(), notice))
        }
        check(response.status.isSuccess()) { "No se pudo publicar el aviso: HTTP ${response.status.value}" }
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun revokeSystemAnnouncement(accessToken: String, id: String): Result<Unit> = runCatching {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        val response = supabaseConfig.httpClient.delete("$baseUrl/v1/admin/system/announcements/$id") { localHeaders(accessToken) }
        check(response.status.isSuccess()) { "No se pudo retirar el aviso: HTTP ${response.status.value}" }
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun adminGetCatalog(accessToken: String): Result<AdminCatalog> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/catalog") { localHeaders(accessToken) }
            if (!response.status.isSuccess()) error("Error al cargar catálogo: ${response.status} - ${response.bodyAsText()}")
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            AdminCatalog(
                badges = payload["badges"]?.jsonArray?.map { mapAdminBadge(it.jsonObject) }.orEmpty(),
                frames = payload["frames"]?.jsonArray?.map {
                    val row = it.jsonObject
                    AdminFrameOption(textOf(row, "id"), textOf(row, "name"))
                }.orEmpty()
            )
        }
    }

    suspend fun adminCreateBadge(
        accessToken: String,
        name: String,
        description: String,
        icon: String,
        color: String
    ): Result<AdminBadgeOption> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/admin/badges") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("name", name.trim())
                    put("description", description.trim())
                    put("icon", icon.trim())
                    put("color", color.trim())
                })
            }
            if (!response.status.isSuccess()) {
                error("No se pudo crear la insignia: ${response.status} - ${response.bodyAsText()}")
            }
            val badge = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["badge"]
                ?.jsonObject
                ?: error("El servidor no devolvió la insignia creada.")
            mapAdminBadge(badge)
        }
    }

    suspend fun adminDeleteBadge(
        accessToken: String,
        badgeId: String
    ): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val encodedId = URLEncoder.encode(badgeId.trim(), Charsets.UTF_8.name())
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/admin/badges/$encodedId") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) {
                throw toException(response, decodeError(response.bodyAsText()))
            }
            true
        }
    }

    suspend fun adminGetUsers(accessToken: String, search: String = ""): Result<AdminUserPage> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/users") {
                localHeaders(accessToken)
                if (search.isNotBlank()) parameter("search", search.trim())
                parameter("limit", 100)
            }
            if (!response.status.isSuccess()) error("Error al cargar usuarios: ${response.status} - ${response.bodyAsText()}")
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            AdminUserPage(
                items = payload["items"]?.jsonArray?.map { mapAdminUser(it.jsonObject) }.orEmpty(),
                total = intOf(payload, "total")
            )
        }
    }

    suspend fun adminGetRecoveryCases(accessToken: String, query: String = "", status: String = "pending", offset: Int = 0): Result<AdminRecoveryPage> = runCatching {
        val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/account-recovery/cases") {
            localHeaders(accessToken)
            parameter("q", query); parameter("status", status); parameter("offset", offset); parameter("limit", 20)
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val cases = payload["cases"]?.jsonArray?.map { element ->
            val row = element.jsonObject
            AdminRecoveryCase(
                id = textOf(row, "id"), identifier = textOf(row, "identifier"),
                alternateContact = textOf(row, "alternate_contact"),
                contactType = textOf(row, "contact_type"), profileHint = textOf(row, "profile_hint"),
                description = textOf(row, "description"), status = textOf(row, "status"),
                candidateUserId = textOf(row, "user_id").ifBlank { textOf(row, "candidate_user_id") },
                candidateName = textOf(row, "candidate_name"),
                candidateUsername = textOf(row, "candidate_username"),
                candidateEmail = textOf(row, "candidate_email"),
                contactVerified = textOf(row, "contact_verified_at").isNotBlank(),
                createdAt = textOf(row, "created_at"),
                reviewedAt = textOf(row, "reviewed_at"), reviewedBy = textOf(row, "reviewed_by"),
                evidence = (row["evidence"] as? JsonArray)?.map { item ->
                    val file = item.jsonObject
                    AdminRecoveryEvidence(textOf(file, "id"), textOf(file, "name"), textOf(file, "mime"),
                        file["size"]?.jsonPrimitive?.intOrNull ?: 0)
                }.orEmpty(),
            )
        }.orEmpty()
        AdminRecoveryPage(cases, intOf(payload, "total"), intOf(payload, "offset"),
            intOf(payload, "limit").takeIf { it > 0 } ?: 20,
            (payload["statusCounts"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }.orEmpty())
    }

    suspend fun adminDownloadRecoveryEvidence(accessToken: String, caseId: String, evidenceId: String): Result<ByteArray> = runCatching {
        require(UUID.fromString(caseId).toString() == caseId && UUID.fromString(evidenceId).toString() == evidenceId)
        val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/account-recovery/cases/$caseId/evidence/$evidenceId") {
            localHeaders(accessToken)
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
        val data: ByteArray = response.body()
        check(data.size in 1..(8 * 1024 * 1024)) { "El archivo excede el límite permitido." }
        data
    }

    suspend fun adminFindRecoveryAccounts(accessToken: String, query: String): Result<List<AdminRecoveryAccount>> = runCatching {
        val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/account-recovery/accounts") {
            localHeaders(accessToken)
            parameter("q", query)
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
        json.parseToJsonElement(response.bodyAsText()).jsonObject["accounts"]?.jsonArray?.map { element ->
            val row = element.jsonObject
            AdminRecoveryAccount(textOf(row, "id"), textOf(row, "email"), textOf(row, "display_name"), textOf(row, "username"))
        }.orEmpty()
    }

    suspend fun adminRecoveryAction(accessToken: String, caseId: String, action: String, payload: JsonObject): Result<JsonObject> = runCatching {
        require(action in setOf("contact-code", "issue-code", "reject"))
        val id = URLEncoder.encode(caseId, Charsets.UTF_8.name())
        val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/admin/account-recovery/cases/$id/$action") {
            localHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) throw toException(response, decodeError(response.bodyAsText()))
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun adminGetUser(accessToken: String, userId: String): Result<AdminUser> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val encodedId = URLEncoder.encode(userId, Charsets.UTF_8.name())
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/users/$encodedId") { localHeaders(accessToken) }
            if (!response.status.isSuccess()) error("Error al cargar detalle: ${response.status} - ${response.bodyAsText()}")
            val user = json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]?.jsonObject
                ?: error("El servidor no devolvió el usuario.")
            mapAdminUser(user)
        }
    }

    suspend fun adminUpdateUserDecorations(
        accessToken: String,
        userId: String,
        verified: Boolean? = null,
        badgeIds: List<String>? = null,
        profileFrame: String? = null
    ): Result<AdminUser> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        if (verified == null && badgeIds == null && profileFrame == null) {
            return Result.failure(IllegalArgumentException("No hay cambios administrativos para guardar."))
        }
        return runCatching {
            val encodedId = URLEncoder.encode(userId, Charsets.UTF_8.name())
            val response = supabaseConfig.httpClient.patch("$baseUrl/v1/data/admin/users/$encodedId/decorations") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    verified?.let { put("verified", JsonPrimitive(it)) }
                    badgeIds?.let { values ->
                        put("badge_ids", buildJsonArray {
                            values.distinct().forEach { add(JsonPrimitive(it)) }
                        })
                    }
                    profileFrame?.let { put("profile_frame", JsonPrimitive(it)) }
                })
            }
            if (!response.status.isSuccess()) error("Error al actualizar usuario: ${response.status} - ${response.bodyAsText()}")
            val user = json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]?.jsonObject
                ?: error("El servidor no devolvió el usuario actualizado.")
            mapAdminUser(user)
        }
    }

    suspend fun adminGetAccess(accessToken: String): Result<AdminAccessInfo> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        RuntimeBackendEndpoint.ensureFreshEndpoint()
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/access") {
                localHeaders(accessToken)
            }
            if (response.status.value == 200) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                Result.success(
                    AdminAccessInfo(
                        isAdmin = boolOf(body, "isAdmin", "is_admin"),
                        accessLevel = textOf(body, "accessLevel", "access_level").ifBlank { "none" },
                        isOwner = boolOf(body, "isOwner", "is_owner"),
                        canWrite = boolOf(body, "canWrite", "can_write"),
                        canDeleteUsers = boolOf(body, "canDeleteUsers", "can_delete_users"),
                        capabilities = body["capabilities"]
                            ?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                            ?.toSet()
                            .orEmpty()
                    )
                )
            } else {
                Result.failure(Exception("Acceso denegado: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminGetStats(accessToken: String): Result<AdminDashboardStats> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/stats") {
                localHeaders(accessToken)
            }
            if (!response.status.isSuccess()) return Result.failure(Exception("Error: ${response.status}"))
            val stats = json.parseToJsonElement(response.bodyAsText()).jsonObject["stats"]?.jsonObject
                ?: JsonObject(emptyMap())
            Result.success(
                AdminDashboardStats(
                    totalUsers = intOf(stats, "total_users"),
                    suspendedUsers = intOf(stats, "suspended_users"),
                    pendingUserReports = intOf(stats, "pending_user_reports"),
                    pendingVideoReports = intOf(stats, "pending_video_reports"),
                    totalVideos = intOf(stats, "total_videos")
                )
            )
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
                    decisionId = textOf(obj, "decision_id").ifBlank { null },
                    ruleTitle = textOf(obj, "rule_title").ifBlank { null },
                    explanation = textOf(obj, "explanation").ifBlank { null },
                    evidence = obj["evidence"]?.jsonArray
                        ?.mapNotNull { evidence ->
                            textOf(evidence.jsonObject, "label").takeIf { it.isNotBlank() }
                        }
                        .orEmpty(),
                    severity = textOf(obj, "severity").ifBlank { null },
                    appealDeadline = textOf(obj, "appeal_deadline").ifBlank { null },
                    appealStatus = textOf(obj, "appeal_status").ifBlank { null },
                    updatedAt = textOf(obj, "updated_at").ifBlank { null }
                )
            }
            Result.success(profiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminCheckAccess(accessToken: String): Result<Boolean> =
        adminGetAccess(accessToken).map { it.isAdmin }

    suspend fun adminUpdateUserAccess(
        accessToken: String,
        userId: String,
        accessLevel: String
    ): Result<AdminUser> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return runCatching {
            val encodedId = URLEncoder.encode(userId, Charsets.UTF_8.name())
            val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/admin/users/$encodedId/access") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("access_level", accessLevel) })
            }
            if (!response.status.isSuccess()) {
                error("No se pudo cambiar el acceso administrativo: ${response.status} - ${response.bodyAsText()}")
            }
            val user = json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]?.jsonObject
                ?: error("El servidor no devolvió el usuario actualizado.")
            mapAdminUser(user)
        }
    }

    suspend fun adminGetModerationAppeals(
        accessToken: String,
        status: String = "all"
    ): Result<List<AdminModerationAppeal>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/admin/moderation/appeals") {
                localHeaders(accessToken)
                parameter("status", status)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return Result.failure(toException(response, decodeError(body)))
            }
            val items = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty()
            Result.success(items.map { element ->
                val row = element.jsonObject
                AdminModerationAppeal(
                    id = textOf(row, "id"),
                    decisionId = textOf(row, "decision_id"),
                    userId = textOf(row, "user_id"),
                    displayName = textOf(row, "display_name").ifBlank { "Usuario" },
                    email = textOf(row, "email"),
                    statement = textOf(row, "statement"),
                    status = textOf(row, "status").ifBlank { "pending" },
                    ruleCode = textOf(row, "rule_code"),
                    ruleTitle = textOf(row, "rule_title").ifBlank { "Normas de la comunidad" },
                    explanation = textOf(row, "explanation"),
                    severity = textOf(row, "severity").ifBlank { "high" },
                    reviewerNote = textOf(row, "reviewer_note").ifBlank { null },
                    createdAt = textOf(row, "created_at").ifBlank { null },
                    reviewedAt = textOf(row, "reviewed_at").ifBlank { null }
                )
            })
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun adminReviewModerationAppeal(
        accessToken: String,
        appealId: String,
        outcome: String,
        note: String
    ): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put(
                "$baseUrl/v1/data/admin/moderation/appeals/${urlEncode(appealId)}"
            ) {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("outcome", JsonPrimitive(outcome))
                    put("note", JsonPrimitive(note.trim()))
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) Result.success(true)
            else Result.failure(toException(response, decodeError(body)))
        } catch (error: Exception) {
            Result.failure(error)
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
                    evidence_url = UbuntuStorageUrl.rewrite(textOf(obj, "evidence_url"))?.takeIf { it.isNotBlank() },
                    contentType = textOf(obj, "content_type").ifBlank { null },
                    contentId = textOf(obj, "content_id").ifBlank { null },
                    status = textOf(obj, "status"),
                    created_at = textOf(obj, "created_at"),
                    updated_at = textOf(obj, "updated_at").ifBlank { null }
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

    suspend fun adminSuspendUser(
        accessToken: String,
        userId: String,
        reason: String,
        days: Int,
        ruleCode: String = "community_standards_other",
        severity: String = "high",
        evidence: List<String> = emptyList()
    ): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/admin/suspend") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("user_id", kotlinx.serialization.json.JsonPrimitive(userId))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                    put("days", kotlinx.serialization.json.JsonPrimitive(days))
                    put("rule_code", JsonPrimitive(ruleCode))
                    put("severity", JsonPrimitive(severity))
                    put("evidence", buildJsonArray {
                        evidence.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { label ->
                            add(buildJsonObject {
                                put("source", JsonPrimitive("manual"))
                                put("label", JsonPrimitive(label))
                            })
                        }
                    })
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminUnsuspendUser(accessToken: String, userId: String, reason: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/admin/unsuspend") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("user_id", JsonPrimitive(userId))
                    put("reason", JsonPrimitive(reason))
                })
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminUpdateReportStatus(accessToken: String, reportId: String, status: String, type: String): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.put("$baseUrl/v1/data/admin/reports/$reportId/status") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
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

    /**
     * Elimina COMPLETAMENTE un usuario y todos sus datos asociados:
     * cuenta, perfil, publicaciones, videos, imágenes, comentarios,
     * chats, grupos, comunidades, seguidores, seguidos, archivos,
     * tokens, notificaciones, registros y documentos en almacenamiento.
     */
    suspend fun adminDeleteUser(
        accessToken: String,
        userId: String,
        reason: String,
        confirmation: String,
        expectedEmail: String,
        deleteSuspendedAccount: Boolean
    ): Result<Boolean> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val encodedId = URLEncoder.encode(userId, Charsets.UTF_8.name())
            val response = supabaseConfig.httpClient.delete("$baseUrl/v1/data/admin/users/$encodedId") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("reason", JsonPrimitive(reason))
                    put("confirmation", JsonPrimitive(confirmation))
                    put("hard_delete", JsonPrimitive(true))
                    put("expected_email", JsonPrimitive(expectedEmail.trim().lowercase()))
                    put("allow_suspended_account_delete", JsonPrimitive(deleteSuspendedAccount))
                    put("acknowledge_irreversible", JsonPrimitive(true))
                    put("acknowledge_full_purge", JsonPrimitive(true))
                })
            }
            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                val body = response.bodyAsText()
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exploreCommunities(accessToken: String, query: String): Result<List<Community>> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get("$baseUrl/v1/data/messages/communities/explore") {
                localHeaders(accessToken)
                query.trim()
                    .take(80)
                    .takeIf(String::isNotBlank)
                    ?.let { parameter("q", it) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return Result.failure(toException(response, decodeError(body)))
            }

            val payload = json.parseToJsonElement(body)
            val items = payload as? JsonArray ?: return Result.failure(
                Exception("La respuesta de comunidades no tiene un formato válido.")
            )
            val communities = items.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<Community>(element) }.getOrNull()
            }
            Result.success(communities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitUserReport(
        accessToken: String,
        reportedUserId: String,
        reportedUserName: String,
        reason: String,
        description: String?,
        evidenceUrl: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/reports/users") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("reportedUserId", reportedUserId)
                    put("reportedUserName", reportedUserName)
                    put("reason", reason)
                    description?.let { put("description", it) }
                    evidenceUrl?.let { put("evidenceUrl", it) }
                })
            }
            val responseBody = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("No se pudo enviar el reporte: ${response.status} ${responseBody.take(300)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitContentReport(
        accessToken: String,
        contentType: String,
        contentId: String,
        reportedUserId: String,
        reportedUserName: String,
        reason: String,
        description: String?,
        evidenceUrl: String?
    ): Result<Unit> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/reports/content") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("contentType", contentType)
                    put("contentId", contentId)
                    put("reportedUserId", reportedUserId)
                    put("reportedUserName", reportedUserName)
                    put("reason", reason)
                    description?.let { put("description", it) }
                    evidenceUrl?.let { put("evidenceUrl", it) }
                })
            }
            val responseBody = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("No se pudo enviar el reporte: ${response.status} ${responseBody.take(300)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
