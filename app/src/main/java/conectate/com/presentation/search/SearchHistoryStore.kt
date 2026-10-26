package conectate.com.presentation.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal fun searchHistoryAccountKey(accountId: String?): String? = accountId?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotBlank() }?.let { id ->
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

@Singleton
class SearchHistoryStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("search_history_by_account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(accountId: String?): List<String> {
        val key = searchHistoryAccountKey(accountId) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(prefs.getString(key, "[]") ?: "[]") }
            .getOrDefault(emptyList()).map(::searchTerm).filter { it.length >= 2 }.distinctBy(::searchTermKey).take(20)
    }

    fun write(accountId: String?, history: List<String>) {
        val key = searchHistoryAccountKey(accountId) ?: return
        if (history.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, json.encodeToString(history.take(20))).apply()
    }
}
