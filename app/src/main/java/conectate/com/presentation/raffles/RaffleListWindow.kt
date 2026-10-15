package conectate.com.presentation.raffles

import java.text.Normalizer
import java.util.Locale

/** Display-only window: never used to choose, remove or draw participants. */
internal data class RaffleListWindow<T>(val items: List<T>, val matching: Int, val total: Int)

internal fun raffleDisplayLimit(value: String): Int? = when (value) {
    "all" -> null
    else -> (value.toIntOrNull() ?: 10).coerceIn(1, 1000)
}

private fun raffleSearchKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()

internal fun <T> raffleListWindow(source: List<T>, search: String, limit: Int?, name: (T) -> String): RaffleListWindow<T> {
    val query = raffleSearchKey(search.take(120))
    val matching = if (query.isEmpty()) source else source.filter { raffleSearchKey(name(it)).contains(query) }
    return RaffleListWindow(if (limit == null) matching else matching.take(limit.coerceIn(1, 1000)), matching.size, source.size)
}
