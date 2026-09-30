package conectate.com.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import conectate.com.data.model.CallHistoryEntry
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

private val Context.callHistoryDataStore by preferencesDataStore(name = "call_history")

@Singleton
class CallHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authService: AuthService
) {
    val entries: Flow<List<CallHistoryEntry>> = combine(
        authService.currentSession, context.callHistoryDataStore.data
    ) { _, prefs -> CallHistoryAccountStorage.read(prefs, authService.getCurrentUserId()) }
        .catch { error ->
            android.util.Log.e("CallHistoryRepository", "Unable to read call history", error)
            emit(emptyList())
        }

    suspend fun record(entry: CallHistoryEntry, ownerId: String? = authService.getCurrentUserId()) {
        val owner = ownerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        context.callHistoryDataStore.edit { prefs ->
            val current = CallHistoryAccountStorage.read(prefs, owner).toMutableList()
            current.removeAll { existing ->
                existing.callId.equals(entry.callId, ignoreCase = true) &&
                    existing.status == entry.status &&
                    kotlin.math.abs(existing.timestampEpochMs - entry.timestampEpochMs) < 2_000L
            }
            val updated = (listOf(entry) + current)
                .sortedByDescending { it.timestampEpochMs }
                .take(250)
            CallHistoryAccountStorage.write(prefs, owner, updated)
        }
    }

    suspend fun delete(entryId: String) {
        val cleanId = entryId.trim()
        if (cleanId.isBlank()) return
        val owner = authService.getCurrentUserId() ?: return
        context.callHistoryDataStore.edit { prefs ->
            val updated = CallHistoryAccountStorage.read(prefs, owner)
                .filterNot { it.id.equals(cleanId, ignoreCase = true) }
            CallHistoryAccountStorage.write(prefs, owner, updated)
        }
    }

    suspend fun clearAll() {
        val owner = authService.getCurrentUserId() ?: return
        context.callHistoryDataStore.edit { prefs ->
            CallHistoryAccountStorage.write(prefs, owner, emptyList())
        }
    }

}
