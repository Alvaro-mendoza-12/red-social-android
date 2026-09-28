package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Only controls the novelty badge. Viewing a request does not accept or reject it. */
@Singleton
class SeenFriendRequestsPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("seen_friend_requests", Context.MODE_PRIVATE)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun unseenCount(accountId: String, pendingIds: Collection<String>): Int {
        if (accountId.isBlank()) return 0
        val seen = prefs.getStringSet(key(accountId), emptySet()).orEmpty()
        return pendingIds.distinct().count { it !in seen }
    }

    fun markSeen(accountId: String, pendingIds: Collection<String>) {
        if (accountId.isBlank()) return
        val current = pendingIds.filter(String::isNotBlank).toSet()
        val storageKey = key(accountId)
        if (prefs.getStringSet(storageKey, emptySet()).orEmpty() == current) return
        // Keep only currently pending IDs so old requests do not accumulate forever.
        if (prefs.edit().putStringSet(storageKey, current).commit()) {
            _revision.value += 1
        }
    }

    private fun key(accountId: String) = "seen_${accountId.trim().lowercase()}"
}
