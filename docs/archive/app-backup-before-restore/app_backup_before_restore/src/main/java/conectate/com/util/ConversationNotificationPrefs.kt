package conectate.com.util

import android.content.Context

class ConversationNotificationPrefs(context: Context) {
    companion object {
        private const val PREFS_NAME = "conversation_notification_prefs"
        private const val KEY_MUTED_CONVERSATIONS = "muted_conversations"
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

    fun isMuted(targetType: String, conversationId: String?): Boolean {
        var key = buildKey(targetType, conversationId ?: return false) ?: return false
        return prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()).orEmpty().contains(key)
    }

    private fun buildKey(targetType: String, conversationId: String): String? {
        val cleanType = targetType.trim().lowercase().ifBlank { return null }
        val cleanId = conversationId.trim().lowercase().ifBlank { return null }
        return "$cleanType:$cleanId"
    }
}




