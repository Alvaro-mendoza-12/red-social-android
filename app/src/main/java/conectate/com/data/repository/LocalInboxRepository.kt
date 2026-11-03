package conectate.com.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.local.AppDatabase
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.repository.message.InboxPreviewReader
import conectate.com.data.supabase.AuthService
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class LocalInboxSnapshot(
    val accountId: String? = null,
    val conversations: List<ChatWithLastMessage> = emptyList(),
)

/** Also guard cached StateFlow values while a session change is reaching Room's observer. */
internal fun LocalInboxSnapshot.forAccount(activeAccountId: String?): LocalInboxSnapshot {
    val owner = activeAccountId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    return if (owner != null && owner == accountId?.trim()?.lowercase()) this else LocalInboxSnapshot()
}

/** Start on first use, then keep the local projection current across navigation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeLocalInboxSnapshots(
    scope: CoroutineScope,
    accounts: Flow<String?>,
    currentAccount: () -> String?,
    observeConversations: (String) -> Flow<List<ChatWithLastMessage>>,
) = accounts
    .map { it?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
    .distinctUntilChanged()
    .flatMapLatest { account ->
        flow {
            emit(LocalInboxSnapshot())
            if (account == null) return@flow
            emitAll(observeConversations(account).map { conversations ->
                if (currentAccount()?.trim()?.lowercase() == account) {
                    LocalInboxSnapshot(account, conversations)
                } else LocalInboxSnapshot()
            })
        }
    }
    .stateIn(scope, SharingStarted.Lazily, LocalInboxSnapshot())

/** Shared factory keeps the startup inbox bound to notification preferences. */
internal fun createLocalInboxReader(
    database: AppDatabase,
    currentAccount: () -> String?,
    context: Context,
): InboxPreviewReader {
    val preferences = ConversationNotificationPrefs(context)
    return InboxPreviewReader(
        database, currentAccount,
        preferenceChanges = preferences.observeChanges(),
        isChatMuted = { owner, chatId, remote -> remote || preferences.isMuted("chat", chatId, owner) },
    )
}

/** The first inbox read needs Room and the session, not upload, signaling or other tabs. */
@Singleton
class LocalInboxRepository @Inject constructor(
    private val authService: AuthService,
    private val databaseProvider: Lazy<AppDatabase>,
    @param:ApplicationContext private val context: Context,
) {
    private val scope = newSafeScope("LocalInboxRepository", Dispatchers.IO)

    val snapshots = observeLocalInboxSnapshots(
        scope = scope,
        accounts = authService.currentSession.map { it?.user?.id },
        currentAccount = authService::getCurrentUserId,
    ) { account ->
        val reader = createLocalInboxReader(databaseProvider.get(), authService::getCurrentUserId, context)
        reader.observe(flowOf(account))
    }
}
