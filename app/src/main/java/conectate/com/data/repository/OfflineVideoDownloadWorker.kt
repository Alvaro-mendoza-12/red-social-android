package conectate.com.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class OfflineVideoDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun coordinator(): OfflineVideoPrefetchCoordinator
    }

    private val coordinator by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java
        ).coordinator()
    }

    override suspend fun doWork(): Result = offlineVideoWorkResult(inputData.getString(ACCOUNT_KEY), runAttemptCount) {
        coordinator.replenish(it)
    }

    companion object {
        internal const val ACCOUNT_KEY = "offline_video_account"
    }
}

internal suspend fun offlineVideoWorkResult(
    account: String?,
    attempt: Int,
    replenish: suspend (String) -> OfflineVideoPrefetchResult,
): ListenableWorker.Result {
    // Older jobs carry no owner. Reopening the library enqueues the current account's target.
    if (account.isNullOrBlank()) return ListenableWorker.Result.success()
    try {
        val result = replenish(account)
        if (result.available >= result.requested || result.failed == 0) return ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Use the same bounded WorkManager backoff as an incomplete transfer.
    }
    return if (attempt < 4) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
}
