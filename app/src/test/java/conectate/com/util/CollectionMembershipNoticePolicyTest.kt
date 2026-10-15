package conectate.com.util

import conectate.com.data.repository.collection.CollectionMessageSyncSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionMembershipNoticePolicyTest {
    @Test fun membershipEventsAreRecognizedWithoutInventingAHumanSender() {
        assertTrue(isCollectionMembershipNotice("system_membership"))
    }

    @Test fun normalizedMembershipTypeRemainsSilent() {
        assertTrue(isCollectionMembershipNotice(" SYSTEM_MEMBERSHIP "))
        assertFalse(isCollectionMembershipNotice("system_membership_forged"))
    }

    @Test fun ordinaryContentKeepsItsExistingNotificationPath() {
        for (type in listOf(null, "", "text", "image", "video", "audio", "sticker", "poll", "call_event")) {
            assertFalse(isCollectionMembershipNotice(type))
        }
    }

    @Test fun inboxPreviewsRetainAllLifecycleNoticeText() {
        for (notice in listOf("María se unió al grupo con el enlace de invitación",
            "María salió del grupo", "Pedro expulsó a María del grupo",
            "María salió de la comunidad")) {
            assertEquals(notice, CollectionMessageSyncSupport.buildMetadataPreview("system_membership", notice))
        }
    }
}
