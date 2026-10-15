package conectate.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real native objects: rapid departure, trim, re-entry and concurrent UI level reads. */
@RunWith(AndroidJUnit4::class)
class MediaLifecycleDeviceAuditTest {
    @Test(timeout = 120_000) fun rapidReentryDrainsDeferredNativePeersBeforeMemoryTrim() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditMediaLifecycle") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WebRTCManager(context, object : SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: JSONObject) = Unit
            override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = Unit
            override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = Unit
        })
        val executorField = manager.javaClass.getDeclaredField("executor").apply { isAccessible = true }
        // The production field is now a rejection-safe MediaTaskExecutor wrapper.
        // A barrier on its backing queue observes completion of the same native tasks.
        val wrappedExecutor = executorField.get(manager)
        val delegateField = wrappedExecutor.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
        val executor = delegateField.get(wrappedExecutor) as java.util.concurrent.ExecutorService
        val pendingField = manager.javaClass.getDeclaredField("deferredPeerDisposals").apply { isAccessible = true }
        val queue = pendingField.get(manager)
        val pendingEntries = queue.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        fun barrier() {
            val ready = CountDownLatch(1)
            executor.execute { ready.countDown() }
            assertTrue("Native media queue stalled", ready.await(10, TimeUnit.SECONDS))
        }
        val disposalMs = org.json.JSONArray()
        fun awaitRendererDetachDisposals() {
            // hardResetAwait clears logical state; native sinks retain the existing
            // 96 ms renderer-detach grace. Observe its actual bounded completion.
            val began = System.nanoTime()
            val deadline = began + TimeUnit.SECONDS.toNanos(2)
            while (true) {
                barrier()
                if (synchronized(queue) { (pendingEntries.get(queue) as Set<*>).isEmpty() }) break
                assertTrue("Native peers exceeded the two-second cleanup bound", System.nanoTime() < deadline)
                Thread.sleep(10)
            }
            disposalMs.put(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began))
        }
        val polling = AtomicBoolean(true)
        val reader = Thread {
            while (polling.get()) {
                manager.getLocalAudioLevel()
                manager.isHealthy()
                Thread.sleep(2)
            }
        }.apply { start() }
        try {
            repeat(16) {
                val peer = UUID.randomUUID().toString()
                manager.setCurrentUser(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                manager.onUserJoined(peer, UUID.randomUUID().toString())
                barrier()
                assertTrue("No real native peer was created", manager.peerConnectionStates.value.isNotEmpty())
                manager.recoverAfterNetworkChange("qa-rapid-departure")
                manager.onUserLeft(peer)
                runBlocking { manager.hardResetAwait() }
                barrier()
                assertTrue("Queued recovery restored a departed peer", manager.peerConnectionStates.value.isEmpty())
                awaitRendererDetachDisposals()
                assertTrue("Teardown left native peers pending", synchronized(queue) { (pendingEntries.get(queue) as Set<*>).isEmpty() })
                WebRTCManager.releaseIdleResources()
                barrier()
                manager.ensureLocalTracks()
                barrier()
                assertTrue("Media factory was not restored", manager.isHealthy())
                Thread.sleep(120)
            }
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "media-lifecycle.json")
                .writeText(JSONObject().put("iterations", 16).put("trimAndReentry", true)
                    .put("noPendingPeers", true).put("recoveryDuringDeparture", true)
                    .put("rendererDetachDisposalMs", disposalMs).put("cleanupBoundMs", 2000).toString(2))
        } finally {
            val closed = CountDownLatch(1)
            try {
                manager.destroy { closed.countDown() }
                assertTrue("Media engine did not close", closed.await(15, TimeUnit.SECONDS))
            } finally {
                polling.set(false)
                reader.join(5_000)
            }
        }
    }
}
