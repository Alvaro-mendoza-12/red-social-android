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
        key(owner, scope, room)?.let { safeConversationDraft(preferences.getString(it, "").orEmpty()) }.orEmpty()

    /** Real conversation IDs remain stable when profile/participant aliases change. */
    fun readAndMigrate(owner: String?, scope: String, room: String, legacyRoom: String?): String {
        val current = read(owner, scope, room)
        val legacy = legacyRoom?.takeIf { it.isNotBlank() && !it.equals(room, true) } ?: return current
        val value = current.ifEmpty { read(owner, scope, legacy) }
        if (value.isNotEmpty()) write(owner, scope, room, value)
        write(owner, scope, legacy, "")
        return value
    }

    fun write(owner: String?, scope: String, room: String, text: String) {
        val name = key(owner, scope, room) ?: return
        val safeText = safeConversationDraft(text)
        // Cursor/composition updates must not enqueue another XML write for the same draft.
        if (preferences.getString(name, "").orEmpty() == safeText) return
        val editor = preferences.edit()
        if (safeText.isEmpty()) editor.remove(name) else editor.putString(name, safeText)
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
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$account\u0000$scope\u0000$conversation".toByteArray(Charsets.UTF_8))
            // This path runs while typing. Avoid constructing a Formatter for every byte.
            val hex = "0123456789abcdef"
            return CharArray(digest.size * 2).apply {
                digest.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    this[index * 2] = hex[value ushr 4]
                    this[index * 2 + 1] = hex[value and 0xf]
                }
            }.concatToString()
        }
    }
}
