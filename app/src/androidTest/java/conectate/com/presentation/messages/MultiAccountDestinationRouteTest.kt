package conectate.com.presentation.messages

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.presentation.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class MultiAccountDestinationRouteTest {

    @Test
    fun directDestination_buildsRouteWithUserAndChatContext() {
        val route = buildMultiAccountDestinationRoute(
            MultiAccountPendingDestination(
                targetType = "chat",
                conversationId = "chat-42",
                userId = "user-9",
                focusMessageId = "message-3"
            )
        )

        assertEquals(
            Screen.Chat.createRoute(
                userId = "user-9",
                focusMessageId = "message-3"
            ),
            route
        )
    }

    @Test
    fun communityDestination_buildsCommunityRoute() {
        val route = buildMultiAccountDestinationRoute(
            MultiAccountPendingDestination(
                targetType = "community",
                conversationId = "community-77",
                focusMessageId = "message-9"
            )
        )

        assertEquals(
            Screen.CommunityChat.createRoute(
                communityId = "community-77",
                focusMessageId = "message-9"
            ),
            route
        )
    }
}
