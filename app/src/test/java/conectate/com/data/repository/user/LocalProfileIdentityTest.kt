package conectate.com.data.repository.user

import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LocalProfileIdentityTest {
    @Test fun chosenIdentityFromTheOwnProfileKeepsItsHandleEvenWhenItMatchesTheEmailPrefix() {
        val fields = readLocalProfileIdentity(Json.parseToJsonElement(
            """{"numeric_id":12345,"username_origin":"chosen"}""").jsonObject)
        val user = User(username = "mirella_vr07", email = "mirella_vr07@example.invalid",
            display_name = "Mirella Vásquez", numeric_id = fields.numericId, username_origin = fields.usernameOrigin)
        assertEquals("mirella_vr07", profilePublicHandle(user))
        assertEquals(12345L, fields.numericId)
    }
    @Test fun handlesStringIdsAndMigratedNames() {
        val fields = readLocalProfileIdentity(Json.parseToJsonElement(
            """{"numeric_id":"23456","username_origin":"migrated_name"}""").jsonObject)
        assertEquals(23456L, fields.numericId)
        assertEquals("migrated_name", fields.usernameOrigin)
    }
    @Test fun legacyMissingOrMalformedFieldsDoNotInventAnAuthoritativeIdentity() {
        listOf("{}", """{"numeric_id":0,"username_origin":"legacy_email"}""",
            """{"numeric_id":[],"username_origin":null}""").forEach { json ->
            val fields = readLocalProfileIdentity(Json.parseToJsonElement(json).jsonObject)
            assertNull(fields.numericId); assertNull(fields.usernameOrigin)
        }
    }
}
