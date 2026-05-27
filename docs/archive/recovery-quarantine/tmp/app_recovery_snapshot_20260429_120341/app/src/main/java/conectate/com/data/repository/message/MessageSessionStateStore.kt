package conectate.com.data.repository.message

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class MessageSessionStateStore(
    private val context: Context
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
        var cleanId = chatId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        activeChatId = cleanId
        isActiveChatScreenVisible = cleanId != null && isVisible
    }

    fun isActiveChatVisible(chatId: String): Boolean {
        if (!isActiveChatScreenVisible) return false
        var active = activeChatId ?: return false
        return active == chatId.trim().lowercase()
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



