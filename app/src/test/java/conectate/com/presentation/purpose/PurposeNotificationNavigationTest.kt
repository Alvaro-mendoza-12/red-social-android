package conectate.com.presentation.purpose

import android.app.Application
import conectate.com.data.model.Notification
import conectate.com.presentation.profile.notificationDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class,sdk = [34])
class PurposeNotificationNavigationTest {
    @Test fun persistedInvitationsOpenTheirOwnRegisteredRouteAndRejectMalformedIdentifiers() {
        val id = "11111111-1111-4111-8111-111111111111"
        val notice = Notification(recipientId = "recipient",actorId = "actor",type = "gaming_plan",targetType = "gaming_plan",targetId = id)
        assertEquals("game_plan/$id",notificationDestination(notice))
        assertNull(notificationDestination(notice.copy(targetId = "../../other")))
        assertNull(notificationDestination(notice.copy(targetId = null)))
    }
}
