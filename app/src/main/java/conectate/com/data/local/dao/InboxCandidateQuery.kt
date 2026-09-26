package conectate.com.data.local.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Bounded UNION of indexed seeks; no correlated subquery or history-sized result. */
internal fun inboxCandidateQuery(chatIds: List<String>): SupportSQLiteQuery {
    require(chatIds.isNotEmpty() && chatIds.size <= 100)
    val seek = "SELECT * FROM (SELECT * FROM messages WHERE chat_id = ? ORDER BY created_at DESC, id DESC LIMIT 1)"
    return SimpleSQLiteQuery(chatIds.joinToString(" UNION ALL ") { seek }, chatIds.toTypedArray())
}
