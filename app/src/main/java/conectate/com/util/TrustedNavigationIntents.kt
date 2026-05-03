package conectate.com.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object TrustedNavigationIntents {

    private const val PREFS = "trusted_navigation_intents"
    private const val ENTRY_PREFIX = "nav_token_"
    private const val EXTRA_TOKEN = "trusted_navigation_token"
    private const val MAX_ENTRIES = 256
    private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L

    private val protectedTypes = setOf(
        "new_message",
        "like",
        "like_post",
        "like_video",
        "follow",
        "friend_request",
        "friend_request_accepted",
        "comment",
        "comment_post",
        "reply_post",
        "comment_video",
        "reply_video",
        "mention",
        "mention_post",
        "mention_video",
        "share",
        "share_post",
        "share_video",
        "admin_warning_video",
        "challenge_invite",
        "voice_invite",
        "voice_access_request",
        "voice_return",
        "call_return",
        "call_cancel",
        "live_return",
        "call_invite"
    )

    private val secureRandom = SecureRandom()

    fun requiresTrust(type: String?): Boolean =
        protectedTypes.contains(type?.trim()?.lowercase().orEmpty())

    fun markTrusted(context: Context, intent: Intent): Intent {
        val type = intent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (!requiresTrust(type)) return intent

        prune(context.applicationContext)

        val token = randomToken()
        val expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS
        val subjectHash = hashSubject(buildSubject(type, intent))

        prefs(context.applicationContext).edit()
            .putString("$ENTRY_PREFIX$token", "$expiresAt:$subjectHash")
            .apply()

        intent.putExtra(EXTRA_TOKEN, token)
        return intent
    }

    fun isTrusted(context: Context, intent: Intent): Boolean {
        val type = intent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (!requiresTrust(type)) return true

        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim().orEmpty()
        if (token.isBlank()) return false

        val key = "$ENTRY_PREFIX$token"
        val stored = prefs(context.applicationContext).getString(key, null) ?: return false
        val separator = stored.indexOf(':')
        if (separator <= 0) {
            prefs(context.applicationContext).edit().remove(key).apply()
            return false
        }

        val expiresAt = stored.substring(0, separator).toLongOrNull()
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            prefs(context.applicationContext).edit().remove(key).apply()
            return false
        }

        val expectedHash = stored.substring(separator + 1)
        val actualHash = hashSubject(buildSubject(type, intent))
        return expectedHash == actualHash
    }

    private fun buildSubject(type: String, intent: Intent): String {
        fun extra(name: String): String = intent.getStringExtra(name)?.trim().orEmpty()
        fun bool(name: String): String = when {
            intent.hasExtra(name) -> intent.getBooleanExtra(name, false).toString()
            else -> extra(name)
        }

        val normalizedType = type.trim().lowercase()
        val accountSubject = listOf(
            extra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_USER_ID),
            extra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_EMAIL),
            extra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_LABEL)
        )

        return when (normalizedType) {
            "new_message" -> (listOf(
                normalizedType,
                extra("chat_id"),
                extra("sender_id"),
                extra("message_id"),
                extra("group_id"),
                extra("community_id"),
                extra("target_type"),
                bool("is_group"),
                bool("is_community")
            ) + accountSubject).joinToString("|")

            "call_invite" -> (listOf(
                normalizedType,
                extra("call_id"),
                extra("sender_id").ifBlank { extra("caller_user_id") },
                extra("call_context_type"),
                extra("call_context_id")
            ) + accountSubject).joinToString("|")

            "call_cancel" -> (listOf(
                normalizedType,
                extra("call_id"),
                extra("call_action")
            ) + accountSubject).joinToString("|")

            "call_return" -> (listOf(normalizedType, extra("call_id")) + accountSubject).joinToString("|")
            "voice_return" -> (listOf(normalizedType, extra("room_id")) + accountSubject).joinToString("|")
            "live_return" -> (listOf(normalizedType, extra("live_id"), extra("live_role")) + accountSubject).joinToString("|")

            else -> (listOf(
                normalizedType,
                extra("target_id"),
                extra("post_id"),
                extra("video_id"),
                extra("comment_id"),
                extra("room_id"),
                extra("challenge_id"),
                extra("follower_id"),
                extra("target_type")
            ) + accountSubject).joinToString("|")
        }
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        val entries = prefs.all.entries
            .filter { (key, value) -> key.startsWith(ENTRY_PREFIX) && value is String }
            .mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val separator = raw.indexOf(':')
                val expiry = if (separator > 0) raw.substring(0, separator).toLongOrNull() else null
                key to expiry
            }

        val expiredKeys = entries.filter { (_, expiry) -> expiry == null || expiry <= now }.map { it.first }
        if (expiredKeys.isNotEmpty()) {
            val editor = prefs.edit()
            expiredKeys.forEach(editor::remove)
            editor.apply()
        }

        val activeEntries = prefs.all.entries
            .filter { (key, value) -> key.startsWith(ENTRY_PREFIX) && value is String }
            .mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val separator = raw.indexOf(':')
                val expiry = if (separator > 0) raw.substring(0, separator).toLongOrNull() else null
                if (expiry == null || expiry <= now) null else key to expiry
            }
            .sortedByDescending { it.second }

        if (activeEntries.size > MAX_ENTRIES) {
            val editor = prefs.edit()
            activeEntries.drop(MAX_ENTRIES).forEach { (key, _) -> editor.remove(key) }
            editor.apply()
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hashSubject(subject: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
