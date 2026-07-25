package conectate.com.core.feed

import conectate.com.data.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class FeedRankingEngineTest {

    private val engine = DefaultFeedRankingEngine()

    @Test
    fun `author affinity can promote personalized content`() {
        val now = Instant.now()
        val baseline = post(id = "p1", userId = "u1", createdAt = now.minusSeconds(600), likeCount = 20)
        val personalized = post(id = "p2", userId = "u2", createdAt = now.minusSeconds(600), likeCount = 8)

        val ranked = engine.rankPosts(
            posts = listOf(baseline, personalized),
            currentUserId = "viewer",
            friendIds = emptySet(),
            signalSnapshot = FeedSignalSnapshot(
                authorAffinity = mapOf("u2" to 4.0),
                mediaAffinity = emptyMap(),
                seenPostIds = emptySet()
            )
        )

        assertEquals("u2", ranked.first().userId)
    }

    @Test
    fun `seen posts are downranked when alternatives exist`() {
        val now = Instant.now()
        val seen = post(id = "seen", userId = "u1", createdAt = now.minusSeconds(300), likeCount = 10)
        val fresh = post(id = "fresh", userId = "u2", createdAt = now.minusSeconds(300), likeCount = 10)

        val ranked = engine.rankPosts(
            posts = listOf(seen, fresh),
            currentUserId = "viewer",
            friendIds = emptySet(),
            signalSnapshot = FeedSignalSnapshot(
                authorAffinity = emptyMap(),
                mediaAffinity = emptyMap(),
                seenPostIds = setOf("seen")
            )
        )

        assertNotEquals("seen", ranked.first().id)
    }

    @Test
    fun `diversity avoids repeating same author at top`() {
        val now = Instant.now()
        val posts = listOf(
            post(id = "a1", userId = "a", createdAt = now.minusSeconds(60), likeCount = 80),
            post(id = "a2", userId = "a", createdAt = now.minusSeconds(70), likeCount = 79),
            post(id = "a3", userId = "a", createdAt = now.minusSeconds(80), likeCount = 78),
            post(id = "b1", userId = "b", createdAt = now.minusSeconds(90), likeCount = 30),
            post(id = "c1", userId = "c", createdAt = now.minusSeconds(100), likeCount = 28)
        )

        val ranked = engine.rankPosts(
            posts = posts,
            currentUserId = "viewer",
            friendIds = emptySet(),
            signalSnapshot = FeedSignalSnapshot.EMPTY
        )

        val topThreeAuthors = ranked.take(3).map { it.userId }.toSet()
        assertNotEquals(setOf("a"), topThreeAuthors)
    }

    @Test
    fun `profile interests promote matching social content`() {
        val now = Instant.now()
        val generic = post(
            id = "generic",
            userId = "u1",
            createdAt = now.minusSeconds(300),
            likeCount = 3,
            content = "Una publicación general"
        )
        val matching = post(
            id = "matching",
            userId = "u2",
            createdAt = now.minusSeconds(300),
            likeCount = 2,
            content = "Consejos de fotografía nocturna"
        )

        val ranked = engine.rankPosts(
            posts = listOf(generic, matching),
            currentUserId = "viewer",
            friendIds = emptySet(),
            signalSnapshot = FeedSignalSnapshot.EMPTY,
            interestTerms = setOf("fotografía")
        )

        assertEquals("matching", ranked.first().id)
    }

    private fun post(
        id: String,
        userId: String,
        createdAt: Instant,
        likeCount: Int,
        content: String = "content_$id"
    ): Post {
        return Post(
            id = id,
            userId = userId,
            userName = "user_$userId",
            content = content,
            createdAt = createdAt.toString(),
            likeCount = likeCount
        )
    }
}
