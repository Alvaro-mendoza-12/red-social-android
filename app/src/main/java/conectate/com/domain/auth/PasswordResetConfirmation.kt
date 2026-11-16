package conectate.com.domain.auth

import java.security.MessageDigest

/** A server acknowledgement is distinct from successfully opening the local session. */
internal class PasswordResetConfirmation(
    val requiresTwoFactor: Boolean,
    private val commitCurrentAccountVisit: (() -> Unit) -> Boolean,
) {
    fun mayPublishCompletion(): Boolean = commitCurrentAccountVisit {}
    fun publishIfCurrent(completion: () -> Unit): Boolean = commitCurrentAccountVisit(completion)
}

/**
 * Process-local UI suppression, never authentication or a replacement for server single use.
 * Retains at most 64 hashes, no grant, password, user or session credentials. A receipt can
 * suppress an old grant even when its account visit no longer permits UI completion.
 */
internal object ConfirmedPasswordResets {
    private val lock = Any()
    private val receipts = LinkedHashMap<String, PasswordResetConfirmation>()

    private fun key(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun record(token: String, requiresTwoFactor: Boolean, commitCurrentAccountVisit: (() -> Unit) -> Boolean) {
        if (token.isBlank()) return
        val hash = key(token)
        synchronized(lock) {
            receipts[hash] = PasswordResetConfirmation(requiresTwoFactor, commitCurrentAccountVisit)
            if (receipts.size > 64) receipts.remove(receipts.keys.first())
        }
    }

    // Call the visit predicate outside the registry lock: session mutations can record receipts.
    fun confirmation(token: String): PasswordResetConfirmation? = if (token.isBlank()) null
        else synchronized(lock) { receipts[key(token)] }
}

internal class PasswordResetConfirmedNeedsSignInException(cause: Throwable) : Exception(
    "Tu contraseña ya cambió, pero no pudimos completar el acceso en este dispositivo. Inicia sesión con tu nueva contraseña.",
    cause,
)
