package conectate.com.presentation.search

import conectate.com.data.model.SemanticSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDiscoveryTest {
    @Test fun historyIsBoundedRecentAndAccentInsensitiveWithoutSplittingUnicode() {
        var history = listOf("Música", "Casas modernas")
        history = updatedSearchHistory(history, "  MUSICA ")
        assertEquals(listOf("MUSICA", "Casas modernas"), history)
        repeat(30) { history = updatedSearchHistory(history, "Consulta $it") }
        assertEquals(20, history.size)
        assertEquals("Consulta 29", history.first())
        assertEquals("x".repeat(119), searchInput("x".repeat(119) + "😀"))
        assertEquals("dos palabras ", searchInput("dos palabras "))
    }

    @Test fun accountKeysNeverMixGuestsOrDifferentAccounts() {
        assertNull(searchHistoryAccountKey(null))
        assertNull(searchHistoryAccountKey("  "))
        assertEquals(searchHistoryAccountKey("USER-A"), searchHistoryAccountKey(" user-a "))
        assertNotEquals(searchHistoryAccountKey("user-a"), searchHistoryAccountKey("user-b"))
        assertFalse(searchHistoryAccountKey("user-a")!!.contains("user-a"))
    }

    @Test fun suggestionsRequireRealResultsOrOnlySuppliedAccountHistory() = runTest {
        assertTrue(observeSearchDiscovery(flowOf("constru"), flowOf(emptyList()), flowOf(emptyList())).first().suggestions.isEmpty())
        val content = listOf(SemanticSearchResult(id = "1", type = "video", title = "Construcción de casas",
            excerpt = "Aprende #Construcción y #casas"))
        val suggestions = observeSearchDiscovery(
            flowOf("constru"), flowOf(content), flowOf(listOf("Construcción moderna", "Receta de pan"))
        ).first().suggestions
        assertTrue(suggestions.any { it.query == "Construcción de casas" })
        assertTrue(suggestions.any { it.query == "#Construcción" })
        assertTrue(suggestions.any { it.query == "Construcción moderna" })
        assertFalse(suggestions.any { it.query == "Receta de pan" })
        assertEquals(1, searchHashtags(content).first { it.tag == "Construcción" }.resultCount)
    }

    @Test fun hashtagsAreCountedOncePerResultAndNeverInventGlobalMetrics() {
        val hashtags = searchHashtags(listOf(
            SemanticSearchResult(id = "1", title = "#Música #música", excerpt = "más #Música"),
            SemanticSearchResult(id = "2", title = "#musica", excerpt = "#jazz"),
        ))
        assertEquals(2, hashtags.first { searchTermKey(it.tag) == "musica" }.resultCount)
        assertEquals(1, hashtags.first { it.tag == "jazz" }.resultCount)
    }

    @Test fun ranksAccentInsensitiveMatchesAndKeepsTheFirstSuggestionForEachKey() = runTest {
        val content = listOf(
            SemanticSearchResult(type = "video", title = "Café de altura", excerpt = "#Café #Filtrado"),
            SemanticSearchResult(title = "Cafe de altura", excerpt = "#cafe"),
            SemanticSearchResult(title = "Guía de grano", excerpt = "#Grano"),
        )
        val discovery = observeSearchDiscovery(
            flowOf("#CAFÉ"),
            flowOf(content),
            flowOf(listOf("Café", "Cafetera", "Notas de café", "Viaje")),
        ).first()

        assertEquals(
            listOf("Café de altura", "Cafetera", "#Filtrado", "#Grano", "Guía de grano", "Notas de café"),
            discovery.suggestions.map { it.query },
        )
        assertEquals("Video relacionado", discovery.suggestions.first().label)
        assertEquals(listOf(SearchHashtag("cafe", 2), SearchHashtag("Filtrado", 1), SearchHashtag("Grano", 1)), discovery.hashtags)
    }

    @Test fun changingTheQueryOrHistoryDoesNotReadTheSameResultsAgain() = runTest {
        val page = CountingResults(listOf(
            SemanticSearchResult(title = "Café tostado", excerpt = "#Café"),
            SemanticSearchResult(title = "Grano molido", excerpt = "#Molido"),
        ))
        val content = MutableStateFlow<List<SemanticSearchResult>>(page)
        val query = MutableStateFlow("ca")
        val history = MutableStateFlow(listOf("Cafetera"))
        val states = mutableListOf<SearchDiscovery>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSearchDiscovery(query, content, history).collect { states += it }
        }
        runCurrent()
        assertTrue(states.last().suggestions.any { it.query == "Cafetera" })
        query.value = "cafe"
        runCurrent()
        assertTrue(states.last().suggestions.none { it.query == "#Café" })
        history.value = listOf("Café frío")
        runCurrent()
        assertTrue(states.last().suggestions.any { it.query == "Café frío" })
        assertEquals(4, page.reads)
    }

    @Test fun clearingResultsAndHistoryDropsTheirSuggestionsAndTags() = runTest {
        val content = MutableStateFlow(listOf(SemanticSearchResult(title = "Casa nueva", excerpt = "#Casa")))
        val query = MutableStateFlow("cas")
        val history = MutableStateFlow(listOf("Casas antiguas"))
        val states = mutableListOf<SearchDiscovery>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSearchDiscovery(query, content, history).collect { states += it }
        }
        runCurrent()
        assertTrue(states.last().suggestions.any { it.query == "Casas antiguas" })
        assertTrue(states.last().hashtags.any { it.tag == "Casa" })
        content.value = emptyList()
        history.value = emptyList()
        query.value = ""
        runCurrent()
        assertTrue(states.last().suggestions.isEmpty())
        assertTrue(states.last().hashtags.isEmpty())

        content.value = listOf(SemanticSearchResult(title = "Museo de arte", excerpt = "#Arte"))
        query.value = "art"
        runCurrent()
        assertEquals(listOf("Arte"), states.last().hashtags.map { it.tag })
        assertTrue(states.last().suggestions.any { it.query == "#Arte" })
    }

    private class CountingResults(private val results: List<SemanticSearchResult>) : AbstractList<SemanticSearchResult>() {
        var reads = 0
            private set
        override val size: Int get() = results.size
        override fun get(index: Int): SemanticSearchResult {
            reads++
            return results[index]
        }
    }
}
