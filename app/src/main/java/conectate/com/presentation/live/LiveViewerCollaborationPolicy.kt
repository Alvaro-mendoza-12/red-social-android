package conectate.com.presentation.live

import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import conectate.com.data.model.LiveGuestInvitation

/** Stable desired connection identity; SDK transport state is deliberately absent. */
internal data class LiveViewerCollaborationPlan(
    val liveId: String,
    val role: String,
    val publishAudio: Boolean,
    val publishCamera: Boolean,
    val invitationId: String? = null,
    val battleId: String? = null
)

internal fun liveViewerCollaborationPlan(
    liveId: String?,
    currentUserId: String,
    invitations: List<LiveGuestInvitation>,
    guestsBattlesEnabled: Boolean,
    sfuAvailable: Boolean,
    requestedCamera: Boolean
): LiveViewerCollaborationPlan? {
    val id = liveId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val accepted = invitations.firstOrNull {
        currentUserId.isNotBlank() && it.id.isNotBlank() &&
            it.liveId.equals(id, ignoreCase = true) &&
            it.inviteeUserId.equals(currentUserId, ignoreCase = true) &&
            it.status.equals("accepted", ignoreCase = true)
    }
    if (accepted != null) {
        return LiveViewerCollaborationPlan(id, "guest", true, requestedCamera, accepted.id)
    }
    return if (guestsBattlesEnabled && sfuAvailable) {
        LiveViewerCollaborationPlan(id, "viewer", false, false)
    } else null
}

internal fun liveViewerBattleCollaborationPlan(
    liveId: String?,
    battle: LiveBattleState
): LiveViewerCollaborationPlan? {
    val id = liveId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (battle.status == LiveBattleStatus.ACTIVE &&
        battle.battleId.isNotBlank() && battle.opponentLiveId.isNotBlank()
    ) {
        LiveViewerCollaborationPlan(id, "viewer", false, false, battleId = battle.battleId)
    } else null
}

/** A normal LIVE viewer must join the SFU when the broadcaster uses it. */
internal fun liveViewerMediaPlan(
    liveId: String?,
    battle: LiveBattleState,
    sfuAvailable: Boolean
): LiveViewerCollaborationPlan? {
    val id = liveId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (battle.status == LiveBattleStatus.ACTIVE &&
        battle.battleId.isNotBlank() && battle.opponentLiveId.isNotBlank()
    ) {
        return LiveViewerCollaborationPlan(id, "viewer", false, false, battleId = battle.battleId)
    }
    return if (sfuAvailable) LiveViewerCollaborationPlan(id, "viewer", false, false) else null
}
