package conectate.com.data.repository.message

import android.os.SystemClock
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisibleReadSynchronizer @Inject constructor(
    private val journal: VisibleReadJournal,
    private val api: LocalFallbackApiClient,
    private val auth: AuthService,
) {
    private val mutex = Mutex()
    private val failedAt = mutableMapOf<String, Long>()

    suspend fun flush(account: String, kind: String, token: String) = mutex.withLock {
        for (room in journal.pendingRooms(account, kind)) {
            if (auth.getCurrentUserId()?.trim()?.lowercase() != account.trim().lowercase()) return@withLock
            val key = "$account/$kind/$room"
            if (failedAt[key]?.let { SystemClock.elapsedRealtime() - it in 0 until 5_000 } == true) continue
            val ids = journal.pendingIds(account, kind, room).filter { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            for (batch in ids.chunked(200)) {
                if (auth.getCurrentUserId()?.trim()?.lowercase() != account.trim().lowercase()) return@withLock
                val result = api.markVisibleConversationMessagesAsRead(token, kind, room, batch)
                if (result.isFailure) { failedAt[key] = SystemClock.elapsedRealtime(); break }
                journal.acknowledge(account, kind, room, result.getOrThrow())
                failedAt.remove(key)
            }
        }
    }
}
