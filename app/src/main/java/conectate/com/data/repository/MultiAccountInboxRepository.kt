package conectate.com.data.repository

import android.content.Context
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.model.Chat
import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.countDistinctUnreadDirectConversations
import conectate.com.util.buildConversationBackgroundPreview
import conectate.com.util.resolveStoredAccountForTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

data class MultiAccountInboxSummary(
    val account: StoredAccountSession,
    val isActive: Boolean,
    val unreadConversationCount: Int,
    val hasPendingHints: Boolean,
    val latestPendingDestination: MultiAccountPendingDestination? = null
)

internal data class InactiveAccountUnreadSnapshot(
    val unreadConversationCount: Int,
    val pendingConversationKeys: Set<String>,
    val latestPendingDestination: MultiAccountPendingDestination?
)

private data class ActiveInboxIdentity(
    val userId: String,
    val email: String?,
    val lease: AuthSessionLease,
) {
    val target get() = NotificationAccountTarget(userId, email)
}

@Singleton
class MultiAccountInboxRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountSessionRepository: AccountSessionRepository,
    private val authService: AuthService,
    private val supabaseConfig: SupabaseConfig,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository
) {

    companion object {
        private const val PREFS = "multi_account_inbox"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val inboxState = InboxStateStore { prefs }
    private var scope = newSafeScope("MultiAccountInboxRepository", Dispatchers.IO)

    private val activeIdentity = authService.currentSession.map { session ->
        val user = session?.user ?: return@map null
        val lease = authService.captureAccountLease()
        // Both fields come from one published response, never two separately combined emissions.
        ActiveInboxIdentity(user.id.trim().lowercase(java.util.Locale.ROOT),
            user.email.trim().lowercase(java.util.Locale.ROOT).takeIf(String::isNotBlank), lease)
            .takeIf { lease.isCurrent() && it.userId == lease.accountId && authService.currentSession.value === session }
    }.distinctUntilChanged()

    private val activeUnreadConversationCount = combine(
        messageRepository.getUserChatsFlow()
            .map(::countDistinctUnreadDirectConversations),
        groupRepository.getGroupsFlow()
            .map { groups -> groups.count { it.unreadCount > 0 } },
        communityRepository.getCommunitiesFlow()
            .map { communities -> communities.count { it.unreadCount > 0 } }
    ) { directUnread, groupUnread, communityUnread ->
        directUnread + groupUnread + communityUnread
    }

    init {
        scope.launch {
            accountSessionRepository.accounts.collectLatest {
                // An older observer emission must not prune an account just added by another thread.
                accountSessionRepository.withStoredAccountsSnapshot { current ->
                    inboxState.prune(current.map { it.id }.toSet())
                }
            }
        }
        scope.launch {
            combine(
                accountSessionRepository.accounts,
                activeIdentity,
                activeUnreadConversationCount
            ) { accounts, identity, unreadCount ->
                Triple(accounts, identity, unreadCount)
            }.collectLatest { (accounts, identity, unreadCount) ->
                if (identity == null) return@collectLatest
                val activeAccount = resolveStoredAccountForTarget(identity.target, accounts) ?: return@collectLatest
                authService.commitForAccountLease(identity.lease) {
                    accountSessionRepository.commitIfStoredAccountCurrent(activeAccount) {
                        inboxState.persistActive(it.id, unreadCount)
                    }
                }
            }
        }
    }

    val accountSummaries: StateFlow<List<MultiAccountInboxSummary>> = combine(
        accountSessionRepository.accounts,
        activeIdentity,
        activeUnreadConversationCount,
        observeInboxPreferenceChanges { prefs },
    ) { accounts, identity, activeUnread, _ ->
        accounts
            .sortedByDescending { it.last_used_at }
            .map { account ->
                val isActive = identity != null && identity.lease.isCurrent() && identity.target.matches(account)
                val saved = inboxState.snapshot(account.id)
                val pendingCount = saved.pendingConversationKeys.size
                MultiAccountInboxSummary(
                    account = account,
                    isActive = isActive,
                    unreadConversationCount = if (isActive) {
                        activeUnread
                    } else {
                        maxOf(saved.cachedUnreadConversations, pendingCount)
                    },
                    hasPendingHints = pendingCount > 0,
                    latestPendingDestination = saved.latestPendingDestination
                )
            }
            .sortedWith(
                compareByDescending<MultiAccountInboxSummary> { it.isActive }
                    .thenByDescending { it.unreadConversationCount }
                    .thenByDescending { it.latestPendingDestination?.receivedAt ?: 0L }
                    .thenByDescending { it.account.last_used_at }
            )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    suspend fun refreshInactiveAccounts() = withContext(Dispatchers.IO) {
        val session = authService.currentSession.value
        val activeTarget = NotificationAccountTarget(session?.user?.id, session?.user?.email)
        accountSessionRepository.accounts.value.forEach { account ->
            currentCoroutineContext().ensureActive()
            if (activeTarget.matches(account)) return@forEach
            val beforeRefresh = inboxState.beginRefresh(account.id)

            val results = mapOf(
                InboxUnreadSection.DIRECT to fetchUnreadDirectConversationSnapshot(account),
                InboxUnreadSection.GROUP to fetchUnreadCollectionConversationSnapshot(
                    account = account,
                    membershipTable = "group_members",
                    collectionTable = "groups",
                    messageTable = "group_messages",
                    scopeColumn = "group_id",
                    targetType = "group"
                ),
                InboxUnreadSection.COMMUNITY to fetchUnreadCollectionConversationSnapshot(
                    account = account,
                    membershipTable = "community_members",
                    collectionTable = "communities",
                    messageTable = "community_messages",
                    scopeColumn = "community_id",
                    targetType = "community"
                )
            )
            val successfulSnapshots = results.mapNotNull { (section, result) ->
                result.onFailure {
                    android.util.Log.w(
                        "MultiAccountInboxRepo",
                        "Inactive inbox section ${section.targetType} could not be refreshed",
                        it
                    )
                }
                result.getOrNull()?.let { section to it }
            }.toMap()

            if (successfulSnapshots.isNotEmpty()) {
                // Keep the captured credential pair, including when no account is active.
                // Removed/replaced credentials and newer local hints cannot be revived by this read.
                currentCoroutineContext().ensureActive()
                accountSessionRepository.commitIfStoredAccountCurrent(account) {
                    inboxState.applyRefresh(beforeRefresh, successfulSnapshots)
                }
            }
        }
    }

    fun registerUnreadConversationHint(
        target: NotificationAccountTarget?,
        destination: MultiAccountPendingDestination
    ) {
        if (target == null || !target.isDefined) return
        val normalizedConversationId = destination.normalizedConversationId
        if (normalizedConversationId.isBlank()) return
        accountSessionRepository.withStoredAccountsSnapshot { accounts ->
            val account = resolveStoredAccountForTarget(target, accounts) ?: return@withStoredAccountsSnapshot
            val normalizedDestination = destination.copy(
                targetType = destination.normalizedTargetType,
                conversationId = normalizedConversationId,
                userId = destination.normalizedUserId,
                conversationTitle = destination.conversationTitle?.trim()?.ifBlank { null },
                senderName = destination.senderName?.trim()?.ifBlank { null },
                preview = if (conectate.com.util.DeletedMessageIdentity.create(account.user_id, destination.normalizedTargetType,
                        normalizedConversationId, destination.focusMessageId)?.let { conectate.com.util.DeletedMessageStore(context).isDeleted(it) } == true)
                    "Mensaje eliminado" else destination.preview?.trim()?.ifBlank { null },
                focusMessageId = destination.focusMessageId?.trim()?.ifBlank { null }
            )
            inboxState.registerHint(account.id, normalizedDestination)
        }
    }

    fun redactDeletedMessageHint(identity: conectate.com.util.DeletedMessageIdentity) {
        accountSessionRepository.withStoredAccountsSnapshot { accounts ->
            val account = accounts.firstOrNull { it.user_id?.trim()?.lowercase(java.util.Locale.ROOT) == identity.accountId }
                ?: return@withStoredAccountsSnapshot
            inboxState.redactLatest(account.id, identity.targetType, identity.conversationId, identity.messageId)
        }
    }

    fun registerUnreadDirectConversationHint(
        target: NotificationAccountTarget?,
        chatId: String?
    ) {
        var cleanChatId = chatId?.trim()?.lowercase().orEmpty()
        if (cleanChatId.isBlank()) return
        registerUnreadConversationHint(
            target = target,
            destination = MultiAccountPendingDestination(
                targetType = "chat",
                conversationId = cleanChatId
            )
        )
    }

    fun acknowledgeAccount(accountId: String) {
        accountSessionRepository.withStoredAccountsSnapshot { accounts ->
            val account = accounts.firstOrNull { it.id == accountId.trim() } ?: return@withStoredAccountsSnapshot
            inboxState.acknowledge(account.id)
        }
    }

    fun acknowledgeCurrentAccount() {
        authService.commitForAccountLease(authService.captureAccountLease(), ::clearCurrentAccountHints)
    }

    internal fun acknowledgeCurrentAccount(lease: AuthSessionLease) {
        if (!authService.commitForAccountLease(lease, ::clearCurrentAccountHints)) {
            throw CancellationException("La cuenta cambió mientras se abrían sus avisos.")
        }
    }

    /** Called only while the captured auth generation excludes a newer login. */
    private fun clearCurrentAccountHints() {
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase(java.util.Locale.ROOT)
        val currentEmail = authService.getCurrentUserEmail()?.trim()?.lowercase(java.util.Locale.ROOT)
        accountSessionRepository.withStoredAccountsSnapshot { accounts ->
            val activeAccount = resolveStoredAccountForTarget(NotificationAccountTarget(currentUserId, currentEmail), accounts)
                ?: return@withStoredAccountsSnapshot
            inboxState.acknowledge(activeAccount.id)
        }
    }

    private suspend fun fetchUnreadDirectConversationSnapshot(
        account: StoredAccountSession
    ): Result<InactiveAccountUnreadSnapshot> = withContext(Dispatchers.IO) {
        inboxReadResult {
            var accessToken = account.access_token.trim().ifBlank {
                throw IllegalStateException("Account token missing")
            }
            var cleanUserId = resolveStoredAccountUserId(account)
                ?: throw IllegalStateException("Account user id missing")

            val chatsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("participants", "cs.{\"$cleanUserId\"}")
                parameter("order", "last_message_at.desc.nullslast")
            }
            if (!chatsResponse.status.isSuccess()) {
                throw IllegalStateException("Chats fetch failed: ${chatsResponse.status}")
            }

            val directChats = chatsResponse.body<List<Chat>>()
                .filter { chat ->
                    !chat.is_group && chat.participants.map { it.trim().lowercase() }.contains(cleanUserId)
                }
                .mapNotNull { chat ->
                    var cleanChatId = chat.id.trim().lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    cleanChatId to chat
                }
                .distinctBy { it.first }
                .toMap()

            val directChatIds = directChats.keys.toList()

            if (directChatIds.isEmpty()) {
                return@inboxReadResult InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 0,
                    pendingConversationKeys = emptySet(),
                    latestPendingDestination = null
                )
            }

            val unreadChatIds = mutableSetOf<String>()
            var latestUnreadDestination: MultiAccountPendingDestination? = null
            directChatIds.chunked(30).forEach { chunk ->
                val unreadResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("select", "id,chat_id,read_at,sender_id,status,content,content_type,sender_name,created_at")
                    parameter("chat_id", "in.(${chunk.joinToString(",")})")
                    parameter("sender_id", "neq.$cleanUserId")
                    parameter("read_at", "is.null")
                }

                if (!unreadResponse.status.isSuccess()) {
                    throw IllegalStateException("Unread fetch failed: ${unreadResponse.status}")
                }

                unreadResponse.body<List<JsonObject>>().forEach unreadLoop@{ item ->
                    val unreadChatId = item["chat_id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@unreadLoop
                    var senderId = item["sender_id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?: return@unreadLoop
                    var readAt = item["read_at"]?.jsonPrimitive?.contentOrNull
                    val status = item["status"]?.jsonPrimitive?.contentOrNull
                    if (senderId != cleanUserId && MessageStatusNormalizer.isUnread(status, readAt)) {
                        unreadChatIds.add(unreadChatId)
                        var content = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        var contentType = item["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        var senderName = item["sender_name"]?.jsonPrimitive?.contentOrNull
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        var messageId = item["id"]?.jsonPrimitive?.contentOrNull
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        var createdAt = item["created_at"]?.jsonPrimitive?.contentOrNull
                        val chat = directChats[unreadChatId]
                        var preview = buildConversationBackgroundPreview(contentType, content) ?: content
                        var destination = MultiAccountPendingDestination(
                            targetType = "chat",
                            conversationId = unreadChatId,
                            userId = senderId,
                            conversationTitle = chat?.nickname?.takeIf { !it.isNullOrBlank() } ?: senderName,
                            senderName = senderName,
                            preview = preview.takeIf { it.isNotBlank() },
                            focusMessageId = messageId,
                            receivedAt = createdAt.toSortableEpochMillis()
                        )
                        val latestDestination = latestUnreadDestination
                        if (latestDestination == null || destination.receivedAt >= latestDestination.receivedAt) {
                            latestUnreadDestination = destination
                        }
                    }
                }
            }

            InactiveAccountUnreadSnapshot(
                unreadConversationCount = unreadChatIds.size,
                pendingConversationKeys = unreadChatIds.map { "chat:$it" }.toSet(),
                latestPendingDestination = latestUnreadDestination
            )
        }
    }

    private suspend fun fetchUnreadCollectionConversationSnapshot(
        account: StoredAccountSession,
        membershipTable: String,
        collectionTable: String,
        messageTable: String,
        scopeColumn: String,
        targetType: String
    ): Result<InactiveAccountUnreadSnapshot> = withContext(Dispatchers.IO) {
        inboxReadResult {
            var accessToken = account.access_token.trim().ifBlank {
                throw IllegalStateException("Account token missing")
            }
            var cleanUserId = resolveStoredAccountUserId(account)
                ?: throw IllegalStateException("Account user id missing")

            val membershipResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/$membershipTable") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$cleanUserId")
                parameter("select", scopeColumn)
            }
            if (!membershipResponse.status.isSuccess()) {
                throw IllegalStateException("Membership fetch failed: ${membershipResponse.status}")
            }

            val joinedIds = membershipResponse.body<List<JsonObject>>()
                .mapNotNull { row ->
                    row[scopeColumn]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                }
                .distinct()

            if (joinedIds.isEmpty()) {
                return@inboxReadResult InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 0,
                    pendingConversationKeys = emptySet(),
                    latestPendingDestination = null
                )
            }

            val collectionTitles = mutableMapOf<String, String?>()
            val activeCollectionIds = mutableSetOf<String>()
            joinedIds.chunked(30).forEach { chunk ->
                val collectionResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/$collectionTable") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "in.(${chunk.joinToString(",")})")
                    parameter(
                        "select",
                        if (targetType == "community") {
                            "id,name,is_deleted,is_member,creator_id"
                        } else {
                            "id,name,is_deleted"
                        }
                    )
                }
                if (!collectionResponse.status.isSuccess()) {
                    throw IllegalStateException("Collection fetch failed: ${collectionResponse.status}")
                }

                collectionResponse.body<List<JsonObject>>().forEach collectionLoop@{ row ->
                    val collectionId = row["id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@collectionLoop
                    val isDeleted = row["is_deleted"]?.jsonPrimitive?.contentOrNull == "true"
                    if (isDeleted) return@collectionLoop
                    if (targetType == "community") {
                        val isCreator = row["creator_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            ?.lowercase() == cleanUserId
                        val isMember = row["is_member"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            ?.equals("true", ignoreCase = true) == true
                        if (!isCreator && !isMember) return@collectionLoop
                    }
                    activeCollectionIds.add(collectionId)
                    collectionTitles[collectionId] = row["name"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
            }

            if (activeCollectionIds.isEmpty()) {
                return@inboxReadResult InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 0,
                    pendingConversationKeys = emptySet(),
                    latestPendingDestination = null
                )
            }

            val unreadConversationKeys = mutableSetOf<String>()
            var latestUnreadDestination: MultiAccountPendingDestination? = null
            val receiptTable = when (targetType) {
                "community" -> "community_message_receipts"
                else -> "group_message_receipts"
            }
            activeCollectionIds.toList().chunked(30).forEach { chunk ->
                val messagesResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/$messageTable") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter(
                        "select",
                        "id,$scopeColumn,sender_id,status,content,content_type,sender_name,created_at,deleted_for_all"
                    )
                    parameter(scopeColumn, "in.(${chunk.joinToString(",")})")
                    parameter("sender_id", "neq.$cleanUserId")
                    parameter("deleted_for_all", "eq.false")
                }

                if (!messagesResponse.status.isSuccess()) {
                    throw IllegalStateException("Collection unread fetch failed: ${messagesResponse.status}")
                }

                val readReceiptResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/$receiptTable") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("select", "message_id,read_at")
                    parameter(scopeColumn, "in.(${chunk.joinToString(",")})")
                    parameter("user_id", "eq.$cleanUserId")
                }

                if (!readReceiptResponse.status.isSuccess()) {
                    throw IllegalStateException("Collection receipt fetch failed: ${readReceiptResponse.status}")
                }

                val readReceiptIds = readReceiptResponse.body<List<JsonObject>>()
                    .mapNotNull { receipt ->
                        var readAt = receipt["read_at"]?.jsonPrimitive?.contentOrNull?.trim()
                        if (readAt.isNullOrBlank()) return@mapNotNull null
                        receipt["message_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            ?.lowercase()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .toSet()

                messagesResponse.body<List<JsonObject>>().forEach unreadLoop@{ item ->
                    var conversationId = item[scopeColumn]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@unreadLoop
                    var senderId = item["sender_id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?: return@unreadLoop
                    var messageId = item["id"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@unreadLoop
                    if (senderId != cleanUserId && !readReceiptIds.contains(messageId)) {
                        val conversationKey = "$targetType:$conversationId"
                        unreadConversationKeys.add(conversationKey)
                        var content = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        var contentType = item["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        var senderName = item["sender_name"]?.jsonPrimitive?.contentOrNull
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        var createdAt = item["created_at"]?.jsonPrimitive?.contentOrNull
                        var destination = MultiAccountPendingDestination(
                            targetType = targetType,
                            conversationId = conversationId,
                            userId = senderId,
                            conversationTitle = collectionTitles[conversationId] ?: senderName,
                            senderName = senderName,
                            preview = (buildConversationBackgroundPreview(contentType, content) ?: content)
                                .takeIf { it.isNotBlank() },
                            focusMessageId = messageId,
                            receivedAt = createdAt.toSortableEpochMillis()
                        )
                        val latestDestination = latestUnreadDestination
                        if (latestDestination == null || destination.receivedAt >= latestDestination.receivedAt) {
                            latestUnreadDestination = destination
                        }
                    }
                }
            }

            InactiveAccountUnreadSnapshot(
                unreadConversationCount = unreadConversationKeys.size,
                pendingConversationKeys = unreadConversationKeys,
                latestPendingDestination = latestUnreadDestination
            )
        }
    }
}

internal fun mergeInactiveUnreadSnapshots(
    snapshots: List<InactiveAccountUnreadSnapshot>
): InactiveAccountUnreadSnapshot {
    val mergedKeys = linkedSetOf<String>()
    var latestDestination: MultiAccountPendingDestination? = null

    snapshots.forEach { snapshot ->
        mergedKeys += snapshot.pendingConversationKeys
        val candidate = snapshot.latestPendingDestination ?: return@forEach
        val currentLatest = latestDestination
        if (currentLatest == null || candidate.receivedAt >= currentLatest.receivedAt) {
            latestDestination = candidate
        }
    }

    return InactiveAccountUnreadSnapshot(
        unreadConversationCount = mergedKeys.size,
        pendingConversationKeys = mergedKeys,
        latestPendingDestination = latestDestination
    )
}

internal fun resolveStoredAccountUserId(account: StoredAccountSession): String? {
    return account.user_id
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: extractJwtClaim(account.access_token, "sub")
}

private fun extractJwtClaim(token: String, claim: String): String? {
    val cleanToken = token.trim()
    if (cleanToken.isBlank()) return null
    val parts = cleanToken.split(".")
    if (parts.size < 2) return null

    return runCatching {
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        Json.parseToJsonElement(payload)
            .jsonObject[claim]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun String?.toSortableEpochMillis(): Long {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return System.currentTimeMillis()
    return runCatching {
        val normalized = if (value.contains("T")) value else value.replace(" ", "T")
        java.time.Instant.parse(
            if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z"
        ).toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }
}
/** Cancellation is control flow; a background read must not turn it into an empty section. */
internal inline fun <T> inboxReadResult(read: () -> T): Result<T> = try {
    Result.success(read())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
