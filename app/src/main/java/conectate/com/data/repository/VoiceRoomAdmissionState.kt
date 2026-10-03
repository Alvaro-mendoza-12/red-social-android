package conectate.com.data.repository

import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.model.VoiceStageRoles
import conectate.com.data.model.isStageMode

/** Until the server supplies a role, stage visitors enter as listeners. */
internal fun initialVoiceParticipantRole(room: VoiceRoom, userId: String): String = when {
    !room.isStageMode() -> VoiceStageRoles.SPEAKER
    room.creatorId.trim().equals(userId.trim(), ignoreCase = true) -> VoiceStageRoles.MODERATOR
    else -> VoiceStageRoles.AUDIENCE
}

internal fun normalizedVoiceParticipantRole(raw: String): String = when (raw.trim().lowercase()) {
    VoiceStageRoles.MODERATOR -> VoiceStageRoles.MODERATOR
    VoiceStageRoles.SPEAKER -> VoiceStageRoles.SPEAKER
    else -> VoiceStageRoles.AUDIENCE
}

/** Authoritative role updates cannot undo a local mute or grant unknown roles. */
internal fun applyVoiceAdmissionState(
    participants: List<VoiceParticipant>,
    selfId: String,
    role: String,
    serverMuted: Boolean,
    handRaised: Boolean,
    locallyMuted: Boolean,
): List<VoiceParticipant> {
    if (selfId.isBlank()) return participants
    val normalizedRole = normalizedVoiceParticipantRole(role)
    return participants.map { participant ->
        if (!participant.id.trim().equals(selfId.trim(), ignoreCase = true)) participant
        else participant.copy(
            role = normalizedRole,
            isMuted = locallyMuted || serverMuted || normalizedRole == VoiceStageRoles.AUDIENCE,
            handRaised = handRaised,
        )
    }
}
