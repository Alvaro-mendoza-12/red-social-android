package conectate.com.data.repository.collection

import conectate.com.data.localfallback.LocalFallbackCreateCollectionResponse
import conectate.com.data.localfallback.LocalFallbackCreatedCollectionMember
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CollectionCreationCacheTest {
    private fun receipt(public: Boolean = false) = LocalFallbackCreateCollectionResponse(
        id = "room", created = false,
        room = Json.parseToJsonElement("""{"id":"room","name":"Edited after creation","description":"New description","image":"new.png","creator_id":"legacy-owner","created_at":"2026-11-01T12:00:00Z","is_public":$public,"only_admins_can_talk":true}""").jsonObject,
        initialMembers = listOf(LocalFallbackCreatedCollectionMember("legacy-owner", "owner", "2026-11-01T12:00:00Z")),
    )

    @Test fun recoveredGroupUsesTheServersEditedMetadataAndActualMemberships() {
        val (room, members) = collectionCreationGroupCache(receipt(), "current-owner")
        assertEquals("Edited after creation", room.name)
        assertEquals("new.png", room.image)
        assertEquals("New description", room.description)
        assertEquals("current-owner", room.creator_id)
        assertEquals(listOf("current-owner"), members.map { it.user_id })
        assertEquals("owner", members.single().role)
        assertTrue(room.only_admins_can_talk)
    }

    @Test fun recoveredCommunityPreservesALaterPrivacyChangeAndDoesNotReintroduceRemovedMembers() {
        val (room, members) = collectionCreationCommunityCache(receipt(public = true), "current-owner")
        assertTrue(room.is_public)
        assertTrue(room.is_member)
        assertEquals("current-owner", room.creator_id)
        assertEquals(1, members.size)
        assertEquals("current-owner", members.single().user_id)
    }
}
