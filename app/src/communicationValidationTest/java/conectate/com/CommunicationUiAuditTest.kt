package conectate.com

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.CallInvite
import conectate.com.data.service.IncomingCallNotificationFactory
import conectate.com.presentation.auth.EmailRecoveryDraft
import conectate.com.presentation.auth.EmailRecoverySteps
import conectate.com.presentation.calls.IncomingCallActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationUiAuditTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
        val folder = instrumentation.targetContext.getExternalFilesDir("communication-qa")!!
        folder.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun codeAndPasswordAreSeparateAndConfirmationIsRequired() {
        val draft = mutableStateOf(EmailRecoveryDraft("qa@example.invalid", code="12345", expiresAt=System.currentTimeMillis()+600000))
        compose.setContent { MaterialTheme { Column(Modifier.padding(20.dp)) {
            EmailRecoverySteps(draft.value, false, "", "", {}, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("email_recovery_verify").assertIsNotEnabled()
        compose.onNodeWithTag("email_recovery_password").assertDoesNotExist()
        compose.runOnIdle { draft.value=draft.value.copy(code="123456") }
        compose.onNodeWithTag("email_recovery_verify").assertIsEnabled()
        screenshot("email-code-step")
        compose.runOnIdle { draft.value=draft.value.copy(code="",resetToken="test-only-grant") }
        compose.onNodeWithTag("email_recovery_code").assertDoesNotExist()
        compose.onNodeWithTag("email_recovery_password").assertExists()
        compose.onNodeWithTag("email_recovery_confirmation").assertExists()
        compose.onNodeWithTag("email_recovery_save").assertIsNotEnabled()
        screenshot("email-password-step")
    }
    @Test fun incomingNotificationIncludesAnswerDeclineAndFullscreenIntent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val invite=CallInvite("77777777-7777-4777-8777-777777777777", "Llamada de prueba", "88888888-8888-4888-8888-888888888888", "Cuenta QA", null, "dm", null, false)
        IncomingCallNotificationFactory.ensureChannel(context)
        val notification=IncomingCallNotificationFactory.build(context,invite,null,null)
        assertEquals(Notification.CATEGORY_CALL,notification.category)
        assertNotNull(notification.fullScreenIntent)
        assertTrue(notification.actions.size>=2)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val manager=context.getSystemService(NotificationManager::class.java)
        val id=invite.callId.hashCode()
        try { manager.notify(id,notification); Thread.sleep(500); screenshot("incoming-call-notification") }
        finally { manager.cancel(id) }
        val intent=Intent(context,IncomingCallActivity::class.java).apply {
            putExtra("call_id",invite.callId); putExtra("caller_user_id",invite.callerUserId)
            putExtra("caller_name",invite.callerName); putExtra("call_title",invite.title)
            putExtra("call_context_type","dm"); putExtra("call_is_video",false)
        }
        ActivityScenario.launch<IncomingCallActivity>(intent).use { screenshot("incoming-call-controls") }
    }
}
