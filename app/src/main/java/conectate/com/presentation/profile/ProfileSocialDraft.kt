package conectate.com.presentation.profile

import conectate.com.core.profile.ProfileSocialLinks

private val profileSocialDraftPlatforms = listOf("instagram", "tiktok", "facebook", "x", "whatsapp")

/** Compare formatting canonically, but preserve edits to/from invalid legacy values. */
internal fun profileSocialDraftHasChanges(draft: Map<String, String>, saved: Map<String, String>): Boolean {
    val changedInvalidValue = profileSocialDraftPlatforms.any { platform ->
        val draftValue = draft[platform].orEmpty()
        val savedValue = saved[platform].orEmpty()
        draftValue != savedValue && (
            !ProfileSocialLinks.isValidInput(platform, draftValue) ||
                !ProfileSocialLinks.isValidInput(platform, savedValue)
        )
    }
    return changedInvalidValue || !ProfileSocialLinks.equivalent(draft, saved)
}
