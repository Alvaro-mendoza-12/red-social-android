package conectate.com.core.concurrency

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

fun newSafeScope(
    scopeName: String,
    dispatcher: CoroutineDispatcher
): CoroutineScope {
    val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        val resolvedName = context[CoroutineName]?.name ?: scopeName
        Log.e("SafeScope", "Unhandled coroutine exception in $resolvedName", throwable)
    }

    return CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName(scopeName) + exceptionHandler
    )
}


