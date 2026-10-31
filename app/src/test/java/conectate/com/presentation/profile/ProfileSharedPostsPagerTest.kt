package conectate.com.presentation.profile

import conectate.com.data.model.Post
import conectate.com.data.localfallback.LocalFallbackFeedResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileSharedPostsPagerTest {
    private fun post(id: String) = Post(id = id, userId = "original-author", userName = "Autora")
    @Test fun visitsLoadTheDisplayedProfilesSharesAndNextPage() = runTest {
        val reads = ProfileReadScope { "visitor" }; reads.begin("profile-b")
        val calls = mutableListOf<Pair<String, Int>>(); val received = mutableListOf<Pair<List<Post>, Boolean>>()
        val pager = ProfileSharedPostsPager(backgroundScope, reads, { owner, offset ->
            calls += owner to offset
            Result.success(LocalFallbackFeedResponse(listOf(post("post-$offset")), offset == 0, if (offset == 0) 40 else null))
        }, { posts, reset -> received += posts to reset }, { fail("Read failed") })
        pager.refresh("profile-b"); runCurrent(); pager.load(); runCurrent()
        assertEquals(listOf("profile-b" to 0, "profile-b" to 40), calls)
        assertEquals(listOf(true, false), received.map { it.second })
        assertFalse(pager.hasMore.value)
        assertEquals("original-author", received.last().first.single().userId)
    }

    @Test fun failedNextPageKeepsEarlierPostsAndCanBeRetried() = runTest {
        val reads = ProfileReadScope { "visitor" }; reads.begin("profile")
        var errors = 0; var failPage = true; val published = mutableListOf<String>()
        val pager = ProfileSharedPostsPager(backgroundScope, reads, { _, offset ->
            if (offset > 0 && failPage) Result.failure(Exception("Network interrupted"))
            else Result.success(LocalFallbackFeedResponse(listOf(post("$offset")), offset == 0, if (offset == 0) 40 else null))
        }, { posts, _ -> published += posts.map { it.id } }, { errors++ })
        pager.refresh("profile"); runCurrent(); pager.load(); runCurrent()
        assertEquals(listOf("0"), published); assertEquals(1, errors); assertTrue(pager.hasMore.value)
        failPage = false; pager.load(); runCurrent()
        assertEquals(listOf("0", "40"), published)
    }

    @Test fun aLatePageCannotPopulateAnotherProfileOrAnotherAccount() = runTest {
        var actor = "a"; val reads = ProfileReadScope { actor }; reads.begin("profile")
        val pending = CompletableDeferred<LocalFallbackFeedResponse>(); var published = false
        val pager = ProfileSharedPostsPager(backgroundScope, reads, { _, _ -> Result.success(pending.await()) },
            { _, _ -> published = true }, { fail("Old failure leaked") })
        pager.refresh("profile"); runCurrent()
        actor = "b"; reads.begin("other")
        pending.complete(LocalFallbackFeedResponse(listOf(post("old")), false, null)); runCurrent()
        assertFalse(published)
    }
}
