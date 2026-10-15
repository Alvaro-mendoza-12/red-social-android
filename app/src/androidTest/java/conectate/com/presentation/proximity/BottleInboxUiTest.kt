package conectate.com.presentation.proximity

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import conectate.com.data.localfallback.AnonymousBottle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottleInboxUiTest {
    @get:Rule val compose = createComposeRule()
    private val bottle = AnonymousBottle("own-fixture", "Hola desde otra botella", "offered", "recipient")

    @Test fun receiveSectionExistsBeforeAnyBottleArrives() {
        compose.setContent { MaterialTheme {
            BottleInboxCard(null, BottleInboxStatus.WAITING, false, {}, {})
        } }
        compose.onNodeWithText("Recibir botellas").assertIsDisplayed()
        compose.onNodeWithText("Todavía no te ha llegado una botella.").assertIsDisplayed()
        compose.onNodeWithText("Puedes recibir sin enviar un mensaje. Mantén este apartado abierto para que te lleguen botellas.").assertIsDisplayed()
    }

    @Test fun receivedMessageOffersReplyAndPass() {
        var accepted = 0
        var declined = 0
        compose.setContent { MaterialTheme {
            BottleInboxCard(bottle, BottleInboxStatus.RECEIVED, false, { accepted++ }, { declined++ })
        } }
        compose.onNodeWithText(bottle.content).assertIsDisplayed()
        compose.onNodeWithText("Responder").performClick()
        compose.onNodeWithText("Pasar").performClick()
        assertEquals(1, accepted)
        assertEquals(1, declined)
    }

    @Test fun pendingOperationDisablesBothResponses() {
        compose.setContent { MaterialTheme {
            BottleInboxCard(bottle, BottleInboxStatus.RECEIVED, true, {}, {})
        } }
        compose.onNodeWithText("Responder").assertIsNotEnabled()
        compose.onNodeWithText("Pasar").assertIsNotEnabled()
    }

    @Test fun connectionFailureDoesNotPretendToBeAnEmptyConnectedInbox() {
        compose.setContent { MaterialTheme {
            BottleInboxCard(null, BottleInboxStatus.ERROR, false, {}, {})
        } }
        compose.onNodeWithText("No pudimos conectar. Volveremos a intentarlo automáticamente.").assertIsDisplayed()
        compose.onNodeWithText("Todavía no te ha llegado una botella.").assertDoesNotExist()
    }

    @Test fun emptyHomeHasSendAndReceiveWithoutGenderFields() {
        showHome(null, null)
        compose.onNodeWithText("Recibir botellas").assertIsDisplayed()
        saveScreen("empty")
        compose.onNodeWithText("Enviar botella").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Me identifico como").assertDoesNotExist()
        compose.onNodeWithText("Quiero conversar con").assertDoesNotExist()
    }

    @Test fun incomingBottleDoesNotHideTheSendSection() {
        showHome(bottle, null)
        compose.onNodeWithText(bottle.content).assertIsDisplayed()
        saveScreen("received")
        compose.onNodeWithText("Enviar botella").performScrollTo().assertIsDisplayed()
    }

    @Test fun outgoingBottleDoesNotHideReception() {
        showHome(null, bottle.copy(status = "searching", perspective = "sender"))
        compose.onNodeWithText("Recibir botellas").assertIsDisplayed()
        compose.onNodeWithText("Retirar botella").performScrollTo().assertIsDisplayed()
    }

    private fun showHome(incoming: AnonymousBottle?, outgoing: AnonymousBottle?) {
        compose.setContent { MaterialTheme {
            AnonymousTab(
                session = null, sessions = emptyList(), messages = emptyList(),
                outgoingBottle = outgoing, incomingBottle = incoming,
                inboxStatus = if (incoming == null) BottleInboxStatus.WAITING else BottleInboxStatus.RECEIVED,
                busy = false, onLaunchBottle = {}, onCancelBottle = {}, onAcceptBottle = {},
                onDeclineBottle = {}, onSend = {}, onSendSticker = { _, _ -> },
                onSendMedia = { _, _ -> }, onEnd = {}, onSelectSession = {}, onBackToSessions = {},
                normalChatPeerId = null, onOpenProfile = {}, onOpenNormalChat = {},
            )
        } }
    }

    private fun saveScreen(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        File(folder, "bottle-home-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
