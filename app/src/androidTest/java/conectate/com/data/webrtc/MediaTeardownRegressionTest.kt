package conectate.com.data.webrtc

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import conectate.com.data.service.CallCancellationGuard
import conectate.com.presentation.components.WebRtcVideoView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaTeardownRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disposedVideoTrackDoesNotCrashRecomposition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack("qa-disposed-track", source)
        val egl = EglBase.create()
        val visible = mutableStateOf(true)
        try {
            track.dispose()
            compose.setContent { if (visible.value) WebRtcVideoView(track, egl.eglBaseContext, Modifier.size(100.dp)) }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        } finally { source.dispose(); factory.dispose(); egl.release() }
    }

    @Test fun duplicateTeardownAndLateCallbacksFinishWithoutClosingTheApplication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WebRTCManager(context, object : SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: JSONObject) = Unit
            override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = Unit
            override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = Unit
        })
        repeat(3) { manager.hardResetAwait(); manager.stopVideoAwait() }
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        manager.destroy { first.complete(Unit) }
        manager.destroy { second.complete(Unit) }
        withTimeout(8_000) { first.await(); second.await() }
        manager.onUserLeft("qa-late-peer")
        manager.setAudioEnabled(false)
        withTimeout(2_000) { manager.stopVideoAwait(); manager.hardResetAwait() }
        assertNull(manager.localVideoTrack.value)
        assertTrue(manager.remoteVideoTracks.value.isEmpty())
    }

    @Test fun cancellationPersistsForAnInvitationArrivingAfterColdStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "qa-" + UUID.randomUUID()
        try {
            CallCancellationGuard(context).mark(id.uppercase())
            assertTrue(CallCancellationGuard(context).contains(id))
            assertFalse(CallCancellationGuard(context).contains(id + "-other"))
        } finally {
            context.getSharedPreferences("call_notification_guard", Context.MODE_PRIVATE).edit().remove("cancelled_call_$id").commit()
        }
    }
}
