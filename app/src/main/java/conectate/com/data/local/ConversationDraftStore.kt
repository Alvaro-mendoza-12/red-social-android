package conectate.com.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Private device storage. No draft is uploaded or mixed with another account. */
@Singleton
class ConversationDraftStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("conversation-drafts-v1", Context.MODE_PRIVATE)

    fun read(owner: String?, scope: String, room: String): String =
        key(owner, scope, room)?.let { preferences.getString(it, "").orEmpty() }.orEmpty()

    fun write(owner: String?, scope: String, room: String, text: String) {
        val name = key(owner, scope, room) ?: return
        val editor = preferences.edit()
        if (text.isEmpty()) editor.remove(name) else editor.putString(name, text.take(16_000))
        // apply updates memory immediately and serializes disk writes across normal lifecycle exits.
        editor.apply()
    }

    fun observe(owner: String?, scope: String, room: String) = callbackFlow {
        val name = key(owner, scope, room)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == name || changed == null) trySend(read(owner, scope, room))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(owner, scope, room))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        internal fun key(owner: String?, scope: String, room: String): String? {
            val account = owner?.trim()?.lowercase().orEmpty()
            val conversation = room.trim().lowercase()
            if (account.isEmpty() || conversation.isEmpty() || scope !in setOf("chat", "group", "community")) return null
            return MessageDigest.getInstance("SHA-256")
                .digest("$account\u0000$scope\u0000$conversation".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
