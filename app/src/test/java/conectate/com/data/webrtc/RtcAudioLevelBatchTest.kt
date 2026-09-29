package conectate.com.data.webrtc

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class RtcAudioLevelBatchTest {
    @Test fun `all peers deliver one immutable snapshot`() {
        val results = mutableListOf<Map<String, Double>>()
        val batch = RtcAudioLevelBatch(setOf("a", "b"), results::add)
        batch.accept("a", 0.5)
        assertTrue(results.isEmpty())
        batch.accept("b", 0.0)
        batch.accept("a", 0.9)
        batch.finish()
        assertEquals(listOf(mapOf("a" to 0.5)), results)
    }

    @Test fun `timeout ignores late native callbacks`() {
        val results = mutableListOf<Map<String, Double>>()
        val batch = RtcAudioLevelBatch(setOf("a", "b"), results::add)
        batch.accept("a", 0.3)
        batch.finish()
        batch.accept("b", 0.8)
        assertEquals(listOf(mapOf("a" to 0.3)), results)
    }

    @Test fun `concurrent callbacks deliver exactly once`() {
        val gate = CountDownLatch(1)
        val results = mutableListOf<Map<String, Double>>()
        val batch = RtcAudioLevelBatch(setOf("a", "b"), results::add)
        val threads = listOf("a", "b").map { id ->
            Thread { gate.await(); batch.accept(id, 0.4) }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, results.size)
        assertEquals(mapOf("a" to 0.4, "b" to 0.4), results.single())
    }

    @Test fun `empty batch invalid values and unknown peers are safe`() {
        var result: Map<String, Double>? = null
        RtcAudioLevelBatch(emptySet()) { result = it }.finish()
        assertEquals(emptyMap<String, Double>(), result)
        val batch = RtcAudioLevelBatch(setOf("a", "b")) { result = it }
        batch.accept("unknown", 1.0)
        batch.accept("a", Double.NaN)
        batch.accept("b", 3.0)
        assertEquals(mapOf("b" to 1.0), result)
    }
}
