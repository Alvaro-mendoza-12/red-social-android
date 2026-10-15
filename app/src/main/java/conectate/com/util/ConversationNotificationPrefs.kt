package conectate.com.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

class ConversationNotificationPrefs(context: Context) {
    companion object {
        private const val PREFS_NAME = "conversation_notification_prefs"
        private const val KEY_MUTED_CONVERSATIONS = "muted_conversations"
        private const val KEY_MUTE_UNTIL_PREFIX = "mute_until_"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun muteConversation(targetType: String, conversationId: String, accountId: String? = null) {
        val key = buildKey(targetType, conversationId, accountId) ?: return
        putMute(key, null)
    }

    fun muteConversationUntil(targetType: String, conversationId: String, untilEpochMillis: Long, accountId: String? = null) {
        val key = buildKey(targetType, conversationId, accountId) ?: return
        if (untilEpochMillis <= System.currentTimeMillis()) removeMute(key) else putMute(key, untilEpochMillis)
    }

    fun unmuteConversation(targetType: String, conversationId: String, accountId: String? = null) {
        val key = buildKey(targetType, conversationId, accountId) ?: return
        removeMute(key)
    }

    private fun putMute(key: String, until: Long?) = synchronized(prefs) {
        val muted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().toMutableSet()
        muted.add(key)
        val edit = prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, muted)
        if (until == null) edit.remove(KEY_MUTE_UNTIL_PREFIX + key) else edit.putLong(KEY_MUTE_UNTIL_PREFIX + key, until)
        edit.apply()
    }

    private fun removeMute(key: String) = synchronized(prefs) {
        val muted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().toMutableSet()
        muted.remove(key)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, muted).remove(KEY_MUTE_UNTIL_PREFIX + key).apply()
    }

    fun isMuted(targetType: String, conversationId: String?, accountId: String? = null): Boolean {
        val key = buildKey(targetType, conversationId ?: return false, accountId) ?: return false
        // A global legacy key has no trustworthy account identity. It cannot
        // silence another signed-in account after switching profiles.
        return synchronized(prefs) {
            if (key !in prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty()) false
            else {
                val until = prefs.getLong(KEY_MUTE_UNTIL_PREFIX + key, 0L)
                if (until > 0L && until <= System.currentTimeMillis()) { removeMute(key); false } else true
            }
        }
    }

    /** Emits on changes and the next actual expiry, without a polling timer. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeChanges(): Flow<Long> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_MUTED_CONVERSATIONS || key.startsWith(KEY_MUTE_UNTIL_PREFIX)) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED).flatMapLatest {
        flow {
            emit(System.nanoTime())
            while (true) {
                val now = System.currentTimeMillis()
                val next = prefs.all.entries.asSequence()
                    .filter { it.key.startsWith(KEY_MUTE_UNTIL_PREFIX) }
                    .mapNotNull { it.value as? Long }
                    .filter { it > now }.minOrNull() ?: break
                delay((next - now).coerceAtLeast(1L))
                emit(System.nanoTime())
            }
        }
    }

    private fun buildKey(targetType: String, conversationId: String, accountId: String? = null): String? {
        val cleanType = targetType.trim().lowercase().ifBlank { return null }
        val cleanId = conversationId.trim().lowercase().ifBlank { return null }
        return accountId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { "$it:$cleanType:$cleanId" } ?: "$cleanType:$cleanId"
    }
}




