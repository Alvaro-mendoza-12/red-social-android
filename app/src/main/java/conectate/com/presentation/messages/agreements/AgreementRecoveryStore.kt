package conectate.com.presentation.messages.agreements

import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.local.AgreementRecoveryPreferences
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val AGREEMENT_RECOVERY_PREFERENCES = AgreementRecoveryPreferences.NAME

internal fun encryptedAgreementRecoveryPreferences(context: Context): SharedPreferences = AgreementRecoveryPreferences.open(context)

/** No plaintext fallback. Commits share authentication's account-transition lock. */
@Singleton
internal class AgreementRecoveryStore internal constructor(
    journalProvider: () -> AgreementRecoveryJournal,
    private val commitForLease: (AuthSessionLease, () -> Unit) -> Boolean,
) : AgreementRecoveryPersistence {
    @Inject constructor(@ApplicationContext context: Context, auth: AuthService) : this(
        { AgreementRecoveryJournal(encryptedAgreementRecoveryPreferences(context)) }, auth::commitForAccountLease,
    )
    private val journal by lazy(journalProvider)
    override suspend fun read(lease: AuthSessionLease, chatId: String): AgreementRecovery? = withContext(Dispatchers.IO) {
        mutex.withLock {
            lease.requireCurrent()
            journal.read(checkNotNull(lease.accountId), chatId).also { lease.requireCurrent() }
        }
    }
    override suspend fun replace(lease: AuthSessionLease, chatId: String, expected: AgreementRecovery?,
                                 draft: AgreementDraft?, pending: AgreementPendingWrite?): AgreementRecovery = withContext(Dispatchers.IO) {
        mutex.withLock {
            lease.requireCurrent()
            var result: AgreementRecovery? = null
            val accepted = commitForLease(lease) {
                result = journal.replace(checkNotNull(lease.accountId), chatId, expected, draft, pending)
            }
            if (!accepted) throw CancellationException("La sesión cambió. Vuelve a abrir Acuerdos.")
            checkNotNull(result)
        }
    }
    companion object { private val mutex = Mutex() }
}

/** Empty records retain their revision so a delayed screen cannot resurrect a discarded draft. */
internal class AgreementRecoveryJournal(private val preferences: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(accountId: String, chatId: String): AgreementRecovery? = synchronized(lock) {
        AgreementRecoveryPreferences.requireAvailable(preferences, accountId)
        val raw = preferences.getString(key(accountId, chatId), null) ?: return@synchronized null
        require(raw.length <= 16_000) { "No se pudo leer el borrador guardado." }
        json.decodeFromString<AgreementRecovery>(raw).validate()
    }
    fun replace(accountId: String, chatId: String, expected: AgreementRecovery?,
                draft: AgreementDraft?, pending: AgreementPendingWrite?): AgreementRecovery = synchronized(lock) {
        val current = read(accountId, chatId)
        if (current != expected) throw AgreementRecoveryConflict()
        val next = AgreementRecovery(revision = Math.addExact(current?.revision ?: 0, 1), draft = draft, pending = pending).validate()
        write(key(accountId, chatId), json.encodeToString(next))
        next
    }
    fun eraseAccount(accountId: String) = synchronized(lock) {
        val prefix = prefix(accountId)
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        val previous = keys.associateWith { preferences.getString(it, null) }
        if (!preferences.edit().apply { keys.forEach(::remove) }.commit()) {
            preferences.edit().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
            throw IOException("No se pudo eliminar la recuperación de esta cuenta.")
        }
    }
    private fun write(storageKey: String, value: String) {
        val previous = preferences.getString(storageKey, null)
        if (!preferences.edit().putString(storageKey, value).commit()) {
            preferences.edit().putString(storageKey, previous).commit()
            throw IOException("No se pudo guardar el borrador en este dispositivo.")
        }
    }
    private fun key(accountId: String, chatId: String): String {
        require(agreementUuid(chatId))
        return prefix(accountId) + AgreementRecoveryPreferences.digest(chatId)
    }
    private fun prefix(accountId: String) = AgreementRecoveryPreferences.accountPrefix(accountId)
    companion object { private val lock = AgreementRecoveryPreferences.lock }
}
