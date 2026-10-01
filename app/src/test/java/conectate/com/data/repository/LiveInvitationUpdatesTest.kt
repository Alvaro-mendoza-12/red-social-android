package conectate.com.data.repository

import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import conectate.com.data.model.LiveGuestInvitation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LiveInvitationUpdatesTest {
    private fun invitation(id: String = "invite-1", status: String = "pending", time: String? = "2026-10-01T10:00:00Z") =
        LiveGuestInvitation(id = id, liveId = "live-1", hostUserId = "host", inviteeUserId = "guest",
            status = status, updatedAt = time, createdAt = "2026-10-01T09:00:00Z")
    private fun event(channel: String = "user:guest", live: String = "live-1", includeInvitation: Boolean = true) =
        UbuntuRealtimeEnvelope(channel, "live_collaboration", buildJsonObject {
            put("liveId", live)
            if (includeInvitation) put("invitation", buildJsonObject { put("id", "invite-1") })
        })

    @Test fun acceptanceArrivesImmediatelyWithoutWaitingForAnotherSnapshot() {
        val accepted = invitation(status = "accepted", time = "2026-10-01T10:00:01Z")
        assertEquals(listOf(accepted), mergeLiveInvitations(listOf(invitation()), listOf(accepted), "live-1", "guest"))
    }

    @Test fun aLateSnapshotCannotUndoNewerAcceptance() {
        val accepted = invitation(status = "accepted", time = "2026-10-01T10:00:01Z")
        assertEquals(listOf(accepted), mergeLiveInvitations(listOf(accepted), listOf(invitation()), "live-1", "guest"))
    }

    @Test fun duplicatesAndTimestampTiesCannotTurnAcceptedBackIntoPending() {
        val accepted = invitation(status = "accepted")
        assertEquals(listOf(accepted), mergeLiveInvitations(listOf(accepted), listOf(invitation(), accepted), "live-1", "host"))
    }

    @Test fun newerCancellationReplacesAcceptanceAndCannotBeUndoneByOldPush() {
        val accepted = invitation(status = "accepted", time = "2026-10-01T10:00:01Z")
        val canceled = invitation(status = "canceled", time = "2026-10-01T10:00:02Z")
        val result = mergeLiveInvitations(listOf(accepted), listOf(canceled), "live-1", "guest")
        assertEquals(listOf(canceled), mergeLiveInvitations(result, listOf(accepted), "live-1", "guest"))
    }

    @Test fun serverMicrosecondsAndOffsetDatesAreComparedAsInstants() {
        val newer = invitation(status = "accepted", time = "2026-10-01T05:00:00.000002-05:00")
        val older = invitation(time = "2026-10-01T10:00:00.000001Z")
        assertEquals(listOf(newer), mergeLiveInvitations(listOf(newer), listOf(older), "live-1", "guest"))
    }

    @Test fun emptyOrInvalidTimestampDoesNotReplaceKnownNewerVersion() {
        val accepted = invitation(status = "accepted")
        val invalid = invitation(time = "not a date")
        assertEquals(listOf(accepted), mergeLiveInvitations(listOf(accepted), listOf(invalid), "live-1", "guest"))
    }

    @Test fun aSnapshotInFlightKeepsInvitationsThatArrivedAfterItsRead() {
        val pushed = invitation(id = "invite-new")
        assertEquals(setOf("invite-new", "invite-1"),
            mergeLiveInvitations(listOf(pushed), listOf(invitation()), "live-1", "guest").map { it.id }.toSet())
    }

    @Test fun anotherRoomOrAnotherAccountNeverKeepsOldInvitations() {
        val old = invitation()
        assertTrue(mergeLiveInvitations(listOf(old), emptyList(), "live-2", "guest").isEmpty())
        assertTrue(mergeLiveInvitations(listOf(old), listOf(old), "live-1", "outsider").isEmpty())
        assertTrue(mergeLiveInvitations(listOf(old), listOf(old), "live-1", "").isEmpty())
    }

    @Test fun historyStaysBoundedAndKeepsLatestCreatedInvitations() {
        val rows = (0..65).map { n -> invitation(id = "invite-$n")
            .copy(createdAt = java.time.Instant.ofEpochSecond(n.toLong()).toString()) }
        val result = mergeLiveInvitations(rows, rows, "live-1", "HOST")
        assertEquals(50, result.size)
        assertEquals("invite-65", result.first().id)
        assertEquals("invite-16", result.last().id)
    }

    @Test fun eventsAreScopedToTheAccountAndLiveAndIgnoredAfterAccountSwitch() {
        assertTrue(isLiveInvitationUpdate(event(), "live-1", "guest", "GUEST"))
        assertFalse(isLiveInvitationUpdate(event(), "live-1", "guest", "other"))
        assertFalse(isLiveInvitationUpdate(event(channel = "user:host"), "live-1", "guest", "guest"))
        assertFalse(isLiveInvitationUpdate(event(live = "live-2"), "live-1", "guest", "guest"))
        assertFalse(isLiveInvitationUpdate(event(includeInvitation = false), "live-1", "guest", "guest"))
        assertFalse(isLiveInvitationUpdate(event().copy(event = "notification"), "live-1", "guest", "guest"))
    }

    @Test fun onlyCurrentUsersSubscriptionCanRequestImmediateReconciliation() {
        val ready = event().copy(event = "subscription_ready")
        assertTrue(isLiveInvitationChannelReady(ready, "guest", "GUEST"))
        assertFalse(isLiveInvitationChannelReady(ready, "guest", "other"))
        assertFalse(isLiveInvitationChannelReady(ready.copy(channel = "user:host"), "guest", "guest"))
        assertFalse(isLiveInvitationChannelReady(event(), "guest", "guest"))
        assertFalse(isLiveInvitationChannelReady(ready, "", ""))
    }
}
