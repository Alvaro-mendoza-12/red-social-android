package conectate.com.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import conectate.com.data.model.CallHistoryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.callHistoryDataStore by preferencesDataStore(name = "call_history")

@Singleton
class CallHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entriesKey = stringPreferencesKey("entries_json")
    private val serializer = ListSerializer(CallHistoryEntry.serializer())

    val entries: Flow<List<CallHistoryEntry>> = context.callHistoryDataStore.data
        .map { prefs -> decodeEntries(prefs[entriesKey]) }
        .catch { error ->
            android.util.Log.e("CallHistoryRepository", "Unable to read call history", error)
            emit(emptyList())
        }

    suspend fun record(entry: CallHistoryEntry) {
        context.callHistoryDataStore.edit { prefs ->
            val current = decodeEntries(prefs[entriesKey]).toMutableList()
            current.removeAll { existing ->
                existing.callId.equals(entry.callId, ignoreCase = true) &&
                    existing.status == entry.status &&
                    kotlin.math.abs(existing.timestampEpochMs - entry.timestampEpochMs) < 2_000L
            }
            val updated = (listOf(entry) + current)
                .sortedByDescending { it.timestampEpochMs }
                .take(250)
            prefs[entriesKey] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun delete(entryId: String) {
        val cleanId = entryId.trim()
        if (cleanId.isBlank()) return
        context.callHistoryDataStore.edit { prefs ->
            val updated = decodeEntries(prefs[entriesKey])
                .filterNot { it.id.equals(cleanId, ignoreCase = true) }
            prefs[entriesKey] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun clearAll() {
        context.callHistoryDataStore.edit { prefs ->
            prefs[entriesKey] = json.encodeToString(serializer, emptyList())
        }
    }

    private fun decodeEntries(raw: String?): List<CallHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}


