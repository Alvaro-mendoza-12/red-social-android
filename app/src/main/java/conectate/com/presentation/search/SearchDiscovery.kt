package conectate.com.presentation.search

import conectate.com.data.model.SemanticSearchResult
import java.text.Normalizer
import java.util.Locale

internal fun searchInput(value: String): String = value.take(120)
    .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

internal fun searchTerm(value: String): String = searchInput(value.trim().replace(Regex("\\s+"), " "))

internal fun searchTermKey(value: String): String = Normalizer.normalize(searchTerm(value), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

internal fun updatedSearchHistory(history: List<String>, query: String): List<String> {
    val clean = searchTerm(query)
    if (clean.length < 2) return history
    return (listOf(clean) + history).filter { it.length >= 2 }.distinctBy(::searchTermKey).take(20)
}

internal data class SearchSuggestion(val query: String, val label: String)
internal data class SearchHashtag(val tag: String, val resultCount: Int)
private val hashtagPattern = Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]{2,48})")

/** Counts only content actually returned to this account; these are never global trend counts. */
internal fun searchHashtags(content: List<SemanticSearchResult>): List<SearchHashtag> {
    val counts = linkedMapOf<String, Pair<String, Int>>()
    content.forEach { result ->
        hashtagPattern.findAll("${result.title} ${result.excerpt}")
            .map { it.groupValues[1] }.distinctBy(::searchTermKey).forEach { tag ->
                val key = searchTermKey(tag)
                counts[key] = tag to ((counts[key]?.second ?: 0) + 1)
            }
    }
    return counts.values.sortedByDescending { it.second }.map { SearchHashtag(it.first, it.second) }
}

/** Suggestions are metadata of actual results or this account's own searches, never a fixed taxonomy. */
internal fun relatedSearchSuggestions(
    rawQuery: String,
    content: List<SemanticSearchResult>,
    history: List<String>,
): List<SearchSuggestion> {
    val query = searchTermKey(rawQuery).removePrefix("#")
    if (query.length < 2) return emptyList()
    val candidates = mutableListOf<SearchSuggestion>()
    searchHashtags(content).forEach { candidates += SearchSuggestion("#${it.tag}", "Etiqueta en resultados") }
    content.forEach { result ->
        val title = searchTerm(result.title)
        if (title.length in 3..72 && !title.contains('@') && !title.contains("http", ignoreCase = true)) {
            candidates += SearchSuggestion(title, "${if (result.type == "video") "Video" else "Publicación"} relacionado")
        }
    }
    history.filter { searchTermKey(it).contains(query) }.forEach { candidates += SearchSuggestion(it, "Tu búsqueda reciente") }
    return candidates.filter { searchTermKey(it.query).removePrefix("#") != query }
        .distinctBy { searchTermKey(it.query) }
        .sortedByDescending { searchTermKey(it.query).removePrefix("#").startsWith(query) }
        .take(6)
}
