package conectate.com.data.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.Post
import conectate.com.test.UnitTestApplication
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PostFeedCacheTest {
    private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("post-feed-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val cache = PostFeedCache(prefs)
    private fun post(liked: Boolean = false) = Post(id = "p", userId = "author", userName = "Author",
        content = "keep me", likeCount = if (liked) 4 else 3, likedByMe = liked,
        myReaction = if (liked) "LIKE" else null, isSensitive = true)
    private fun seed(mode: String = "discover", owner: String = "a", liked: Boolean = false) {
        assertTrue(cache.persist(mode, listOf(post(liked)), cache.beginRead(owner), owner))
    }

    @Test fun preservesTheExistingAccountScopedPreferenceFormat() {
        prefs.edit().putString("discover:a", Json.encodeToString(listOf(post()))).commit()
        assertEquals(listOf(post()), cache.read("discover", " A "))
        assertTrue(cache.read("discover", "b").isEmpty())
    }

    @Test fun confirmedLikeIsDurableInBothModesWithoutChangingAnotherAccount() {
        seed(); seed("following"); seed(owner = "b")
        assertTrue(cache.confirmReaction("a", "P", "LOVE", "a"))
        val restarted = PostFeedCache(prefs)
        for (mode in listOf("discover", "following")) {
            val saved = restarted.read(mode, "a").single()
            assertTrue(saved.likedByMe); assertEquals("LOVE", saved.myReaction)
            assertEquals(4, saved.likeCount); assertEquals("keep me", saved.content); assertTrue(saved.isSensitive)
        }
        assertFalse(restarted.read("discover", "b").single().likedByMe)
    }

    @Test fun oldNetworkReadCannotUndoAnAcknowledgedLikeOnDisk() {
        seed()
        val oldRead = cache.beginRead("a")
        assertTrue(cache.confirmReaction("a", "p", "FIRE", "a"))
        assertTrue(cache.persist("discover", listOf(post().copy(content = "new content")), oldRead, "a"))
        val saved = PostFeedCache(prefs).read("discover", "a").single()
        assertEquals("FIRE", saved.myReaction); assertEquals(4, saved.likeCount)
        assertEquals("new content", saved.content)
    }

    @Test fun oldReadCannotRestoreAnAcknowledgedUnlike() {
        seed(liked = true)
        val oldRead = cache.beginRead("a")
        assertTrue(cache.confirmReaction("a", "p", null, "a"))
        cache.persist("discover", listOf(post(true)), oldRead, "a")
        val saved = PostFeedCache(prefs).read("discover", "a").single()
        assertFalse(saved.likedByMe); assertNull(saved.myReaction); assertEquals(3, saved.likeCount)
    }

    @Test fun newReadAfterAckCanReflectAnUnlikeFromAnotherDevice() {
        seed()
        cache.confirmReaction("a", "p", "LIKE", "a")
        cache.persist("discover", listOf(post()), cache.beginRead("a"), "a")
        assertFalse(cache.read("discover", "a").single().likedByMe)
    }

    @Test fun repeatedAcksDoNotInflateCounts() {
        seed()
        repeat(3) { assertTrue(cache.confirmReaction("a", "p", "LIKE", "a")) }
        assertEquals(4, cache.read("discover", "a").single().likeCount)
    }

    @Test fun accountChangeRejectsBothDelayedReadAndAck() {
        seed(); seed(owner = "b")
        val read = cache.beginRead("a")
        assertFalse(cache.persist("discover", listOf(post(true)), read, "b"))
        assertFalse(cache.confirmReaction("a", "p", "LIKE", "b"))
        assertFalse(cache.read("discover", "a").single().likedByMe)
        assertFalse(cache.read("discover", "b").single().likedByMe)
    }

    @Test fun ackBeforeFirstSnapshotProtectsTheAlreadyRunningRead() {
        val read = cache.beginRead("a")
        cache.confirmReaction("a", "p", "LIKE", "a")
        cache.persist("discover", listOf(post()), read, "a")
        assertTrue(cache.read("discover", "a").single().likedByMe)
    }
}
