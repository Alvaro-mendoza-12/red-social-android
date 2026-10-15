package conectate.com.data.repository.message

import androidx.room.withTransaction
import conectate.com.data.local.AppDatabase
import conectate.com.data.model.ChatWithLastMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Account-bound, local-only inbox projection, invalidated by history AND deletion intents. */
internal class InboxPreviewReader(
    private val database: AppDatabase,
    private val currentAccount: () -> String?,
    private val deletionChanges: Flow<Long> = flowOf(0L),
    private val preferenceChanges: Flow<Long> = flowOf(0L),
    private val isChatMuted: (String, String, Boolean) -> Boolean = { _, _, remote -> remote },
    private val redact: (conectate.com.data.model.Message, String) -> conectate.com.data.model.Message = { message, _ -> message },
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        accounts: Flow<String?>,
        onSnapshotLoaded: (String) -> Unit = {},
    ): Flow<List<ChatWithLastMessage>> = flow {
        var previousAccount: String? = null
        emitAll(accounts.map { it?.trim()?.lowercase() }.distinctUntilChanged().flatMapLatest { account ->
            val changedAccount = previousAccount != null && previousAccount != account
            previousAccount = account
            if (account.isNullOrBlank()) flowOf(emptyList()) else {
                database.invalidationTracker.createFlow("chats", "users", "messages", "message_deletions")
                    .combine(deletionChanges) { tables, _ -> tables }
                    .combine(preferenceChanges) { tables, _ -> tables }
                    .mapLatest {
                        if (currentAccount()?.trim()?.lowercase() != account) emptyList()
                        else read(account).also {
                            if (currentAccount()?.trim()?.lowercase() == account) onSnapshotLoaded(account)
                        }
                    }
                    .onStart { if (changedAccount) emit(emptyList()) }
                    .map { if (currentAccount()?.trim()?.lowercase() == account) it else emptyList() }
            }
        })
    }.buffer(0).distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun read(account: String): List<ChatWithLastMessage> = withContext(Dispatchers.IO) {
        fun checkAccount() {
            if (account.isBlank() || currentAccount()?.trim()?.lowercase() != account) {
                throw CancellationException("La cuenta de la bandeja cambió")
            }
        }
        checkAccount()
        val result = database.withTransaction {
            val chats = database.chatDao().getAllChatsWithUsers().filter { it.chat.toDomain().isAccessibleTo(account) }
            checkAccount()
            val ids = chats.map { it.chat.id }
            val deletions = ids.chunked(800).flatMap { database.messageDeletionDao().forChats(it) }.groupBy { it.chat_id }
            val latest = ids.chunked(800).flatMap { database.messageDao().getLatestInboxCandidates(it) }.associateBy { it.chat_id }
            chats.map { entity ->
                checkAccount()
                val chat = entity.chat
                val intents = deletions[chat.id].orEmpty()
                fun visible(message: conectate.com.data.model.Message?) =
                    visibleInboxMessage(message?.let { redact(it, account) }, chat.id, chat.cleared_at, intents, account)
                val stored = latest[chat.id]
                var cursor = stored
                var localPreview = visible(stored?.toSafeInboxMessage())
                // Most chats need only the batched lookup. Hidden rows are walked by keyset,
                // without a fixed history cap that could incorrectly declare the chat empty.
                while (cursor != null && localPreview == null && isAfterInboxClear(cursor.created_at, chat.cleared_at)) {
                    val older = database.messageDao().getOlderInboxCandidates(chat.id, cursor.created_at, cursor.id, 32)
                    checkAccount()
                    if (older.isEmpty()) break
                    localPreview = older.firstNotNullOfOrNull { visible(it.toSafeInboxMessage()) }
                    cursor = older.last()
                }
                val summary = cachedInboxMessage(entity, intents, account)?.takeUnless {
                    it.id.startsWith("inbox-summary:") && stored != null &&
                        (stored.deleted_for_all || visible(stored.toSafeInboxMessage()) == null)
                }
                // If Room knows the summary's ID, its deletion markers take precedence,
                // including malformed metadata. Never resurrect the raw chat-table fallback.
                val summaryRow = summary?.id?.takeUnless { it.startsWith("inbox-summary:") }
                    ?.let { id -> if (stored?.id == id) stored else database.messageDao().getMessageById(id) }
                val safeSummary = visible(if (summaryRow != null) summaryRow.toSafeInboxMessage() else summary)
                val conversation = buildVisibleInboxConversation(entity, newestInboxMessage(localPreview, safeSummary))
                conversation.copy(chat = conversation.chat.copy(
                    is_muted = isChatMuted(account, chat.id, conversation.chat.is_muted),
                ))
            }.sortedWith(
                compareBy<ChatWithLastMessage> { it.chat.is_archived }
                    .thenByDescending { it.chat.is_pinned }
                    .thenByDescending { it.chat.is_favorite }
                    .thenByDescending { it.chat.last_message_at.orEmpty() }
            )
        }
        checkAccount()
        result
    }
}
