package conectate.com.data.repository

import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class ChatBackgroundSettings(val backgroundType: String = "default", val backgroundUri: String? = null)

private fun normalizedOwner(value: String?) = value?.trim()?.lowercase()?.takeIf(String::isNotBlank)
internal fun accountChatBackgroundKey(owner: String?, conversation: String): String? {
    val account = normalizedOwner(owner) ?: return null
    val target = conversation.trim().lowercase().takeIf(String::isNotBlank) ?: return null
    // Length prefixes keep separators inside IDs unambiguous on every supported Android API.
    return "background:v2:${account.length}:$account:${target.length}:$target"
}
internal fun chatBackgroundFileName(key: String): String = "bg_" + MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".jpg"

/** Never adopt ownerless legacy rows; late image work cannot write another account's key. */
internal class AccountChatBackgroundStore(
    private val currentOwner: () -> String?,
    private val owners: Flow<String?>,
    private val read: (String) -> Flow<ChatBackgroundSettings>,
    private val write: suspend (String, ChatBackgroundSettings) -> Unit,
    private val clear: suspend (String) -> Unit,
    private val prepare: suspend (String, String) -> String?
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(conversation: String): Flow<ChatBackgroundSettings> = owners.map(::normalizedOwner)
        .distinctUntilChanged().flatMapLatest { owner ->
            val key = accountChatBackgroundKey(owner, conversation)
            if (key == null) flowOf(owner to ChatBackgroundSettings())
            else read(key).map { owner to it }.onStart { emit(owner to ChatBackgroundSettings()) }
        }.map { (owner, settings) ->
            if (normalizedOwner(currentOwner()) == owner) settings else ChatBackgroundSettings()
        }

    private fun keyFor(expectedOwner: String, conversation: String): String? {
        val owner = normalizedOwner(expectedOwner) ?: return null
        if (normalizedOwner(currentOwner()) != owner) return null
        return accountChatBackgroundKey(owner, conversation)
    }

    suspend fun set(expectedOwner: String, conversation: String, type: String, source: String?) {
        val key = keyFor(expectedOwner, conversation) ?: return
        val normalizedType = type.trim().lowercase().ifBlank { "default" }
        val uri = if (normalizedType == "custom" && !source.isNullOrBlank()) {
            prepare(key, source) ?: return
        } else source
        if (keyFor(expectedOwner, conversation) != key) return
        write(key, ChatBackgroundSettings(normalizedType, uri?.trim()?.ifBlank { null }))
    }

    suspend fun remove(expectedOwner: String, conversation: String) {
        val key = keyFor(expectedOwner, conversation) ?: return
        clear(key)
    }
}
