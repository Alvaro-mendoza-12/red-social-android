package conectate.com.presentation.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.domain.auth.ConfirmedPasswordResets
import java.util.WeakHashMap
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class EmailRecoveryDraft(
    val email: String,
    val code: String = "",
    val resetToken: String = "",
    val expiresAt: Long,
    val resendAt: Long = 0,
    // API acknowledgement, not a guarantee that the message reached the inbox.
    val deliveryConfirmed: Boolean = true,
) {
    val verified: Boolean get() = resetToken.isNotBlank()
}

/** Process-local permission, captured before an asynchronous writer is queued. */
class EmailRecoveryVisit internal constructor(
    internal val guard: EmailRecoveryVisitGuard,
    internal val generation: Long,
) {
    fun isCurrent(): Boolean = guard.isCurrent(this)
    fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("La recuperación cambió en otra pantalla.")
    }
}

internal class EmailRecoveryVisitGuard {
    private val admissionLock = Any()
    private val writes = Any()
    private data class Writer(val generation: Long, val owner: Any?)
    @Volatile private var writer = Writer(0L, null)
    // UI recovery suppression only; server-side single use is authoritative.
    // Keep the most recent 64 acknowledgements in this process without raw tokens.
    private val consumedTokenHashes = LinkedHashSet<String>()
    private fun tokenHash(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(token.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun tokenWasConsumed(token: String): Boolean = token.isNotBlank() && synchronized(admissionLock) {
        tokenHash(token) in consumedTokenHashes
    }

    fun retireConfirmedToken(expected: EmailRecoveryVisit, owner: Any, token: String,
        install: (EmailRecoveryVisit) -> Unit): EmailRecoveryVisit = synchronized(admissionLock) {
        requireCurrent(expected)
        if (token.isNotBlank()) {
            consumedTokenHashes.add(tokenHash(token))
            if (consumedTokenHashes.size > 64) consumedTokenHashes.remove(consumedTokenHashes.first())
        }
        // Retire all earlier readers/writers, including another screen on the same flow.
        begin(owner, install)
    }

    private val changes = MutableStateFlow(0L)
    val visits: StateFlow<Long> = changes.asStateFlow()

    fun snapshot() = EmailRecoveryVisit(this, writer.generation)

    // Admission stays separate from disk/crypto work. A new UI intent must not
    // wait on a previous SharedPreferences commit merely to retire its writer.
    fun begin(owner: Any, install: (EmailRecoveryVisit) -> Unit): EmailRecoveryVisit = synchronized(admissionLock) {
        val next = Writer(writer.generation + 1, owner)
        writer = next
        val visit = EmailRecoveryVisit(this, next.generation)
        try { install(visit) } finally { changes.value = writer.generation }
        visit
    }

    fun resume(expected: EmailRecoveryVisit, owner: Any, install: (EmailRecoveryVisit) -> Unit): EmailRecoveryVisit =
        synchronized(admissionLock) {
            requireCurrent(expected)
            if (writer.owner === owner) { install(expected); expected }
            else begin(owner, install)
        }

    fun isCurrent(visit: EmailRecoveryVisit): Boolean = visit.guard === this && visit.generation == writer.generation

    fun <T> write(visit: EmailRecoveryVisit, mutation: () -> T): T = synchronized(writes) {
        requireCurrent(visit)
        val result = mutation()
        // Another intent can be admitted while commit is in progress. Its own
        // write runs after this one; an older completion cannot publish success.
        requireCurrent(visit)
        result
    }

    private fun requireCurrent(visit: EmailRecoveryVisit) {
        if (!isCurrent(visit)) throw CancellationException("La recuperación cambió en otra pantalla.")
    }

    fun <T> pruneExpired(readAndPrune: () -> T): T = synchronized(writes) { readAndPrune() }
}

private object EmailRecoveryVisitGuards {
    private val contexts = WeakHashMap<Context, EmailRecoveryVisitGuard>()
    fun forContext(context: Context): EmailRecoveryVisitGuard = synchronized(contexts) {
        contexts.getOrPut(context) { EmailRecoveryVisitGuard() }
    }
}

/** Recovery progress survives process recreation; passwords are never stored here. */
class EmailRecoveryDraftStore(context: Context) {
    private val appContext = context.applicationContext
    private val guard = EmailRecoveryVisitGuards.forContext(appContext)
    private val writer = Any()
    private val preferences by lazy {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(appContext, "novvin_email_recovery", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    val visitChanges: StateFlow<Long> get() = guard.visits
    fun currentVisit(): EmailRecoveryVisit = guard.snapshot()
    fun beginWriteVisit(install: (EmailRecoveryVisit) -> Unit = {}): EmailRecoveryVisit = guard.begin(writer, install)
    fun resumeWriteVisit(expected: EmailRecoveryVisit, install: (EmailRecoveryVisit) -> Unit = {}): EmailRecoveryVisit =
        guard.resume(expected, writer, install)

    fun retireConfirmedResetToken(token: String, expected: EmailRecoveryVisit,
        install: (EmailRecoveryVisit) -> Unit = {}): EmailRecoveryVisit =
        guard.retireConfirmedToken(expected, writer, token, install)

    fun wasResetTokenConsumed(token: String): Boolean = guard.tokenWasConsumed(token) ||
        ConfirmedPasswordResets.confirmation(token) != null

    fun read(): EmailRecoveryDraft? = runCatching {
        val raw = preferences.getString("draft", null) ?: return@runCatching null
        val draft = decode(raw)
        if (wasResetTokenConsumed(draft.resetToken)) return@runCatching null
        if (draft.expiresAt > System.currentTimeMillis()) return@runCatching draft
        guard.pruneExpired {
            // The first read can precede another writer. Re-read under the
            // shared write lock, retaining a newer valid replacement.
            val latestRaw = preferences.getString("draft", null) ?: return@pruneExpired null
            val latest = if (latestRaw == raw) draft else decode(latestRaw)
            if (wasResetTokenConsumed(latest.resetToken)) null
            else if (latest.expiresAt > System.currentTimeMillis()) latest
            else { clearRaw(); null }
        }
    }.getOrNull()

    private fun decode(raw: String): EmailRecoveryDraft {
        val data = JSONObject(raw)
        return EmailRecoveryDraft(data.getString("email"), data.optString("code"),
            data.optString("resetToken"), data.getLong("expiresAt"), data.optLong("resendAt"),
            data.optBoolean("deliveryConfirmed", false))
    }

    /** Explicit replacement; queued visitors use saveForVisit with their captured permission. */
    fun save(draft: EmailRecoveryDraft) = saveForVisit(draft, beginWriteVisit())

    fun saveForVisit(draft: EmailRecoveryDraft, visit: EmailRecoveryVisit) = guard.write(visit) {
        val data = JSONObject().put("email", draft.email).put("code", draft.code)
            .put("resetToken", draft.resetToken).put("expiresAt", draft.expiresAt).put("resendAt", draft.resendAt)
            .put("deliveryConfirmed", draft.deliveryConfirmed)
        replaceRaw(data.toString(), "No se pudo guardar el avance de recuperación.")
    }

    /** Explicit replacement; queued visitors use clearForVisit with their captured permission. */
    fun clear() = clearForVisit(beginWriteVisit())

    fun clearForVisit(visit: EmailRecoveryVisit) = guard.write(visit) { clearRaw() }

    private fun clearRaw() = replaceRaw(null, "No se pudo eliminar el avance de recuperación.")

    private fun replaceRaw(next: String?, message: String) {
        val previous = preferences.getString("draft", null)
        fun editor(value: String?) = preferences.edit().apply {
            if (value == null) remove("draft") else putString("draft", value)
        }
        try {
            check(editor(next).commit()) { message }
        } catch (error: Exception) {
            // commit(false) can already have updated the in-memory preference.
            // The shared write lock prevents restoring over a newer writer.
            try {
                if (!editor(previous).commit()) error.addSuppressed(
                    IllegalStateException("No se pudo confirmar la restauración del borrador anterior."))
            } catch (restoreError: Exception) { error.addSuppressed(restoreError) }
            throw error
        }
    }
}
