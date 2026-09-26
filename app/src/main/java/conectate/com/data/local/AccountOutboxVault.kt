package conectate.com.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import conectate.com.core.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Keeps only unfinished messaging work when the shared Room cache changes account. */
@Singleton
class AccountOutboxVault private constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val encrypt: suspend (String) -> String,
    private val decrypt: suspend (String) -> String
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: AppDatabase,
        encryptionManager: EncryptionManager
    ) : this(
        context,
        database,
        { encryptionManager.encrypt(it).getOrThrow() },
        { encryptionManager.decrypt(it).getOrThrow() }
    )

    internal constructor(context: Context, database: AppDatabase, cipher: TestCipher) : this(
        context, database, { cipher.encrypt(it) }, { cipher.decrypt(it) }
    )

    internal interface TestCipher {
        fun encrypt(value: String): String
        fun decrypt(value: String): String
    }

    private val mutex = Mutex()
    private val ownerPrefs = context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AccountOutboxVault"
        private const val VERSION = 1
        private const val OWNER_HASH = "active_database_owner"
        private val TABLES = listOf(
            "chats", "groups", "communities", "group_members", "community_members",
            "messages", "group_messages", "community_messages", "message_deletions"
        )
        private val KEYS = mapOf(
            "chats" to listOf("id"),
            "groups" to listOf("id"),
            "communities" to listOf("id"),
            "group_members" to listOf("group_id", "user_id"),
            "community_members" to listOf("community_id", "user_id"),
            "messages" to listOf("id"),
            "group_messages" to listOf("id"),
            "community_messages" to listOf("id"),
            "message_deletions" to listOf("message_id", "actor_id", "for_everyone")
        )
    }

    suspend fun requireCompatibleAccount(accountId: String) = withContext(Dispatchers.IO) {
        val owner = accountId.trim().lowercase()
        require(owner.isNotBlank()) { "Falta la identidad de la cuenta" }
        mutex.withLock {
            val db = database.openHelper.writableDatabase
            val activeOwner = activeOwnerOrClearIdleCache(db, hash(owner))
            check(activeOwner == null || activeOwner == hash(owner)) {
                "No se puede abrir otra cuenta mientras haya mensajes locales pendientes"
            }
            check(activeOwner != null || !hasForeignPendingWork(db, owner)) {
                "Hay mensajes locales pendientes de otra cuenta"
            }
        }
    }

    /** A failed archive aborts the cache wipe; a failed wipe never removes the archive. */
    suspend fun preserveAndClear(accountId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owner = accountId?.trim()?.lowercase().orEmpty()
            val db = database.openHelper.writableDatabase
            if (owner.isBlank() && hasPendingWork(db)) {
                error("No se puede borrar la bandeja de salida sin identificar su cuenta")
            }
            val activeOwner = activeOwnerOrClearIdleCache(db, owner.takeIf(String::isNotBlank)?.let(::hash))
            if (owner.isNotBlank() && activeOwner != null && activeOwner != hash(owner)) {
                error("La caché local pertenece a otra cuenta")
            }
            if (owner.isNotBlank()) {
                val current = capture(db, owner)
                val previous = read(owner)
                val merged = merge(previous, current)
                if (hasPendingRows(merged)) write(owner, merged)
                // Do not leave an owner marker behind if encryption/archive fails.
                check(ownerPrefs.edit().putString(OWNER_HASH, hash(owner)).commit()) {
                    "No se pudo registrar el propietario de la caché local"
                }
            }
            database.clearAllTables()
            check(ownerPrefs.edit().remove(OWNER_HASH).commit()) {
                "No se pudo actualizar el propietario de la caché local"
            }
        }
    }

    /** Safe to retry after process death: existing rows win over archived copies. */
    suspend fun restore(accountId: String) = withContext(Dispatchers.IO) {
        val owner = accountId.trim().lowercase()
        require(owner.isNotBlank()) { "Falta la identidad de la cuenta" }
        mutex.withLock {
            val db = database.openHelper.writableDatabase
            val activeOwner = activeOwnerOrClearIdleCache(db, hash(owner))
            check(activeOwner == null || activeOwner == hash(owner)) {
                "La caché local pertenece a otra cuenta"
            }
            check(activeOwner != null || !hasForeignPendingWork(db, owner)) {
                "Hay mensajes locales pendientes de otra cuenta"
            }
            check(ownerPrefs.edit().putString(OWNER_HASH, hash(owner)).commit()) {
                "No se pudo registrar el propietario de la caché local"
            }
            val snapshot = read(owner)
            if (snapshot != null) {
                val deletedMessageIds = ids(snapshot.getJSONArray("message_deletions"), "message_id")
                database.withTransaction {
                    val db = database.openHelper.writableDatabase
                    for (table in TABLES) {
                        val rows = snapshot.optJSONArray(table) ?: continue
                        for (index in 0 until rows.length()) {
                            val row = rows.getJSONObject(index)
                            if (table == "group_messages" || table == "community_messages" ||
                                (table == "messages" && row.getString("id") !in deletedMessageIds)) {
                                check(row.getString("sender_id").trim().equals(owner, ignoreCase = true)) {
                                    "Mensaje pendiente de otra cuenta"
                                }
                            }
                            if (table == "message_deletions") {
                                check(row.getString("actor_id").trim().equals(owner, ignoreCase = true)) {
                                    "Eliminación pendiente de otra cuenta"
                                }
                            }
                            db.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values(row))
                        }
                    }
                }
                val archive = file(owner)
                archive.delete()
                check(!archive.baseFile.exists()) { "No se pudo cerrar el archivo de mensajes pendientes" }
                Log.i(TAG, "Restored account-bound pending messaging work")
            }
        }
    }

    private fun capture(db: SupportSQLiteDatabase, owner: String): JSONObject {
        val pendingDirect = rows(db,
            "SELECT * FROM messages WHERE LOWER(TRIM(sender_id)) = ? AND (" +
                "(is_synced = 0 AND pending_action = 'send') OR pending_action IN ('edit','delete'))",
            owner
        )
        val deletions = rows(db,
            "SELECT * FROM message_deletions WHERE LOWER(TRIM(actor_id)) = ? AND state = 'pending'", owner)
        for (index in 0 until deletions.length()) {
            val messageId = deletions.getJSONObject(index).getString("message_id")
            appendMissing(pendingDirect, rows(db, "SELECT * FROM messages WHERE id = ?", messageId), "id")
        }
        val pendingGroups = rows(db,
            "SELECT * FROM group_messages WHERE LOWER(TRIM(sender_id)) = ? " +
                "AND is_synced = 0 AND pending_action = 'send'", owner)
        val pendingCommunities = rows(db,
            "SELECT * FROM community_messages WHERE LOWER(TRIM(sender_id)) = ? " +
                "AND is_synced = 0 AND pending_action = 'send'", owner)

        val chats = JSONArray()
        val groups = JSONArray()
        val communities = JSONArray()
        val groupMembers = JSONArray()
        val communityMembers = JSONArray()
        for (id in ids(pendingDirect, "chat_id") + ids(deletions, "chat_id")) {
            appendMissing(chats, rows(db, "SELECT * FROM chats WHERE id = ?", id), "id")
        }
        for (id in ids(pendingGroups, "group_id")) {
            appendMissing(groups, rows(db, "SELECT * FROM groups WHERE id = ?", id), "id")
            appendMissing(groupMembers, rows(db,
                "SELECT * FROM group_members WHERE group_id = ? AND LOWER(TRIM(user_id)) = ?", id, owner),
                "group_id", "user_id")
        }
        for (id in ids(pendingCommunities, "community_id")) {
            appendMissing(communities, rows(db, "SELECT * FROM communities WHERE id = ?", id), "id")
            appendMissing(communityMembers, rows(db,
                "SELECT * FROM community_members WHERE community_id = ? AND LOWER(TRIM(user_id)) = ?", id, owner),
                "community_id", "user_id")
        }
        return JSONObject().put("version", VERSION).put("owner", owner)
            .put("chats", chats).put("groups", groups).put("communities", communities)
            .put("group_members", groupMembers).put("community_members", communityMembers)
            .put("messages", pendingDirect).put("group_messages", pendingGroups)
            .put("community_messages", pendingCommunities).put("message_deletions", deletions)
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String, vararg args: String): JSONArray {
        val result = JSONArray()
        db.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            while (cursor.moveToNext()) result.put(row(cursor))
        }
        return result
    }

    private fun row(cursor: Cursor): JSONObject = JSONObject().apply {
        for (column in 0 until cursor.columnCount) {
            val value: Any = when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                else -> error("No se puede archivar una columna binaria de mensajes")
            }
            put(cursor.getColumnName(column), value)
        }
    }

    private fun values(row: JSONObject): ContentValues = ContentValues().apply {
        val names = row.keys()
        while (names.hasNext()) {
            val key = names.next()
            when (val value = row.get(key)) {
                JSONObject.NULL -> putNull(key)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is String -> put(key, value)
                else -> error("Tipo no soportado en el archivo de mensajes")
            }
        }
    }

    private fun ids(rows: JSONArray, column: String): Set<String> = buildSet {
        for (index in 0 until rows.length()) add(rows.getJSONObject(index).getString(column))
    }

    private fun appendMissing(target: JSONArray, source: JSONArray, vararg keys: String) {
        val seen = buildSet {
            for (index in 0 until target.length()) {
                val row = target.getJSONObject(index)
                add(keys.joinToString("\u0000") { row.get(it).toString() })
            }
        }
        for (index in 0 until source.length()) {
            val row = source.getJSONObject(index)
            if (keys.joinToString("\u0000") { row.get(it).toString() } !in seen) target.put(row)
        }
    }

    private fun merge(old: JSONObject?, fresh: JSONObject): JSONObject {
        if (old == null) return fresh
        for (table in TABLES) {
            val merged = JSONArray()
            val key = KEYS.getValue(table).toTypedArray()
            appendMissing(merged, fresh.getJSONArray(table), *key)
            appendMissing(merged, old.getJSONArray(table), *key)
            fresh.put(table, merged)
        }
        return fresh
    }

    private fun hasPendingRows(snapshot: JSONObject): Boolean = listOf(
        "messages", "group_messages", "community_messages", "message_deletions"
    ).any { snapshot.getJSONArray(it).length() > 0 }

    /** A completed outbox must not prevent a later login if logout cleanup was interrupted. */
    private fun activeOwnerOrClearIdleCache(
        db: SupportSQLiteDatabase,
        expectedOwnerHash: String?,
    ): String? {
        val activeOwner = ownerPrefs.getString(OWNER_HASH, null) ?: return null
        if (activeOwner == expectedOwnerHash) return activeOwner
        if (hasPendingWork(db)) return activeOwner
        // The old account's server-backed rows may still be here after an
        // interrupted logout. Clear them before accepting a different account.
        database.clearAllTables()
        check(ownerPrefs.edit().remove(OWNER_HASH).commit()) {
            "No se pudo reparar la caché local de la cuenta anterior"
        }
        Log.i(TAG, "Cleared idle account cache and stale owner marker")
        return null
    }

    private fun hasPendingWork(db: SupportSQLiteDatabase): Boolean = listOf(
        "SELECT 1 FROM messages WHERE (is_synced = 0 AND pending_action = 'send') " +
            "OR pending_action IN ('edit','delete') LIMIT 1",
        "SELECT 1 FROM group_messages WHERE is_synced = 0 AND pending_action = 'send' LIMIT 1",
        "SELECT 1 FROM community_messages WHERE is_synced = 0 AND pending_action = 'send' LIMIT 1",
        "SELECT 1 FROM message_deletions WHERE state = 'pending' LIMIT 1"
    ).any { db.query(it).use { cursor -> cursor.moveToFirst() } }

    private fun hasForeignPendingWork(db: SupportSQLiteDatabase, owner: String): Boolean = listOf(
        "SELECT 1 FROM messages WHERE ((is_synced = 0 AND pending_action = 'send') " +
            "OR pending_action IN ('edit','delete')) AND LOWER(TRIM(sender_id)) != ? LIMIT 1",
        "SELECT 1 FROM group_messages WHERE is_synced = 0 AND pending_action = 'send' " +
            "AND LOWER(TRIM(sender_id)) != ? LIMIT 1",
        "SELECT 1 FROM community_messages WHERE is_synced = 0 AND pending_action = 'send' " +
            "AND LOWER(TRIM(sender_id)) != ? LIMIT 1",
        "SELECT 1 FROM message_deletions WHERE state = 'pending' " +
            "AND LOWER(TRIM(actor_id)) != ? LIMIT 1"
    ).any { sql -> db.query(SimpleSQLiteQuery(sql, arrayOf(owner))).use { it.moveToFirst() } }

    private suspend fun read(owner: String): JSONObject? {
        val archive = file(owner)
        val ciphertext = try {
            archive.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return null
        }
        val snapshot = JSONObject(decrypt(ciphertext))
        check(snapshot.getInt("version") == VERSION && snapshot.getString("owner") == owner) {
            "El archivo de mensajes no corresponde a esta cuenta o versión"
        }
        for (table in TABLES) snapshot.getJSONArray(table)
        return snapshot
    }

    private suspend fun write(owner: String, snapshot: JSONObject) {
        val archive = file(owner)
        val ciphertext = encrypt(snapshot.toString()).toByteArray(Charsets.UTF_8)
        val output = archive.startWrite()
        try {
            output.write(ciphertext)
            archive.finishWrite(output)
        } catch (error: Exception) {
            archive.failWrite(output)
            throw error
        }
    }

    private fun file(owner: String): AtomicFile {
        val directory = File(context.noBackupFilesDir, "account_outbox")
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear el archivo privado de mensajes" }
        return AtomicFile(File(directory, "${hash(owner)}.vault"))
    }

    private fun hash(owner: String): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
