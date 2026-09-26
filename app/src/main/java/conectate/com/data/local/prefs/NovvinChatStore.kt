package conectate.com.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.novvinChatDataStore by preferencesDataStore(name = "novvin_chat_history")

@Singleton
class NovvinChatStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun load(userId: String): String? =
        context.novvinChatDataStore.data.first()[keyFor(userId)]

    suspend fun save(userId: String, payload: String) {
        context.novvinChatDataStore.edit { preferences ->
            preferences[keyFor(userId)] = payload
        }
    }

    private fun keyFor(userId: String) = stringPreferencesKey(
        "history_${MessageDigest.getInstance("SHA-256")
            .digest(userId.trim().lowercase().toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }}"
    )
}
