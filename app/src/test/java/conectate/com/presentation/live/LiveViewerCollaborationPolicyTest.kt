package conectate.com.presentation.live

import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import conectate.com.data.model.LiveGuestInvitation
import org.junit.Assert.*
import org.junit.Test

class LiveViewerCollaborationPolicyTest {
    private val accepted = LiveGuestInvitation(
        id = "invitation", liveId = "live", inviteeUserId = "viewer",
        initiatedBy = "viewer", status = "accepted"
    )

    private fun plan(
        invitations: List<LiveGuestInvitation> = emptyList(),
        enabled: Boolean = true,
        available: Boolean = true,
        camera: Boolean = false,
        liveId: String? = "live"
    ) = liveViewerCollaborationPlan(liveId, "viewer", invitations, enabled, available, camera)

    @Test fun `viewer stays receive only even if camera was requested`() {
        val actual = plan(camera = true)!!
        assertEquals("viewer", actual.role)
        assertFalse(actual.publishAudio)
        assertFalse(actual.publishCamera)
    }

    @Test fun `accepted guest wins while capabilities are still loading`() {
        val actual = plan(listOf(accepted), enabled = false, available = false, camera = true)!!
        assertEquals("guest", actual.role)
        assertEquals("invitation", actual.invitationId)
        assertTrue(actual.publishAudio)
        assertTrue(actual.publishCamera)
    }

    @Test fun `accepted host invitation also promotes its recipient`() {
        assertEquals("guest", plan(listOf(accepted.copy(initiatedBy = "host")))!!.role)
    }

    @Test fun `another room or user cannot promote the current viewer`() {
        assertEquals("viewer", plan(listOf(accepted.copy(liveId = "different")))!!.role)
        assertEquals("viewer", plan(listOf(accepted.copy(inviteeUserId = "different")))!!.role)
    }

    @Test fun `pending or revoked permission never starts guest media`() {
        for (status in listOf("pending", "rejected", "expired", "canceled")) {
            assertEquals("viewer", plan(listOf(accepted.copy(status = status)))!!.role)
        }
    }

    @Test fun `capability refresh does not change an accepted guest identity`() {
        val before = plan(listOf(accepted), enabled = false, available = false)
        val after = plan(listOf(accepted.copy()), enabled = true, available = true)
        assertEquals(before, after)
    }

    @Test fun `no active session or unavailable spectator service yields no attempt`() {
        assertNull(plan(liveId = null))
        assertNull(plan(liveId = " "))
        assertNull(plan(enabled = false))
        assertNull(plan(available = false))
    }

    @Test fun `battle viewer receives without opening a second microphone or camera`() {
        val battle = LiveBattleState(battleId = "battle", status = LiveBattleStatus.ACTIVE, opponentLiveId = "opponent")
        val actual = liveViewerBattleCollaborationPlan("live", battle)!!
        assertEquals("viewer", actual.role)
        assertFalse(actual.publishAudio)
        assertFalse(actual.publishCamera)
        assertEquals(actual, liveViewerBattleCollaborationPlan("live", battle.copy(hostScore = 24)))
        assertNull(liveViewerBattleCollaborationPlan(null, battle))
        assertNull(liveViewerBattleCollaborationPlan("live", battle.copy(status = LiveBattleStatus.INVITED)))
        assertNull(liveViewerBattleCollaborationPlan("live", battle.copy(opponentLiveId = "")))
    }
}
