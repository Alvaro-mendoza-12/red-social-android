package conectate.com.presentation.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestSocialSectionsTest {
    @Test fun readyPostsArePublishedWhileMomentsAreStillLoading() = runTest {
        val moments = CompletableDeferred<Unit>()
        var postsVisible = false
        val refresh = launch { loadGuestSocialSections({ postsVisible = true }, { moments.await() }) }
        runCurrent()
        assertTrue(postsVisible)
        assertTrue(refresh.isActive)
        moments.complete(Unit)
        refresh.join()
    }

    @Test fun readyMomentsArePublishedWhilePostsAreStillLoading() = runTest {
        val posts = CompletableDeferred<Unit>()
        var momentsVisible = false
        val refresh = launch { loadGuestSocialSections({ posts.await() }, { momentsVisible = true }) }
        runCurrent()
        assertTrue(momentsVisible)
        posts.complete(Unit)
        refresh.join()
    }

    @Test fun leavingCancelsBothPendingSectionsWithoutLateUpdates() = runTest {
        val posts = CompletableDeferred<Unit>(); val moments = CompletableDeferred<Unit>()
        var lateUpdates = 0
        val refresh = launch { loadGuestSocialSections(
            { posts.await(); lateUpdates++ }, { moments.await(); lateUpdates++ },
        ) }
        runCurrent()
        refresh.cancelAndJoin()
        posts.complete(Unit); moments.complete(Unit)
        runCurrent()
        assertEquals(0, lateUpdates)
    }
}
