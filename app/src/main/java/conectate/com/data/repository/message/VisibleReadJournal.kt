package conectate.com.data.repository.message

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Durable receipt outbox, separate from the replaceable cache used by the active account.
 * Stores identifiers only, never message contents or session tokens. Call from Dispatchers.IO.
 */
@Singleton
class VisibleReadJournal @Inject constructor(@ApplicationContext context: Context) {
    internal fun close() = helper.close()
    private val helper = object : SQLiteOpenHelper(context, "visible_read_receipts.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE receipts(account TEXT NOT NULL, kind TEXT NOT NULL, room TEXT NOT NULL, message TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(account,kind,room,message))")
            db.execSQL("CREATE INDEX pending_receipts ON receipts(account,kind,pending,room)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Synchronized
    fun record(account: String, kind: String, room: String, ids: Collection<String>): Set<String> {
        val scope = scope(account, kind, room)
        val added = mutableSetOf<String>()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().forEach { id ->
                val values = ContentValues().apply {
                    put("account", scope[0]); put("kind", scope[1]); put("room", scope[2]); put("message", id)
                }
                if (db.insertWithOnConflict("receipts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added.add(id)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    fun seenIds(account: String, kind: String, room: String): Set<String> =
        queryIds("SELECT message FROM receipts WHERE account=? AND kind=? AND room=?", scope(account, kind, room))

    fun pendingIds(account: String, kind: String, room: String): Set<String> =
        queryIds("SELECT message FROM receipts WHERE account=? AND kind=? AND room=? AND pending=1 LIMIT 1000", scope(account, kind, room))

    fun pendingRooms(account: String, kind: String): Set<String> =
        queryIds("SELECT DISTINCT room FROM receipts WHERE account=? AND kind=? AND pending=1 LIMIT 20", arrayOf(account.trim().lowercase(), kind))

    fun acknowledge(account: String, kind: String, room: String, ids: Collection<String>) {
        val scope = scope(account, kind, room)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id -> db.execSQL("UPDATE receipts SET pending=0 WHERE account=? AND kind=? AND room=? AND message=?", scope + id.trim().lowercase()) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun resolveUnread(account: String, kind: String, room: String, remoteCount: Int, remoteUnreadIds: List<String>?): Int {
        if (remoteUnreadIds.isNullOrEmpty() || remoteCount <= 0) return remoteCount.coerceAtLeast(0)
        val seen = seenIds(account, kind, room)
        return (remoteCount - remoteUnreadIds.map { it.trim().lowercase() }.distinct().count { it in seen }).coerceAtLeast(0)
    }

    private fun queryIds(sql: String, args: Array<String>): Set<String> = helper.readableDatabase.rawQuery(sql, args).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun scope(account: String, kind: String, room: String): Array<String> {
        require(account.isNotBlank() && room.isNotBlank() && kind in setOf("chat", "group", "community"))
        return arrayOf(account.trim().lowercase(), kind, room.trim().lowercase())
    }
}
