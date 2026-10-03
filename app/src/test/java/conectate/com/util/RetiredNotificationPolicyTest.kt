package conectate.com.util

import conectate.com.data.model.Notification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RetiredNotificationPolicyTest {
    @Test fun viewsAreSuppressedRegardlessOfCaseOrPayloadAlias() {
        assertTrue(isRetiredMomentViewNotification(" VIEW_MOMENT "))
        assertTrue(isRetiredMomentViewNotification("activity", "view_moment"))
        assertFalse(isRetiredMomentViewNotification("new_moment", "like_moment"))
        assertFalse(isRetiredMomentViewNotification(null))
    }
    @Test fun wrappedViewsNeverBecomeVisibleSocialActivity() {
        val notice = Notification(recipientId = "owner", actorId = "viewer", type = "activity",
            extraData = buildJsonObject { put("notification_type", "view_moment") })
        assertTrue(notice.isRetiredNotification())
        assertFalse(notice.copy(type = "new_moment", extraData = null).isRetiredNotification())
    }
}
