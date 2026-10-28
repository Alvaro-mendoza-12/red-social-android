package conectate.com.smoke

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingShareRestorationTest {
    @Test fun aHistoricalShareStartsNormallyAndDoesNotReturnAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(intent).use { activity ->
            // Reproduce the old share Intent delivered when Android recreates a task.
            // Keep the Scenario's launch filter MAIN, which is also the consumed intent.
            activity.onActivity { current ->
                current.intent = Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Entrega antigua de prueba")
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            }
            activity.recreate()
            activity.onActivity { assertEquals(Intent.ACTION_MAIN, it.intent.action) }
            activity.recreate()
            activity.onActivity { assertEquals(Intent.ACTION_MAIN, it.intent.action) }
        }
    }
}
