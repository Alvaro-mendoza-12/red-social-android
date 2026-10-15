package conectate.com.data.model

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SocialIdentityTest {
    @Test fun relationshipLabelsRequireReciprocalFollowing() {
        assertEquals(AuthorFollowState.NOT_FOLLOWING, AuthorFollowState.from(false, false))
        assertEquals(AuthorFollowState.NOT_FOLLOWING, AuthorFollowState.from(false, true))
        assertEquals(AuthorFollowState.FOLLOWING, AuthorFollowState.from(true, false))
        assertEquals(AuthorFollowState.MUTUAL, AuthorFollowState.from(true, true))
    }
    @Test fun avatarUsesFirstUnseenVisibleMomentAndRejectsExpiredOrMalformedDates() {
        val now = Instant.parse("2026-10-10T22:00:00Z")
        val seen = Moment(id="seen", userId="AUTHOR", mediaUrl="", mediaType="image", expiresAt="2026-10-11T22:00:00Z", isViewed=true)
        val unseen = seen.copy(id="unseen", isViewed=false)
        assertEquals("unseen", activeAvatarMoments(listOf(seen, unseen), now)["author"]?.id)
        assertTrue(activeAvatarMoments(listOf(unseen.copy(expiresAt=now.toString()), unseen.copy(expiresAt="invalid")), now).isEmpty())
        assertTrue(activeAvatarMoments(emptyList(), now).isEmpty())
        assertEquals(true, activeAvatarMoments(listOf(seen), now)["author"]?.isViewed)
    }
    @Test fun legacyMomentExpiresUsingItsCreationDate() {
        val moment = Moment(id="legacy", userId="a", mediaUrl="", mediaType="image", createdAt="2026-10-10T22:00:00Z")
        assertEquals(1, activeAvatarMoments(listOf(moment), Instant.parse("2026-10-10T23:00:00Z")).size)
        assertTrue(activeAvatarMoments(listOf(moment), Instant.parse("2026-10-11T22:00:00Z")).isEmpty())
    }
    @org.junit.Test
    fun avatarOwnerQueriesAreCanonicalUniqueAndBounded() {
        val id = "11111111-1111-4111-8111-111111111111"
        org.junit.Assert.assertEquals(listOf(id), momentAvatarOwnerIds(listOf(" $id ", id.uppercase(), "owner_ids=oops")))
        val ids = (0..120).map { "11111111-1111-4111-8111-" + it.toString().padStart(12, '0') }
        org.junit.Assert.assertEquals(100, momentAvatarOwnerIds(ids).size)
    }
}
