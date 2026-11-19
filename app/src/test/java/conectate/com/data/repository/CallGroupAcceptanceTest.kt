package conectate.com.data.repository

import android.app.Application
import conectate.com.core.media.InteractiveMediaGate
import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallParticipant
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.webrtc.WebRTCManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.webrtc.PeerConnection

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CallGroupAcceptanceTest {
    private class Fixture(scope: TestScope, contextType: String, invitees: Set<String>) {
        val callId = UUID.randomUUID().toString()
        private val supabase = mockk<SupabaseConfig>()
        val control = mockk<CallControlClient>()
        val repository = CallRepository(
            RuntimeEnvironment.getApplication(), supabase, mockk(relaxed = true), mockk(), mockk(),
            mockk(), mockk(), mockk(relaxed = true), mockk(), mockk(relaxed = true),
            mockk(), mockk(), control, InteractiveMediaGate(),
        )
        private val activeCall = ReflectionHelpers.getField<MutableStateFlow<ActiveCall?>>(repository, "_activeCall")
        val pending = ReflectionHelpers.getField<MutableSet<String>>(repository, "pendingInvitees")

        init {
            every { supabase.isLocalFallbackPrimary } returns true
            coEvery { control.leave(any(), any(), any()) } returns Result.success(Unit)
            activeCall.value = ActiveCall(callId, "Llamada de prueba", "host",
                CallConnectionState.RINGING_OUTGOING, contextType = contextType, peerUserId = "first")
            ReflectionHelpers.setField(repository, "scope", scope.backgroundScope)
            pending.addAll(invitees)
        }

        fun acceptFirst() = repository.handleExternalCallResponse(callId, "accept", "first")

        fun connectFirst() {
            val media = mockk<WebRTCManager>()
            every { media.peerConnectionStates } returns MutableStateFlow(
                mapOf("first" to PeerConnection.IceConnectionState.CONNECTED)
            )
            ReflectionHelpers.getField<MutableStateFlow<WebRTCManager?>>(repository, "_webRTCManager").value = media
            ReflectionHelpers.getField<MutableStateFlow<List<CallParticipant>>>(repository, "_participants").value =
                listOf(CallParticipant("host", "Anfitrión", socketId = "host"),
                    CallParticipant("first", "Primero", socketId = "first"))
            ReflectionHelpers.callInstanceMethod<Unit>(repository, "promoteConnectedIfReady")
        }

        fun finalNotificationTargets(): List<String> = ReflectionHelpers.callInstanceMethod(
            repository, "buildEndSignalTargets", ClassParameter.from(String::class.java, "host")
        )
    }

    @Test fun theFirstGroupAnswerDoesNotEndTheCall() = runTest {
        val fixture = Fixture(this, "group", setOf("first", "second"))
        fixture.acceptFirst()
        runCurrent()
        assertEquals(CallConnectionState.CONNECTING, fixture.repository.activeCall.value?.connectionState)
        coVerify(exactly = 0) { fixture.control.leave(any(), fixture.callId, "cancel") }
        assertEquals(setOf("first", "second"), fixture.pending)
    }

    @Test fun aSecondCommunityInviteCanStillBeClearedWhenTheHostEndsTheConnectedCall() = runTest {
        val fixture = Fixture(this, "community", setOf("first", "second"))
        fixture.acceptFirst()
        fixture.connectFirst()
        runCurrent()
        assertEquals(CallConnectionState.CONNECTED, fixture.repository.activeCall.value?.connectionState)
        assertEquals(setOf("first", "second"), fixture.finalNotificationTargets().toSet())
    }

    @Test fun acceptingADirectCallDoesNotSendAnyCancellation() = runTest {
        val fixture = Fixture(this, "dm", setOf("first"))
        fixture.acceptFirst()
        runCurrent()
        assertEquals(CallConnectionState.CONNECTING, fixture.repository.activeCall.value?.connectionState)
        coVerify(exactly = 0) { fixture.control.leave(any(), any(), any()) }
    }
}
