package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendMutationPolicyTest {
    @Test
    fun `both directions must confirm deletion`() {
        assertTrue(friendshipDeleteCompleted(204, 204))
        assertTrue(friendshipDeleteCompleted(200, 204))
        assertFalse(friendshipDeleteCompleted(204, 500))
        assertFalse(friendshipDeleteCompleted(403, 204))
        assertFalse(friendshipDeleteCompleted(409, 204))
        assertFalse(friendshipDeleteCompleted(204, 403))
        assertFalse(friendshipDeleteCompleted(204, 409))
        assertFalse(friendshipDeleteCompleted(500, 503))
        assertFalse(friendshipDeleteCompleted(302, 204))
    }
}
