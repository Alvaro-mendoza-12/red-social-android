package conectate.com.presentation.messages

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-only private material. Never fall back to unencrypted storage on key failure. */
@Singleton
class ChatWorkspaceStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "chat_workspace_private_v1", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun read(accountId: String, scope: String): ChatWorkspace = withContext(Dispatchers.IO) {
        mutex.withLock { readKey(chatWorkspaceStorageKey(accountId, scope)) }
    }

    suspend fun update(accountId: String, scope: String, transform: (ChatWorkspace) -> ChatWorkspace): ChatWorkspace =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = chatWorkspaceStorageKey(accountId, scope)
                val next = transform(readKey(key))
                if (!preferences.edit().putString(key, json.encodeToString(next)).commit()) {
                    throw IOException("No se pudo guardar en este dispositivo.")
                }
                next
            }
        }

    private fun readKey(key: String): ChatWorkspace {
        val raw = preferences.getString(key, null) ?: return ChatWorkspace()
        // Corrupt data must be surfaced rather than overwritten with an empty workspace.
        require(raw.length <= 2_000_000) { "El espacio guardado supera el límite admitido." }
        return json.decodeFromString<ChatWorkspace>(raw)
    }
}
