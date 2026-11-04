package conectate.com.presentation.purpose

import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.local.AgreementRecoveryPreferences
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shares the encrypted account-deletion boundary, with a separate key namespace. */
@Singleton
internal class PurposeRecoveryStore internal constructor(
    preferences: () -> SharedPreferences, private val commit: (AuthSessionLease, () -> Unit) -> Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context, auth: AuthService) : this(
        { AgreementRecoveryPreferences.open(context) }, auth::commitForAccountLease,
    )
    private val preferences by lazy(preferences)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    suspend fun read(lease: AuthSessionLease, target: PurposeTarget): PurposeRecovery = withContext(Dispatchers.IO) {
        mutex.withLock { lease.requireCurrent(); synchronized(AgreementRecoveryPreferences.lock) { readNow(lease, target) } }
    }
    suspend fun replace(lease: AuthSessionLease, target: PurposeTarget, expected: PurposeRecovery, next: PurposeRecovery): PurposeRecovery = withContext(Dispatchers.IO) {
        mutex.withLock {
            lease.requireCurrent()
            val value = next.copy(revision = Math.addExact(expected.revision, 1))
            val raw = json.encodeToString(value)
            require(raw.length <= 20_000)
            if (!commit(lease) {
                synchronized(AgreementRecoveryPreferences.lock) {
                    if (readNow(lease, target) != expected) throw IOException("Este borrador cambió en otra pantalla. Vuelve a abrirlo antes de continuar.")
                    val key = key(lease, target)
                    val old = preferences.getString(key, null)
                    if (!preferences.edit().putString(key, raw).commit()) {
                        preferences.edit().apply { if (old == null) remove(key) else putString(key, old) }.commit()
                        throw IOException("No se pudo guardar el borrador. Reintenta antes de enviar.")
                    }
                }
            }) throw kotlinx.coroutines.CancellationException("La sesión cambió.")
            value
        }
    }
    private fun readNow(lease: AuthSessionLease, target: PurposeTarget): PurposeRecovery {
        AgreementRecoveryPreferences.requireAvailable(preferences, checkNotNull(lease.accountId))
        val raw = preferences.getString(key(lease, target), null) ?: return PurposeRecovery()
        require(raw.length <= 20_000)
        return json.decodeFromString<PurposeRecovery>(raw).also { require(it.revision >= 0) }
    }
    private fun key(lease: AuthSessionLease, target: PurposeTarget) = AgreementRecoveryPreferences.accountPrefix(checkNotNull(lease.accountId)) + "purpose:" + AgreementRecoveryPreferences.digest(target.key)
    companion object { private val mutex = Mutex() }
}
