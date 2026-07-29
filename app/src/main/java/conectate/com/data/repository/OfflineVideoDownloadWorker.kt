package conectate.com.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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

    override suspend fun doWork(): Result = try {
        coordinator.replenish()
        Result.success()
    } catch (error: Throwable) {
        if (runAttemptCount < 4) Result.retry() else Result.failure()
    }
}
