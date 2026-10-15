package conectate.com

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.testing.RuntimeSessionOverride
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.data.service.IncomingCallReceiverEntryPoint
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.presentation.calls.IncomingCallActivity
import conectate.com.presentation.navigation.IntentRouter
import conectate.com.presentation.navigation.Screen
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.TrustedNavigationIntents
import conectate.com.util.putNotificationAccountTarget
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import conectate.com.data.webrtc.WebRTCManager
import conectate.com.data.webrtc.MediaTaskExecutor

@RunWith(AndroidJUnit4::class)
class ColdCallNativeAuditTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun missingSavedAccountShowsRetryInsteadOfHangingAcrossRecreation() {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra("call_id", UUID.randomUUID().toString())
            putExtra("caller_user_id", UUID.randomUUID().toString())
            putExtra("caller_name", "QA llamada local")
            putExtra("call_context_type", "dm")
            putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
            putNotificationAccountTarget(NotificationAccountTarget(userId=UUID.randomUUID().toString()))
        }
        ActivityScenario.launch<IncomingCallActivity>(intent).use { scenario ->
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Contestar")).fetchSemanticsNodes().isNotEmpty()
            }
            // The absent target cannot generate any real accept request or call.
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Inicia sesión en la cuenta que recibió esta llamada.")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Contestar").assertIsEnabled()
            scenario.recreate()
            compose.onNodeWithText("Inicia sesión en la cuenta que recibió esta llamada.").assertIsDisplayed()
            compose.onNodeWithText("Contestar").assertIsEnabled()
            compose.onNodeWithText("Rechazar").performClick()
        }
    }

    @Test(timeout=150_000) fun ownedCallerConnectsAudioToColdPhoneAnswer() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("ownedColdCall") == "true")
        val fixture = JSONObject(File(context.cacheDir,"owned-cold-call-private.json").readText())
        val caller=fixture.getJSONObject("caller");val recipient=fixture.getJSONObject("recipient")
        assertTrue(caller.getString("username") in listOf("alvaro","novvin"))
        assertTrue(recipient.getString("username") in listOf("alvaro","novvin"))
        assertNotEquals(caller.getString("id"),recipient.getString("id"))
        val auth=EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java).authService()
        runBlocking { withTimeout(20_000) { auth.isInitializing.first { !it } } }
        assertNull("Use an owned emulator without a member session",auth.getCurrentUserId())
        TestRuntimeOverrides.setCurrentSession(context, RuntimeSessionOverride(
            caller.getString("id"),caller.getString("email"),caller.getString("token"),"qa-only",
            displayName="QA llamada"))
        runBlocking { auth.importSession(caller.getString("token"),"qa-only") }
        val callerActivity=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
        val repository=EntryPointAccessors.fromApplication(context,IncomingCallReceiverEntryPoint::class.java).callRepository()
        assertNull(repository.activeCall.value)
        val output=File(context.cacheDir,"communication-qa").apply { mkdirs() }
        val reportFile=File(output,"cold-call-result.json");val ready=File(output,"cold-call-ready.json")
        val stop=File(context.cacheDir,"cold-call-stop")
        ready.delete();stop.delete()
        val report=JSONObject().put("realCallRepository",true).put("onlyOwnedAccounts",true)
        val began=SystemClock.elapsedRealtime()
        try {
            val id=runBlocking { repository.startOutgoingCall("Prueba llamada cerrada",listOf(recipient.getString("id")),"dm", contextId=fixture.getString("chatId")).getOrThrow() }
            report.put("callId",id)
            ready.writeText(JSONObject().put("callId",id).put("phase","ringing").toString())
            runBlocking { withTimeout(65_000) { repository.activeCall.first {
                it?.callId==id && it.connectionState==CallConnectionState.CONNECTED
            } } }
            report.put("connectedFromStartMs",SystemClock.elapsedRealtime()-began)
            val field=CallRepository::class.java.getDeclaredField("_webRTCManager").apply { isAccessible=true }
            val manager=(field.get(repository) as StateFlow<*>).value as WebRTCManager
            val peersField=WebRTCManager::class.java.getDeclaredField("peerConnections").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            val peers=peersField.get(manager) as ConcurrentHashMap<String,PeerConnection>
            val executor=WebRTCManager::class.java.getDeclaredField("executor").apply { isAccessible=true }.get(manager) as MediaTaskExecutor
            fun packets(): Long {
                val latch=CountDownLatch(1);var count=0L
                executor.execute(onUnavailable={latch.countDown()}) {
                    val peer=peers.values.firstOrNull()
                    if(peer==null) latch.countDown() else peer.getStats { stats ->
                        count=stats.statsMap.values.filter {it.type=="inbound-rtp" && it.members["kind"]=="audio"}
                            .sumOf {(it.members["packetsReceived"] as? Number)?.toLong() ?: 0L}
                        latch.countDown()
                    }
                }
                assertTrue(latch.await(4,TimeUnit.SECONDS));return count
            }
            val initial=packets();SystemClock.sleep(2_000);val after=packets()
            assertTrue("Receiver's audio did not arrive",after>initial)
            report.put("freshIncomingAudioPackets",after-initial).put("passed",true)
            reportFile.writeText(report.toString(2))
            ready.writeText(JSONObject().put("callId",id).put("phase","audio-connected").toString())
            val deadline=SystemClock.elapsedRealtime()+35_000
            while(!stop.exists() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(200)
        } catch(error: Exception) {
            report.put("failureType",error.javaClass.simpleName)
                .put("failureMessage",error.toUserFacingMessage("No se completó la llamada de prueba."))
            throw error
        } finally {
            runCatching { runBlocking { repository.hangup() } }
                .onFailure { report.put("hangupCleanupFailure",it.javaClass.simpleName) }
            runCatching { callerActivity.close() }
                .onFailure { report.put("activityCleanupFailure",it.javaClass.simpleName) }
            TestRuntimeOverrides.clearSession(context)
            File(context.cacheDir,"owned-cold-call-private.json").delete()
            reportFile.writeText(report.toString(2))
        }
    }
}
