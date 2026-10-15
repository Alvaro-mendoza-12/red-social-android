package conectate.com.data.localfallback

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/** Account-scoped server grants survive process restart; a later join invalidates an old grant. */
internal class CollectionHistoryAccessStore(context: Context) {
    private val preferences = context.getSharedPreferences("collection_history_access_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(account: String, kind: String, room: String) =
        listOf(account, kind, room).joinToString(":") { it.trim().lowercase() }

    fun save(account: String, kind: String, room: String, access: CollectionHistoryAccess) {
        preferences.edit().putString(key(account, kind, room), json.encodeToString(access)).commit()
    }

    fun load(account: String, kind: String, room: String): CollectionHistoryAccess? =
        preferences.getString(key(account, kind, room), null)?.let {
            runCatching { json.decodeFromString<CollectionHistoryAccess>(it) }.getOrNull()
        }
}

internal fun resolveCachedCollectionHistory(
    cached: CollectionHistoryAccess?, joinedAt: String?, isCreator: Boolean, isMember: Boolean
): CollectionHistoryAccess {
    if (!isMember) return cached?.takeIf { !it.isMember && it.canReadHistory } ?: CollectionHistoryAccess()
    val localJoin = joinedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val cachedJoin = cached?.joinedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (cached != null && cachedJoin != null && (localJoin == null || !localJoin.isAfter(cachedJoin))) return cached
    if (isCreator) return CollectionHistoryAccess(isMember = true, allHistory = true)
    return CollectionHistoryAccess(isMember = true, joinedAt = joinedAt, retainedRanges = cached?.retainedRanges.orEmpty())
}
