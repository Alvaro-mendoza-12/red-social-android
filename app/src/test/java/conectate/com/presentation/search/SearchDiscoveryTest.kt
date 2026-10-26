package conectate.com.presentation.search

import conectate.com.data.model.SemanticSearchResult
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun suggestionsRequireRealResultsOrOnlySuppliedAccountHistory() {
        assertTrue(relatedSearchSuggestions("constru", emptyList(), emptyList()).isEmpty())
        val content = listOf(SemanticSearchResult(id = "1", type = "video", title = "Construcción de casas",
            excerpt = "Aprende #Construcción y #casas"))
        val suggestions = relatedSearchSuggestions("constru", content, listOf("Construcción moderna", "Receta de pan"))
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
}
