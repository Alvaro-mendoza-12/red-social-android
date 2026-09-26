package conectate.com.data.repository.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListenerBindingTest {
    @Test fun `logout cleanup leaves observer ready for another login`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val started = mutableListOf<String>()
        val binding = SessionListenerBinding(backgroundScope, accounts, {}) { started += it }
        runCurrent()
        binding.stopActive()
        accounts.value = null
        runCurrent()
        accounts.value = "b"
        runCurrent()
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun `returning to same account after logout restarts reception`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val started = mutableListOf<String>()
        SessionListenerBinding(backgroundScope, accounts, {}) { started += it }
        runCurrent()
        accounts.value = null
        runCurrent()
        accounts.value = "a"
        runCurrent()
        assertEquals(listOf("a", "a"), started)
    }

    @Test fun `switch cancels old children before starting next session`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val events = mutableListOf<String>()
        SessionListenerBinding(backgroundScope, accounts, {}) { account ->
            events += "start-$account"
            launch { try { awaitCancellation() } finally { events += "stop-$account" } }
        }
        runCurrent()
        accounts.value = "b"
        runCurrent()
        assertEquals(listOf("start-a", "stop-a", "start-b"), events)
    }

    @Test fun `new session waits for asynchronous old child cleanup`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val events = mutableListOf<String>()
        SessionListenerBinding(backgroundScope, accounts, {}) { account ->
            events += "start-$account"
            launch {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { delay(100); events += "stop-$account" }
                }
            }
        }
        runCurrent()
        accounts.value = "b"
        runCurrent()
        assertEquals(listOf("start-a"), events)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("start-a", "stop-a", "start-b"), events)
    }

    @Test fun `renewed credentials can rebind the same user without a logout`() = runTest {
        val accounts = MutableSharedFlow<String?>(extraBufferCapacity = 2)
        val started = mutableListOf<String>()
        SessionListenerBinding(backgroundScope, accounts, {}) { started += it }
        runCurrent()
        accounts.emit(" A ")
        runCurrent()
        accounts.emit("A")
        runCurrent()
        assertEquals(listOf("a", "a"), started)
    }
}
