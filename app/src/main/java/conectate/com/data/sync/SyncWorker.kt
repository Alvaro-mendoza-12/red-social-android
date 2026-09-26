package conectate.com.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import conectate.com.domain.sync.shouldRetrySync
import kotlinx.coroutines.CancellationException

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun syncManager(): SyncManager
    }

    private val syncManager: SyncManager by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            SyncWorkerEntryPoint::class.java
        ).syncManager()
    }

    override suspend fun doWork(): Result {
        var reason = inputData.getString(SyncManager.SYNC_REASON_KEY) ?: "worker"
        return try {
            syncManager.syncNow(reason = reason)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (shouldRetrySync(e, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}



