package conectate.com.data.cache

import android.content.SharedPreferences
import conectate.com.data.model.Post
import conectate.com.data.model.withPostReaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Existing account-scoped feed format; all calls run off the UI thread. */
internal class PostFeedCache(private val preferences: SharedPreferences) {
    internal data class ReadTicket(val owner: String, val revision: Long)
    private data class Edit(val reaction: String?, val revision: Long)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val edits = linkedMapOf<Pair<String, String>, Edit>()
    private var revision = 0L

    fun beginRead(owner: String?): ReadTicket = synchronized(lock) { ReadTicket(account(owner), revision) }

    fun read(mode: String, owner: String?): List<Post> = synchronized(lock) { readKey(key(mode, account(owner))) }

    /** A read begun before an ACK cannot put the old reaction back on disk. */
    fun persist(mode: String, posts: List<Post>, ticket: ReadTicket, currentOwner: String?): Boolean = synchronized(lock) {
        if (ticket.owner != account(currentOwner)) return false
        val reconciled = posts.take(60).map { post ->
            val edit = edits[ticket.owner to post.id.trim().lowercase()]
            if (edit != null && edit.revision > ticket.revision) post.withPostReaction(edit.reaction) else post
        }
        preferences.edit().putString(key(mode, ticket.owner), json.encodeToString(reconciled)).commit()
    }

    /** Called only after the server ACK, for the account that sent the mutation. */
    fun confirmReaction(owner: String, postId: String, reaction: String?, currentOwner: String?): Boolean = synchronized(lock) {
        val ownerKey = account(owner)
        if (ownerKey == "guest" || ownerKey != account(currentOwner)) return false
        val id = postId.trim().lowercase()
        if (id.isBlank()) return false
        edits[ownerKey to id] = Edit(reaction, ++revision)
        // Bounded alongside the two 60-post snapshots; no permanent per-post journal.
        while (edits.size > 512) edits.remove(edits.keys.first())
        val editor = preferences.edit()
        for (mode in listOf("discover", "following")) {
            val cacheKey = key(mode, ownerKey)
            val posts = readKey(cacheKey)
            if (posts.any { it.id.equals(id, ignoreCase = true) }) {
                editor.putString(cacheKey, json.encodeToString(posts.map {
                    if (it.id.equals(id, ignoreCase = true)) it.withPostReaction(reaction) else it
                }))
            }
        }
        editor.commit()
    }

    private fun readKey(key: String): List<Post> = runCatching {
        preferences.getString(key, null)?.let { json.decodeFromString<List<Post>>(it) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun key(mode: String, owner: String): String {
        val normalizedMode = mode.trim().lowercase().takeIf { it == "following" } ?: "discover"
        return "$normalizedMode:$owner"
    }
    private fun account(owner: String?) = owner?.trim()?.lowercase().orEmpty().ifBlank { "guest" }
}
