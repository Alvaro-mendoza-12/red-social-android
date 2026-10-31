package conectate.com.data.repository

import conectate.com.data.model.Post
import org.junit.Assert.*
import org.junit.Test

class PostShareChangeTest {
    @Test fun aServerConfirmationReplacesOnlyShareFieldsAndIsIdempotent() {
        val original = Post(id = "p", userId = "original-author", userName = "Autora", content = "Original", likeCount = 12)
        val event = PostShareChange("visitor", "p", true, 4)
        val applied = event.applyTo(original)
        assertEquals(original.userId, applied.userId); assertEquals(original.content, applied.content)
        assertEquals(12, applied.likeCount); assertEquals(4, applied.shareCount); assertTrue(applied.sharedByMe)
        assertEquals(applied, event.applyTo(applied))
    }
    @Test fun removingAShareCannotMakeTheCountNegativeOrChangeAnotherPost() {
        val original = Post(id = "p", userId = "author", userName = "Autora", sharedByMe = true)
        assertEquals(0, PostShareChange("visitor", "p", false).applyTo(original).shareCount)
        assertEquals(original, PostShareChange("visitor", "different", false, 9).applyTo(original))
    }
}
