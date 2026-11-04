package conectate.com.presentation.messages.agreements

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatAgreementTest {
    private val row = ChatAgreement("id", "source", "alice", "Una propuesta", 1, 1,
        participants = listOf(AgreementParticipant("alice"), AgreementParticipant("bob")))
    @Test fun unknownOrInconsistentConsentIsRejectedRatherThanRenderedAsAccepted() {
        assertEquals(row, row.validated())
        assertThrows(IllegalArgumentException::class.java) { row.copy(status = "accepted").validated() }
        assertThrows(IllegalArgumentException::class.java) { row.copy(participants = listOf(AgreementParticipant("alice", response = "automatic"), AgreementParticipant("bob"))).validated() }
        assertThrows(IllegalArgumentException::class.java) { row.copy(participants = listOf(AgreementParticipant("alice"), AgreementParticipant("alice"))).validated() }
    }
    @Test fun bothExplicitAcceptancesAndCancellationHaveTheirOwnVisibleStates() {
        val accepted = row.copy(participants = row.participants.map { it.copy(response = "accepted") }, status = "accepted")
        assertEquals("accepted", accepted.validated().computedStatus())
        assertEquals("cancelled", accepted.copy(cancelled = true, status = "cancelled").validated().computedStatus())
    }
    @Test fun aResponseCommandContainsNoForgedActorOrImplicitParticipantDecision() {
        val json = Json.encodeToString(AgreementCommand("operation", expectedVersion = 2, action = "accept"))
        assertFalse(json.contains("null")); assertFalse(json.contains("sourceMessageId")); assertFalse(json.contains("actor")); assertFalse(json.contains("participants"))
        assertEquals(AgreementCommand("operation", expectedVersion = 2, action = "accept"), Json.decodeFromString<AgreementCommand>(json))
    }
}
