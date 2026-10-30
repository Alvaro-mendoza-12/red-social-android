package conectate.com.data.repository.message

import android.content.SharedPreferences
import conectate.com.data.model.ChatFolder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Account-owned cache. The legacy ownerless file is retained without guessing its owner. */
internal class ChatFolderStore(
    private val prefs: SharedPreferences,
    private val currentAccount: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val revision = MutableStateFlow(0L)
    private val mutations = Mutex()

    suspend fun <T> withAccount(operation: suspend (String) -> T): Result<T> {
        // Capture before waiting: a queued tap belongs to the account that made
        // it, even if a previous request keeps the mutex occupied during logout.
        val owner = normalize(currentAccount())
            ?: return Result.failure(IllegalStateException("Inicia sesión para organizar tus chats."))
        return mutations.withLock {
            try {
                requireAccount(owner)
                val result = operation(owner)
                requireAccount(owner)
                Result.success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private suspend fun requireAccount(owner: String) {
        currentCoroutineContext().ensureActive()
        if (normalize(currentAccount()) != owner) throw CancellationException("Folder account changed")
    }

    fun observe(accounts: Flow<String?>): Flow<List<ChatFolder>> = combine(accounts, revision) { _, _ ->
        read(currentAccount())
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun read(account: String?): List<ChatFolder> {
        val owner = normalize(account) ?: return emptyList()
        if (owner != normalize(currentAccount())) return emptyList()
        val raw = prefs.getString("$PREFIX$owner", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ChatFolder>>(raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun replace(account: String, folders: List<ChatFolder>) {
        val owner = normalize(account)
        if (owner == null || owner != normalize(currentAccount())) throw CancellationException("Folder account changed")
        check(prefs.edit().putString("$PREFIX$owner", json.encodeToString(folders)).commit()) {
            "No se pudieron guardar tus carpetas en este dispositivo."
        }
        revision.value += 1
    }

    private fun normalize(value: String?) = value?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }

    companion object { private const val PREFIX = "folders_by_account_v1:" }
}
