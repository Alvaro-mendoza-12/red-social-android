package conectate.com

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in bootstrap of a newly created QA emulator; never replaces an unrelated account. */
@RunWith(AndroidJUnit4::class)
class FreshQaSessionSetupTest {
    @Test(timeout = 150_000) fun ownFreshAccountReachesInboxAndObtainsAnActualFcmToken() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditFreshQa") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(ConnectionResult.SUCCESS, GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
        val fixtures = JSONArray(File(context.cacheDir, "media-qa-login-private.json").readText())
        assertEquals(2, fixtures.length())
        val account = fixtures.getJSONObject(0)
        val email = account.getString("email")
        require(email.startsWith("cierre11.google.") && email.endsWith("@example.invalid"))
        val auth = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java).authService()
        awaitCondition(15_000) { !auth.isInitializing.value }
        val previous = auth.getCurrentUserId()
        assertTrue("Never replace a human or unrelated QA session", previous == null || previous == account.getString("id"))
        if (previous == null) runBlocking { auth.signIn(email, account.getString("password")).getOrThrow() }
        assertEquals(account.getString("id"), auth.getCurrentUserId())
        // Physical devices can block an instrumentation process starting its own
        // Activity from the background. Use the authorized shell launch surface.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                assertTrue("QA foreground launch failed", input.bufferedReader().readText().contains("Status: ok"))
            }
        }
        awaitCondition(30_000) {
            hasText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, "Mensajes")
        }
        val ready = CountDownLatch(1)
        var success = false
        FirebaseMessaging.getInstance().token.addOnCompleteListener {
            success = it.isSuccessful && !it.result.isNullOrBlank()
            ready.countDown()
        }
        assertTrue("Google emulator did not return an FCM token", ready.await(60, TimeUnit.SECONDS))
        assertTrue("Actual Firebase token acquisition failed", success)
        // MainActivity's production SessionSideEffects registers this token for the session.
        // The isolated server outbox independently checks the persisted recipient token.
        Thread.sleep(2_000)
        File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "fresh-qa-session.json")
            .writeText(JSONObject().put("ownAccountOnly", true).put("authenticatedInboxVisible", true)
                .put("actualGoogleServices", true).put("actualFirebaseTokenObtained", true)
                .put("humanSessionNeverReplaced", true).toString(2))
        File(context.cacheDir, "media-qa-login-private.json").delete()
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Fresh QA session did not reach the requested state", condition())
    }

    private fun hasText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), text) }
    }
}
