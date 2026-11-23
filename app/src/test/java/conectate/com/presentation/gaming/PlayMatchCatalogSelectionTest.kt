package conectate.com.presentation.gaming

import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.PlayMatchCatalogPageResponse
import conectate.com.data.model.PlayMatchGameCatalogItem
import conectate.com.data.model.PlayMatchGameConfigurationResponse
import conectate.com.data.repository.PlayMatchRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthService
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PlayMatchCatalogSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = mockk<PlayMatchRepository>()
    private val first = PlayMatchGameCatalogItem("first", name = "Primer juego")
    private val second = PlayMatchGameCatalogItem("second", name = "Segundo juego")
    private lateinit var model: PlayMatchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val auth = mockk<AuthService>()
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        model = PlayMatchViewModel(repository, mockk(), mockk(), auth)
        store.put("catalog-selection", model)
    }

    @After
    fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun aLateDetailCannotReplaceTheCurrentGame() = runTest(dispatcher) {
        runCurrent()
        lateinit var response: Continuation<Result<PlayMatchGameConfigurationResponse>>
        coEvery { repository.gameConfiguration(first.id) } coAnswers { suspendCoroutine { response = it } }
        val configuredSecond = second.copy(platforms = listOf("Android"))
        coEvery { repository.gameConfiguration(second.id) } returns Result.success(
            PlayMatchGameConfigurationResponse(configuredSecond),
        )

        model.selectCatalogGame(first)
        runCurrent()
        model.selectCatalogGame(second)
        runCurrent()
        response.resume(Result.success(PlayMatchGameConfigurationResponse(first)))
        runCurrent()

        assertEquals(configuredSecond, model.state.value.selectedCatalogGame)
        assertFalse(model.state.value.isCatalogSelectionLoading)
        assertNull(model.state.value.catalogError)
    }

    @Test
    fun changingTheSearchDropsAnInFlightDetailError() = runTest(dispatcher) {
        runCurrent()
        lateinit var response: Continuation<Result<PlayMatchGameConfigurationResponse>>
        coEvery { repository.gameConfiguration(first.id) } coAnswers { suspendCoroutine { response = it } }

        model.selectCatalogGame(first)
        runCurrent()
        coEvery { repository.searchGames("otro juego") } returns Result.success(PlayMatchCatalogPageResponse())
        model.updateCatalogQuery("otro juego")
        response.resume(Result.failure(IllegalStateException("Old detail failed")))
        runCurrent()

        assertNull(model.state.value.selectedCatalogGame)
        assertFalse(model.state.value.isCatalogSelectionLoading)
        assertNull(model.state.value.catalogError)
        assertTrue(model.state.value.isCatalogLoading)
    }

    @Test
    fun theCurrentDetailTimesOutWithoutDiscardingTheSelection() = runTest(dispatcher) {
        runCurrent()
        coEvery { repository.gameConfiguration(first.id) } coAnswers { awaitCancellation() }

        model.selectCatalogGame(first)
        runCurrent()
        advanceTimeBy(8_001L)
        runCurrent()

        assertEquals(first, model.state.value.selectedCatalogGame)
        assertFalse(model.state.value.isCatalogSelectionLoading)
        assertNotNull(model.state.value.catalogError)
    }
}
