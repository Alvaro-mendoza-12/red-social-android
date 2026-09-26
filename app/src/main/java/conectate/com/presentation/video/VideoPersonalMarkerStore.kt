package conectate.com.presentation.video

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class VideoPersonalMarker(val positionMs: Long, val note: String)

/** Private, device-local tutorial steps. The old single-point JSON keeps its original note. */
internal class VideoPersonalMarkerStore(
    context: Context,
    accountId: String?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val account = accountId?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() && '|' !in it }
    private val preferences = context.applicationContext.getSharedPreferences(
        "video_personal_markers", Context.MODE_PRIVATE
    )

    private fun key(videoId: String): String? = account?.let { owner ->
        videoId.trim().takeIf(String::isNotEmpty)?.let { "$owner|$it" }
    }

    /** Kept for callers that need the most recently saved point. */
    fun read(videoId: String): VideoPersonalMarker? = key(videoId)?.let(::readStored)?.lastOrNull()

    fun readAll(videoId: String): List<VideoPersonalMarker> =
        key(videoId)?.let(::readStored).orEmpty().sortedBy { it.positionMs }

    /** Adds a point, or updates that exact point; a full video never silently loses older steps. */
    fun save(videoId: String, positionMs: Long, note: String): VideoPersonalMarker? = synchronized(lock) {
        val key = key(videoId) ?: return@synchronized null
        val marker = VideoPersonalMarker(positionMs.coerceAtLeast(0L), note.take(MAX_NOTE_LENGTH).trim())
        val markers = readStored(key).filterNot { it.positionMs == marker.positionMs }
        if (markers.size >= MAX_MARKERS_PER_VIDEO) return@synchronized null
        persist(key, markers + marker)
        marker
    }

    fun updateNote(videoId: String, positionMs: Long, note: String): Boolean = synchronized(lock) {
        val key = key(videoId) ?: return@synchronized false
        val markers = readStored(key)
        if (markers.none { it.positionMs == positionMs }) return@synchronized false
        persist(key, markers.map {
            if (it.positionMs == positionMs) it.copy(note = note.take(MAX_NOTE_LENGTH).trim()) else it
        })
        true
    }

    fun remove(videoId: String, positionMs: Long): Boolean = synchronized(lock) {
        val key = key(videoId) ?: return@synchronized false
        val markers = readStored(key)
        val remaining = markers.filterNot { it.positionMs == positionMs }
        if (remaining.size == markers.size) return@synchronized false
        if (remaining.isEmpty()) preferences.edit().remove(key).apply() else persist(key, remaining)
        true
    }

    fun remove(videoId: String) = synchronized(lock) {
        key(videoId)?.let { preferences.edit().remove(it).apply() }
    }

    private fun readStored(key: String): List<VideoPersonalMarker> = runCatching {
        val row = JSONObject(preferences.getString(key, null) ?: return emptyList())
        val points = row.optJSONArray("markers") ?: JSONArray().put(row)
        buildList<VideoPersonalMarker> {
            for (index in 0 until points.length()) {
                val point = points.optJSONObject(index) ?: continue
                val position = runCatching { point.getLong("position").coerceAtLeast(0L) }.getOrNull() ?: continue
                if (none { it.positionMs == position }) {
                    add(VideoPersonalMarker(position, point.optString("note").take(MAX_NOTE_LENGTH).trim()))
                }
                if (size >= MAX_MARKERS_PER_VIDEO) break
            }
        }
    }.getOrDefault(emptyList())

    private fun persist(key: String, markers: List<VideoPersonalMarker>) {
        val points = JSONArray()
        markers.forEach { points.put(JSONObject().put("position", it.positionMs).put("note", it.note)) }
        val row = JSONObject().put("version", 2).put("markers", points).put("updated", now())
        val editor = preferences.edit().putString(key, row.toString())
        // Retention applies only to this account; all points of each retained video stay together.
        preferences.all.filterKeys { it.startsWith("$account|") && it != key }
            .entries.sortedBy { entry ->
                runCatching { JSONObject(entry.value as String).optLong("updated") }.getOrDefault(0L)
            }.let { entries -> entries.take((entries.size - (MAX_VIDEOS_PER_ACCOUNT - 1)).coerceAtLeast(0)) }
            .forEach { editor.remove(it.key) }
        editor.apply()
    }

    companion object {
        const val MAX_MARKERS_PER_VIDEO = 20
        const val MAX_VIDEOS_PER_ACCOUNT = 200
        const val MAX_NOTE_LENGTH = 240
        private val lock = Any()
    }
}
