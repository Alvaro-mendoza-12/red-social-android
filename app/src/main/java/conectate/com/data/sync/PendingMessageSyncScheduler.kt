package conectate.com.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Persists network-constrained message operations outside the app process.
 * A queued message therefore survives closing the UI, a process kill and a device restart.
 */
object PendingMessageSyncScheduler {
    private const val UNIQUE_WORK_NAME = "pending_message_sync"

    fun enqueue(context: Context, reason: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncManager.SYNC_REASON_KEY to reason.take(80)))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                20,
                TimeUnit.SECONDS,
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            // A new intent can arrive after a worker's last empty-queue check.
            // KEEP loses that wake-up; appending schedules a pass after it finishes.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
