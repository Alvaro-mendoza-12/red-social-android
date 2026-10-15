package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
class RoomMentionCandidatesTest {
    private fun user(id: String, name: String, handle: String? = null) = User(id = id, display_name = name, username = handle, username_origin = "chosen")
    @Test fun bareAtOffersOtherCurrentMembers() { assertEquals(listOf("b"), roomMentionCandidates(listOf(user("a", "Yo"), user("b", "Ana")), "", "a").map { it.id }) }
    @Test fun searchMatchesAccentsNameAndChosenHandle() { val u = user("b", "Álvaro Pérez", "alv67"); assertEquals(listOf(u), roomMentionCandidates(listOf(u), "alvaro", "a")); assertEquals(listOf(u), roomMentionCandidates(listOf(u), "alv6", "a")) }
    @Test fun sameNameDifferentHandlesRemainSelectable() { assertEquals(2, roomMentionCandidates(listOf(user("b", "Ana", "ana01"), user("c", "Ana", "ana02")), "ana", "a").size) }
    @Test fun ambiguousHandlesAreNotGuessed() { assertTrue(roomMentionCandidates(listOf(user("b", "Ana"), user("c", "Ana")), "ana", "a").isEmpty()) }
    @Test fun duplicateIdsAndSelfAreExcluded() { val u=user("b", "Ana"); assertEquals(1, roomMentionCandidates(listOf(u,u,user("a", "Yo")), "", "a").size) }
    @Test fun listIsBounded() { assertEquals(6, roomMentionCandidates((1..40).map { user(it.toString(), "Persona $it") }, "", "owner").size) }
    @Test fun unresolvedParticipantIsNeverInventedFromDisplayName() {
        assertTrue(resolvedRoomMentionMembers(listOf("ana-id"), emptyList()).isEmpty())
    }
    @Test fun resolvedProfilesAreScopedToCurrentMembers() {
        val ana=user("ana-id", "Ana", "ana01")
        val outsider=user("outside", "Otra", "otra01")
        assertEquals(listOf(ana), resolvedRoomMentionMembers(listOf(" ANA-ID "), listOf(ana, outsider)))
    }
    @Test fun refreshedIdentityWinsAndDuplicateProfilesAreRemoved() {
        val current=user("ana-id", "Ana", "ana_actual")
        val cached=user("ANA-ID", "Ana", "ana_anterior")
        assertEquals(listOf(current), resolvedRoomMentionMembers(listOf("ana-id"), listOf(current,cached)))
    }

}
