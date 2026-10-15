package conectate.com.util

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ConversationNotificationDeviceAuditTest {
    @Test fun nativeConversationHasAvatarHistoryActionsAndPrivateLockScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { p ->
            ParcelFileDescriptor.AutoCloseInputStream(p).use { it.readBytes() }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val avatar = Bitmap.createBitmap(104,104,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val payload = MessageNotificationPayload("notification_device_audit", "Mensajes de prueba local", "Fixture sin red", "owned-local-conversation", "group", "Grupo de clase", "Adolfo", "Bacán", 740031,
            senderId = "owned-sender", messageId = "first", largeIcon = avatar, conversationIcon = avatar, isGroupConversation = true, notificationAccountTarget = NotificationAccountTarget(userId = "owned-recipient"))
        try {
            manager.notify(payload.notificationId, MessageNotificationFactory.build(context,payload))
            Thread.sleep(300)
            val next = MessageNotificationFactory.build(context,payload.copy(messageId = "second", messageBody = "Jajaja"))
            manager.notify(payload.notificationId,next)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(next)!!
            assertEquals("Tú",style.user.name.toString())
            assertEquals("Grupo de clase",style.conversationTitle.toString())
            assertEquals(listOf("Bacán","Jajaja"),style.messages.map { it.text.toString() })
            assertEquals(listOf("Responder","Marcar como leído","Silenciar 8 h"),next.actions.map { it.title.toString() })
            assertEquals(1,next.actions.first().remoteInputs.size)
            assertNotNull(next.getLargeIcon()); assertNotNull(style.messages.last().person?.icon)
            assertNotNull(next.shortcutId)
            assertTrue(context.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts.any { it.id == next.shortcutId && it.isDynamic && it.isEnabled && it.locusId?.id == next.shortcutId })
            assertEquals("Nuevo mensaje",next.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).first { it.id == next.shortcutId }
            val route = Intent(shortcut.intent)
            assertTrue(TrustedNavigationIntents.isTrusted(context,route))
            route.putExtra("group_id","different-conversation")
            assertFalse(TrustedNavigationIntents.isTrusted(context,route))
            val other = MessageNotificationFactory.build(context,payload.copy(notificationAccountTarget = NotificationAccountTarget(userId="other"),allowInlineActions=false,messageBody="Otra cuenta"))
            assertEquals(1,NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(other)!!.messages.size)
            assertTrue(other.actions.isNullOrEmpty())
            val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
            val automation = instrumentation.uiAutomation
            val service = automation.serviceInfo
            val oldFlags = service.flags
            service.flags = oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = service
            fun labels(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> =
                if (node == null) emptyList() else listOfNotNull(node.text?.toString(),node.contentDescription?.toString()) + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
            try {
                // Read the command descriptor to completion; closing it immediately can abort the command.
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar expand-notifications")).use { it.readBytes() }
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
                var visible = false
                while (!visible && android.os.SystemClock.elapsedRealtime() < deadline) {
                    runCatching { automation.waitForIdle(100,800) }
                    visible = (automation.windows.flatMap { labels(it.root) } + labels(automation.rootInActiveWindow)).any { it.contains("Grupo de clase") }
                    if (!visible) Thread.sleep(150)
                }
                automation.takeScreenshot().also { screenshot ->
                    java.io.File(folder,"conversation-notification-expanded.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                assertTrue("The native shade must display the actual conversation title", visible)
            } finally {
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar collapse")).use { it.readBytes() }
                service.flags = oldFlags; automation.serviceInfo = service
            }
        } finally { manager.cancel(payload.notificationId); avatar.recycle() }
    }
    @Test fun historyIsBoundedDeduplicatedAndActionsAreAccountBound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val manager = context.getSystemService(NotificationManager::class.java)
        val payload = MessageNotificationPayload("notification_device_audit","Mensajes de prueba local","Fixture sin red","owned-history","chat","Amigo","Amigo","Mensaje",740032,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            for (i in 1..15) { manager.notify(payload.notificationId,MessageNotificationFactory.build(context,payload.copy(messageId="$i"))); Thread.sleep(350) }
            val duplicate = MessageNotificationFactory.build(context,payload.copy(messageId="15"))
            assertEquals(10,NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(duplicate)!!.messages.size)
            val secondAccount = MessageNotificationFactory.build(context,payload.copy(notificationAccountTarget=NotificationAccountTarget(userId="other-recipient")))
            assertNotEquals(duplicate.actions[0].actionIntent,secondAccount.actions[0].actionIntent)
            val unbound = MessageNotificationFactory.build(context,payload.copy(notificationAccountTarget=null))
            assertTrue(unbound.actions.isNullOrEmpty())
        } finally { manager.cancel(payload.notificationId) }
    }
    private fun photoPixel(icon: android.graphics.drawable.Icon?, context: android.content.Context): Int {
        val drawable = requireNotNull(icon?.loadDrawable(context))
        val image = Bitmap.createBitmap(312,312,Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,312,312); drawable.draw(android.graphics.Canvas(image))
        return image.getPixel(156,156).also { image.recycle() }
    }

    private fun photoPixel(icon: androidx.core.graphics.drawable.IconCompat?, context: android.content.Context): Int {
        val drawable = requireNotNull(icon?.loadDrawable(context))
        val image = Bitmap.createBitmap(312,312,Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,312,312); drawable.draw(android.graphics.Canvas(image))
        return image.getPixel(156,156).also { image.recycle() }
    }

    @Test fun incompleteDuplicateNeverReplacesKnownSenderOrGroupPhotoWithInitials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val group = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-photo-consistency","group","Grupo","Ana","Hola",740041,
            senderId="sender-one",messageId="same-message",largeIcon=photo,conversationIcon=group,isGroupConversation=true,
            notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            assertTrue(MessageNotificationFactory.post(context,p))
            val incomplete = p.copy(largeIcon=null,conversationIcon=null,messageBody="Actualización incompleta")
            val update = MessageNotificationFactory.build(context,incomplete)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(update)!!
            assertEquals(1,style.messages.size)
            assertEquals(Color.MAGENTA,photoPixel(style.messages.last().person?.icon,context))
            assertEquals(Color.GREEN,photoPixel(update.getLargeIcon(),context))
            assertTrue(MessageNotificationFactory.post(context,incomplete))
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var delivered: android.app.Notification? = null
            while (delivered == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                delivered = manager.activeNotifications.firstOrNull {
                    it.id == p.notificationId &&
                        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)
                            ?.messages?.lastOrNull()?.text?.toString() == incomplete.messageBody
                }?.notification
                if (delivered == null) Thread.sleep(100)
            }
            val actual = requireNotNull(delivered) { "The updated native notification was not delivered" }
            assertEquals(Color.GREEN,photoPixel(actual.getLargeIcon(),context))
            assertEquals(Color.MAGENTA,photoPixel(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(actual)!!
                .messages.single().person?.icon,context))
        } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"group",p.conversationId); photo.recycle(); group.recycle() }
    }

    @Test fun actualPhotoUpgradesOlderFallbackRowsFromTheSameSender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-photo-upgrade","group","Grupo","Ana","Primero",740042,
            senderId="sender-one",messageId="first",isGroupConversation=true,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            assertTrue(MessageNotificationFactory.post(context,p))
            val next = MessageNotificationFactory.build(context,p.copy(largeIcon=photo,messageId="second",messageBody="Segundo"))
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(next)!!
            assertEquals(2,style.messages.size)
            style.messages.forEach { assertEquals(Color.MAGENTA,photoPixel(it.person?.icon,context)) }
        } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"group",p.conversationId); photo.recycle() }
    }

    @Test fun differentSenderCannotInheritAnotherMembersPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-photo-isolation","group","Grupo","Ana","Hola",740043,
            senderId="sender-one",messageId="first",largeIcon=photo,isGroupConversation=true,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            assertTrue(MessageNotificationFactory.post(context,p))
            val update = MessageNotificationFactory.build(context,p.copy(senderId="sender-two",senderName="Luis",messageId="second",largeIcon=null))
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(update)!!
            assertEquals(Color.MAGENTA,photoPixel(style.messages.first().person?.icon,context))
            assertNotEquals(Color.MAGENTA,photoPixel(style.messages.last().person?.icon,context))
        } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"group",p.conversationId); photo.recycle() }
    }

    @Test fun anotherAccountCannotInheritPriorRecipientsPhotosOrHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-photo-account","chat","Ana","Ana","Hola",740044,
            senderId="sender-one",messageId="first",largeIcon=photo,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            assertTrue(MessageNotificationFactory.post(context,p))
            val update = MessageNotificationFactory.build(context,p.copy(notificationAccountTarget=NotificationAccountTarget(userId="other-recipient"),largeIcon=null))
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(update)!!
            assertEquals(1,style.messages.size)
            assertNotEquals(Color.MAGENTA,photoPixel(style.messages.single().person?.icon,context))
            assertNotEquals(Color.MAGENTA,photoPixel(update.getLargeIcon(),context))
        } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"chat",p.conversationId); photo.recycle() }
    }

    @Test fun incompleteGroupIdentityCannotShareAPhotoAcrossMembers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val second = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            listOf(null,"","unknown").forEachIndexed { index, missingId ->
                val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-incomplete-identity-$index","group","Grupo","Ana","Primero",740050+index,
                    senderId=missingId,messageId="first",largeIcon=first,isGroupConversation=true,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
                try {
                    assertTrue(MessageNotificationFactory.post(context,p))
                    val incomplete = MessageNotificationFactory.build(context,p.copy(senderName="Luis",messageId="second",messageBody="Segundo",largeIcon=null))
                    val old = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(incomplete)!!.messages
                    assertEquals(Color.MAGENTA,photoPixel(old.first().person?.icon,context))
                    assertNotEquals(Color.MAGENTA,photoPixel(old.last().person?.icon,context))
                    val richer = MessageNotificationFactory.build(context,p.copy(senderName="Luis",messageId="second",largeIcon=second))
                    val corrected = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(richer)!!.messages
                    assertEquals(Color.MAGENTA,photoPixel(corrected.first().person?.icon,context))
                    assertEquals(Color.GREEN,photoPixel(corrected.last().person?.icon,context))
                } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"group",p.conversationId) }
            }
        } finally { first.recycle(); second.recycle() }
    }

    @Test fun incompleteDuplicateWithSameMessageAndNameKeepsKnownPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val p = MessageNotificationPayload("notification_device_audit","Local","Fixture","owned-incomplete-duplicate","group","Grupo","Ana","Hola",740054,
            senderId=null,messageId="same",largeIcon=photo,isGroupConversation=true,notificationAccountTarget=NotificationAccountTarget(userId="owned-recipient"))
        try {
            assertTrue(MessageNotificationFactory.post(context,p))
            val same = MessageNotificationFactory.build(context,p.copy(largeIcon=null))
            assertEquals(Color.MAGENTA,photoPixel(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(same)!!.messages.single().person?.icon,context))
            val other = MessageNotificationFactory.build(context,p.copy(senderName="Luis",largeIcon=null))
            assertNotEquals(Color.MAGENTA,photoPixel(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(other)!!.messages.single().person?.icon,context))
        } finally { MessageNotificationFactory.cancelConversation(context,p.notificationAccountTarget,"group",p.conversationId); photo.recycle() }
    }

}
