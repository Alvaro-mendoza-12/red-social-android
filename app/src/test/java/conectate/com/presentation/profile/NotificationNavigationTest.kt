package conectate.com.presentation.profile

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.Notification
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.navigation.addMainRoutes
import conectate.com.test.UnitTestApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class NotificationNavigationTest {
    private fun controller() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        graph = createGraph(startDestination = Screen.Notifications.route) { addMainRoutes(this@apply, isLoggedIn = true) }
    }
    private fun notice(type: String) = Notification(recipientId = "owner", actorId = "actor", type = type, targetId = "target")

    @Test fun supportedSocialNoticesActuallyNavigateThroughTheRegisteredGraph() {
        val controller = controller()
        val cases = listOf(
            "like" to Screen.PostDetail.route, "comment" to Screen.PostDetail.route, "new_post" to Screen.PostDetail.route,
            "mention_video_description" to Screen.VideoPlayer.route, "new_video" to Screen.VideoPlayer.route,
            "friend_request_accepted" to Screen.UserProfile.route, "friend_request" to Screen.FriendRequests.route,
            "like_moment" to Screen.MomentViewer.route, "new_live" to Screen.LiveViewer.route,
            "voice_invite" to Screen.RoomDetail.route, "notification_digest" to Screen.NotificationSettings.route,
            "admin_access_changed" to Screen.AdminPanel.route, "account_enabled" to Screen.UserProfile.route,
        )
        cases.forEach { (type, expected) ->
            controller.navigate(Screen.Notifications.route)
            handleNotificationNavigation(controller, notice(type))
            assertEquals(type, expected, controller.currentDestination?.route)
            if (type == "account_enabled") {
                assertEquals("owner", controller.currentBackStackEntry?.arguments?.getString("userId"))
            }
            assertTrue(controller.popBackStack())
            assertEquals(Screen.Notifications.route, controller.currentDestination?.route)
        }
    }

    @Test fun aVideoMentionKeepsItsVideoAndCommentInsteadOfOpeningAPost() {
        val controller = controller()
        val notification = notice("mention").copy(extraData = JsonObject(mapOf(
            "video_id" to JsonPrimitive("actual-video"), "focus_comment_id" to JsonPrimitive("actual-comment"),
        )))
        handleNotificationNavigation(controller, notification)
        assertEquals(Screen.VideoPlayer.route, controller.currentDestination?.route)
        assertEquals("actual-video", controller.currentBackStackEntry?.arguments?.getString("videoId"))
        assertEquals("actual-comment", controller.currentBackStackEntry?.arguments?.getString("focusCommentId"))
    }

    @Test fun missingContentIsInformationalAndDoesNotInventAnEmptyDestination() {
        assertNull(notificationDestination(notice("like_post").copy(targetId = " ")))
        assertNull(notificationDestination(notice("system_announcement").copy(targetId = null, content = "Información")))
        assertEquals(Screen.LiveDiscovery.route, notificationDestination(notice("live_started").copy(targetId = null)))
    }

    @Test fun malformedOptionalIdsDoNotPreventOpeningTheKnownTarget() {
        val controller = controller()
        handleNotificationNavigation(controller, notice("like_post").copy(extraData = JsonObject(mapOf(
            "notification_type" to JsonObject(emptyMap()), "comment_id" to JsonObject(emptyMap()),
        ))))
        assertEquals(Screen.PostDetail.route, controller.currentDestination?.route)
        assertEquals("target", controller.currentBackStackEntry?.arguments?.getString("postId"))
        assertNull(controller.currentBackStackEntry?.arguments?.getString("focusCommentId"))
    }
}
