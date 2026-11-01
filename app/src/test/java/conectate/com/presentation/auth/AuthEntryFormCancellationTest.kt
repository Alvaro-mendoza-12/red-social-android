package conectate.com.presentation.auth

import conectate.com.core.concurrency.AccountTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthEntryFormCancellationTest {
    @Test fun leavingTheFormCancelsItsRequestWithoutPublishingAnError() = runTest {
        val form = AccountTasks(this)
        var published = false
        val job = form.scope.launch {
            authEntryOperation<Unit> { awaitCancellation() }
            published = true
        }
        runCurrent()
        form.reset()
        job.join()
        assertFalse(published)
        form.close()
    }

    @Test fun aLateRecoveryResponseCannotReplaceTheNextFormResultEvenIfTransportSwallowsCancellation() = runTest {
        val form = AccountTasks(this)
        val previousResponse = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val old = form.scope.launch {
            val result = authEntryOperation {
                withContext(NonCancellable) { Result.success(previousResponse.await()) }
            }
            published += result.getOrThrow()
        }
        runCurrent()
        form.reset()
        form.scope.launch { published += authEntryOperation { Result.success("next") }.getOrThrow() }
        runCurrent()
        previousResponse.complete("old")
        old.join()
        assertEquals(listOf("next"), published)
        form.close()
    }
}
