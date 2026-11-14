package conectate.com.data.repository

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.media.InteractiveMediaGate
import conectate.com.core.observability.AppMetrics
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.local.prefs.LivePreferenceStorage
import conectate.com.data.local.prefs.LivePreferences
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveCollaborationCapabilities
import conectate.com.data.model.LiveStream
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.VideoTrack

/** Uses the real repository and its startup/teardown; all network/native boundaries are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRepositorySessionOwnershipTest {
    private companion object {
        const val LIVE_ID = "11111111-1111-4111-8111-111111111111"
        const val ACCOUNT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }

    internal class Fixture(scope: TestScope, viewer: Boolean = false, source: String = "audio") {
        val guard = AuthSessionMutationGuard()
        var account = ACCOUNT_A
        var token = "fixture-a-first-visit"
        val auth = mockk<AuthService>()
        val api = mockk<LocalFallbackApiClient>()
        val mediaEvents = mutableListOf<SignalingEvents>()
        val tokenProviders = mutableListOf<() -> String?>()
        val emittedIce = mutableListOf<JSONObject>()
        val guestExitTokens = mutableListOf<String>()
        val sockets = mutableListOf<Socket>()
        val managers = mutableListOf<WebRTCManager>()
        val socketListeners = mutableListOf<MutableMap<String, Emitter.Listener>>()
        val remoteTrackStates = mutableListOf<MutableStateFlow<Map<String, VideoTrack>>>()
        val lookupEntered = CompletableDeferred<Unit>()
        var lookupRelease: CompletableDeferred<Unit>? = null
        val stream = LiveStream(id = LIVE_ID, hostUserId = if (viewer) ACCOUNT_B else ACCOUNT_A,
            hostName = "Anfitrión de laboratorio", title = "Sesión de laboratorio", source = source)
        val repository: LiveRepository
        val dispatchers: DispatcherProvider

        init {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            val config = mockk<SupabaseConfig>()
            every { config.isLocalFallbackPrimary } returns true
            every { auth.isLocalFallbackModeEnabled() } returns true
            every { auth.getCurrentUserId() } answers { account }
            every { auth.getAccessToken() } answers { token }
            every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
            every { auth.accountVisitChanges } returns guard.accountVisitChanges
            every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
            every { api.isConfigured } returns true
            coEvery { api.loadLiveStream(any(), LIVE_ID) } coAnswers {
                lookupEntered.complete(Unit)
                lookupRelease?.await()
                Result.success(stream)
            }
            coEvery { api.loadLiveStreams(any(), any()) } returns Result.success(emptyList())
            coEvery { api.loadLiveComments(any(), any(), any()) } returns Result.success(emptyList())
            coEvery { api.loadLiveCollaborationCapabilities(any()) } returns Result.success(LiveCollaborationCapabilities(sfuAvailable = viewer))
            coEvery { api.leaveLiveGuest(any(), any()) } answers { guestExitTokens += firstArg<String>(); Result.success(Unit) }
            val realtime = mockk<UbuntuRealtimeClient>(relaxUnitFun = true)
            every { realtime.eventsOnSubscription(any()) } returns emptyFlow()
            val bridge = mockk<LiveKitRoomBridge>(relaxUnitFun = true)
            every { bridge.errors } returns MutableSharedFlow()
            every { bridge.state } returns MutableStateFlow(LiveKitRoomBridge.State.DISCONNECTED)
            every { bridge.remoteVideos } returns MutableStateFlow(emptyList())
            every { bridge.localPreview } returns MutableStateFlow(null)
            every { bridge.remoteParticipants } returns MutableStateFlow(emptyList())
            val users = mockk<UserRepository>()
            coEvery { users.getCachedUserById(any()) } returns null
            val platform = mockk<LiveSessionPlatform>()
            every { platform.media(any(), any(), any(), any()) } answers {
                mediaEvents += secondArg<SignalingEvents>()
                tokenProviders += thirdArg<() -> String?>()
                mockk<WebRTCManager>(relaxed = true).also { manager ->
                    var videoStarted = false
                    val remoteTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
                    remoteTrackStates += remoteTracks
                    every { manager.remoteVideoTracks } returns remoteTracks
                    every { manager.localVideoTrack } returns MutableStateFlow(null)
                    every { manager.peerConnectionStates } returns MutableStateFlow(emptyMap())
                    every { manager.hasLocalVideoTrack() } answers { videoStarted }
                    coEvery { manager.startVideoAwait(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                        videoStarted = true; true
                    }
                    every { manager.switchCamera(any()) } answers { firstArg<(Boolean) -> Unit>().invoke(true); true }
                    every { manager.destroy(any()) } answers { firstArg<() -> Unit>().invoke(); Unit }
                    managers += manager
                }
            }
            every { platform.socket(any(), any()) } answers {
                mockk<Socket>(relaxed = true).also { socket ->
                    val listeners = mutableMapOf<String, Emitter.Listener>()
                    socketListeners += listeners
                    every { socket.connect() } returns socket
                    every { socket.disconnect() } returns socket
                    every { socket.connected() } returns false
                    every { socket.on(any<String>(), any<Emitter.Listener>()) } answers {
                        listeners[firstArg()] = secondArg(); socket
                    }
                    val ice = slot<JSONObject>()
                    every { socket.emit("ice-candidate", capture(ice)) } answers { emittedIce += ice.captured; socket }
                    sockets += socket
                }
            }
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            dispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = dispatcher
                override val default: CoroutineDispatcher = dispatcher
                override val main: CoroutineDispatcher = dispatcher
                override val mainImmediate: CoroutineDispatcher = dispatcher
            }
            val preferences = LivePreferences(object : LivePreferenceStorage {
                override fun getString(key: String): String? = null
                override fun putString(key: String, value: String) = Unit
            })
            repository = LiveRepository(context, config, auth, users, api, realtime, bridge,
                mockk<StorageService>(), preferences, mockk<AppMetrics>(relaxUnitFun = true),
                InteractiveMediaGate(), platform, dispatchers)
        }

        fun switchTo(accountId: String, accessToken: String) {
            guard.invalidate { account = accountId; token = accessToken }
        }

        fun cancelBackgroundWork() {
            val field = LiveRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(repository) as CoroutineScope).cancel()
        }
    }

    private fun exercise(viewer: Boolean = false, source: String = "audio", block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, viewer, source)
        try { block(fixture) } finally { fixture.cancelBackgroundWork(); runCurrent() }
    }

    private suspend fun Fixture.startHost() {
        val result = repository.hostLive(LIVE_ID)
        assertTrue("Fixture host must start before ownership assertions: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(ACCOUNT_A, repository.activeSession.value?.stream?.hostUserId)
        assertTrue(managers.isNotEmpty())
    }

    @Test fun anOwnedHostCanStartWithControlledNetworkAndMedia() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val firstVisit = fixture.repository.activeSession.value!!.sessionId
        assertTrue(fixture.repository.hostLive(LIVE_ID).isSuccess)
        assertEquals(firstVisit, fixture.repository.activeSession.value!!.sessionId)
        assertEquals(1, fixture.managers.size)
        assertEquals(1, fixture.sockets.size)
        assertEquals(fixture.token, fixture.tokenProviders.single().invoke())
    }

    @Test fun anotherAccountCannotReuseThePreviousAccountsHostSession() = exercise { fixture ->
        fixture.startHost()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        val result = fixture.repository.hostLive(LIVE_ID)
        assertFalse("Account B must not adopt account A's existing host session", result.isSuccess)
    }

    @Test fun returningToTheSameAccountDoesNotReuseItsEarlierMediaVisit() = exercise { fixture ->
        fixture.startHost()
        val firstVisit = fixture.repository.activeSession.value!!.sessionId
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        fixture.switchTo(ACCOUNT_A, "fixture-a-second-visit")
        val result = fixture.repository.hostLive(LIVE_ID)
        assertTrue(result.isSuccess)
        assertNotEquals("A -> B -> A needs a new media visit", firstVisit, fixture.repository.activeSession.value?.sessionId)
    }

    @Test fun anOldMediaCallbackCannotEmitUsingTheNewAccountsIdentity() = exercise { fixture ->
        fixture.startHost()
        val oldCallback = fixture.mediaEvents.single()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        oldCallback.onSendIceCandidate("target-fixture", JSONObject().put("candidate", "fixture-candidate"))
        assertTrue("Invalidated media must not emit ICE as the incoming account", fixture.emittedIce.isEmpty())
    }

    @Test fun anOldMediaTokenProviderCannotBorrowCredentialsFromTheNextVisit() = exercise { fixture ->
        fixture.startHost()
        val oldProvider = fixture.tokenProviders.single()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        assertNull("An invalidated engine must not read the next account's token", oldProvider())
    }

    @Test fun viewerCleanupUsesItsOwnCredentialsInsteadOfTheIncomingAccountsToken() = exercise(viewer = true) { fixture ->
        val joined = fixture.repository.joinLiveAsViewer(LIVE_ID)
        assertTrue("Fixture viewer must join before cleanup assertions: ${joined.exceptionOrNull()}", joined.isSuccess)
        val sessionId = fixture.repository.activeSession.value!!.sessionId
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        fixture.repository.endLive(sessionId)
        assertTrue("Viewer cleanup must use the captured outgoing credential",
            fixture.guestExitTokens.isNotEmpty() && fixture.guestExitTokens.all { it == "fixture-a-first-visit" })
    }

    @Test fun changingAccountReleasesTheOldNativeMediaAndSocket() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val manager = fixture.managers.single()
        val socket = fixture.sockets.single()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        runCurrent()
        assertNull("The outgoing media visit must no longer be active", fixture.repository.activeSession.value)
        verify(atLeast = 1) { manager.destroy(any()) }
        verify(atLeast = 1) { socket.disconnect() }
    }

    @Test fun aNewVisitWaitsForTheOldNativeResourcesToBeReleased() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val firstVisit = fixture.repository.activeSession.value!!.sessionId
        val oldManager = fixture.managers.single()
        val cleanupStarted = CompletableDeferred<Unit>()
        var releaseNative: (() -> Unit)? = null
        every { oldManager.destroy(any()) } answers {
            releaseNative = firstArg(); cleanupStarted.complete(Unit)
            Unit
        }
        try {
            fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
            fixture.switchTo(ACCOUNT_A, "fixture-a-second-visit")
            runCurrent()
            assertTrue("Account invalidation must start releasing its old native resources", cleanupStarted.isCompleted)
            val next = async { fixture.repository.hostLive(LIVE_ID) }
            runCurrent()
            assertFalse("A new media visit must wait for teardown", next.isCompleted)
            assertEquals(1, fixture.managers.size)
            requireNotNull(releaseNative).invoke()
            assertTrue(next.await().isSuccess)
            assertEquals(2, fixture.managers.size)
            assertNotEquals(firstVisit, fixture.repository.activeSession.value!!.sessionId)
        } finally { releaseNative?.invoke() }
    }

    @Test fun aLookupCompletedAfterChangingAccountCannotPublishTheOldSession() = exercise { fixture ->
        val release = CompletableDeferred<Unit>(); fixture.lookupRelease = release
        val startup = async { runCatching { fixture.repository.hostLive(LIVE_ID) } }
        try {
            runCurrent()
            assertTrue("The controlled lookup must be pending before account invalidation", fixture.lookupEntered.isCompleted)
            fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
            runCurrent()
            release.complete(Unit)
            startup.await()
            assertNull("A stale lookup must not publish a media visit", fixture.repository.activeSession.value)
            assertTrue("A stale lookup must not start native media", fixture.managers.isEmpty())
        } finally { release.complete(Unit) }
    }

    @Test fun aCurrentSocketCanUpdateItsOwnedAudience() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        fixture.socketListeners.single().getValue("viewer-count").call(
            JSONObject().put("liveId", LIVE_ID).put("viewerCount", 7))
        assertEquals(7, fixture.repository.viewerCount.value)
        fixture.socketListeners.single().getValue("viewer-joined").call(
            JSONObject().put("socketId", "fixture-viewer").put("userId", ACCOUNT_B).put("name", "Visor"))
        assertEquals("fixture-viewer", fixture.repository.liveViewers.value.single().socketId)
    }

    @Test fun anOldSocketCannotUpdateAudienceAfterReturningToTheSameAccount() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val listener = fixture.socketListeners.single().getValue("viewer-count")
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        fixture.switchTo(ACCOUNT_A, "fixture-a-second-visit")
        listener.call(JSONObject().put("liveId", LIVE_ID).put("viewerCount", 999))
        assertEquals("A socket from an earlier account visit must not update the current audience", 0,
            fixture.repository.viewerCount.value)
    }

    @Test fun anOldSocketCannotCreatePeersForTheIncomingAccount() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val manager = fixture.managers.single()
        val listener = fixture.socketListeners.single().getValue("viewer-joined")
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        listener.call(JSONObject().put("socketId", "stale-viewer").put("userId", ACCOUNT_B))
        assertTrue("Invalidated signaling must not expose an old participant", fixture.repository.liveViewers.value.isEmpty())
        verify(exactly = 0) { manager.onUserJoined("stale-viewer", ACCOUNT_B, any()) }
    }

    @Test fun anOldNativeCollectorCannotPublishTracksAfterAccountInvalidation() = exercise { fixture ->
        fixture.startHost(); runCurrent()
        val tracks = fixture.remoteTrackStates.single()
        val oldTrack = mockk<VideoTrack>()
        tracks.value = mapOf("owned-peer" to oldTrack); runCurrent()
        assertEquals(oldTrack, fixture.repository.remoteVideoTracks.value["owned-peer"])
        tracks.value = emptyMap(); runCurrent()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit")
        runCurrent()
        tracks.value = mapOf("late-peer" to oldTrack); runCurrent()
        assertTrue("Late tracks from invalidated native media must not reach the incoming account",
            fixture.repository.remoteVideoTracks.value.isEmpty())
    }

    @Test fun aCameraPreviewKeepsItsSelectedLensAndNativeManagerWhenTheOwnerStartsHosting() = exercise(source = "camera") { fixture ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        val previewManager = fixture.managers.single()
        assertTrue(fixture.repository.switchSetupCameraPreview())
        assertFalse(fixture.repository.isFrontCamera.value)
        fixture.startHost(); runCurrent()
        assertEquals(listOf(previewManager), fixture.managers)
        assertFalse(fixture.repository.isFrontCamera.value)
        coVerify(exactly = 0) { previewManager.hardResetAwait() }
        verify(exactly = 0) { previewManager.destroy(any()) }
    }

    @Test fun aPrivatePreviewIsReleasedWhenItsAccountVisitEnds() = exercise(source = "camera") { fixture ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        val previewManager = fixture.managers.single()
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit"); runCurrent()
        verify(atLeast = 1) { previewManager.destroy(any()) }
        assertNull(fixture.tokenProviders.single().invoke())
    }

    @Test fun aScreenPreviewPermissionCannotBeConsumedByTheNextAccount() = exercise { fixture ->
        assertTrue(fixture.repository.startSetupScreenPreview(Intent("fixture-projection")).isSuccess)
        assertTrue(fixture.repository.hasSetupScreenProjectionIntent())
        fixture.switchTo(ACCOUNT_B, "fixture-b-visit"); runCurrent()
        assertNull("MediaProjection permission belongs to the visit that obtained it",
            fixture.repository.consumeSetupScreenProjectionIntent())
    }
}
