package conectate.com.data.repository

import java.util.concurrent.atomic.AtomicLong

internal class SessionWriteGuard(
    private val accountIdProvider: () -> String?
) {
    data class Snapshot(
        val epoch: Long,
        val accountId: String?
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

    fun isValid(snapshot: Snapshot): Boolean {
        return snapshot.epoch == epoch.get() &&
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



