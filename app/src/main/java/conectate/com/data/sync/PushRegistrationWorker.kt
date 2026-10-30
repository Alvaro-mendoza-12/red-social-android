package conectate.com.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Work data never contains a token or credentials; each pass resolves the live session. */
object PushRegistrationScheduler {
    const val WORK_NAME = "novvin_push_registration"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }
}

internal suspend fun registerCurrentPushToken(
    accountId: () -> String?,
    token: suspend () -> String,
    register: suspend (String) -> Result<Unit>,
): Result<Unit> {
    val owner = accountId()?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return Result.success(Unit)
    val currentToken = token().trim()
    if (owner != accountId()?.trim()?.lowercase()) return Result.success(Unit)
    require(currentToken.isNotBlank()) { "Push token unavailable" }
    return register(currentToken)
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun authService(): AuthService
        fun notificationRepository(): NotificationRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return try {
            val result = registerCurrentPushToken(
                accountId = { deps.authService().getCurrentUserId() },
                token = { suspendCancellableCoroutine { continuation ->
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(task.result.orEmpty())
                            else continuation.resumeWithException(task.exception ?: IllegalStateException("Push token unavailable"))
                        }
                    }
                } },
                register = { deps.notificationRepository().registerFcmToken(it) },
            )
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) Result.success() else retryOrFail()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < 5) Result.retry() else Result.failure()
}
