package conectate.com.data.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Run only with the owned emulator disconnected; never registers a synthetic FCM token. */
class PushRegistrationQueueDeviceTest {
    @Test fun offlineRefreshQueuesAFollowupPassInsteadOfLosingTheWakeup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        assumeTrue(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PushRegistrationScheduler.WORK_NAME).result.get(5, TimeUnit.SECONDS)
        PushRegistrationScheduler.enqueue(context)
        PushRegistrationScheduler.enqueue(context)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var queued: List<WorkInfo>
        do {
            queued = manager.getWorkInfosForUniqueWork(PushRegistrationScheduler.WORK_NAME).get(5, TimeUnit.SECONDS)
                .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            if (queued.size >= 2) break
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        assertEquals(2, queued.size)
        assertTrue(queued.any { it.state == WorkInfo.State.BLOCKED })
        manager.cancelUniqueWork(PushRegistrationScheduler.WORK_NAME).result.get(5, TimeUnit.SECONDS)
    }
}
