package conectate.com.presentation.social

import conectate.com.data.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SocialRefreshOrderTest {
    private fun candidates() = (1..40).map { Post(id = "p-$it", userId = "u-$it", userName = "User", content = "Post") }

    @Test fun accountAndVisitSeedAreStableAndIndependent() {
        val posts = candidates()
        val a = orderSocialDiscoveryForSession(posts, "account-a", "visit-1")
        assertEquals(a, orderSocialDiscoveryForSession(posts, "account-a", "visit-1"))
        assertNotEquals(a, orderSocialDiscoveryForSession(posts, "account-b", "visit-1"))
        assertNotEquals(a, orderSocialDiscoveryForSession(posts, "account-a", "visit-2"))
        assertEquals(posts.take(5).map(Post::id).toSet(), a.take(5).map(Post::id).toSet())
    }

    @Test fun freshVisitDoesNotStartWithPreviousFirstWhenAlternativesExist() {
        val posts = candidates()
        val first = orderSocialDiscoveryForSession(posts, "account", "visit").first().id
        val next = orderSocialDiscoveryForSession(posts + posts.take(3), "account", "visit", first)
        assertNotEquals(first, next.first().id)
        assertEquals(40, next.map(Post::id).toSet().size)
        assertEquals(40, next.size)
        assertEquals(posts.take(5).map(Post::id).toSet(), next.take(5).map(Post::id).toSet())
    }

    @Test
    fun refreshChangesOrderOnlyInsideRelevanceBands() {
        val posts = (1..12).map {
            Post(id = "post-$it", userId = "user-$it", userName = "User $it", content = "Post $it")
        }
        val first = diversifySocialRefresh(posts, "refresh-a", bandSize = 4)
        val second = diversifySocialRefresh(posts, "refresh-b", bandSize = 4)

        assertNotEquals(first.map(Post::id), second.map(Post::id))
        assertEquals(posts.take(4).map(Post::id).toSet(), first.take(4).map(Post::id).toSet())
        assertEquals(posts.drop(4).take(4).map(Post::id).toSet(), first.drop(4).take(4).map(Post::id).toSet())
    }
}
