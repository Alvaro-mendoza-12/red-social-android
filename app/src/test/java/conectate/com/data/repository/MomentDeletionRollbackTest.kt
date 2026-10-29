package conectate.com.data.repository

import conectate.com.data.model.Moment
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MomentDeletionRollbackTest {
    private val now = Instant.parse("2026-10-29T06:00:00Z")
    private fun moment(id: String) = Moment(id = id, userId = "owner", mediaUrl = "https://example.invalid/image", mediaType = "image")
    @Test fun failureRestoresOnlyTheMissingMomentWithoutLosingConcurrentNewPosts() {
        val original = moment("old")
        assertEquals(setOf("new", "old"), restoreFailedMomentDeletion(listOf(moment("new")), original, now).map { it.id }.toSet())
        val newer = original.copy(isLikedByMe = true)
        assertSame(newer, restoreFailedMomentDeletion(listOf(newer), original, now).single())
    }
    @Test fun failureDoesNotResurrectExpiredOrUnknownContent() {
        assertTrue(restoreFailedMomentDeletion(emptyList(), moment("expired").copy(expiresAt = now.toString()), now).isEmpty())
        assertTrue(restoreFailedMomentDeletion(emptyList(), null, now).isEmpty())
    }
}
