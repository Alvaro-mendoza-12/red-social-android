package conectate.com.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34], application = UnitTestApplication::class)
class LocalNotificationHelperTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private fun account() = NotificationAccountTarget(userId = "owned-${UUID.randomUUID()}")
    private fun drain() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    private fun alerts(owner: NotificationAccountTarget) = manager.activeNotifications.filter {
        it.notification.extras.getString(MessageNotificationFactory.EXTRA_ACCOUNT) == owner.userId
    }
    private fun show(owner: NotificationAccountTarget, id: String, type: String = "like_post", extra: JsonObject? = null) {
        LocalNotificationHelper(context).showSocialNotification(
            account = owner, title = "Owned fixture", body = "Fixture $id", type = type,
            targetId = "target-$id", senderId = "actor", notificationId = id, extraData = extra,
        )
    }

    @Test fun identicalRecordIdsForDifferentAccountsKeepDistinctTrustedDestinations() {
        val first = account()
        val second = account()
        try {
            show(first, "same-record")
            show(second, "same-record")
            drain()
            val firstAlert = alerts(first).single().notification
            val secondAlert = alerts(second).single().notification
            assertNotEquals(firstAlert.contentIntent, secondAlert.contentIntent)
            for ((owner, alert) in listOf(first to firstAlert, second to secondAlert)) {
                val intent = shadowOf(alert.contentIntent).savedIntent
                assertEquals(owner.userId, notificationAccountTargetFromIntent(intent)?.userId)
                assertEquals("target-same-record", intent.getStringExtra("post_id"))
                assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
                assertEquals(Notification.VISIBILITY_PRIVATE, alert.visibility)
                assertTrue(alert.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                intent.putExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_USER_ID, "another-account")
                assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
            }
        } finally {
            MessageNotificationFactory.cancelAccount(context, first.userId)
            MessageNotificationFactory.cancelAccount(context, second.userId)
        }
    }

    @Test fun clearingSocialAlertsLeavesAnotherAccountAndTheSameAccountsConversationIntact() {
        val first = account()
        val second = account()
        try {
            show(first, "first-social")
            show(second, "second-social")
            val conversation = MessageNotificationPayload(
                channelId = "owned_social_isolation", channelName = "Owned fixture", channelDescription = "No network",
                conversationId = "room-${UUID.randomUUID()}", targetType = "chat", conversationTitle = "Owned room",
                senderName = "Owned sender", messageBody = "Owned conversation remains", notificationId = 740055,
                messageId = UUID.randomUUID().toString(), notificationAccountTarget = first, allowInlineActions = false,
            )
            assertTrue(MessageNotificationFactory.post(context, conversation))
            // Cancel before the queued Binder publications have all run.
            LocalNotificationHelper(context).clearAllSocialNotifications(first.userId)
            drain()
            assertEquals(1, alerts(second).size)
            val remaining = alerts(first).single().notification
            assertEquals("Owned conversation remains", remaining.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        } finally {
            MessageNotificationFactory.cancelAccount(context, first.userId)
            MessageNotificationFactory.cancelAccount(context, second.userId)
        }
    }

    @Test fun accountRemovalAlsoCancelsSocialPublicationsStillInTheQueue() {
        val removed = account()
        val retained = account()
        try {
            show(retained, "already-enqueued")
            show(removed, "waiting-to-publish")
            MessageNotificationFactory.cancelAccount(context, removed.userId)
            drain()
            assertTrue(alerts(removed).isEmpty())
            assertEquals(1, alerts(retained).size)
        } finally {
            MessageNotificationFactory.cancelAccount(context, removed.userId)
            MessageNotificationFactory.cancelAccount(context, retained.userId)
        }
    }

    @Test fun malformedOptionalMetadataDoesNotDropAnOtherwiseValidAlert() {
        val owner = account()
        try {
            show(owner, "valid-record", extra = JsonObject(mapOf(
                "comment_id" to JsonObject(mapOf("unexpected" to JsonPrimitive(true))),
                "notification_type" to JsonArray(emptyList()),
            )))
            drain()
            val intent = shadowOf(alerts(owner).single().notification.contentIntent).savedIntent
            assertEquals("target-valid-record", intent.getStringExtra("post_id"))
            assertNull(intent.getStringExtra("comment_id"))
        } finally { MessageNotificationFactory.cancelAccount(context, owner.userId) }
    }

    @Test fun liveAlertsAndTheirSummariesRemainSeparateForEachAccount() {
        val first = account()
        val second = account()
        try {
            show(first, "shared-live", type = "new_live")
            show(second, "shared-live", type = "live_started")
            drain()
            assertEquals(2, alerts(first).size)
            assertEquals(2, alerts(second).size)
            val firstSummary = alerts(first).single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
            val secondSummary = alerts(second).single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
            assertNotEquals(firstSummary.contentIntent, secondSummary.contentIntent)
            assertEquals(second.userId, notificationAccountTargetFromIntent(shadowOf(secondSummary.contentIntent).savedIntent)?.userId)
            LocalNotificationHelper(context).clearAllSocialNotifications(first.userId)
            drain()
            assertTrue(alerts(first).isEmpty())
            assertEquals(2, alerts(second).size)
        } finally {
            MessageNotificationFactory.cancelAccount(context, first.userId)
            MessageNotificationFactory.cancelAccount(context, second.userId)
        }
    }

    @Test fun readingOneRecordDoesNotClearTheOtherOwnersCopyOrAnotherRecord() {
        val first = account()
        val second = account()
        try {
            show(first, "shared")
            show(first, "other")
            show(second, "shared")
            LocalNotificationHelper(context).clearSocialNotification(first.userId, "shared")
            drain()
            assertEquals(1, alerts(first).size)
            assertEquals(1, alerts(second).size)
            assertEquals("Fixture other", alerts(first).single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        } finally {
            MessageNotificationFactory.cancelAccount(context, first.userId)
            MessageNotificationFactory.cancelAccount(context, second.userId)
        }
    }

    @Test fun socialEventVariantsCarryTheFieldsUsedByTheLaunchRouter() {
        val owner = account()
        try {
            val routes = listOf(
                "like_moment" to "moment_id", "mention_video_description" to "video_id",
                "new_video" to "video_id", "new_post" to "post_id", "friend_request_accepted" to "follower_id",
            )
            routes.forEach { (type, _) -> show(owner, type, type = type) }
            drain()
            routes.forEach { (type, field) ->
                val notification = alerts(owner).single {
                    shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("type") == type
                }.notification
                val intent = shadowOf(notification.contentIntent).savedIntent
                assertEquals(type, if (field == "follower_id") "actor" else "target-$type", intent.getStringExtra(field))
                assertTrue(type, TrustedNavigationIntents.isTrusted(context, intent))
            }
        } finally { MessageNotificationFactory.cancelAccount(context, owner.userId) }
    }

    @Test fun aVideoCommentMentionKeepsItsExplicitVideoContext() {
        val owner = account()
        try {
            show(owner, "video-mention", type = "mention", extra = JsonObject(mapOf(
                "video_id" to JsonPrimitive("actual-video"), "comment_id" to JsonPrimitive("actual-comment"),
            )))
            drain()
            val intent = shadowOf(alerts(owner).single().notification.contentIntent).savedIntent
            assertEquals("actual-video", intent.getStringExtra("video_id"))
            assertEquals("video", intent.getStringExtra("target_type"))
            assertEquals("actual-comment", intent.getStringExtra("comment_id"))
            assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        } finally { MessageNotificationFactory.cancelAccount(context, owner.userId) }
    }
}
