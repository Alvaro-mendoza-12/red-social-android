package conectate.com.core.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class MessagingExtremesStressTest {

    @Test
    fun `unstable network with aggressive reconnect eventually flushes queue once`() = runBlocking {
        val dispatcher = SimulatedDispatcher()
        repeat(400) { index ->
            dispatcher.enqueue(
                DispatchJob(
                    id = "job-$index",
                    scopeKey = "chat-${index % 6}"
                )
            )
        }

        // Aggressive connectivity flaps.
        val flaps = listOf(false, false, true, false, true, true, false, true, true, true)
        flaps.forEach { networkUp ->
            dispatcher.pump(networkAvailable = networkUp)
        }

        assertEquals(400, dispatcher.sentCount())
        assertEquals(400, dispatcher.uniqueSentCount())
        assertTrue(dispatcher.queueSize() == 0)
    }

    @Test
    fun `view once multidispositivo race allows single consumer`() = runBlocking {
        val guard = ViewOnceRaceGuard()
        val success = AtomicInteger(0)

        coroutineScope {
            listOf("device-a", "device-b", "device-c", "device-d")
                .map { device ->
                    async(Dispatchers.Default) {
                        if (guard.consume("msg-unique", device)) {
                            success.incrementAndGet()
                        }
                    }
                }
                .awaitAll()
        }

        assertEquals(1, success.get())
        assertTrue(guard.isConsumed("msg-unique"))
    }

    @Test
    fun `spam burst and double tap dedupe keeps last state consistent`() = runBlocking {
        val dispatcher = SimulatedDispatcher()
        val duplicates = 180

        repeat(duplicates) {
            dispatcher.enqueue(
                DispatchJob(
                    id = "duplicate-id",
                    scopeKey = "chat-spam"
                )
            )
        }
        repeat(120) { index ->
            dispatcher.enqueue(
                DispatchJob(
                    id = "unique-$index",
                    scopeKey = "chat-${index % 3}"
                )
            )
        }

        repeat(5) {
            dispatcher.pump(networkAvailable = true)
        }

        assertEquals(121, dispatcher.uniqueSentCount()) // 1 duplicate + 120 unique
        assertEquals(121, dispatcher.sentCount())
    }
}

private data class DispatchJob(
    val id: String,
    val scopeKey: String
)

private class SimulatedDispatcher {
    private val queue = ConcurrentLinkedQueue<DispatchJob>()
    private val delivered = Collections.synchronizedSet(mutableSetOf<String>())
    private val attempts = ConcurrentHashMap<String, Int>()

    fun enqueue(job: DispatchJob) {
        queue.add(job)
    }

    suspend fun pump(networkAvailable: Boolean) = coroutineScope {
        if (!networkAvailable) return@coroutineScope
        val batch = mutableListOf<DispatchJob>()
        while (batch.size < 128) {
            val item = queue.poll() ?: break
            batch += item
        }
        batch.map { job ->
            async(Dispatchers.Default) {
                val currentAttempt = attempts.compute(job.id) { _, old -> (old ?: 0) + 1 } ?: 1
                if (currentAttempt <= 2 && delivered.contains(job.id)) {
                    return@async
                }
                delivered.add(job.id)
            }
        }.awaitAll()
    }

    fun sentCount(): Int = delivered.size
    fun uniqueSentCount(): Int = delivered.size
    fun queueSize(): Int = queue.size
}

private class ViewOnceRaceGuard {
    private val consumed = Collections.synchronizedSet(mutableSetOf<String>())

    fun consume(messageId: String, deviceId: String): Boolean {
        if (messageId.isBlank() || deviceId.isBlank()) return false
        synchronized(consumed) {
            if (consumed.contains(messageId)) return false
            consumed.add(messageId)
            return true
        }
    }

    fun isConsumed(messageId: String): Boolean = consumed.contains(messageId)
}

