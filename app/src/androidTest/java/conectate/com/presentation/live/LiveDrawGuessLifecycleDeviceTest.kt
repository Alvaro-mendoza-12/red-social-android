package conectate.com.presentation.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.localfallback.LiveDrawGuessApi
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.*
import conectate.com.data.repository.LiveDrawGuessRepository
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose lifecycle and dialog; synthetic API/transport with no external requests. */
@RunWith(AndroidJUnit4::class)
class LiveDrawGuessLifecycleDeviceTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Socket : WebSocket {
        override fun request() = Request.Builder().url("https://fixture.invalid/").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { }
    }
    private class Fixture {
        val live = "7245c3f0-3a6a-4d9d-93c1-e20236506111"
        val guard = AuthSessionMutationGuard()
        @Volatile var account = "a"
        val reads = AtomicInteger()
        @Volatile var prompt = "Sol privado"
        @Volatile var load: suspend () -> Result<LiveDrawGuessState?> = { Result.success(state()) }
        fun state() = LiveDrawGuessState(
            game = LiveDrawGuessGame("game", liveId = live, hostUserId = "host", status = "active"),
            players = listOf(LiveDrawGuessPlayer("a", status = "accepted")),
            round = LiveDrawGuessRound("round", "game", drawerUserId = "a", endsAt = Instant.now().plusSeconds(60).toString()),
            privatePrompt = if (account == "a") LiveDrawGuessPrompt("fixture", prompt) else null,
        )
        val api = object : LiveDrawGuessApi {
            override suspend fun loadCurrentLiveDrawGuess(accessToken: String, liveId: String): Result<LiveDrawGuessState?> {
                reads.incrementAndGet(); return load()
            }
            override suspend fun createLiveDrawGuess(accessToken: String, liveId: String, participantIds: List<String>, totalRounds: Int, roundSeconds: Int, idempotencyKey: String) = Result.success(state())
            override suspend fun acceptLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state())
            override suspend fun startLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state())
            override suspend fun cancelLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state())
            override suspend fun advanceLiveDrawGuess(accessToken: String, liveId: String, gameId: String) = Result.success(state())
            override suspend fun sendLiveDrawPacket(accessToken: String, liveId: String, gameId: String, packet: LiveDrawPacket) = Result.success(LiveDrawPacketReceipt(true))
            override suspend fun submitLiveDrawGuess(accessToken: String, liveId: String, gameId: String, guess: String, idempotencyKey: String) = Result.success(LiveDrawGuessGuessResult(state = state()))
        }
        val realtime = UbuntuRealtimeClient({ guard.captureAccountLease { account } }, { "synthetic-token" }, guard::commitForLease, { _, _ -> Socket() })
        val repository = LiveDrawGuessRepository(api, { guard.captureAccountLease { account } }, { "synthetic-token" }, guard::commitForLease,
            realtime, accountVisitChanges = guard.accountVisitChanges)
        val viewModel = LiveDrawGuessViewModel(repository)
        fun close() { viewModel.unbind(); repository.unbind() }
    }

    @Test fun pausingAndResumingRebindsTheGameAndDoesNotRestoreAnOldPrompt() {
        lateinit var owner: Owner
        lateinit var f: Fixture
        compose.runOnUiThread { owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }; f = Fixture() }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            LiveDrawGuessDialog(f.live, "a", false, emptyList(), {}, f.viewModel)
        } } }
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Dibuja: Sol privado").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(10_000) { f.viewModel.gameState.value == null }
            compose.runOnUiThread { f.prompt = "Luna reanudada"; owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Dibuja: Luna reanudada").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Dibuja: Sol privado").assertDoesNotExist()
            assertTrue(f.reads.get() >= 2)
        } finally { compose.runOnUiThread { f.close(); owner.registry.currentState = Lifecycle.State.DESTROYED } }
    }

    @Test fun aLateInitialSnapshotCannotReappearAfterClosingTheDialog() {
        lateinit var f: Fixture
        val shown = mutableStateOf(true)
        val response = CompletableDeferred<Result<LiveDrawGuessState?>>()
        compose.runOnUiThread { f = Fixture(); f.load = { withContext(NonCancellable) { response.await() } } }
        compose.setContent { MaterialTheme { if (shown.value) LiveDrawGuessDialog(f.live, "a", false, emptyList(), {}, f.viewModel) } }
        try {
            compose.waitUntil(15_000) { f.reads.get() > 0 }
            compose.runOnUiThread { shown.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { response.complete(Result.success(f.state())) }
            compose.waitUntil(10_000) { f.viewModel.gameState.value == null }
            assertFalse(f.viewModel.busy.value)
            compose.onNodeWithText("Dibuja: Sol privado").assertDoesNotExist()
        } finally { response.complete(Result.success(null)); compose.runOnUiThread { f.close() } }
    }

    @Test fun switchingAccountsRemovesThePrivateWordFromTheVisibleDialog() {
        lateinit var f: Fixture
        val account = mutableStateOf("a")
        compose.runOnUiThread { f = Fixture() }
        compose.setContent { MaterialTheme { LiveDrawGuessDialog(f.live, account.value, false, emptyList(), {}, f.viewModel) } }
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Dibuja: Sol privado").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { f.guard.invalidate { f.account = "b" }; account.value = "b" }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Dibuja: Sol privado").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Dibuja: Sol privado").assertDoesNotExist()
        } finally { compose.runOnUiThread { f.close() } }
    }
}
