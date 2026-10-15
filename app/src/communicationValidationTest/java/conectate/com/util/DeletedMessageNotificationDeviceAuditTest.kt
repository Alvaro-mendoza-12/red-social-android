package conectate.com.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.Message
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owned fixtures only: no FCM server, account login, network, or production data. */
class DeletedMessageNotificationDeviceAuditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun isolateOsQuotaFromPreviousLegacyFixtures() {
        // Legacy migration fixtures intentionally call NotificationManager
        // directly. Separate tests, retaining rapid posts inside each case.
        Thread.sleep(2_000L)
    }
    private fun grant() {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
    }
    private fun payload(type: String = "chat", channel: String? = null): MessageNotificationPayload {
        val unique = UUID.randomUUID().toString()
        return MessageNotificationPayload("deleted_message_device_audit", "Eliminación local", "Fixture sin red",
            "owned-room-$unique", type, "Sala de eliminación", "Autor local", "secreto-$unique", 740041,
            messageId = "owned-message-$unique", senderId = "owned-sender", isGroupConversation = type != "chat",
            notificationAccountTarget = NotificationAccountTarget(userId = "owned-account-$unique"), channelContextId = channel)
    }
    private fun identity(p: MessageNotificationPayload) = DeletedMessageIdentity.create(
        p.notificationAccountTarget?.userId, p.targetType, p.conversationId, p.messageId)!!
    private fun scope(p: MessageNotificationPayload) = ConversationNotificationIdentity.scope(
        p.notificationAccountTarget, p.targetType, p.conversationId, p.channelContextId)
    private fun active(p: MessageNotificationPayload, ready: (Notification) -> Boolean = { true }): android.service.notification.StatusBarNotification? {
        val deadline = android.os.SystemClock.elapsedRealtime() + 4_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.notification.extras.getString(MessageNotificationFactory.EXTRA_SCOPE) == scope(p) && ready(it.notification) }?.let { return it }
            Thread.sleep(60)
        }
        return null
    }
    private fun texts(p: MessageNotificationPayload): List<String> = NotificationCompat.MessagingStyle
        .extractMessagingStyleFromNotification(active(p)!!.notification)!!.messages.map { it.text.toString() }
    private fun awaitTexts(p: MessageNotificationPayload, expected: List<String>): Notification {
        val current = active(p) { value -> NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(value)?.messages?.map { it.text.toString() } == expected }
        assertNotNull("OS notification did not reach $expected within 4 seconds", current)
        return current!!.notification
    }
    private fun clear(vararg p: MessageNotificationPayload) = p.forEach {
        MessageNotificationFactory.cancelConversation(context, it.notificationAccountTarget, it.targetType, it.conversationId)
    }

    @Test fun publishedPrivateGroupCommunityTextImageAudioBecomeSilentPlaceholder() {
        grant()
        for ((type, media) in listOf("chat" to "text", "group" to "image", "community" to "audio")) {
            val p = payload(type, if (type == "community") "owned-channel" else null)
            try {
                MessageNotificationFactory.post(context, p)
                assertEquals(listOf(p.messageBody), texts(p))
                MessageNotificationFactory.redactMessage(context, identity(p), "2026-10-11T12:00:00Z", channelContextId = p.channelContextId)
                val notification = awaitTexts(p, listOf("Mensaje eliminado"))
                assertEquals(listOf("Mensaje eliminado"), texts(p))
                assertEquals("Mensaje eliminado", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertNull(notification.sound); assertNull(notification.vibrate)
                assertEquals(0, notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
                val safe = DeletedMessageStore(context).redact(Message(id=p.messageId!!,chat_id=p.conversationId,
                    content=p.messageBody,content_type=media,media_url="https://owned.invalid/media",thumbnail_url="thumbnail",deleted_for_all=true),p.notificationAccountTarget?.userId)
                assertEquals("",safe.content); assertNull(safe.media_url); assertNull(safe.thumbnail_url)
                if (type == "community") captureShade("deleted-message-notification-expanded.png")
            } finally { clear(p) }
        }
    }

    @Test fun deletionBeforeLatePushRemainsSuppressedAfterStoreReopen() {
        grant(); val p = payload()
        try {
            MessageNotificationFactory.redactMessage(context, identity(p))
            assertTrue(DeletedMessageStore(context).isSuppressed(identity(p)))
            repeat(3) { MessageNotificationFactory.post(context, p) }
            Thread.sleep(300)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(MessageNotificationFactory.EXTRA_SCOPE) == scope(p) })
        } finally { clear(p) }
    }

    @Test fun sameIdAndIntegerInAnotherAccountAndConversationStayVisible() {
        grant(); val a = payload("group")
        val b = a.copy(notificationAccountTarget=NotificationAccountTarget(userId="owned-other-${UUID.randomUUID()}"),messageBody="another account")
        val c = a.copy(conversationId="owned-other-room-${UUID.randomUUID()}",messageBody="another conversation")
        try {
            // Existing untagged notifications must migrate only by the exact account/conversation scope.
            manager.notify(a.notificationId, MessageNotificationFactory.build(context,a)); assertNotNull(active(a))
            MessageNotificationFactory.post(context,a)
            MessageNotificationFactory.post(context,b); MessageNotificationFactory.post(context,c)
            assertEquals(scope(a),active(a)!!.tag)
            MessageNotificationFactory.redactMessage(context,identity(a))
            awaitTexts(a,listOf("Mensaje eliminado"))
            assertEquals(listOf("Mensaje eliminado"),texts(a))
            assertEquals(listOf("another account"),texts(b)); assertEquals(listOf("another conversation"),texts(c))
        } finally { clear(a,b,c); manager.cancel(a.notificationId) }
    }

    @Test fun deleteForMeRemovesOnlyOwnEntryAndLeavesOtherAccountContent() {
        grant(); val a=payload(); val b=a.copy(notificationAccountTarget=NotificationAccountTarget(userId="other-${UUID.randomUUID()}"))
        val next=a.copy(messageId="next-${UUID.randomUUID()}",messageBody="mensaje restante")
        try {
            MessageNotificationFactory.post(context,a); assertNotNull(active(a))
            MessageNotificationFactory.post(context,next); MessageNotificationFactory.post(context,b)
            MessageNotificationFactory.redactMessage(context,identity(a),forEveryone=false)
            awaitTexts(a,listOf("mensaje restante"))
            assertEquals(listOf("mensaje restante"),texts(a)); assertEquals(listOf(b.messageBody),texts(b))
            assertFalse(DeletedMessageStore(context).isDeleted(identity(a)))
            val original=Message(id=a.messageId!!,chat_id=a.conversationId,content=a.messageBody)
            assertFalse(DeletedMessageStore(context).redact(original,a.notificationAccountTarget?.userId).deleted_for_all)
        } finally { clear(a,b) }
    }

    @Test fun cancellationAndLatePushOrActionCannotResurrectDeletedContent() {
        grant(); val p=payload(); val newer=p.copy(messageId="new-${UUID.randomUUID()}",messageBody="contenido nuevo")
        try {
            MessageNotificationFactory.post(context,p); assertNotNull(active(p))
            MessageNotificationFactory.redactMessage(context,identity(p)); clear(p)
            MessageNotificationFactory.post(context,p)
            MessageNotificationFactory.finishAction(context,scope(p),p.messageId,p.notificationId,"retry")
            Thread.sleep(300)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(MessageNotificationFactory.EXTRA_SCOPE)==scope(p) })
            MessageNotificationFactory.post(context,newer)
            assertEquals(listOf("contenido nuevo"),texts(newer))
            MessageNotificationFactory.finishAction(context,scope(p),p.messageId,p.notificationId,"retry")
            assertEquals(listOf("contenido nuevo"),texts(newer))
        } finally { clear(p) }
    }

    @Test fun legacyCommunityChannelAndOtherHistoryMessageArePreserved() {
        grant(); val p=payload("community","owned-channel-${UUID.randomUUID()}")
        val next=p.copy(messageId="next-${UUID.randomUUID()}",messageBody="otro mensaje")
        try {
            manager.notify(p.notificationId,MessageNotificationFactory.build(context,p)); assertNotNull(active(p))
            val legacy=MessageNotificationFactory.build(context,next)
            // Reproduce notifications already displayed by the prior factory, whose only routing metadata was scope.
            listOf("novvin_notification_account","novvin_notification_target_type","novvin_notification_conversation","novvin_notification_channel_context").forEach(legacy.extras::remove)
            manager.notify(p.notificationId,legacy)
            awaitTexts(p,listOf(p.messageBody,"otro mensaje"))
            MessageNotificationFactory.redactMessage(context,identity(p),channelContextId=p.channelContextId)
            awaitTexts(p,listOf("Mensaje eliminado","otro mensaje"))
            assertEquals(listOf("Mensaje eliminado","otro mensaje"),texts(p))
            assertEquals(scope(p),active(p)!!.notification.extras.getString(MessageNotificationFactory.EXTRA_SCOPE))
        } finally { clear(p); manager.cancel(p.notificationId) }
    }

    @Test fun immediatePostsAndRecallPreserveHistoryBeforeOsEnqueueCompletes() {
        grant(); val p=payload(); val next=p.copy(messageId="rapid-${UUID.randomUUID()}",messageBody="mensaje posterior")
        try {
            MessageNotificationFactory.post(context,p)
            MessageNotificationFactory.post(context,next)
            MessageNotificationFactory.redactMessage(context,identity(p))
            awaitTexts(p,listOf("Mensaje eliminado","mensaje posterior"))
            assertEquals(listOf("Mensaje eliminado","mensaje posterior"),texts(p))
        } finally { clear(p) }
    }

    @Test fun legacyIntegerReusedByDifferentAccountKeepsOwnHistory() {
        grant(); val a=payload(); val b=payload("community","owned-other-channel")
        val next=b.copy(messageId="legacy-next-${UUID.randomUUID()}",messageBody="otro mensaje")
        try {
            manager.notify(a.notificationId,MessageNotificationFactory.build(context,a))
            awaitTexts(a,listOf(a.messageBody))
            clear(a)
            manager.notify(b.notificationId,MessageNotificationFactory.build(context,b))
            awaitTexts(b,listOf(b.messageBody))
            MessageNotificationFactory.post(context,next)
            awaitTexts(b,listOf(b.messageBody,"otro mensaje"))
            assertEquals(listOf(b.messageBody,"otro mensaje"),texts(b))
        } finally { clear(a,b); manager.cancel(b.notificationId) }
    }

    @Test fun eightImmediateMessagesAndDeletionCoalesceWithoutLosingHistory() {
        grant(); val first=payload()
        val messages=(0 until 8).map { first.copy(messageId="burst-$it-${UUID.randomUUID()}",messageBody="mensaje $it") }
        try {
            messages.forEach { MessageNotificationFactory.post(context,it) }
            MessageNotificationFactory.redactMessage(context,identity(messages[3]))
            awaitTexts(first,(0 until 8).map { if(it==3) "Mensaje eliminado" else "mensaje $it" })
            assertFalse(texts(first).contains("mensaje 3"))
            clear(first)
            Thread.sleep(500)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(MessageNotificationFactory.EXTRA_SCOPE)==scope(first) })
        } finally { clear(first) }
    }

    private fun captureShade(name:String) {
        val automation=instrumentation.uiAutomation
        val service=automation.serviceInfo
        val oldFlags=service.flags
        service.flags=oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo=service
        try {
            assertTrue("Android must accept the notification shade action", automation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            // The notification shade can be an interactive, non-focused window.
            // A focused launcher root does not establish that SystemUI is absent.
            val deadline=android.os.SystemClock.elapsedRealtime()+8_000L
            var visible=false
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val roots=automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                if(roots.any { root -> root.packageName?.toString()=="com.android.systemui" &&
                    root.findAccessibilityNodeInfosByText("Mensaje eliminado").any { it.isVisibleToUser } }) {
                    visible=true;break
                }
                Thread.sleep(80L)
            }
            runCatching { automation.waitForIdle(150,1_000) }
            val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
            val screenshot=automation.takeScreenshot()
            checkNotNull(screenshot)
            File(folder,name).outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))
            }
            screenshot.recycle()
            // MessagingStyle, EXTRA_TEXT, silent flags and persisted redaction
            // were asserted before this capture. SystemUI can render the text
            // without exposing a searchable accessibility node on Android35.
            // This screenshot requires the separate, exact-image visual review;
            // a successful fixture run alone does not accept its appearance.
            File(folder,"deleted-message-shade-capture.json").writeText(
                "{\"androidGlobalActionAccepted\":true,\"accessibilityTextMatched\":$visible,\"manualVisualReviewRequired\":true}")
        } finally {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar collapse")).use { it.readBytes() }
            service.flags=oldFlags;automation.serviceInfo=service
        }
    }
}
