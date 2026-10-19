package conectate.com.data.localfallback

import org.junit.Assert.*
import org.junit.Test

class CollectionInviteQueryTest {
    @Test fun bothPrivateCollectionTypesRetainTheirToken() {
        for (kind in listOf("groups", "communities")) assertEquals("?token=invite_test-123", collectionInviteQuery(kind," invite_test-123 "))
        assertEquals("", collectionInviteQuery("communities",null))
        assertEquals("?token=a%26b", collectionInviteQuery("communities","a&b"))
    }
    @Test(expected = IllegalArgumentException::class) fun groupRequiresAnInvitation() { collectionInviteQuery("groups",null) }
}
