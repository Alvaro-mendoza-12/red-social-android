package conectate.com.data.repository.message

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSessionStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val VIEW_ONCE_PREFS = "view_once_guard"
        private const val KEY_CONSUMED_IDS = "consumed_ids_json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val viewOncePrefs by lazy {
        context.getSharedPreferences(VIEW_ONCE_PREFS, Context.MODE_PRIVATE)
    }

    private val viewOnceConsumedIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val locallyReadChatOverrides = ConcurrentHashMap<String, Long>()

    @Volatile
    private var activeChatId: String? = null

    @Volatile
    private var isActiveChatScreenVisible: Boolean = false

    @Volatile
    private var activeConversationType: String? = null

    init {
        runCatching {
            val raw = viewOncePrefs.getString(KEY_CONSUMED_IDS, null).orEmpty()
            if (raw.isNotBlank()) {
                val restored = json.decodeFromString<List<String>>(raw)
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                viewOnceConsumedIds.addAll(restored)
            }
        }
    }

    fun setActiveChatSession(chatId: String?, isVisible: Boolean) {
        setActiveConversation("chat", chatId, isVisible)
    }

    fun isActiveChatVisible(chatId: String): Boolean {
        return isActiveConversationVisible("chat", chatId)
    }

    fun setActiveConversation(type: String, conversationId: String?, isVisible: Boolean) {
        val cleanType = type.trim().lowercase()
        val cleanId = conversationId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (cleanId != null && isVisible) {
            activeConversationType = cleanType
            activeChatId = cleanId
            isActiveChatScreenVisible = true
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(cleanId.hashCode())
        } else if (activeConversationType == cleanType && (cleanId == null || activeChatId == cleanId)) {
            activeConversationType = null
            activeChatId = null
            isActiveChatScreenVisible = false
        }
    }

    fun isActiveConversationVisible(type: String, conversationId: String): Boolean {
        if (!isActiveChatScreenVisible) return false
        return activeConversationType == type.trim().lowercase() &&
            activeChatId == conversationId.trim().lowercase()
    }

    fun rememberLocalReadOverride(chatId: String) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isNotBlank()) {
            locallyReadChatOverrides[cleanId] = System.currentTimeMillis()
        }
    }

    fun clearLocalReadOverride(chatId: String) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isNotBlank()) {
            locallyReadChatOverrides.remove(cleanId)
        }
    }

    fun hasLocalReadOverride(chatId: String): Boolean {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return false
        return locallyReadChatOverrides.containsKey(cleanId)
    }

    fun isViewOnceConsumed(consumptionKey: String): Boolean {
        return viewOnceConsumedIds.contains(consumptionKey.trim().lowercase())
    }

    fun markViewOnceConsumed(consumptionKey: String) {
        val cleanKey = consumptionKey.trim().lowercase()
        if (cleanKey.isBlank()) return
        viewOnceConsumedIds.add(cleanKey)
        persistViewOnceConsumedIds()
    }

    fun clearAll() {
        locallyReadChatOverrides.clear()
        activeChatId = null
        activeConversationType = null
        isActiveChatScreenVisible = false
    }

    private fun persistViewOnceConsumedIds() {
        runCatching {
            val snapshot = viewOnceConsumedIds.toList()
            viewOncePrefs.edit()
                .putString(KEY_CONSUMED_IDS, json.encodeToString(snapshot))
                .apply()
        }
    }
}



