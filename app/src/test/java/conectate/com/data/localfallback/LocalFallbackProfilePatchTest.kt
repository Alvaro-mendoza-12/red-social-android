package conectate.com.data.localfallback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFallbackProfilePatchTest {

    @Test
    fun `partial profile patch omits every unspecified field`() {
        val payload = LocalFallbackUpdateProfileRequest(
            profileWelcome = "Hola",
            interests = listOf("Fotografía")
        ).toSparseJson()

        assertEquals(setOf("profileWelcome", "interests"), payload.keys)
        assertEquals(JsonPrimitive("Hola"), payload["profileWelcome"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Fotografía"))), payload["interests"])
        assertFalse(payload.containsKey("hobbies"))
        assertFalse(payload.containsKey("avatarUrl"))
        assertFalse(payload.containsKey("isPrivate"))
    }

    @Test
    fun `explicit empty values clear fields while null and false defaults stay absent`() {
        val payload = LocalFallbackUpdateProfileRequest(
            profileWelcome = "",
            hobbies = emptyList(),
            removeAvatar = true
        ).toSparseJson()

        assertEquals(JsonPrimitive(""), payload["profileWelcome"])
        assertEquals(JsonArray(emptyList()), payload["hobbies"])
        assertEquals(JsonPrimitive(true), payload["removeAvatar"])
        assertFalse(payload.containsKey("removeCover"))
        assertFalse(payload.containsKey("linkedGames"))
        assertTrue(payload.size == 3)
    }

    @Test
    fun `changing the public handle does not overwrite the display name or profile media`() {
        val payload = LocalFallbackUpdateProfileRequest(username = "Adolfo_01").toSparseJson()

        assertEquals(setOf("username"), payload.keys)
        assertEquals(JsonPrimitive("Adolfo_01"), payload["username"])
        assertFalse(payload.containsKey("displayName"))
        assertFalse(payload.containsKey("avatarUrl"))
    }
}
