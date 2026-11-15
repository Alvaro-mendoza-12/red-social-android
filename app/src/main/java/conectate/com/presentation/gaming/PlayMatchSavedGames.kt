package conectate.com.presentation.gaming

import android.content.Context
import conectate.com.data.model.PlayMatchGameCatalogItem
import org.json.JSONArray
import org.json.JSONObject

internal data class PlayMatchSavedGame(val id: String, val name: String, val platforms: List<String>)

/** Saved catalog shortcuts stay on this device and are scoped to a PlayMatch account. */
internal class PlayMatchSavedGames(context: Context, private val accountId: String) {
    private val preferences = context.applicationContext.getSharedPreferences("playmatch_saved_games", Context.MODE_PRIVATE)

    fun read(): List<PlayMatchSavedGame> = runCatching {
        val rows = JSONArray(preferences.getString(accountId, null) ?: "[]")
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val platforms = row.getJSONArray("platforms")
            PlayMatchSavedGame(row.getString("id"), row.getString("name"),
                List(platforms.length()) { platform -> platforms.getString(platform) })
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun toggle(game: PlayMatchGameCatalogItem, current: List<PlayMatchSavedGame>): List<PlayMatchSavedGame> {
        if (game.id.isBlank() || game.name.isBlank()) return current
        val next = if (current.any { it.id == game.id }) current.filterNot { it.id == game.id }
        else listOf(PlayMatchSavedGame(game.id, game.name, game.platforms)) + current
        return write(next)
    }

    fun remove(id: String, current: List<PlayMatchSavedGame>) = write(current.filterNot { it.id == id })

    private fun write(games: List<PlayMatchSavedGame>): List<PlayMatchSavedGame> {
        val rows = JSONArray()
        games.forEach { game ->
            rows.put(JSONObject().put("id", game.id).put("name", game.name).put("platforms", JSONArray(game.platforms)))
        }
        preferences.edit().putString(accountId, rows.toString()).apply()
        return games
    }
}
