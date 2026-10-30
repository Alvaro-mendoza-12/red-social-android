package conectate.com

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.auth.EmailRecoveryDraft
import conectate.com.presentation.auth.EmailRecoveryDraftStore
import conectate.com.presentation.auth.EmailRecoverySteps
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/** Component and persistence checks. Uses no login and no real recovery secrets. */
@RunWith(AndroidJUnit4::class)
class EmailRecoveryUiAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(verified: Boolean = false, loading: Boolean = false, password: String = "", confirmation: String = "",
        deliveryConfirmed: Boolean = true) {
        val configuration = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(Locale.forLanguageTag("es")) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                MaterialTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            EmailRecoverySteps(
                                draft = EmailRecoveryDraft("qa@example.invalid", code = "001234",
                                    resetToken = if (verified) "synthetic-grant-only" else "",
                                    expiresAt = System.currentTimeMillis() + 60_000, deliveryConfirmed = deliveryConfirmed),
                                loading = loading, newPassword = password, confirmation = confirmation,
                                onCodeChange = {}, onVerify = {}, onNewPasswordChange = {}, onConfirmationChange = {},
                                onSavePassword = {}, onResend = {}, onChangeEmail = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        File(folder, name).outputStream().use {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun codeStepIsSeparateFromPasswordAndReadableInSpanish() {
        show()
        compose.onNodeWithTag("email_recovery_code").assertIsDisplayed().assertTextContains("001234")
        compose.onNodeWithTag("email_recovery_password").assertDoesNotExist()
        compose.onNodeWithTag("email_recovery_verify").assertIsEnabled().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(Locale.forLanguageTag("es")) }).getString(R.string.auth_email_verify_code)).assertIsDisplayed()
        val field = compose.onNodeWithTag("email_recovery_code").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("email_recovery_verify").fetchSemanticsNode().boundsInRoot
        assertTrue("Code input overlaps verify button", field.bottom < button.top)
        screenshot("email-recovery-code-es.png")
    }

    @Test fun verifiedStepHasTwoPasswordFieldsAndSaveAction() {
        show(verified = true, password = "QA-example-only", confirmation = "QA-example-only")
        compose.onNodeWithTag("email_recovery_code").assertDoesNotExist()
        compose.onNodeWithTag("email_recovery_password").assertIsDisplayed()
        compose.onNodeWithTag("email_recovery_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("email_recovery_save").assertIsEnabled().performScrollTo().assertIsDisplayed()
        val field = compose.onNodeWithTag("email_recovery_confirmation").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("email_recovery_save").fetchSemanticsNode().boundsInRoot
        assertTrue("Password confirmation overlaps save button", field.bottom < button.top)
        screenshot("email-recovery-password-es.png")
    }

    @Test fun loadingDisablesRepeatedVerification() {
        show(loading = true)
        compose.onNodeWithTag("email_recovery_code").assertIsNotEnabled()
        compose.onNodeWithTag("email_recovery_verify").assertIsNotEnabled()
    }

    @Test fun uncertainDeliveryKeepsCodeEntryWithoutPromisingAnEmail() {
        show(deliveryConfirmed = false)
        compose.onNodeWithTag("email_recovery_code").assertIsDisplayed()
        val configuration = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(Locale.forLanguageTag("es")) }
        val localized = context.createConfigurationContext(configuration)
        compose.onNodeWithText(localized.getString(R.string.auth_email_delivery_unknown_hint)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.auth_email_code_sent_hint)).assertDoesNotExist()
        compose.onNodeWithTag("email_recovery_resend").assertIsEnabled()
    }

    @Test fun verifiedGrantDoesNotOfferResendThatWouldInvalidateIt() {
        show(verified = true, deliveryConfirmed = false)
        compose.onNodeWithTag("email_recovery_resend").assertDoesNotExist()
        compose.onNodeWithTag("email_recovery_password").assertIsDisplayed()
    }

    @Test fun mismatchedPasswordsCannotBeSubmitted() {
        show(verified = true, password = "QA-example-only", confirmation = "Different-example-only")
        compose.onNodeWithTag("email_recovery_save").assertIsNotEnabled()
    }

    @Test fun encryptedDraftSurvivesStoreRecreationAndExpiredProgressIsCleared() {
        val name = "qa_email_recovery_" + UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(unused: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(name, mode)
        }
        try {
            val draft = EmailRecoveryDraft("qa@example.invalid", code = "001234", expiresAt = System.currentTimeMillis() + 60_000,
                deliveryConfirmed = false)
            EmailRecoveryDraftStore(isolated).save(draft)
            assertEquals(draft, EmailRecoveryDraftStore(isolated).read())
            val verified = draft.copy(code = "", resetToken = "synthetic-grant-only")
            EmailRecoveryDraftStore(isolated).save(verified)
            assertEquals(verified, EmailRecoveryDraftStore(isolated).read())
            val raw = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
            assertFalse("Recovery address stored as plaintext", raw.contains(draft.email))
            assertFalse("Recovery grant stored as plaintext", raw.contains(verified.resetToken))
            EmailRecoveryDraftStore(isolated).save(draft.copy(expiresAt = 1))
            assertNull(EmailRecoveryDraftStore(isolated).read())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
