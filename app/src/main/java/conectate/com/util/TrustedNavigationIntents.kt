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
    private const val EXTRA_SIGNATURE = "trusted_navigation_signature"
    private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L

    private val secureRandom = SecureRandom()

    fun requiresTrust(type: String?): Boolean =
        !type.isNullOrBlank()

    fun markTrusted(context: Context, intent: Intent): Intent {
        val type = intent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (!requiresTrust(type)) return intent

        // A signature stays valid independently of how many newer alerts arrive.
        // Keep the old nonce verifier below for already issued PendingIntents.
        intent.removeExtra(EXTRA_TOKEN)
        intent.removeExtra("conversation_shortcut_signature")
        val expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS
        val signature = signSubject(context, "notice-v2:$expiresAt:${signedSubject(intent)}")
        intent.putExtra(EXTRA_SIGNATURE, "2:$expiresAt:$signature")
        return intent
    }

    /** Persistent, account-bound route for Android's long-lived conversation shortcut. */
    fun markTrustedConversationShortcut(context: Context, intent: Intent): Intent {
        require(intent.getStringExtra("type") == "new_message")
        intent.removeExtra(EXTRA_SIGNATURE)
        intent.removeExtra(EXTRA_TOKEN)
        intent.putExtra("conversation_shortcut_signature", shortcutSignature(context, intent))
        return intent
    }

    private fun shortcutSignature(context: Context, intent: Intent): String =
        signSubject(context, buildSubject("new_message", intent))

    private fun signSubject(context: Context, subject: String): String {
        val storage = prefs(context.applicationContext)
        val encoded = synchronized(this) {
            storage.getString("conversation_shortcut_key", null) ?: ByteArray(32).also(secureRandom::nextBytes)
                .let { Base64.encodeToString(it, Base64.NO_WRAP) }.also {
                    check(storage.edit().putString("conversation_shortcut_key", it).commit())
                }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(subject.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun isTrusted(context: Context, intent: Intent): Boolean {
        val type = intent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (!requiresTrust(type)) return true
        val signature = intent.getStringExtra(EXTRA_SIGNATURE)
        if (signature != null) return runCatching {
            val parts = signature.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "2" || parts[2].length != 44) return@runCatching false
            val expiresAt = parts[1].toLongOrNull() ?: return@runCatching false
            if (expiresAt <= System.currentTimeMillis()) return@runCatching false
            val expected = signSubject(context, "notice-v2:$expiresAt:${signedSubject(intent)}")
            MessageDigest.isEqual(parts[2].toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        }.getOrDefault(false)
        val suppliedShortcutSignature = intent.getStringExtra("conversation_shortcut_signature")
        if (type == "new_message" && suppliedShortcutSignature != null) {
            return runCatching {
                MessageDigest.isEqual(suppliedShortcutSignature.toByteArray(Charsets.UTF_8), shortcutSignature(context, intent).toByteArray(Charsets.UTF_8))
            }.getOrDefault(false)
        }

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

        // Historical reaction notifications were signed as messages before
        // changing this field. Their destination and account are identical.
        val normalizedType = type.trim().lowercase().let { if (it == "message_reaction") "new_message" else it }
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
                extra("channel_id"),
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
            "live_started" -> (listOf(
                normalizedType,
                extra("live_id").ifBlank { extra("target_id") },
                extra("host_id")
            ) + accountSubject).joinToString("|")

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

    /** Type and all app extras, with lengths/types so delimiters cannot change their meaning. */
    @Suppress("DEPRECATION")
    private fun signedSubject(intent: Intent): String = buildString {
        val extras = intent.extras ?: return@buildString
        extras.keySet().sorted().filterNot {
            it in setOf(EXTRA_TOKEN, EXTRA_SIGNATURE, "conversation_shortcut_signature") ||
                it.startsWith("android.") || it.startsWith("androidx.")
        }.forEach { key ->
            val value = when (val raw = extras.get(key)) {
                null -> "null"
                is String -> "string:$raw"
                is Boolean -> "boolean:$raw"
                is Int -> "int:$raw"
                is Long -> "long:$raw"
                is Float -> "float:$raw"
                is Double -> "double:$raw"
                is ByteArray -> "bytes:${Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(raw), Base64.NO_WRAP)}"
                else -> throw IllegalArgumentException("Unsupported internal navigation extra")
            }
            append(key.length).append(':').append(key)
            append(value.length).append(':').append(value)
        }
    }

    private fun hashSubject(subject: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
