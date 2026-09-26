package conectate.com.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSocialLinksTest {
    @Test
    fun normalizesOfficialLinksAndPhoneNumber() {
        assertEquals(
            mapOf(
                "instagram" to "https://www.instagram.com/novvin/",
                "facebook" to "https://facebook.com/fabriciosaul.filvafigueroa/?locale=es_LA",
                "whatsapp" to "573001234567",
            ),
            ProfileSocialLinks.normalize(
                mapOf(
                    "instagram" to "https://www.instagram.com/novvin/",
                    "facebook" to "https://FACEBOOK.com/fabriciosaul.filvafigueroa/?locale=es_LA",
                    "whatsapp" to "+57 300 123 4567",
                ),
            ),
        )
    }

    @Test
    fun serverRootSlashNormalizationIsEquivalent() {
        assertTrue(
            ProfileSocialLinks.equivalent(
                mapOf("x" to "https://x.com"),
                mapOf("x" to "https://x.com/"),
            ),
        )
    }

    @Test
    fun rejectsInsecureLookalikeLinks() {
        assertFalse(ProfileSocialLinks.isValidInput("instagram", "http://instagram.com/novvin"))
        assertFalse(ProfileSocialLinks.isValidInput("tiktok", "https://tiktok.com.evil.example/@novvin"))
        assertFalse(ProfileSocialLinks.isValidInput("facebook", "javascript:alert(1)"))
    }

    @Test
    fun extractsReadableHandlesForProfileCards() {
        assertEquals(
            "@fabriciosaul.filvafigueroa",
            ProfileSocialLinks.displayHandle(
                "facebook",
                "https://www.facebook.com/fabriciosaul.filvafigueroa/?locale=es_LA",
            ),
        )
        assertEquals(
            "@novvin",
            ProfileSocialLinks.displayHandle("tiktok", "https://www.tiktok.com/@novvin"),
        )
        assertEquals(
            "+573001234567",
            ProfileSocialLinks.displayHandle("whatsapp", "+57 300 123 4567"),
        )
    }
}
