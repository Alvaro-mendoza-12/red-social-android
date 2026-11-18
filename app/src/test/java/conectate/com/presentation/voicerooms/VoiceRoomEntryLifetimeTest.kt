package conectate.com.presentation.voicerooms

import android.util.Log
import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.VoiceConnectionState
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.repository.VoiceSessionState
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.voice.VoiceCueSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRoomEntryLifetimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val auth = mockk<AuthService>()
    private val guard = AuthSessionMutationGuard()
    private val repository = mockk<VoiceRoomRepository>(relaxUnitFun = true)
    private val currentRoom = MutableStateFlow<VoiceRoom?>(null)
    private val session = MutableStateFlow(VoiceSessionState())
    private val events = mutableListOf<String>()
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    private lateinit var model: VoiceRoomActiveViewModel

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { "listener" } }
        every { auth.getCurrentUserId() } returns "listener"
        coEvery { auth.getCurrentUserProfileSeed() } returns null
        every { repository.getCurrentUserId() } returns "listener"
        every { repository.currentRoom } returns currentRoom
        every { repository.sessionState } returns session
        every { repository.cueSettings } returns MutableStateFlow(VoiceCueSettings())
        every { repository.currentParticipants } returns MutableStateFlow(emptyList())
        every { repository.currentVideoMode } returns MutableStateFlow(VoiceRoomRepository.VideoMode.NONE)
        every { repository.remoteVideoTracks } returns MutableStateFlow(emptyMap())
        every { repository.localVideoTrack } returns MutableStateFlow(null)
        every { repository.peerConnectionStates } returns MutableStateFlow(emptyMap())
        every { repository.signalingStates } returns MutableStateFlow(emptyMap())
        every { repository.debugInfo } returns MutableStateFlow(emptyMap())
        every { repository.sharingStates } returns MutableStateFlow(emptyMap())
        every { repository.cinemaState } returns MutableStateFlow(null)
        every { repository.incomingMessages } returns MutableSharedFlow()
        every { repository.deletedRoomMessages } returns MutableSharedFlow()
        every { repository.incomingReactions } returns MutableSharedFlow()
        every { repository.videoError } returns MutableStateFlow(null)
        every { repository.captionsEnabled } returns MutableStateFlow(false)
        every { repository.captionConsentUserIds } returns MutableStateFlow(emptySet())
        every { repository.liveCaptions } returns MutableStateFlow(emptyList())
        every { repository.captionError } returns MutableStateFlow(null)
        every { repository.voiceSummary } returns MutableStateFlow(null)
        every { repository.voiceSummaryError } returns MutableStateFlow(null)
        every { repository.isVoiceSummaryLoading } returns MutableStateFlow(false)
        coEvery { repository.getRoomById(any()) } coAnswers { Result.success(room(firstArg())) }
        coEvery { repository.joinRoom(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val joined = room(firstArg())
            events += "join:${joined.id}"
            currentRoom.value = joined
            session.value = VoiceSessionState(roomId = joined.id, room = joined, isMuted = arg(5),
                isConnected = true, connectionState = VoiceConnectionState.CONNECTED)
        }
        coEvery { repository.leaveRoom(any()) } coAnswers {
            events += "leave"
            currentRoom.value = null
            session.value = VoiceSessionState()
        }
        model = VoiceRoomActiveViewModel(repository, auth, mockk<UserRepository>(), mockk<MessageRepository>())
        store.put("voice", model)
        drain()
    }

    @After fun stop() {
        store.clear()
        pending.forEach { it.complete(Unit) }
        drain()
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun room(id: String) = VoiceRoom(id = id, name = id, creatorId = "host", groupId = "group")
    private fun drain() = dispatcher.scheduler.runCurrent()
    private fun slowRoom(id: String, result: Result<VoiceRoom> = Result.success(room(id))): CompletableDeferred<Unit> {
        val reply = CompletableDeferred<Unit>().also(pending::add)
        coEvery { repository.getRoomById(id) } coAnswers {
            withContext(NonCancellable) { reply.await() }
            result
        }
        return reply
    }

    @Test fun anInvitationCanStillJoinOnRequestWithTheMicrophoneMuted() {
        model.joinRoom("a")
        drain()
        assertEquals(listOf("join:a"), events)
        assertEquals("a", model.room.value?.id)
        coVerify(exactly = 1) { repository.joinRoom("a", "listener", any(), any(), "a", true, null, any()) }
    }

    @Test fun leavingDuringTheRoomLookupPreventsALateJoin() {
        val delayed = slowRoom("old")
        model.joinRoom("old")
        drain()
        model.leaveRoom()
        drain()
        delayed.complete(Unit)
        drain()
        assertEquals(listOf("leave"), events)
        assertNull(model.room.value)
        assertFalse(model.isJoined.value)
    }

    @Test fun whenASecondRoomIsSelectedItsAdmissionSupersedesTheLateFirstRoom() {
        val delayed = slowRoom("old")
        model.joinRoom("old")
        drain()
        model.joinRoom("next")
        drain()
        delayed.complete(Unit)
        drain()
        assertEquals(listOf("join:next"), events)
        assertEquals("next", model.room.value?.id)
    }

    @Test fun aLateLookupErrorCannotReplaceTheCurrentConnectedRoom() {
        val delayed = slowRoom("old", Result.failure(IllegalStateException("fixture: old failure")))
        model.joinRoom("old")
        drain()
        model.joinRoom("next")
        drain()
        delayed.complete(Unit)
        drain()
        assertNull(model.joinError.value)
        assertEquals("next", model.room.value?.id)
    }

    @Test fun whenTheViewModelClosesDuringLookupItCannotStartTheMediaSession() {
        val delayed = slowRoom("old")
        model.joinRoom("old")
        drain()
        store.clear()
        delayed.complete(Unit)
        drain()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun explicitExitFinishesAfterInFlightAdmissionEvenIfTheScreenCloses() {
        val admitting = CompletableDeferred<Unit>().also(pending::add)
        coEvery { repository.joinRoom(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            events += "admit:start"
            withContext(NonCancellable) { admitting.await() }
            events += "admit:end"
        }
        model.joinRoom("old")
        drain()
        model.leaveRoom()
        drain()
        assertEquals(listOf("admit:start"), events)
        store.clear()
        admitting.complete(Unit)
        drain()
        assertEquals(listOf("admit:start", "admit:end", "leave"), events)
        assertFalse(model.isJoined.value)
    }

    @Test fun aNewAdmissionWaitsForTheRoomToFinishLeaving() {
        val leaving = CompletableDeferred<Unit>().also(pending::add)
        coEvery { repository.leaveRoom(any()) } coAnswers {
            events += "leave:start"
            withContext(NonCancellable) { leaving.await() }
            events += "leave:end"
            currentRoom.value = null
            session.value = VoiceSessionState()
        }
        model.joinRoom("old")
        drain()
        model.leaveRoom()
        drain()
        model.joinRoom("next")
        drain()
        assertEquals(listOf("join:old", "leave:start"), events)
        leaving.complete(Unit)
        drain()
        assertEquals(listOf("join:old", "leave:start", "leave:end", "join:next"), events)
        assertEquals("next", model.room.value?.id)
    }
}
