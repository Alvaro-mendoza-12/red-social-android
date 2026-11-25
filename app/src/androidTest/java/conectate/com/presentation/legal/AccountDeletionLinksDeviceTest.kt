package conectate.com.presentation.legal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI clicks with an isolated browser handler; no email, network or account mutations. */
@RunWith(AndroidJUnit4::class)
class AccountDeletionLinksDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletionOpensTheAnonymousOfficialRequestPage() {
        val opened = AtomicReference<String>()
        content(object : UriHandler { override fun openUri(uri: String) { opened.set(uri) } })
        compose.onNodeWithText("Eliminar mi cuenta y mis datos").performClick()
        compose.runOnIdle { assertEquals("https://conectate.tail9b267c.ts.net/eliminar-cuenta", opened.get()) }
    }

    @Test fun privacyOpensThePublicPolicy() {
        val opened = AtomicReference<String>()
        content(object : UriHandler { override fun openUri(uri: String) { opened.set(uri) } })
        compose.onNodeWithText("Leer política de privacidad en la web").performClick()
        compose.runOnIdle { assertEquals("https://conectate.tail9b267c.ts.net/privacidad", opened.get()) }
    }

    @Test fun missingBrowserKeepsTheScreenAndOffersTheEmailContact() {
        content(object : UriHandler { override fun openUri(uri: String) { error("No browser available in test") } })
        compose.onNodeWithText("Eliminar mi cuenta y mis datos").performClick()
        compose.onNodeWithText("No se pudo abrir el navegador. Puedes solicitar ayuda y eliminación en administracion@novvinapp.com.").assertIsDisplayed()
        compose.onNodeWithText("Eliminar mi cuenta y mis datos").assertIsDisplayed()
    }

    private fun content(handler: UriHandler) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                MaterialTheme { AccountDeletionLinks() }
            }
        }
    }
}
