package conectate.com.data.repository.message

import conectate.com.data.model.Chat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal fun Chat.isAccessibleTo(accountId: String?): Boolean {
    val account = accountId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return false
    return participants.any { it.trim().lowercase() == account }
}

/** Read-through cache with the same account/ID checks for Room, memory and transport. */
internal class AccountChatReader(
    private val local: suspend (String) -> Chat?,
    private val memory: (String) -> Chat?,
    private val remote: suspend (String) -> Result<Chat>
) {
    suspend fun read(chatId: String, accountId: String?, isCurrentSession: () -> Boolean): Result<Chat> {
        val id = chatId.trim().lowercase()
        val account = accountId?.trim()?.lowercase()
        if (id.isBlank() || account.isNullOrBlank()) return Result.failure(IllegalArgumentException("No chat or account"))
        fun checkSession() {
            if (!isCurrentSession()) throw CancellationException("Chat session changed")
        }
        fun Chat.matches() = this.id.trim().lowercase() == id && isAccessibleTo(account)
        return try {
            checkSession()
            val cached = local(id)
            checkSession()
            if (cached?.matches() == true) return Result.success(cached)
            val inMemory = memory(id)
            checkSession()
            if (inMemory?.matches() == true) return Result.success(inMemory)
            val response = remote(id).getOrThrow()
            checkSession()
            if (!response.matches()) return Result.failure(SecurityException("Chat not accessible to this account"))
            Result.success(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}

/** Cancel the old subscription on a session/membership change and fail closed at emission. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> accountScopedChatFlow(
    accounts: Flow<String?>,
    chats: Flow<Chat?>,
    currentAccount: () -> String?,
    empty: T,
    content: (Chat, String) -> Flow<T>
): Flow<T> = accounts.map { it?.trim()?.lowercase() }.distinctUntilChanged().flatMapLatest { account ->
    if (account.isNullOrBlank()) flowOf(empty) else {
        chats.flatMapLatest { chat ->
            if (chat?.isAccessibleTo(account) != true || currentAccount()?.trim()?.lowercase() != account) {
                flowOf(empty)
            } else {
                content(chat, account).map { value ->
                    if (currentAccount()?.trim()?.lowercase() == account) value else empty
                }
            }
        }.onStart {
            // Clear account-specific visibility before subscribing, even when
            // both stored accounts happen to participate in this same chat.
            emit(empty)
        }
    }
}.buffer(0)
