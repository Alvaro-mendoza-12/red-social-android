package conectate.com.core.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfileIdentityRequestTest {
    @Test fun `a profile response cannot be rebound to a different active account`() = runTest {
        var activeAccount = "a"
        val request = ProfileIdentityRequest(activeAccount, 3L)
        val response = CompletableDeferred<Unit>()
        var persistedAccount: String? = null
        val job = launch {
            response.await()
            if (request.belongsTo(activeAccount)) persistedAccount = request.accountId
        }
        activeAccount = "b"
        response.complete(Unit)
        job.join()
        assertNull(persistedAccount)
        assertEquals("a", request.accountId)
    }

    @Test fun `logout or a blank captured account cannot trigger anonymous persistence`() {
        assertFalse(ProfileIdentityRequest("a", 1).belongsTo(null))
        assertFalse(ProfileIdentityRequest("a", 1).belongsTo(""))
        assertFalse(ProfileIdentityRequest(" ", 1).belongsTo(""))
    }

    @Test fun `normalization accepts the same account without changing its generation`() {
        val request = ProfileIdentityRequest(" A ", 4L)
        assertTrue(request.belongsTo("a"))
        assertEquals(4L, request.generation)
    }

    @Test fun `clear during a profile query invalidates its original image generation`() {
        val fence = ProfileIdentityWriteFence()
        val request = ProfileIdentityRequest("a", fence.capture("a"))
        fence.invalidate("a")
        assertTrue(request.belongsTo("a"))
        assertFalse(fence.isCurrent(request.accountId, request.generation))
    }
}
