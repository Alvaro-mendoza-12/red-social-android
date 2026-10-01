package conectate.com

import android.app.Notification
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import conectate.com.presentation.social.components.RoundStatusCard
import conectate.com.presentation.theme.appColorScheme
import conectate.com.data.local.prefs.AppThemeOption
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationUiAuditTest {
    @get:Rule val compose = createComposeRule()
    @Test fun systemNoticeShowsLocalScheduleAndAnOptionalUpdateAction() {
        val maintenance = conectate.com.data.localfallback.SystemAnnouncement(
            "qa", "maintenance", "Mantenimiento programado", "Podrás volver a usar Novvin cuando finalicen los trabajos.",
            "2030-10-02T23:00:00Z", startsAt = "2030-10-01T21:00:00Z", endsAt = "2030-10-01T22:00:00Z",
        )
        val notice = mutableStateOf(maintenance)
        var closed = 0
        compose.setContent { MaterialTheme {
            conectate.com.presentation.common.SystemAnnouncementDialog(notice.value) { closed++ }
        } }
        compose.onNodeWithText("Mantenimiento programado").assertIsDisplayed()
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.onNodeWithText("Entendido").performClick()
        assertEquals(1, closed)
        screenshot("system-notice-maintenance")
        compose.runOnIdle { notice.value = maintenance.copy(kind = "update", title = "Nueva versión de Novvin", versionCode = 118) }
        compose.onNodeWithText("Actualizar").assertIsDisplayed()
        compose.onNodeWithText("Más tarde").performClick()
        assertEquals(2, closed)
        screenshot("system-notice-update")
    }
    @Test fun systemNoticeAdminPreviewsBeforePublishingAndFitsLargeText() {
        var published: conectate.com.data.localfallback.PublishSystemAnnouncement? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().width(300.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        conectate.com.presentation.profile.SystemAnnouncementAdminForm(true, false) { published = it }
                    }
                } }
            }
        }
        compose.onNodeWithText("Actualización").performScrollTo().performClick()
        compose.onNodeWithText("Título").performScrollTo().performTextInput("Nueva versión de Novvin")
        compose.onNodeWithText("Mensaje para los usuarios").performScrollTo().performTextInput("Actualiza para recibir las mejoras de mensajería.")
        compose.onNodeWithText("Código de versión publicado en Google Play").performScrollTo().performTextInput("118")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Revisar antes de publicar").performScrollTo().performClick()
        assertNull(published)
        compose.onNodeWithText("Publicar para todas las cuentas").assertIsDisplayed()
        screenshot("system-notice-admin-preview")
        compose.onNodeWithText("Publicar aviso").performClick()
        assertEquals(118, published?.versionCode)
        assertEquals("update", published?.kind)
    }
    @Test fun attachmentProgressAndFailureOfferDifferentActions() {
        val progress = mutableStateOf<Float?>(.42f)
        val status = mutableStateOf("sending")
        var attempts = 0
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                conectate.com.presentation.messages.MessageDeliveryControls(status.value, progress.value) { attempts++ }
            }
        } } }
        compose.onNodeWithText("Subiendo 42 %").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
        compose.runOnIdle { progress.value = null; status.value = "failed" }
        compose.onNodeWithText("No se envió").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, attempts)
        screenshot("attachment-retry")
        compose.runOnIdle { status.value = "sent" }
        compose.onNodeWithTag("message_delivery").assertDoesNotExist()
    }

    @Test fun pollSupportsRemovingChangingAndInspectingAVote() {
        val state = mutableStateOf(listOf(
            conectate.com.data.model.MessagePollOptionState("poll", "a", "Hoy por la tarde", votedByMe = true, voteCount = 1, totalVotes = 1),
            conectate.com.data.model.MessagePollOptionState("poll", "b", "Mañana por la mañana", totalVotes = 1),
        ))
        val events = mutableListOf<Pair<String, Boolean>>()
        var inspected = ""
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().width(320.dp).verticalScroll(rememberScrollState())) {
                conectate.com.presentation.messages.PollMessageCard(
                    conectate.com.data.model.Message(content_type = "poll", content = "¿Cuándo nos reunimos?"),
                    state.value, false,
                    onVote = { id, selected -> events += id to selected },
                    onOptionDetails = { inspected = it.optionId },
                )
            }
        } } }
        compose.onNodeWithText("Quitar voto").assertIsDisplayed().performClick()
        compose.onNodeWithText("Votar").assertIsDisplayed().performClick()
        compose.onNodeWithText("Hoy por la tarde").performClick()
        assertEquals(listOf("a" to false, "b" to true), events)
        assertEquals("a", inspected)
        screenshot("poll-change-remove-vote")
    }
    @Test fun profileMessageIsLabelledClickableAndReadableWithLargeText() {
        var opened = 0
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme(colorScheme = appColorScheme(AppThemeOption.CONECTATE, dark.value)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().width(280.dp).padding(16.dp)) {
                            conectate.com.presentation.profile.ProfileMessageButton(onClick = { opened++ })
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.onNodeWithText("Mensaje").assertIsDisplayed()
            compose.onNodeWithTag("profile_message").assertIsEnabled().performClick()
            assertTrue(compose.onNodeWithTag("profile_message").fetchSemanticsNode().boundsInRoot.height >=
                with(InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics) { 48 * density })
            screenshot("profile-message-${if (night) "dark" else "light"}-large-text")
        }
        assertEquals(2, opened)
    }
    @Test fun roomLinksOfferRetryAndCloseWhenThePageCannotLoad() {
        val opened=mutableStateOf(true)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme {
            if(opened.value) conectate.com.presentation.voicerooms.VoiceRoomLinkDialog(
                "https://novvin-qa-does-not-exist.invalid",onDismiss={ opened.value=false })
        } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("room_link_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("room_link_retry").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("room_link_error").fetchSemanticsNodes().isNotEmpty() }
        screenshot("voice-room-link-error-retry")
        compose.onNodeWithContentDescription(context.getString(R.string.room_link_close)).performClick()
        compose.onNodeWithTag("room_link_error").assertDoesNotExist()
    }
    @Test fun liveCollaborationOffersAnExplicitRetryAndClosesWhenItsPlanEnds() {
        val state = mutableStateOf(conectate.com.data.livekit.LiveKitRoomBridge.State.DISCONNECTED)
        val plan = mutableStateOf<conectate.com.presentation.live.LiveViewerCollaborationPlan?>(
            conectate.com.presentation.live.LiveViewerCollaborationPlan("isolated-live", "viewer", false, false))
        var attempts = 0
        var disconnects = 0
        compose.setContent { MaterialTheme {
            conectate.com.presentation.live.LiveViewerCollaborationConnectionOwner(
                plan = plan.value, transportState = state.value, leaving = false,
                connect = {
                    attempts++
                    if (attempts == 1) Result.failure(Exception("isolated failure"))
                    else { state.value = conectate.com.data.livekit.LiveKitRoomBridge.State.CONNECTED; Result.success(Unit) }
                },
                disconnect = { disconnects++ }
            )
        } }
        compose.onNodeWithText("Reintentar").assertIsDisplayed().assertIsEnabled()
        screenshot("live-collaboration-retry")
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitForIdle()
        assertEquals(2, attempts)
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
        compose.runOnIdle { plan.value = null }
        compose.waitForIdle()
        assertEquals(1, disconnects)
    }

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
        try {
            // Verify visibility outside the app, not just the Notification object.
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            fun shell(command: String) {
                // Consume the descriptor so the shell command finishes before inspecting windows.
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(500)
            manager.notify(id,notification)
            Thread.sleep(1_500)
            screenshot("incoming-call-notification-background")
            val serviceInfo=automation.serviceInfo
            val previousFlags=serviceInfo.flags
            serviceInfo.flags=previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo=serviceInfo
            fun nodeLabels(node: AccessibilityNodeInfo?): List<String> {
                if (node==null) return emptyList()
                return listOfNotNull(node.text?.toString(),node.contentDescription?.toString()) +
                    (0 until node.childCount).flatMap { nodeLabels(node.getChild(it)) }
            }
            val answerVisible = try {
                // OEM heads-up overlays may be excluded from accessibility.
                // Check the persistent call notification in the shade as well.
                fun hasAnswer(): Boolean {
                    val labels=automation.windows.flatMap { window -> nodeLabels(window.root) } + nodeLabels(automation.rootInActiveWindow)
                    return labels.any { text -> listOf("Contestar","Answer","Responder").any { text.contains(it,ignoreCase=true) } }
                }
                var visible=hasAnswer()
                if (!visible) shell("cmd statusbar expand-notifications")
                val deadline=android.os.SystemClock.elapsedRealtime()+5_000L
                while (!visible && android.os.SystemClock.elapsedRealtime()<deadline) {
                    runCatching { automation.waitForIdle(100L, 800L) }
                    visible=hasAnswer()
                    if (!visible) Thread.sleep(150)
                }
                visible
            } finally {
                shell("cmd statusbar collapse")
                serviceInfo.flags=previousFlags; automation.serviceInfo=serviceInfo
            }
            assertTrue("Android did not display an answer control outside the app; inspect call channel permissions",answerVisible)
        }
        finally { manager.cancel(id) }
        val intent=Intent(context,IncomingCallActivity::class.java).apply {
            putExtra("call_id",invite.callId); putExtra("caller_user_id",invite.callerUserId)
            putExtra("caller_name",invite.callerName); putExtra("call_title",invite.title)
            putExtra("call_context_type","dm"); putExtra("call_is_video",false)
        }
        ActivityScenario.launch<IncomingCallActivity>(intent).use { screenshot("incoming-call-controls") }
    }

    @Test fun roundCardsSupportLongTitlesLargeTextAndSeparateRefresh() {
        val status = mutableStateOf("voting")
        val dark = mutableStateOf(false)
        var refreshed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme(colorScheme = appColorScheme(AppThemeOption.CONECTATE, dark.value)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        RoundStatusCard("Comparte una pequeña historia que haya mejorado el día de alguien en tu comunidad",
                            status.value, 75, { refreshed++ })
                    }
                }
            }
        }
        for (state in listOf("voting", "completed", "active")) {
            compose.runOnIdle { status.value = state }
            compose.onNodeWithTag("round_title").assertIsDisplayed()
            compose.onNodeWithTag("round_refresh").performClick()
            screenshot("round-$state-light-large-text")
        }
        compose.runOnIdle { dark.value = true; status.value = "voting" }
        compose.onNodeWithTag("round_title").assertIsDisplayed()
        screenshot("round-voting-dark-large-text")
        assertEquals(3, refreshed)
    }
}
