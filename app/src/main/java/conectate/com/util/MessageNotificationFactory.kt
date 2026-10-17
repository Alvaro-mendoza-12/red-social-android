package conectate.com.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.IconCompat
import conectate.com.MainActivity
import conectate.com.R
import conectate.com.data.service.MessageNotificationActionReceiver
import java.util.UUID

data class MessageNotificationPayload(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val conversationId: String,
    val targetType: String,
    val conversationTitle: String,
    val senderName: String,
    val messageBody: String,
    val notificationId: Int,
    val senderId: String? = null,
    val messageId: String? = null,
    val largeIcon: Bitmap? = null,
    val isGroupConversation: Boolean = false,
    val notificationAccountTarget: NotificationAccountTarget? = null,
    val allowInlineActions: Boolean = true,
    val conversationIcon: Bitmap? = null,
    val channelContextId: String? = null,
    val silent: Boolean = false
)

object MessageNotificationFactory {
    const val EXTRA_SCOPE = "novvin_conversation_scope"
    const val EXTRA_LAST_MESSAGE_ID = "novvin_last_message_id"
    private const val MESSAGE_ID = "novvin_message_id"
    private const val REAL_SENDER_ICON = "novvin_real_sender_icon"
    private const val REAL_CONVERSATION_ICON = "novvin_real_conversation_icon"
    private const val HISTORY_LIMIT = 10
    private const val EXTRA_TYPE = "novvin_notification_target_type"
    private const val EXTRA_CONVERSATION = "novvin_notification_conversation"
    private const val EXTRA_ACCOUNT = "novvin_notification_account"
    private const val EXTRA_CHANNEL_CONTEXT = "novvin_notification_channel_context"
    private const val EXTRA_TITLE = "novvin_notification_title"
    private const val EXTRA_SENDER_NAME = "novvin_notification_sender_name"
    private const val EXTRA_SENDER_ID = "novvin_notification_sender_id"

    private data class Published(val tag: String?, val id: Int, val notification: Notification, val atMs: Long)
    // NotificationManager enqueues updates asynchronously. Keep only a bounded
    // in-flight/active snapshot so a following message or recall cannot read an
    // older OS snapshot and discard history or miss a just-posted notification.
    private val published = linkedMapOf<Pair<String?, Int>, Published>()
    private data class Cancelled(val scope: String?, val atMs: Long)
    private val cancelled = linkedMapOf<Pair<String?, Int>, Cancelled>()
    private const val OS_UPDATE_GRACE_MS = 5_000L
    private const val SNAPSHOT_LIMIT = 16

    private data class PendingPublication(val manager: NotificationManager, val notification: Notification)
    private val pendingPublications = linkedMapOf<Pair<String?, Int>, PendingPublication>()
    private val publicationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val MIN_PUBLICATION_INTERVAL_MS = 300L
    private var lastPublicationAtMs = -MIN_PUBLICATION_INTERVAL_MS
    private val drainPublications = object : Runnable {
        override fun run() = synchronized(this@MessageNotificationFactory) { publishNext() }
    }

    // Android drops rapid updates above its per-package notification quota.
    // Keep the latest complete history per key and space actual Binder posts.
    // Redactions first remove the OS copy so old text cannot survive a delay.
    private fun publish(manager: NotificationManager, tag: String?, id: Int, notification: Notification, redact: Boolean = false) {
        val key = tag to id
        rememberPublished(tag, id, notification)
        if (redact) manager.cancel(tag, id)
        pendingPublications[key] = PendingPublication(manager, notification.clone())
        while (pendingPublications.size > SNAPSHOT_LIMIT) {
            val oldest = pendingPublications.keys.first()
            cancelPublished(pendingPublications.getValue(oldest).manager, oldest.first, oldest.second)
        }
        publishNext()
    }

    private fun publishNext() {
        publicationHandler.removeCallbacks(drainPublications)
        if (pendingPublications.isEmpty()) return
        val remaining = MIN_PUBLICATION_INTERVAL_MS - (android.os.SystemClock.elapsedRealtime() - lastPublicationAtMs)
        if (remaining > 0) {
            publicationHandler.postDelayed(drainPublications, remaining)
            return
        }
        val key = pendingPublications.keys.first()
        val next = pendingPublications.remove(key)!!
        next.manager.notify(key.first, key.second, next.notification)
        lastPublicationAtMs = android.os.SystemClock.elapsedRealtime()
        if (pendingPublications.isNotEmpty()) publicationHandler.postDelayed(drainPublications, MIN_PUBLICATION_INTERVAL_MS)
    }

    private fun currentNotifications(context: Context): List<Published> {
        val now = android.os.SystemClock.elapsedRealtime()
        cancelled.entries.removeAll { now - it.value.atMs >= OS_UPDATE_GRACE_MS }
        val current = context.getSystemService(NotificationManager::class.java).activeNotifications
            .associate { (it.tag to it.id) to Published(it.tag, it.id, it.notification, now) }.toMutableMap()
        cancelled.forEach { (key, cancellation) ->
            // Legacy notifications share an untagged integer ID. A cancellation
            // from another account or conversation cannot hide its new occupant.
            if (current[key]?.notification?.extras?.getString(EXTRA_SCOPE) == cancellation.scope) current.remove(key)
        }
        val iterator = published.iterator()
        while (iterator.hasNext()) {
            val (key, value) = iterator.next()
            if (key in current || now - value.atMs < OS_UPDATE_GRACE_MS) current[key] = value
            else iterator.remove()
        }
        return current.values.toList()
    }

    private fun rememberPublished(tag: String?, id: Int, notification: Notification) {
        val key = tag to id
        cancelled.remove(key)
        published.remove(key)
        published[key] = Published(tag, id, notification.clone(), android.os.SystemClock.elapsedRealtime())
        while (published.size > SNAPSHOT_LIMIT) published.remove(published.keys.first())
    }

    private fun cancelPublished(manager: NotificationManager, tag: String?, id: Int) {
        val key = tag to id
        pendingPublications.remove(key)
        val scope = published.remove(key)?.notification?.extras?.getString(EXTRA_SCOPE)
            ?: manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
                ?.notification?.extras?.getString(EXTRA_SCOPE)
        cancelled[key] = Cancelled(scope, android.os.SystemClock.elapsedRealtime())
        while (cancelled.size > SNAPSHOT_LIMIT) cancelled.remove(cancelled.keys.first())
        manager.cancel(tag, id)
    }

    /** Build and publish under the same lock used by deletion/action updates. */
    @Synchronized
    fun post(context: Context, payload: MessageNotificationPayload): Boolean {
        val identity = DeletedMessageIdentity.create(payload.notificationAccountTarget?.userId, payload.targetType, payload.conversationId, payload.messageId)
        if (identity != null && DeletedMessageStore(context).isSuppressed(identity)) return false
        val scope = ConversationNotificationIdentity.scope(payload.notificationAccountTarget, payload.targetType, payload.conversationId, payload.channelContextId)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = build(context, payload)
        // Remove only this conversation's pre-tag legacy notification.
        currentNotifications(context).filter { it.tag == null && it.notification.extras.getString(EXTRA_SCOPE) == scope }
            .forEach { cancelPublished(manager, it.tag, it.id) }
        publish(manager, scope, payload.notificationId, notification)
        return true
    }

    @Synchronized
    fun redactMessage(context: Context, identity: DeletedMessageIdentity, createdAt: String? = null, forEveryone: Boolean = true, channelContextId: String? = null) {
        val store = DeletedMessageStore(context)
        store.mark(identity, createdAt, forEveryone)
        val manager = context.getSystemService(NotificationManager::class.java)
        currentNotifications(context).forEach { active ->
            val extras = active.notification.extras
            val existing = DeletedMessageIdentity.create(extras.getString(EXTRA_ACCOUNT), extras.getString(EXTRA_TYPE), extras.getString(EXTRA_CONVERSATION), identity.messageId)
            // Legacy builds have only EXTRA_SCOPE: match every known channel using that exact account scope.
            val matches = existing == identity || extras.getString(EXTRA_SCOPE) == ConversationNotificationIdentity.scope(
                NotificationAccountTarget(userId = identity.accountId), identity.targetType, identity.conversationId, channelContextId)
            if (!matches) return@forEach
            val old = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification) ?: return@forEach
            if (old.messages.none { it.extras.getString(MESSAGE_ID) == identity.messageId }) return@forEach
            val style = NotificationCompat.MessagingStyle(old.user).setGroupConversation(old.isGroupConversation)
            if (old.isGroupConversation) style.setConversationTitle(old.conversationTitle)
            old.messages.forEach { item ->
                val id = DeletedMessageIdentity.create(identity.accountId, identity.targetType, identity.conversationId, item.extras.getString(MESSAGE_ID))
                if (id != null && store.isSuppressed(id)) {
                    if (store.isDeleted(id)) style.addMessage(NotificationCompat.MessagingStyle.Message("Mensaje eliminado", item.timestamp, item.person).apply {
                        this.extras.putAll(item.extras); this.extras.putString(MESSAGE_ID, id.messageId)
                    })
                } else style.addMessage(item)
            }
            if (style.messages.isEmpty()) { cancelPublished(manager, active.tag, active.id); return@forEach }
            val last = style.messages.last()
            // A fresh immutable PendingIntent replaces any cached text/media preview.
            val payload = MessageNotificationPayload(
                channelId = NotificationCompat.getChannelId(active.notification).orEmpty(), channelName = "Mensajes", channelDescription = "Mensajes",
                conversationId = identity.conversationId, targetType = identity.targetType,
                conversationTitle = extras.getString(EXTRA_TITLE) ?: old.conversationTitle?.toString() ?: "Conversación",
                senderName = last.person?.name?.toString() ?: extras.getString(EXTRA_SENDER_NAME) ?: "Usuario",
                messageBody = last.text?.toString().orEmpty(), notificationId = active.id,
                senderId = last.person?.key ?: extras.getString(EXTRA_SENDER_ID), messageId = last.extras.getString(MESSAGE_ID),
                isGroupConversation = old.isGroupConversation, notificationAccountTarget = NotificationAccountTarget(userId = identity.accountId),
                channelContextId = extras.getString(EXTRA_CHANNEL_CONTEXT) ?: channelContextId, silent = true)
            val intent = PendingIntent.getActivity(context, active.id, buildOpenIntent(context, payload), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val updated = NotificationCompat.Builder(context, active.notification).setStyle(style)
                .setContentText(last.text).setContentIntent(intent).setNumber(style.messages.size)
                .setOnlyAlertOnce(true).setSilent(true)
                .addExtras(Bundle().apply { putString(EXTRA_LAST_MESSAGE_ID, payload.messageId) }).build()
            publish(manager, active.tag, active.id, updated, redact = true)
        }
    }

    @Synchronized
    fun cancelConversation(context: Context, account: NotificationAccountTarget?, type: String, id: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        currentNotifications(context).filter { active ->
            val extras = active.notification.extras
            (extras.getString(EXTRA_ACCOUNT) == account?.userId && extras.getString(EXTRA_TYPE) == type && extras.getString(EXTRA_CONVERSATION) == id) ||
                extras.getString(EXTRA_SCOPE) == ConversationNotificationIdentity.scope(account, type, id)
        }.forEach { cancelPublished(manager, it.tag, it.id) }
    }

    @Synchronized
    fun cancelAccount(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        currentNotifications(context).filter {
            it.notification.extras.getString(EXTRA_ACCOUNT)?.equals(userId, true) == true
        }.forEach { cancelPublished(manager, it.tag, it.id) }
    }

    @Synchronized
    fun finishAction(context: Context, scope: String, messageId: String?, notificationId: Int, errorText: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = currentNotifications(context).firstOrNull { it.id == notificationId &&
            it.notification.extras.getString(EXTRA_SCOPE) == scope && it.notification.extras.getString(EXTRA_LAST_MESSAGE_ID) == messageId } ?: return
        if (errorText == null) cancelPublished(manager, active.tag, active.id)
        else {
            val updated = NotificationCompat.Builder(context, active.notification).setSubText(errorText).setSilent(true).build()
            publish(manager, active.tag, active.id, updated)
        }
    }

    @Synchronized
    fun build(context: Context, payload: MessageNotificationPayload): Notification {
        ensureChannel(context, payload)
        val scope = ConversationNotificationIdentity.scope(payload.notificationAccountTarget, payload.targetType, payload.conversationId, payload.channelContextId)
        val title = (if (payload.isGroupConversation) payload.conversationTitle else payload.senderName).trim().ifBlank { "Conversación" }.take(160)
        val previous = currentNotifications(context).firstOrNull {
            it.id == payload.notificationId && it.notification.extras.getString(EXTRA_SCOPE) == scope
        }?.notification
        val history = previous?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)?.messages.orEmpty()
        val knownSenderKey = payload.senderId?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) && !it.equals("unknown", ignoreCase = true)
        }
        // An incomplete group payload identifies only this message, not every
        // anonymous member of the room. It must never borrow another photo.
        val senderKey = knownSenderKey ?: if (!payload.isGroupConversation) "sender:${payload.conversationId}"
            else "message-sender:${payload.conversationId}:${payload.messageId?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID()}"
        val previousPhoto = history.lastOrNull {
            it.person?.key == senderKey && it.extras.getBoolean(REAL_SENDER_ICON) &&
                (knownSenderKey != null || !payload.isGroupConversation || it.person?.name?.toString() == payload.senderName.ifBlank { "Usuario" }.take(160))
        }?.person?.icon
        val senderIcon = payload.largeIcon?.let { IconCompat.createWithBitmap(avatar(it, payload.senderName)) }
            ?: previousPhoto ?: IconCompat.createWithBitmap(avatar(null, payload.senderName))
        val hasSenderPhoto = payload.largeIcon != null || previousPhoto != null
        val sender = Person.Builder().setName(payload.senderName.ifBlank { "Usuario" }.take(160))
            .setKey(senderKey).setIcon(senderIcon).build()
        val actualConversation = payload.conversationIcon ?: if (!payload.isGroupConversation) payload.largeIcon else null
        val previousConversation = previous?.takeIf { it.extras.getBoolean(REAL_CONVERSATION_ICON) }
            ?.getLargeIcon()?.loadDrawable(context)?.let { it.toBitmap(312, 312) }
        val conversationBitmap = avatar(actualConversation ?: previousConversation, title)
        val hasConversationPhoto = actualConversation != null || previousConversation != null
        // MessagingStyle's user is the receiving account, never the remote sender.
        val self = Person.Builder().setName("Tú").setKey(payload.notificationAccountTarget?.userId ?: "self:$scope").build()
        val style = NotificationCompat.MessagingStyle(self).setGroupConversation(payload.isGroupConversation)
        if (payload.isGroupConversation) style.setConversationTitle(title)
        val deletions = DeletedMessageStore(context)
        history.filter { payload.messageId == null || it.extras.getString(MESSAGE_ID) != payload.messageId }
            .takeLast(HISTORY_LIMIT - 1).forEach { item ->
                val id = DeletedMessageIdentity.create(payload.notificationAccountTarget?.userId, payload.targetType, payload.conversationId, item.extras.getString(MESSAGE_ID))
                if (id != null && deletions.isSuppressed(id)) {
                    if (deletions.isDeleted(id)) style.addMessage(NotificationCompat.MessagingStyle.Message("Mensaje eliminado", item.timestamp, item.person).apply {
                        extras.putAll(item.extras); extras.putString(MESSAGE_ID, id.messageId)
                    })
                } else if (hasSenderPhoto && (knownSenderKey != null || !payload.isGroupConversation) && item.person?.key == senderKey) {
                    // A richer duplicate also fixes that sender's older fallback rows.
                    style.addMessage(NotificationCompat.MessagingStyle.Message(item.text, item.timestamp, sender).apply {
                        extras.putAll(item.extras); extras.putBoolean(REAL_SENDER_ICON, true)
                    })
                } else style.addMessage(item)
            }
        val current = NotificationCompat.MessagingStyle.Message(payload.messageBody.take(1000), System.currentTimeMillis(), sender)
        current.extras.putString(MESSAGE_ID, payload.messageId)
        current.extras.putBoolean(REAL_SENDER_ICON, hasSenderPhoto)
        style.addMessage(current)
        val open = buildOpenIntent(context, payload)
        val contentIntent = PendingIntent.getActivity(context, payload.notificationId, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val shortcutIntent = buildOpenIntent(context, payload, shortcut = true)
        val shortcut = ShortcutInfoCompat.Builder(context, scope).setShortLabel(title).setLongLabel(title)
            .setIcon(IconCompat.createWithAdaptiveBitmap(conversationBitmap)).setIntent(shortcutIntent)
            .setPersons(arrayOf(sender)).setLongLived(true).setLocusId(LocusIdCompat(scope)).build()
        val published = runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }.getOrDefault(false)
        val publicVersion = NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.app_name))
            .setContentText("Nuevo mensaje").setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        return NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(if (payload.isGroupConversation) "${payload.senderName}: ${payload.messageBody}" else payload.messageBody)
            .setStyle(style).setLargeIcon(conversationBitmap).setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setAutoCancel(true).setWhen(current.timestamp).setShowWhen(true).setNumber(style.messages.size)
            .setColor(Color.rgb(0, 137, 169)).setLocusId(LocusIdCompat(scope))
            .setOnlyAlertOnce(payload.silent).setSilent(payload.silent)
            .addExtras(Bundle().apply {
                putBoolean(REAL_CONVERSATION_ICON, hasConversationPhoto)
                putString(EXTRA_SCOPE, scope); putString(EXTRA_LAST_MESSAGE_ID, payload.messageId)
                putString(EXTRA_TYPE, payload.targetType); putString(EXTRA_CONVERSATION, payload.conversationId)
                putString(EXTRA_ACCOUNT, payload.notificationAccountTarget?.userId)
                putString(EXTRA_CHANNEL_CONTEXT, payload.channelContextId); putString(EXTRA_TITLE, title)
                putString(EXTRA_SENDER_NAME, payload.senderName); putString(EXTRA_SENDER_ID, payload.senderId)
            })
            .apply {
                if (published) setShortcutInfo(shortcut)
                if (payload.allowInlineActions && payload.notificationAccountTarget?.isDefined == true) {
                    addAction(replyAction(context, payload, scope))
                    addAction(simpleAction(context, payload, scope, MessageNotificationContract.ACTION_MARK_AS_READ, "Marcar como leído"))
                    addAction(simpleAction(context, payload, scope, MessageNotificationContract.ACTION_MUTE, "Silenciar 8 h"))
                }
            }.build()
    }

    private fun avatar(bitmap: Bitmap?, name: String): Bitmap {
        if (bitmap != null) return Bitmap.createScaledBitmap(bitmap, 312, 312, true)
        val result = Bitmap.createBitmap(312, 312, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(0, 137, 169))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 144f }
        val initials = name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
            .joinToString("") { it.take(1).uppercase() }.ifBlank { "N" }
        canvas.drawText(initials, 156f, 156f - (paint.ascent() + paint.descent()) / 2, paint)
        return result
    }

    private fun replyAction(context: Context, payload: MessageNotificationPayload, scope: String): NotificationCompat.Action {
        val pending = actionPending(context, payload, scope, MessageNotificationContract.ACTION_REPLY, mutable = true)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Responder", pending)
            .addRemoteInput(RemoteInput.Builder(MessageNotificationContract.KEY_TEXT_REPLY).setLabel("Responder").build())
            .setAllowGeneratedReplies(true).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
    }

    private fun simpleAction(context: Context, payload: MessageNotificationPayload, scope: String, action: String, title: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_notification, title, actionPending(context, payload, scope, action, false))
            .setSemanticAction(if (action == MessageNotificationContract.ACTION_MARK_AS_READ) NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ else NotificationCompat.Action.SEMANTIC_ACTION_MUTE).build()

    private fun actionPending(context: Context, payload: MessageNotificationPayload, scope: String, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, MessageNotificationActionReceiver::class.java).apply {
            this.action = action
            data = Uri.Builder().scheme("novvin-notification").authority("action").appendPath(scope).appendPath(action).build()
            putExtra(MessageNotificationContract.EXTRA_CONVERSATION_ID, payload.conversationId)
            putExtra(MessageNotificationContract.EXTRA_TARGET_TYPE, payload.targetType)
            putExtra(MessageNotificationContract.EXTRA_CHANNEL_ID, payload.channelContextId)
            putExtra(MessageNotificationContract.EXTRA_MESSAGE_ID, payload.messageId)
            putExtra(MessageNotificationContract.EXTRA_NOTIFICATION_ID, payload.notificationId)
            putExtra(MessageNotificationContract.EXTRA_REPLY_ID, UUID.randomUUID().toString())
        }.putNotificationAccountTarget(payload.notificationAccountTarget)
        return PendingIntent.getBroadcast(context, payload.notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildOpenIntent(context: Context, payload: MessageNotificationPayload, shortcut: Boolean = false): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.Builder().scheme("novvin-notification").authority("conversation")
                .appendPath(ConversationNotificationIdentity.scope(payload.notificationAccountTarget, payload.targetType, payload.conversationId, payload.channelContextId)).build()
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chat_id", payload.conversationId); putExtra("sender_id", payload.senderId); putExtra("sender_name", payload.senderName)
            putExtra("conversation_title", payload.conversationTitle); putExtra("channel_id", payload.channelContextId)
            if (!shortcut) { putExtra("notification_preview", payload.messageBody); putExtra("message_id", payload.messageId) }
            putExtra("target_type", payload.targetType); putExtra("type", "new_message")
            if (payload.targetType == "group") { putExtra("group_id", payload.conversationId); putExtra("is_group", true) }
            if (payload.targetType == "community") { putExtra("community_id", payload.conversationId); putExtra("is_community", true) }
        }.putNotificationAccountTarget(payload.notificationAccountTarget).also {
            if (shortcut) TrustedNavigationIntents.markTrustedConversationShortcut(context, it) else TrustedNavigationIntents.markTrusted(context, it)
        }

    private fun ensureChannel(context: Context, payload: MessageNotificationPayload) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Existing importance/sound preferences belong to the user.
        manager.createNotificationChannel(NotificationChannel(payload.channelId, payload.channelName, NotificationManager.IMPORTANCE_HIGH).apply {
            description = payload.channelDescription; enableVibration(true); setShowBadge(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        })
    }
}
