package conectate.com.presentation.social

import conectate.com.data.model.Post
import conectate.com.data.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialAuthorHydratorTest {
    private fun post(id: String = "p", author: String = "a") = Post(id = id, userId = author, userName = "Antes")
    private fun user(id: String = "a") = User(id = id, display_name = "Actual", avatar_url = "https://example.test/a.webp")

    @Test
    fun `slow profiles never block visible posts and preserve edits made while waiting`() = runTest {
        val response = CompletableDeferred<Result<List<User>>>()
        var visible = listOf(post(), post("removed"))
        val hydrator = SocialAuthorHydrator(this) { response.await() }
        hydrator.refresh(visible) { profiles -> visible = applyCanonicalPostAuthors(visible, profiles) }
        runCurrent()
        assertEquals(2, visible.size)
        assertEquals("Antes", visible.first().userName)

        visible = listOf(visible.first().copy(content = "editado", likeCount = 1, likedByMe = true,
            myReaction = "LOVE", reactionCounts = mapOf("LOVE" to 1)).also { it.isSensitive = true })
        response.complete(Result.success(listOf(user())))
        advanceUntilIdle()

        assertEquals(1, visible.size)
        assertEquals("Actual", visible.single().userName)
        assertEquals("https://example.test/a.webp", visible.single().userProfileImage)
        assertEquals("editado", visible.single().content)
        assertEquals(mapOf("LOVE" to 1), visible.single().reactionCounts)
        assertTrue(visible.single().likedByMe)
        assertTrue(visible.single().isSensitive)
    }

    @Test
    fun `account or mode change invalidates a pending author response`() = runTest {
        val response = CompletableDeferred<Result<List<User>>>()
        var applied = false
        val hydrator = SocialAuthorHydrator(this) { response.await() }
        hydrator.refresh(listOf(post())) { applied = true }
        runCurrent()
        hydrator.cancel()
        response.complete(Result.success(listOf(user())))
        advanceUntilIdle()
        assertFalse(applied)
    }

    @Test
    fun `latest page cancels older enrichment and batches unique author ids`() = runTest {
        val requests = mutableListOf<List<String>>()
        val hydrated = mutableListOf<String>()
        val hydrator = SocialAuthorHydrator(this) { ids ->
            requests += ids
            delay(100)
            Result.success(ids.map(::user))
        }
        hydrator.refresh(listOf(post())) { hydrated += "old" }
        runCurrent()
        hydrator.refresh(listOf(post("one", " B "), post("two", "b"))) { hydrated += it.single().id }
        advanceUntilIdle()
        assertEquals(listOf(listOf("a"), listOf("b")), requests)
        assertEquals(listOf("b"), hydrated)
    }

    @Test
    fun `profile timeout leaves delivered author snapshots available`() = runTest {
        var visible = listOf(post())
        var cancelled = false
        val hydrator = SocialAuthorHydrator(this) {
            try {
                delay(60_000)
                Result.success(listOf(user()))
            } finally { cancelled = true }
        }
        hydrator.refresh(visible) { visible = applyCanonicalPostAuthors(visible, it) }
        advanceUntilIdle()
        assertEquals(5_000L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertEquals("Antes", visible.single().userName)
    }

    @Test
    fun `missing author stays unchanged and partial profile hydration preserves delivered identity`() {
        val untouched = post("other", "missing")
        val visible = applyCanonicalPostAuthors(
            listOf(post().copy(userProfileImage = "old-photo"), untouched),
            listOf(user(" A ").copy(display_name = "", full_name = null, username = null, avatar_url = null)),
        )
        assertEquals("old-photo", visible.first().userProfileImage)
        assertEquals("Antes", visible.first().userName)
        assertSame(untouched, visible.last())
    }

    @Test fun `missing feed photos use local identities without waiting for remote profiles`() {
        val initial = post().copy(userName = "Usuario", userProfileImage = "", likeCount = 7).also { it.isSensitive = true }
        val missing = post("missing", "unknown")
        val visible = fillMissingPostAuthorIdentities(listOf(initial, missing), listOf(user(" A ")))
        assertEquals("https://example.test/a.webp", visible.first().userProfileImage)
        assertEquals("Actual", visible.first().userName)
        assertEquals(7, visible.first().likeCount)
        assertTrue(visible.first().isSensitive)
        assertSame(missing, visible.last())
    }

    @Test fun `older local identity never replaces a photo already delivered by the feed`() {
        val fresh = post().copy(userName = "Nombre actual", userProfileImage = "new-photo")
        assertSame(fresh, fillMissingPostAuthorIdentities(listOf(fresh), listOf(user())).single())
    }

    @Test fun `confirmed avatar removal clears visible author photos while preserving post edits`() {
        val edited = post().copy(userProfileImage = "old-photo", content = "editado", likedByMe = true)
            .also { it.isSensitive = true }
        val other = post("other", "someone-else").copy(userProfileImage = "other-photo")
        val updated = applyCanonicalPostAuthors(listOf(edited, other), listOf(user().copy(avatar_url = null)), confirmedUpdate = true)
        assertNull(updated.first().userProfileImage)
        assertEquals("editado", updated.first().content)
        assertTrue(updated.first().likedByMe)
        assertTrue(updated.first().isSensitive)
        assertSame(other, updated.last())
    }
}
