package conectate.com.presentation.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileSocialDraftTest {
    @Test fun formattedWhatsAppNumberMatchesAcknowledgedCanonicalNumber() {
        assertFalse(profileSocialDraftHasChanges(
            draft = mapOf("whatsapp" to "+1 (555) 123-4567"),
            saved = mapOf("whatsapp" to "15551234567"),
        ))
    }

    @Test fun harmlessUrlNormalizationIsNotAnUnsavedChange() {
        assertFalse(profileSocialDraftHasChanges(
            draft = mapOf("instagram" to " HTTPS://INSTAGRAM.COM ", "x" to ""),
            saved = mapOf("instagram" to "https://instagram.com/"),
        ))
    }

    @Test fun editingOrRemovingAcknowledgedLinkStillRequiresDiscardConfirmation() {
        val saved = mapOf("whatsapp" to "15551234567")
        assertTrue(profileSocialDraftHasChanges(mapOf("whatsapp" to "15557654321"), saved))
        assertTrue(profileSocialDraftHasChanges(mapOf("whatsapp" to ""), saved))
    }

    @Test fun invalidDraftIsNotLostJustBecauseNormalizationOmitsIt() {
        assertTrue(profileSocialDraftHasChanges(mapOf("instagram" to "not a URL"), emptyMap()))
        assertTrue(profileSocialDraftHasChanges(mapOf("whatsapp" to "123"), emptyMap()))
    }

    @Test fun clearingOrReplacingLegacyInvalidValueRequiresDiscardConfirmation() {
        val legacy = mapOf("instagram" to "legacy handle")
        assertTrue(profileSocialDraftHasChanges(mapOf("instagram" to ""), legacy))
        assertTrue(profileSocialDraftHasChanges(emptyMap(), legacy))
        assertTrue(profileSocialDraftHasChanges(mapOf("instagram" to "another legacy handle"), legacy))
    }

    @Test fun uneditableUnknownSavedPlatformDoesNotCreateAPhantomChange() {
        assertFalse(profileSocialDraftHasChanges(emptyMap(), mapOf("unknown" to "legacy handle")))
    }

    @Test fun unchangedLegacyInvalidValueIsNotReportedAsANewEdit() {
        val legacy = mapOf("instagram" to "legacy handle")
        assertFalse(profileSocialDraftHasChanges(legacy, legacy))
    }
}
