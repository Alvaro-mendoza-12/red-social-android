package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class PushMessagePolicyTest {
    @Test fun activityAndCallContextCannotBecomeALocalMessage() {
        for (type in listOf("follow", "mention_post", "voice_invite", "call_invite", "call_cancel", "activity", "")) {
            assertFalse(type, isMessageDeliveryPush(type, "actual-id", "sender", "owner"))
        }
    }

    @Test fun missingOrPlaceholderDeliveryIdentityDoesNotCreateAFabricatedReplica() {
        for (invalid in listOf(null, "", "  ", "unknown", "x\nheader", "x".repeat(257))) {
            assertFalse(isMessageDeliveryPush("new_message", invalid, "sender", "owner"))
            assertFalse(isMessageDeliveryPush("new_message", "message", invalid, "owner"))
            assertFalse(isMessageDeliveryPush("new_message", "message", "sender", invalid))
        }
        assertTrue(isMessageDeliveryPush(" MESSAGE ", "persisted-message", "sender", "owner"))
    }

    @Test fun ownOtherDeviceEchoCanUpdateHistoryWithoutAddingAnUnreadHint() {
        assertTrue(isMessageDeliveryPush("new_message", "message", "owner", "owner"))
        assertFalse(shouldRecordUnreadPush("new_message", "message", "owner", "owner"))
        assertTrue(shouldRecordUnreadPush("new_message", "message", "sender", "owner"))
    }

    @Test fun aCanonicalCommunityWinsOverContradictoryLegacyGroupFlags() {
        assertEquals("community", pushConversationKind(mapOf("target_type" to "community", "is_group" to "true", "group_id" to "wrong-room")))
        assertEquals("chat", pushConversationKind(mapOf("target_type" to "dm", "community_id" to "another-room", "is_group" to "true")))
        assertEquals("group", pushConversationKind(mapOf("target_type" to "group", "community_id" to "another-room")))
    }

    @Test fun legacyCommunityAliasesStillSelectOneRepository() {
        assertEquals("community", pushConversationKind(mapOf("communityId" to "community", "groupId" to "group")))
        assertEquals("community", pushConversationKind(mapOf("isCommunity" to "1", "is_group" to "true")))
        assertEquals("group", pushConversationKind(mapOf("group_id" to "group")))
        assertEquals("chat", pushConversationKind(emptyMap()))
    }

    @Test fun canonicalMessageDestinationsDoNotFollowContradictoryOptionalRoomIds() {
        assertEquals("canonical-room", pushConversationId(mapOf("target_type" to "community", "target_id" to "canonical-room",
            "group_id" to "wrong-group", "community_id" to "old-community", "chat_id" to "wrong-chat")))
        assertEquals("actual-community", pushConversationId(mapOf("communityId" to "actual-community", "chat_id" to "old-chat")))
        assertEquals("actual-chat", pushConversationId(mapOf("type" to "message", "target_type" to "chat", "target_id" to "actual-chat")))
        assertNull(pushConversationId(mapOf("chat_id" to " ", "target_id" to "bad\nvalue")))
    }
}
