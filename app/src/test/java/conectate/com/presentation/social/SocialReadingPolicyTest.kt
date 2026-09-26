package conectate.com.presentation.social

import conectate.com.data.model.Post
import org.junit.Assert.*
import org.junit.Test

class SocialReadingPolicyTest {
    @Test fun searchesContentAndAuthorWithoutAccents() {
        val post = Post(id = "one", userId = "author", userName = "María", content = "Una conversación")
        assertEquals(listOf(post), SocialReadingPolicy.select(listOf(post), "maria conversacion", 1))
        assertTrue(SocialReadingPolicy.select(listOf(post), "otro tema", 0).isEmpty())
    }
    @Test fun filtersTextByEstimatedTimeAndDeduplicates() {
        val short = Post(id = "one", userId = "a", userName = "a", content = "Hola")
        val long = short.copy(id = "two", content = "palabra ".repeat(181))
        assertEquals(2, SocialReadingPolicy.minutes(long))
        assertEquals(listOf(short), SocialReadingPolicy.select(listOf(short, short, long, short.copy(id = "empty", content = "")), "", 1))
    }
}
