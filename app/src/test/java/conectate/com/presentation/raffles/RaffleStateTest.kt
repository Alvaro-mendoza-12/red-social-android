package conectate.com.presentation.raffles

import conectate.com.data.raffles.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class RaffleStateTest {
    @Test fun selectedWinnerIsRemovedOnlyFromItsRound() {
        val people = listOf(RaffleParticipant("a", name = "Carlos"), RaffleParticipant("b", name = "Carlos"))
        val round = RaffleRound("r1", 1, "running", people, listOf(RaffleResult(1, people[0], "2026-10-04T00:00:00Z")), "admin", startedAt = "today")
        assertEquals(listOf(people[1]), round.remainingParticipants())
        assertEquals(people, round.copy(id = "r2", number = 2, results = emptyList()).remainingParticipants())
    }
    @Test fun wheelStopsAtServerWinnerForSmallAndLargeGroups() {
        for (count in listOf(1, 2, 5, 10, 20, 50, 101, 1000)) for (winner in listOf(0, count / 2, count - 1).distinct()) {
            val target = wheelTargetRotation(41f, winner, count)
            val center = (winner + .5f) * 360f / count
            val normalized = ((target + center) % 360f + 360f) % 360f
            assertTrue("count=$count winner=$winner angle=$normalized", normalized < .01f || normalized > 359.99f)
            assertTrue(target >= 41f + 1440f)
        }
    }
    @Test fun invalidWinnerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { wheelTargetRotation(0f, 2, 2) }
        assertThrows(IllegalArgumentException::class.java) { wheelTargetRotation(0f, 0, 0) }
    }
    @Test fun manyRoundsKeepRotationBoundedAndAlignedForThousandParticipants() {
        for (current in listOf(3_600f, 10_000_000f, 100_000_000f)) for (winner in listOf(0, 500, 999)) {
            val target = wheelTargetRotation(current, winner, 1000)
            assertTrue(target in 1440f..2160f)
            val pointer = ((target + (winner + .5f) * .36f) % 360f + 360f) % 360f
            assertTrue(pointer < .01f || pointer > 359.99f)
        }
    }
    @Test fun persistedRoundIsReconstructedWithoutDrawingLocally() {
        val round = Json.decodeFromString<RaffleRound>("""{"id":"r1","number":1,"status":"completed","participants":[{"id":"p1","kind":"MANUAL_PARTICIPANT","name":"Pedro","userId":null}],"results":[{"position":1,"participant":{"id":"p1","kind":"MANUAL_PARTICIPANT","name":"Pedro","userId":null},"selectedAt":"2026-10-04T00:00:00Z"}],"initiatedBy":"admin","startedAt":"2026-10-04T00:00:00Z"}""")
        assertTrue(round.remainingParticipants().isEmpty())
        assertEquals("Pedro", round.results.single().participant.name)
        assertNull(round.participants.single().userId)
    }
    @Test fun errorMessagesOfferRecoveryWithoutCodes() {
        assertTrue(raffleErrorMessage("MEMBERS_CHANGED").contains("Actualiza"))
        assertTrue(raffleErrorMessage("RAFFLE_ACCESS_DENIED").contains("acceso"))
        assertTrue(raffleErrorMessage("BACKEND_UNAVAILABLE").contains("conexión"))
        assertTrue(raffleErrorMessage("RAFFLES_NOT_DEPLOYED").contains("servidor"))
    }
    @Test fun groupMemberCreationPermissionIsIndependentFromAdministration() {
        val page = Json.decodeFromString<RafflePage>("""{"raffles":[],"canManage":false,"canCreate":true}""")
        assertTrue(page.canCreate)
        assertFalse(page.canManage)
        val members = Json.decodeFromString<RaffleMemberPage>("""{"participants":[],"canManage":false,"canCreate":true}""")
        assertTrue(members.canCreate)
        assertFalse(members.canManage)
        val legacyAdmin = Json.decodeFromString<RafflePage>("""{"canManage":true}""")
        assertTrue(legacyAdmin.canCreate)
        assertFalse(Json.decodeFromString<RafflePage>("{}").canCreate)
    }
}
