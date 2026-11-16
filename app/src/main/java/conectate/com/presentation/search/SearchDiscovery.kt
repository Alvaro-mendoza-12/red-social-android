package conectate.com.presentation.search

import conectate.com.data.model.SemanticSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.util.Locale

private val whitespacePattern = Regex("\\s+")
private val combiningMarkPattern = Regex("\\p{M}+")
private val hashtagPattern = Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]{2,48})")

internal fun searchInput(value: String): String = value.take(120)
    .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

internal fun searchTerm(value: String): String = searchInput(value.trim().replace(whitespacePattern, " "))

internal fun searchTermKey(value: String): String = Normalizer.normalize(searchTerm(value), Normalizer.Form.NFD)
    .replace(combiningMarkPattern, "").lowercase(Locale.ROOT)

internal fun updatedSearchHistory(history: List<String>, query: String): List<String> {
    val clean = searchTerm(query)
    if (clean.length < 2) return history
    return (listOf(clean) + history).filter { it.length >= 2 }.distinctBy(::searchTermKey).take(20)
}

internal data class SearchSuggestion(val query: String, val label: String)
internal data class SearchHashtag(val tag: String, val resultCount: Int)
internal data class SearchDiscovery(
    val suggestions: List<SearchSuggestion> = emptyList(),
    val hashtags: List<SearchHashtag> = emptyList(),
)

/** Counts only tags in the content returned to this account. */
internal fun searchHashtags(content: List<SemanticSearchResult>): List<SearchHashtag> {
    val counts = linkedMapOf<String, SearchHashtag>()
    val seenInResult = hashSetOf<String>()
    for (result in content) {
        seenInResult.clear()
        for (match in hashtagPattern.findAll("${result.title} ${result.excerpt}")) {
            val tag = match.groupValues[1]
            val key = searchTermKey(tag)
            if (seenInResult.add(key)) {
                counts[key] = SearchHashtag(tag, (counts[key]?.resultCount ?: 0) + 1)
            }
        }
    }
    return counts.values.sortedByDescending { it.resultCount }
}

private class SearchSuggestionCandidate(val suggestion: SearchSuggestion) {
    val key = searchTermKey(suggestion.query)
    val term = key.removePrefix("#")
}

private class SearchContentDiscovery(content: List<SemanticSearchResult>) {
    val hashtags = searchHashtags(content)
    val candidates = buildList {
        for (hashtag in hashtags) {
            add(SearchSuggestionCandidate(SearchSuggestion("#${hashtag.tag}", "Etiqueta en resultados")))
        }
        for (result in content) {
            val title = searchTerm(result.title)
            if (title.length in 3..72 && !title.contains('@') && !title.contains("http", ignoreCase = true)) {
                val kind = if (result.type == "video") "Video" else "Publicación"
                add(SearchSuggestionCandidate(SearchSuggestion(title, "$kind relacionado")))
            }
        }
    }
}

internal fun observeSearchDiscovery(
    query: Flow<String>,
    content: Flow<List<SemanticSearchResult>>,
    history: Flow<List<String>>,
): Flow<SearchDiscovery> = combine(
    query,
    content.map(::SearchContentDiscovery),
    history.map { terms -> terms.map { SearchSuggestionCandidate(SearchSuggestion(it, "Tu búsqueda reciente")) } },
) { rawQuery, contentDiscovery, historyCandidates ->
    val term = searchTermKey(rawQuery).removePrefix("#")
    val suggestions = if (term.length < 2) emptyList() else {
        (contentDiscovery.candidates.asSequence() + historyCandidates.asSequence().filter { term in it.key })
            .filter { it.term != term }
            .distinctBy { it.key }
            .sortedByDescending { it.term.startsWith(term) }
            .take(6)
            .map { it.suggestion }
            .toList()
    }
    SearchDiscovery(suggestions, contentDiscovery.hashtags)
}
