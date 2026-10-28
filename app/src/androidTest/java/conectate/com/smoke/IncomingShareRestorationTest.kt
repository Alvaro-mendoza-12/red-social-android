package conectate.com.smoke

import android.content.Intent
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IncomingShareRestorationTest {
    @Test fun aHistoricalShareStartsNormallyAndDoesNotReturnAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = context.applicationContext as Application
        val resumed = LinkedBlockingQueue<MainActivity>()
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) resumed.offer(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(observer)
        var current: MainActivity? = null
        try {
            // ActivityScenario filters PRE_ON_CREATE by the original launch Intent.
            // A restored SEND is deliberately a different filter, so observe the real
            // lifecycle directly rather than lose the recreated Activity in the harness.
            instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            current = checkNotNull(resumed.poll(20, TimeUnit.SECONDS)) { "Initial Activity did not resume" }
            instrumentation.runOnMainSync {
                current!!.intent = Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Entrega antigua de prueba")
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                current!!.recreate()
            }
            current = checkNotNull(resumed.poll(20, TimeUnit.SECONDS)) { "Historical share Activity did not resume" }
            instrumentation.runOnMainSync { assertEquals(Intent.ACTION_MAIN, current!!.intent.action); current!!.recreate() }
            current = checkNotNull(resumed.poll(20, TimeUnit.SECONDS)) { "Consumed Activity did not resume" }
            instrumentation.runOnMainSync { assertEquals(Intent.ACTION_MAIN, current!!.intent.action) }
        } finally {
            instrumentation.runOnMainSync { current?.finish() }
            application.unregisterActivityLifecycleCallbacks(observer)
        }
    }
}
