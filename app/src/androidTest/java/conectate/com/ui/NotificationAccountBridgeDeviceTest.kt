package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.services.voiceRoomReturnIntent
import conectate.com.util.NotificationAccountTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Local navigation only: no login, room join, account fixture or remote write. */
@RunWith(AndroidJUnit4::class)
class NotificationAccountBridgeDeviceTest {
    @get:Rule val compose = createEntryComposeRule()

    @Test fun anUnsavedRecipientCanOpenLoginAndReturnAfterActivityRecreation() {
        val target = NotificationAccountTarget(userId = "fixture-${UUID.randomUUID()}", displayName = "Comprobación local")
        compose.runOnUiThread {
            compose.activity.startActivity(voiceRoomReturnIntent(compose.activity, "fixture-room", target))
        }
        fun awaitBridge() = compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Abrir en otra cuenta").fetchSemanticsNodes().isNotEmpty()
        }
        awaitBridge()
        compose.activityRule.scenario.recreate()
        awaitBridge()
        compose.onNodeWithText("Iniciar sesión con Comprobación local").performScrollTo().performClick()
        compose.onNodeWithTag("auth-login").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("auth-login").assertIsDisplayed()
        pressBack()
        awaitBridge()
        compose.onNodeWithText("Iniciar sesión con Comprobación local").assertExists()
    }
}
