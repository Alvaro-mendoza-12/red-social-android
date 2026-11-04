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
    @Test fun catalogsRejectMixedFiltersDuplicateRowsAndAnotherAccountsData() {
        assertEquals(row, AgreementCatalog(listOf(row)).validated("alice", false, null).agreements.single())
        for (catalog in listOf(AgreementCatalog(listOf(row, row)), AgreementCatalog(listOf(row.copy(archived = true))),
            AgreementCatalog(listOf(row), archived = true), AgreementCatalog(listOf(row), nextCursor = "a".repeat(513)))) {
            assertThrows(IllegalArgumentException::class.java) { catalog.validated("alice", false, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { AgreementCatalog(listOf(row)).validated("eve", false, null) }
    }
    @Test fun aFocusedAgreementMayBeArchivedOutsideThePageButMustMatchTheRequestedIdentity() {
        val catalog = AgreementCatalog(emptyList(), focus = row.copy(archived = true))
        assertEquals(row.id, catalog.validated("alice", false, row.id).focus?.id)
        assertThrows(IllegalArgumentException::class.java) { catalog.validated("alice", false, "another") }
        assertThrows(IllegalArgumentException::class.java) { catalog.copy(focusMissing = true).validated("alice", false, row.id) }
        assertThrows(IllegalArgumentException::class.java) { catalog.validated("eve", false, row.id) }
    }
    @Test fun missingCapabilitiesAndGeneric404RemainDifferentFromADefinitiveDomainRejection() {
        assertFalse(AgreementCapability(true, 2, true, true).available)
        assertTrue(AgreementCapability(true, 1).available)
        assertFalse(AgreementApiException(404, "").definitive)
        assertFalse(AgreementApiException(403, "AGREEMENT_ACCESS_DENIED").definitive)
        assertTrue(AgreementApiException(404, "AGREEMENT_NOT_FOUND").definitive)
        assertTrue(AgreementApiException(409, "AGREEMENT_ROOM_LIMIT").definitive)
    }
}
