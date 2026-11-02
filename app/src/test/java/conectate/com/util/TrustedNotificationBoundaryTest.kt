package conectate.com.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class TrustedNotificationBoundaryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun notice(type: String = "system_announcement") = Intent().apply {
        putExtra("type", type)
        putExtra("notification_id", "notice")
        putExtra("target_id", "target")
        putNotificationAccountTarget(NotificationAccountTarget(userId = "owner"))
    }

    @Test fun `all internal types require app proof including future event names`() {
        for (type in listOf("system_announcement", "activity", "notification_list", "notification_digest", "birthday",
            "new_moment", "like_moment", "new_live", "live_discovery", "account_suspended", "account_enabled",
            "admin_access_changed", "admin_access_audit", "message_reaction", "future_internal_event")) {
            val intent = notice(type)
            assertTrue(type, TrustedNavigationIntents.requiresTrust(type))
            assertFalse(type, TrustedNavigationIntents.isTrusted(context, intent))
            TrustedNavigationIntents.markTrusted(context, intent)
            assertTrue(type, TrustedNavigationIntents.isTrusted(context, intent))
        }
        assertFalse(TrustedNavigationIntents.requiresTrust(null))
        assertFalse(TrustedNavigationIntents.requiresTrust(" "))
    }

    @Test fun `a signed notice cannot mark another record as read or switch its recipient`() {
        val original = TrustedNavigationIntents.markTrusted(context, notice())
        for ((key, value) in listOf("notification_id" to "other", "target_id" to "other", "target_user_id" to "other",
            "birthday_user_id" to "other", "recipient_email" to "foreign@example.test", "body" to "forged")) {
            assertFalse(key, TrustedNavigationIntents.isTrusted(context, Intent(original).putExtra(key, value)))
        }
        assertTrue(TrustedNavigationIntents.isTrusted(context, original))
    }

    @Test fun `many newer notices do not invalidate an older still valid publication`() {
        val first = TrustedNavigationIntents.markTrusted(context, notice("follow"))
        val legacyEntries = context.getSharedPreferences("trusted_navigation_intents", Context.MODE_PRIVATE)
            .all.keys.count { it.startsWith("nav_token_") }
        repeat(600) { index -> TrustedNavigationIntents.markTrusted(context, notice("follow").putExtra("target_id", "person-$index")) }
        assertTrue(TrustedNavigationIntents.isTrusted(context, first))
        assertEquals(legacyEntries, context.getSharedPreferences("trusted_navigation_intents", Context.MODE_PRIVATE)
            .all.keys.count { it.startsWith("nav_token_") })
    }

    @Test fun `an altered signature deadline or payload is rejected`() {
        val intent = TrustedNavigationIntents.markTrusted(context, notice())
        val signed = intent.getStringExtra("trusted_navigation_signature")!!
        val parts = signed.split(':', limit = 3)
        intent.putExtra("trusted_navigation_signature", "2:${parts[1].toLong() + 60_000}:${parts[2]}")
        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
    }

    private fun legacyMessage(): Intent = Intent().apply {
        putExtra("type", "new_message"); putExtra("chat_id", "chat"); putExtra("sender_id", "peer")
        putExtra("message_id", "message"); putExtra("target_type", "chat")
        putNotificationAccountTarget(NotificationAccountTarget(userId = "owner"))
        // Fixed fixture of the old production issuer, not the new signing function.
        val subject = listOf("new_message", "chat", "peer", "message", "", "", "", "chat", "", "", "owner", "", "").joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("trusted_navigation_intents", Context.MODE_PRIVATE).edit()
            .putString("nav_token_legacy-fixture", "${System.currentTimeMillis() + 60_000}:$hash").commit()
        putExtra("trusted_navigation_token", "legacy-fixture")
    }

    @Test fun `previously issued message and reaction pending intents retain their account binding`() {
        val intent = legacyMessage()
        assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        intent.putExtra("type", "message_reaction")
        assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        intent.putNotificationAccountTarget(NotificationAccountTarget(userId = "foreign"))
        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
    }

    @Test fun `long lived conversation shortcuts keep their existing signature contract`() {
        val intent = legacyMessage().apply { removeExtra("trusted_navigation_token") }
        TrustedNavigationIntents.markTrustedConversationShortcut(context, intent)
        assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        intent.putExtra("chat_id", "foreign-chat")
        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
    }
}
