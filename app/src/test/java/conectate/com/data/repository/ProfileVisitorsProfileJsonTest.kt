package conectate.com.data.repository

import conectate.com.data.model.User
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProfileVisitorsProfileJsonTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader().use { it.readText() }

    @Test fun originalCsvCellsReproduceTheVisitorsDecodingFailure() {
        assertTrue(runCatching { json.decodeFromString<User>(fixture("profile-visitors-legacy-raw.json")) }.isFailure)
    }
    @Test fun exactServerNormalizationLoadsTheSameLegacyVisitor() {
        val user = json.decodeFromString<User>(fixture("profile-visitors-legacy-normalized.json"))
        assertEquals("Visitante", user.displayName)
        assertEquals("visitante", user.username)
        assertEquals(42L, user.numeric_id)
        assertTrue(user.is_verified)
        assertFalse(user.is_admin)
        assertEquals(emptyList<String>(), user.interests)
        assertEquals(emptyMap<String, String>(), user.game_logo_urls)
        assertEquals(0, user.suspension_count)
        assertTrue(user.profile_view_history_enabled)
        assertTrue(user.isProfileAvatarVideo)
        assertEquals(2000L, user.avatar_duration_ms)
    }
    @Test fun oneNormalizedLegacyProfileDoesNotBreakTheVisitorsBatch() {
        val old = fixture("profile-visitors-legacy-normalized.json")
        val users = json.decodeFromString<List<User>>("[$old,{\"id\":\"second\",\"display_name\":\"Segundo\"}]")
        assertEquals(listOf("Visitante", "Segundo"), users.map { it.displayName })
    }
}
