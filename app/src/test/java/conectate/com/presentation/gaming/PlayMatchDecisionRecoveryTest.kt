package conectate.com.presentation.gaming

import androidx.lifecycle.ViewModelStore
import conectate.com.data.gaming.PlayMatchApiException
import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchConnectionsResponse
import conectate.com.data.model.PlayMatchDecisionResponse
import conectate.com.data.model.PlayMatchMatch
import conectate.com.data.model.PlayMatchMatchesResponse
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.PlayMatchRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthService
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PlayMatchDecisionRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = mockk<PlayMatchRepository>()
    private lateinit var model: PlayMatchViewModel

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        val auth = mockk<AuthService>()
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        coEvery { repository.matches() } returns Result.success(PlayMatchMatchesResponse())
        coEvery { repository.connections() } returns Result.success(PlayMatchConnectionsResponse())
        model = PlayMatchViewModel(repository, mockk<UserRepository>(), mockk<MessageRepository>(), auth)
        store.put("decision-test", model)
    }

    @After fun stop() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun aConfirmedMatchRefreshesItsConversationImmediately() = runTest(dispatcher) {
        runCurrent()
        val connection = PlayMatchConnection("connection", status = "ACCEPTED", otherUserId = "other")
        coEvery { repository.decideMatch("match", "ACCEPT", "") } returns Result.success(PlayMatchDecisionResponse(status = "MATCHED"))
        coEvery { repository.connections() } returns Result.success(PlayMatchConnectionsResponse(listOf(connection)))
        model.decideMatchById("match", "ACCEPT")
        runCurrent()
        assertEquals(listOf(connection), model.state.value.connections)
        coVerify(exactly = 1) { repository.connections() }
        assertFalse(model.state.value.isWorking)
    }

    @Test fun aTerminalConflictReloadsTheInboxAndDoesNotExposeTheServerCode() = runTest(dispatcher) {
        runCurrent()
        val expired = PlayMatchMatch("match", status = "TIMEOUT")
        coEvery { repository.decideMatch("match", "SKIP", "") } returns Result.failure(PlayMatchApiException("MATCH_NOT_PENDING", 409))
        coEvery { repository.matches() } returns Result.success(PlayMatchMatchesResponse(listOf(expired)))
        model.decideMatchById("match", "SKIP")
        runCurrent()
        assertEquals(listOf(expired), model.state.value.matches)
        coVerify(exactly = 1) { repository.matches() }
        assertFalse(model.state.value.error.orEmpty().contains("MATCH_NOT_PENDING"))
        assertFalse(model.state.value.isWorking)
    }

    @Test fun aConfirmedDecisionSurvivesReadRefreshFailures() = runTest(dispatcher) {
        runCurrent()
        coEvery { repository.decideMatch("match", "ACCEPT", "") } returns Result.success(PlayMatchDecisionResponse(status = "PENDING"))
        coEvery { repository.matches() } returns Result.failure(IllegalStateException("fixture refresh unavailable"))
        coEvery { repository.connections() } returns Result.failure(IllegalStateException("fixture refresh unavailable"))
        model.decideMatchById("match", "ACCEPT")
        runCurrent()
        assertEquals("Aceptación enviada.", model.state.value.message)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.isWorking)
    }
}
