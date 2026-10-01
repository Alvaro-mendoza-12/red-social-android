package conectate.com.presentation.messages

import conectate.com.data.model.PollVoteUser
import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle

internal fun resolvePollVoterIdentities(voters: List<PollVoteUser>, profiles: List<User>): List<PollVoteUser> {
    val byId = profiles.associateBy { it.id.trim().lowercase() }
    return voters.map { voter ->
        val profile = byId[voter.userId.trim().lowercase()]
        if (profile == null) voter else voter.copy(
            fullName = profile.displayName,
            username = profilePublicHandle(profile),
            avatarUrl = profile.profileImageUrl,
        )
    }
}
