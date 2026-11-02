package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class ForwardCoordinatorNavigationTest {
    private val text = ForwardMessagePayload("text", "sender", "hello", "text", null, 1, false)

    @Test fun completionCanOpenItsConversationWhileTheOriginIsStillCurrent() {
        val coordinator = UniversalForwardCoordinator()
        coordinator.bindNavigationOrigin { ShareNavigationOrigin("intent", "entry") }
        coordinator.open(listOf(text), setOf(text.id))
        assertEquals(ShareDismissal.CURRENT, coordinator.dismiss(coordinator.activeRequestId!!))
    }

    @Test fun aNewIntentPreventsLateNavigationEvenWhenTheRouteIsTheSame() {
        var origin = ShareNavigationOrigin("original", "entry")
        val coordinator = UniversalForwardCoordinator()
        coordinator.bindNavigationOrigin { origin }
        coordinator.open(listOf(text), setOf(text.id))
        val requestId = coordinator.activeRequestId!!
        origin = origin.copy(intentId = "new notification")
        val result = coordinator.dismiss(requestId)
        assertTrue(result.consumed); assertFalse(result.mayNavigate)
        assertEquals(ShareDismissal.SUPERSEDED, result)
        assertNull(coordinator.request)
    }

    @Test fun openingACallOrAnotherScreenAlsoPreventsLateNavigation() {
        var origin = ShareNavigationOrigin("intent", "social")
        val coordinator = UniversalForwardCoordinator()
        coordinator.bindNavigationOrigin { origin }
        coordinator.open(listOf(text), setOf(text.id))
        origin = origin.copy(entryId = "active-call")
        assertEquals(ShareDismissal.SUPERSEDED, coordinator.dismiss(coordinator.activeRequestId!!))
    }

    @Test fun replacingTheUiProviderAfterRecreationKeepsTheMatchingNavigationOrigin() {
        val coordinator = UniversalForwardCoordinator()
        coordinator.bindNavigationOrigin { ShareNavigationOrigin("saved intent", "saved entry") }
        coordinator.open(listOf(text), setOf(text.id))
        val request = coordinator.request
        coordinator.bindNavigationOrigin { ShareNavigationOrigin("saved intent", "saved entry") }
        assertSame(request, coordinator.request)
        assertEquals(ShareDismissal.CURRENT, coordinator.dismiss(request!!.requestId))
    }

    @Test fun aLateCallbackCannotClearTheReplacementRequestsOrigin() {
        var origin = ShareNavigationOrigin("first", "one")
        val coordinator = UniversalForwardCoordinator()
        coordinator.bindNavigationOrigin { origin }
        coordinator.open(listOf(text), setOf(text.id))
        val first = coordinator.activeRequestId!!
        origin = ShareNavigationOrigin("second", "two")
        coordinator.open(listOf(text), setOf(text.id))
        val second = coordinator.activeRequestId!!
        assertEquals(ShareDismissal.IGNORED, coordinator.dismiss(first))
        assertEquals(second, coordinator.activeRequestId)
        assertEquals(ShareDismissal.CURRENT, coordinator.dismiss(second))
    }

    @Test fun viewOnceImagesVideosAndFutureViewOnceKindsAreNotOfferedForForwarding() {
        for (type in listOf("view_once_image", "view_once_video", " VIEW_ONCE_IMAGE ", "view_once_other")) {
            val coordinator = UniversalForwardCoordinator()
            val payload = text.copy(contentType = type)
            assertFalse(payload.isForwardable)
            assertFalse(coordinator.open(listOf(payload), setOf(payload.id)))
            assertNull(coordinator.request)
        }
    }

    @Test fun normalMediaAndStructuredMessagesRemainForwardable() {
        for (type in listOf("text", "image", "video", "audio", "gif", "sticker", "document", "file", "contact", "location")) {
            val coordinator = UniversalForwardCoordinator()
            val payload = text.copy(contentType = type)
            assertTrue(payload.isForwardable)
            assertTrue(coordinator.open(listOf(payload), setOf(payload.id)))
            assertEquals(listOf(payload), coordinator.request?.candidates)
        }
    }

    @Test fun aMixedSelectionRemovesDeletedAndViewOnceMessagesFromTheActualRequest() {
        val coordinator = UniversalForwardCoordinator()
        val deleted = text.copy(id = "deleted", deletedForAll = true)
        val once = text.copy(id = "once", contentType = "view_once_image")
        assertTrue(coordinator.open(listOf(deleted, once, text), setOf(deleted.id, once.id, text.id)))
        assertEquals(listOf(text), coordinator.request?.candidates)
        assertEquals(setOf(text.id), coordinator.request?.initiallySelectedIds)
    }

    @Test fun pendingMessagesCannotOpenAForwardRequestOrExposeTheirLocalFile() {
        for (payload in listOf(text.copy(status = "sending"), text.copy(status = "failed"),
            text.copy(contentType = "document", mediaUrl = "file:///private/pending.pptx"))) {
            val coordinator = UniversalForwardCoordinator()
            assertFalse(coordinator.open(listOf(payload), setOf(payload.id)))
            assertNull(coordinator.request)
        }
    }

    @Test fun allThreeMessageConvertersPreserveTheUnsentStateForTheSameEligibilityPolicy() {
        val direct = conectate.com.data.model.Message(id = "dm", status = "sending").toForwardMessagePayload()
        val group = conectate.com.data.model.GroupMessage(id = "gm", group_id = "g", sender_id = "a",
            content = "draft", created_at = "2026-11-01T12:00:00Z", status = "pending").toForwardMessagePayload()
        val community = conectate.com.data.model.CommunityMessage(id = "cm", community_id = "c", sender_id = "a",
            content = "draft", created_at = "2026-11-01T12:00:00Z", status = "failed").toForwardMessagePayload()
        assertFalse(direct.isForwardable)
        assertFalse(group.isForwardable)
        assertFalse(community.isForwardable)
    }

    @Test fun specialMessageActionsCannotBecomeBrokenOrMisleadingCardsInAnotherConversation() {
        for (type in listOf("poll", "raffle", "system_member_joined", " SYSTEM_MEMBER_LEFT ")) {
            val coordinator = UniversalForwardCoordinator()
            assertFalse(coordinator.open(listOf(text.copy(contentType = type)), setOf(text.id)))
            assertNull(coordinator.request)
        }
    }
}
