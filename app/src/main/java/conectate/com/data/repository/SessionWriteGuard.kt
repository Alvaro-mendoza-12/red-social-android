package conectate.com.data.repository

import java.util.concurrent.atomic.AtomicLong
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class SessionWriteGuard(
    private val accountIdProvider: () -> String?
) {
    data class Snapshot(
        val epoch: Long,
        val accountId: String?,
        val accountLease: AuthSessionLease? = null,
        val operationJob: Job? = null,
    )

    private var epoch = AtomicLong(0L)

    fun capture(expectedAccountId: String? = accountIdProvider()): Snapshot {
        return Snapshot(
            epoch = epoch.get(),
            accountId = normalizeAccountId(expectedAccountId ?: accountIdProvider())
        )
    }

    fun invalidate() {
        epoch.incrementAndGet()
    }

    fun capture(accountLease: AuthSessionLease, operationJob: Job?): Snapshot {
        accountLease.requireCurrent()
        return Snapshot(epoch.get(), normalizeAccountId(accountLease.accountId), accountLease, operationJob)
    }

    fun requireValid(snapshot: Snapshot) {
        if (!isValid(snapshot)) throw CancellationException("La sesión o el envío cambió.")
    }

    fun isValid(snapshot: Snapshot): Boolean {
        return snapshot.accountLease?.isCurrent() != false && snapshot.operationJob?.isActive != false &&
            snapshot.epoch == epoch.get() &&
            snapshot.accountId == normalizeAccountId(accountIdProvider())
    }

    companion object {
        fun normalizeAccountId(accountId: String?): String? {
            return accountId
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
        }
    }
}



