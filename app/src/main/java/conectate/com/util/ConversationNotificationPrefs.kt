package conectate.com.util

import android.content.Context

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

    fun muteConversation(targetType: String, conversationId: String) {
        var key = buildKey(targetType, conversationId) ?: return
        val muted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().toMutableSet()
        muted.add(key)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, muted).apply()
    }

    fun muteConversationUntil(targetType: String, conversationId: String, untilEpochMillis: Long) {
        val key = buildKey(targetType, conversationId) ?: return
        if (untilEpochMillis <= System.currentTimeMillis()) {
            unmuteConversation(targetType, conversationId)
            return
        }
        val muted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().toMutableSet()
        muted.add(key)
        prefs.edit()
            .putStringSet(KEY_MUTED_CONVERSATIONS, muted)
            .putLong(KEY_MUTE_UNTIL_PREFIX + key, untilEpochMillis)
            .apply()
    }

    fun unmuteConversation(targetType: String, conversationId: String) {
        val key = buildKey(targetType, conversationId) ?: return
        val muted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().toMutableSet()
        muted.remove(key)
        prefs.edit()
            .putStringSet(KEY_MUTED_CONVERSATIONS, muted)
            .remove(KEY_MUTE_UNTIL_PREFIX + key)
            .apply()
    }

    fun isMuted(targetType: String, conversationId: String?): Boolean {
        var key = buildKey(targetType, conversationId ?: return false) ?: return false
        val isMuted = prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().contains(key)
        if (!isMuted) return false
        val until = prefs.getLong(KEY_MUTE_UNTIL_PREFIX + key, 0L)
        if (until > 0L && until <= System.currentTimeMillis()) {
            unmuteConversation(targetType, conversationId)
            return false
        }
        return true
    }

    private fun buildKey(targetType: String, conversationId: String): String? {
        val cleanType = targetType.trim().lowercase().ifBlank { return null }
        val cleanId = conversationId.trim().lowercase().ifBlank { return null }
        return "$cleanType:$cleanId"
    }
}




