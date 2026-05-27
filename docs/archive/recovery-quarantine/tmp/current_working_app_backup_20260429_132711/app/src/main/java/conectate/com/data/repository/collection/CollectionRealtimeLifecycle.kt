package conectate.com.data.repository.collection

import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun bindCollectionRealtimeAuthLifecycle(
    scope: CoroutineScope,
    supabaseConfig: SupabaseConfig,
    logTag: String,
    logPrefix: String,
    readyLog: String,
    onAuthenticated: (String) -> Unit,
    onNotAuthenticated: () -> Unit
) {
    scope.launch {
        val currentSession = supabaseConfig.client.auth.currentSessionOrNull()
        if (currentSession != null) {
            var userId = currentSession.user?.id.orEmpty()
            android.util.Log.d(logTag, "$logPrefix Initial Session found ($userId). Starting listeners...")
            onAuthenticated(userId)
            android.util.Log.d(logTag, readyLog)
        }

        supabaseConfig.client.auth.sessionStatus.collect { status ->
            android.util.Log.d(logTag, "$logPrefix sessionStatus update: ${status::class.simpleName}")
            when (status) {
                is SessionStatus.Authenticated -> {
                    var userId = status.session.user?.id.orEmpty()
                    android.util.Log.d(logTag, "$logPrefix User authenticated ($userId). Starting listeners...")
                    onAuthenticated(userId)
                }
                is SessionStatus.NotAuthenticated -> {
                    android.util.Log.d(logTag, "$logPrefix User not authenticated. Stopping listeners...")
                    onNotAuthenticated()
                }
                else -> Unit
            }
        }
    }
}

internal fun CoroutineScope.launchCollectionRealtimeLoop(
    userId: String,
    logTag: String,
    collectionLabelPlural: String,
    syncAction: suspend () -> Result<Unit>,
    retryPendingAction: suspend () -> Unit,
    loadSubscribedIds: suspend () -> Set<String>,
    onSubscribedIdsChanged: (Set<String>) -> Unit,
    subscribeAction: suspend (String, List<String>) -> Unit,
    retryIntervalMs: Long = 45_000L
): Job = launch {
    syncAction().onFailure { error ->
        android.util.Log.e(logTag, "Initial sync failed for $collectionLabelPlural", error)
    }
    retryPendingAction()

    val joinedIds = loadSubscribedIds()
    onSubscribedIdsChanged(joinedIds)

    launch {
        while (isActive) {
            delay(retryIntervalMs)
            retryPendingAction()
        }
    }

    android.util.Log.d(logTag, "🚀 [REALTIME] Starting subscriptions for ${joinedIds.size} $collectionLabelPlural")
    subscribeAction(userId, joinedIds.toList())
}



