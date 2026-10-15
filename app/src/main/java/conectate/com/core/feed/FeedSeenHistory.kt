package conectate.com.core.feed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import conectate.com.core.concurrency.newSafeScope
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Visible IDs and their recency are isolated by account and surface. */
@Singleton
class FeedSeenHistory @Inject constructor(@ApplicationContext context: Context) {
    private val database = object : SQLiteOpenHelper(context, "feed_seen_history.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE seen(account TEXT NOT NULL,surface TEXT NOT NULL,id TEXT NOT NULL,last_seen_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,surface,id)) WITHOUT ROWID")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE seen ADD COLUMN last_seen_at INTEGER NOT NULL DEFAULT 0")
        }
    }
    private val scope = newSafeScope("FeedSeenHistory", Dispatchers.IO)
    private val pending = mutableMapOf<Pair<String, String>, MutableMap<String, Long>>()
    private val recorded = mutableMapOf<Pair<String, String>, MutableMap<String, Long>>()
    private val lock = Any()

    suspend fun snapshot(accountId: String?, surface: String): Set<String> = withContext(Dispatchers.IO) {
        val account = canonicalFeedId(accountId) ?: return@withContext emptySet()
        require(surface == "video" || surface == "social")
        val result = mutableSetOf<String>()
        database.readableDatabase.query("seen", arrayOf("id"), "account=? AND surface=?", arrayOf(account, surface), null, null, null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        synchronized(lock) { result.addAll(pending[account to surface].orEmpty().keys) }
        result
    }

    /** Most recent first; bounded independently of the permanent seen-ID set. */
    suspend fun recent(accountId: String?, surface: String, limit: Int = 256): List<String> = withContext(Dispatchers.IO) {
        val account = canonicalFeedId(accountId) ?: return@withContext emptyList()
        require(surface == "video" || surface == "social")
        val boundedLimit = limit.coerceIn(1, 512)
        val samples = mutableMapOf<String, Long>()
        database.readableDatabase.query("seen", arrayOf("id", "last_seen_at"), "account=? AND surface=?", arrayOf(account, surface), null, null,
            "last_seen_at DESC,id ASC", boundedLimit.toString()).use { cursor ->
            while (cursor.moveToNext()) samples[cursor.getString(0)] = cursor.getLong(1)
        }
        synchronized(lock) {
            pending[account to surface].orEmpty().forEach { (id, at) ->
                samples[id] = maxOf(samples[id] ?: 0L, at)
            }
        }
        samples.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(boundedLimit).map { it.key }
    }

    fun record(accountId: String?, surface: String, contentId: String?): Job? =
        recordAt(accountId, surface, contentId, System.currentTimeMillis())

    internal fun recordAt(accountId: String?, surface: String, contentId: String?, seenAtMs: Long): Job? {
        val account = canonicalFeedId(accountId) ?: return null
        val id = canonicalFeedId(contentId) ?: return null
        if (seenAtMs < 0L) return null
        require(surface == "video" || surface == "social")
        val key = account to surface
        synchronized(lock) {
            val last = recorded[key]?.get(id)
            // Playback samples must not write SQLite for every rendered frame.
            if (last != null && seenAtMs - last in 0L until 30_000L) return null
            recorded.getOrPut(key) { mutableMapOf() }[id] = seenAtMs
            pending.getOrPut(key) { mutableMapOf() }[id] = seenAtMs
        }
        return scope.launch {
            var persisted = false
            try {
                // INSERT OR REPLACE also works on the SQLite shipped with API 24.
                database.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO seen(account,surface,id,last_seen_at) VALUES(?,?,?,MAX(?,COALESCE((SELECT last_seen_at FROM seen WHERE account=? AND surface=? AND id=?),0)))",
                    arrayOf(account, surface, id, seenAtMs, account, surface, id),
                )
                persisted = true
            } catch (_: android.database.sqlite.SQLiteException) {
                synchronized(lock) {
                    if (recorded[key]?.get(id) == seenAtMs) recorded[key]?.remove(id)
                }
            } finally {
                if (persisted) synchronized(lock) {
                    pending[key]?.let {
                        if (it[id] == seenAtMs) it.remove(id)
                        if (it.isEmpty()) pending.remove(key)
                    }
                }
            }
        }
    }

    internal fun closeForTest() { database.close() }
}

internal fun canonicalFeedId(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotBlank() && it.length <= 128 }

/** The snapshot belongs to the previous visit. Current visible items must not disappear. */
internal fun <T> unseenFeedItems(items: Iterable<T>, seen: Set<String>, id: (T) -> String): List<T> =
    items.distinctBy { canonicalFeedId(id(it)) }.filter { canonicalFeedId(id(it))?.let { key -> key !in seen } == true }
