package conectate.com.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UserSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun userBalance_defaultsToZeroWhenBackendReturnsNull() {
        val user = json.decodeFromString<User>(
            """
            {
              "id": "user-1",
              "email": "user1@test.com",
              "username": "user1",
              "balance": null
            }
            """.trimIndent()
        )

        assertEquals(0.0, user.balance, 0.0)
    }
}
