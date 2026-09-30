package conectate.com.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import conectate.com.data.model.CallHistoryEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The old device-wide key stays archived: its records have no provable owner. */
internal object CallHistoryAccountStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(CallHistoryEntry.serializer())

    private fun key(ownerId: String?): Preferences.Key<String>? = ownerId
        ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?.let { stringPreferencesKey("entries_json:$it") }

    fun read(prefs: Preferences, ownerId: String?): List<CallHistoryEntry> {
        val raw = key(ownerId)?.let { prefs[it] } ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun write(prefs: MutablePreferences, ownerId: String?, entries: List<CallHistoryEntry>) {
        val accountKey = key(ownerId) ?: return
        prefs[accountKey] = json.encodeToString(serializer, entries)
    }
}
