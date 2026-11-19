package conectate.com.presentation.calls

import conectate.com.data.repository.CallRepository
import conectate.com.data.signaling.SignalingNetwork
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallLauncherViewModelCancellationTest {
    @Test fun anObsoleteStartReleasesTheSpinnerAndAllowsTheNextCall() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(SignalingNetwork)
        try {
            val prepared = CompletableDeferred<Unit>()
            every { SignalingNetwork.prepare(any()) } answers { prepared.complete(Unit); Unit }
            val repository = mockk<CallRepository>()
            var obsolete = true
            coEvery { repository.startOutgoingCall(any(), any(), any(), any(), any()) } coAnswers {
                if (obsolete) throw CancellationException("La sesión cambió.")
                Result.success("call-b")
            }
            val launcher = CallLauncherViewModel(repository)
            runCurrent()
            prepared.await()

            launcher.startCall("Prueba", listOf("peer"), "dm", "chat", false)
            runCurrent()
            assertFalse(launcher.uiState.value.isStarting)
            assertNull(launcher.uiState.value.error)

            obsolete = false
            launcher.startCall("Prueba", listOf("peer"), "dm", "chat", false)
            runCurrent()
            assertEquals("call-b", launcher.navigation.first())
            assertFalse(launcher.uiState.value.isStarting)
            coVerify(exactly = 2) { repository.startOutgoingCall(any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(SignalingNetwork)
            Dispatchers.resetMain()
        }
    }
}
