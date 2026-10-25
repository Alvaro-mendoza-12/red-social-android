package conectate.com.presentation.social

import conectate.com.data.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialCurrentUserLoaderTest {
    private fun user(image: String) = User(id = "owner", avatar_url = image)

    @Test fun savedPhotoIsPublishedBeforeASlowNetworkProfileFinishes() = runTest {
        val remote = CompletableDeferred<Result<User?>>()
        val shown = mutableListOf<User>()
        val load = launch {
            loadSocialCurrentUser({ user("saved.jpg") }, { remote.await() }, { true }, shown::add)
        }
        runCurrent()
        assertEquals(listOf("saved.jpg"), shown.map { it.profileImage })
        assertFalse(load.isCompleted)
        remote.complete(Result.success(user("fresh.jpg")))
        load.join()
        assertEquals(listOf("saved.jpg", "fresh.jpg"), shown.map { it.profileImage })
    }

    @Test fun networkFailureOrMissingProfileNeverErasesTheSavedPhoto() = runTest {
        for (result in listOf(Result.failure<User?>(IllegalStateException("offline")), Result.success(null))) {
            var shown: User? = null
            loadSocialCurrentUser({ user("saved.jpg") }, { result }, { true }, { shown = it })
            assertEquals("saved.jpg", shown?.profileImage)
        }
    }

    @Test fun lateProfileFromPreviousAccountIsDiscarded() = runTest {
        val remote = CompletableDeferred<Result<User?>>()
        val shown = mutableListOf<User>()
        var current = true
        val load = launch {
            loadSocialCurrentUser({ user("saved.jpg") }, { remote.await() }, { current }, shown::add)
        }
        runCurrent()
        current = false
        remote.complete(Result.success(user("old-account.jpg")))
        load.join()
        assertEquals(listOf("saved.jpg"), shown.map { it.profileImage })
    }
}
