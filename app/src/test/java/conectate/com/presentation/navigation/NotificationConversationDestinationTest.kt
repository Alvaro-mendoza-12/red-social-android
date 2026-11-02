package conectate.com.presentation.navigation

import android.content.Intent
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.MessageNotificationPayload
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.TrustedNavigationIntents
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class NotificationConversationDestinationTest {
    @Test fun `message and reaction destinations reach each real conversation with their message focus`() {
        val nav = NavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator()); navigatorProvider.addNavigator(DialogNavigator())
            graph = createGraph(startDestination = Screen.Notifications.route) { addMainRoutes(this@apply, true) }
        }
        for (type in listOf("new_message", "message_reaction")) for ((kind, route, idKey) in listOf(
            Triple("chat", Screen.Chat.route, "userId"), Triple("group", Screen.GroupChat.route, "groupId"),
            Triple("community", Screen.CommunityChat.route, "communityId"),
        )) {
            val intent = Intent().putExtra("type", type).putExtra("target_type", kind)
                .putExtra("chat_id", "conversation").putExtra("sender_id", "peer").putExtra("message_id", "focused-message")
            nav.navigate(requireNotNull(notificationConversationDestination(intent)))
            assertEquals(route, nav.currentDestination?.route)
            assertEquals(if (kind == "chat") "peer" else "conversation", nav.currentBackStackEntry?.arguments?.getString(idKey))
            assertEquals("focused-message", nav.currentBackStackEntry?.arguments?.getString("focusMessageId"))
            nav.popBackStack()
        }
    }

    @Test fun `canonical kind wins over conflicting old group and community extras`() {
        val intent = Intent().putExtra("target_type", "chat").putExtra("sender_id", "peer")
            .putExtra("group_id", "wrong-group").putExtra("community_id", "wrong-community")
        assertEquals(Screen.Chat.createRoute("peer"), notificationConversationDestination(intent))
        intent.putExtra("target_type", "group")
        assertEquals(Screen.GroupChat.createRoute("wrong-group"), notificationConversationDestination(intent))
    }

    @Test fun `blank optional fields keep valid fallbacks and absent conversations do not invent a route`() {
        assertNull(notificationConversationDestination(Intent().putExtra("target_type", "group")))
        val intent = Intent().putExtra("is_group", "true").putExtra("group_id", " ")
            .putExtra("chat_id", " ").putExtra("target_id", "actual-group").putExtra("focus_message_id", "original-message")
        assertEquals(Screen.GroupChat.createRoute("actual-group", "original-message"), notificationConversationDestination(intent))
    }

    @Test fun `the actual reaction publisher issues a trusted intent to the original group message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = NotificationAccountTarget(userId = "fixture-${UUID.randomUUID()}")
        val id = 195_031
        try {
            assertTrue(MessageNotificationFactory.postReaction(context, MessageNotificationPayload(
                channelId = "reaction-test", channelName = "Prueba", channelDescription = "Prueba",
                conversationId = "actual-group", targetType = "group", conversationTitle = "Grupo",
                senderName = "Participante", messageBody = "Reaccionó a tu mensaje", notificationId = id,
                senderId = "peer", messageId = "actual-message", notificationAccountTarget = owner,
                isGroupConversation = true, allowInlineActions = false,
            )))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == id }
            val intent = shadowOf(notification.notification.contentIntent).savedIntent
            assertEquals("message_reaction", intent.getStringExtra("type"))
            assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
            assertEquals(Screen.GroupChat.createRoute("actual-group", "actual-message"), notificationConversationDestination(intent))
        } finally { MessageNotificationFactory.cancelAccount(context, owner.userId) }
    }
}
