package conectate.com.data.auth

import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.security.MessageDigest

/** A deletion response belongs to its request credential or its captured realtime session. */
internal class AccountDeletionNotice private constructor(
    private val credentialDigest: ByteArray? = null,
    private val session: AuthSessionLease? = null,
) {
    private fun matches(currentToken: String?): Boolean = when {
        session != null -> session.isCurrent()
        credentialDigest == null || currentToken.isNullOrBlank() -> false
        else -> MessageDigest.isEqual(credentialDigest, digest(currentToken.trim()))
    }

    /** The owner check and logout must exclude a newer login, not just precede it. */
    fun commitIfCurrent(
        guard: AuthSessionMutationGuard,
        currentAccount: () -> String?,
        currentToken: () -> String?,
        onDelete: () -> Unit,
    ): Boolean = guard.readCurrent {
        if (currentAccount().isNullOrBlank() || !matches(currentToken())) return@readCurrent false
        onDelete()
        true
    }

    companion object {
        fun fromSession(lease: AuthSessionLease): AccountDeletionNotice = AccountDeletionNotice(session = lease)

        fun fromAuthorization(header: String?): AccountDeletionNotice? {
            val value = header?.trim() ?: return null
            if (!value.substringBefore(' ').equals("Bearer", ignoreCase = true)) return null
            val token = value.substringAfter(' ', "").trim()
                .takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
            // Retain a fingerprint only; never queue a bearer credential in this signal.
            return AccountDeletionNotice(credentialDigest = digest(token))
        }

        private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
    }
}
