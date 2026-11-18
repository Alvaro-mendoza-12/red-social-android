package conectate.com.data.payout

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PendingCreatorPayout(val amountMinor: Long, val key: String, val rejected: Boolean = false)

/** A lost HTTP confirmation must survive a process restart without creating a new withdrawal. */
@Singleton
class CreatorPayoutRetryStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("creator_payout_retries", Context.MODE_PRIVATE)

    internal suspend fun load(account: String): PendingCreatorPayout? = withContext(Dispatchers.IO) {
        read(account)
    }

    internal suspend fun save(account: String, attempt: PendingCreatorPayout) = withContext(Dispatchers.IO) {
        require(attempt.amountMinor > 0)
        UUID.fromString(attempt.key)
        if (!commitAttempt(preferences.edit().putString(storageKey(account), serialize(attempt)))) {
            throw IOException("No se pudo guardar el intento de retiro. No se envió la solicitud.")
        }
    }

    internal suspend fun clear(account: String, expectedKey: String) = withContext(Dispatchers.IO) {
        // Do not remove a replacement attempt if cleanup is ever resumed after a later write.
        val previous = read(account)
        if (previous?.key == expectedKey && !commitAttempt(preferences.edit().remove(storageKey(account)))) {
            // A failed commit may already have changed the in-memory map. Retain the
            // old attempt there too so a retry cannot silently allocate a fresh key.
            commitAttempt(preferences.edit().putString(storageKey(account), serialize(previous)))
            throw IOException("No se pudo limpiar el intento de retiro confirmado.")
        }
    }

    private fun commitAttempt(editor: android.content.SharedPreferences.Editor): Boolean {
        val marker = "__payout_attempt_write"
        val previous = preferences.getString(marker, null)?.toLongOrNull() ?: 0L
        // Force a real write on API24/25, which may skip an unchanged pending value.
        return editor.putString(marker, (previous + 1L).toString()).commit()
    }

    private fun serialize(attempt: PendingCreatorPayout): String =
        "${attempt.amountMinor}:${attempt.key}:${if (attempt.rejected) "rejected" else "pending"}"

    private fun storageKey(account: String): String {
        val owner = account.trim().lowercase(java.util.Locale.ROOT)
        require(owner.isNotBlank())
        return "pending:$owner"
    }

    private fun read(account: String): PendingCreatorPayout? {
        val raw = preferences.getString(storageKey(account), null) ?: return null
        return try {
            val parts = raw.split(':', limit = 3)
            val amount = parts[0].toLong().also { require(it > 0) }
            val key = UUID.fromString(parts[1]).toString()
            val phase = parts.getOrNull(2) ?: "pending"
            require(phase == "pending" || phase == "rejected")
            PendingCreatorPayout(amount, key, rejected = phase == "rejected")
        } catch (error: Exception) {
            throw IOException("No se pudo leer el retiro pendiente. Conservamos sus datos para evitar duplicarlo.", error)
        }
    }
}
