package conectate.com.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import conectate.com.data.local.AgreementRecoveryPreferences
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No token or message content is included in WorkManager. No network is required. */
internal object AgreementRecoveryCleanup {
    fun enqueue(context: Context, accountId: String): UUID {
        require(runCatching { UUID.fromString(accountId).toString() == accountId }.getOrDefault(false))
        val request = OneTimeWorkRequestBuilder<AgreementRecoveryCleanupWorker>()
            .setInputData(workDataOf("account_id" to accountId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "agreement-recovery-delete-" + AgreementRecoveryPreferences.digest(accountId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }
    suspend fun erase(context: Context, accountId: String) = withContext(Dispatchers.IO) {
        AgreementRecoveryPreferences.purgeDeletedAccount(AgreementRecoveryPreferences.open(context), accountId)
    }
}

class AgreementRecoveryCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("account_id")?.takeIf {
            runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
        } ?: return Result.failure()
        return try {
            AgreementRecoveryCleanup.erase(applicationContext, owner)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            Log.w("AgreementRecovery", "Account recovery cleanup will retry")
            Result.retry()
        }
    }
}
