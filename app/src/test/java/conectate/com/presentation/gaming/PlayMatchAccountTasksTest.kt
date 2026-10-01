package conectate.com.presentation.gaming

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayMatchAccountTasksTest {
    @Test fun accountChangeCancelsOldWorkAndNewAccountCanRun() = runTest {
        val tasks = PlayMatchAccountTasks(this)
        val previous = tasks.scope.launch { awaitCancellation() }
        runCurrent()
        tasks.reset()
        assertTrue(previous.isCancelled)
        var ran = false
        tasks.scope.launch { ran = true }
        runCurrent()
        assertTrue(ran)
        tasks.close()
    }
}
