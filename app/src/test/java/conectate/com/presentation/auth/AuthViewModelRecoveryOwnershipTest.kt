package conectate.com.presentation.auth

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.repository.AuthRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel transitions; only transport and repositories are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelRecoveryOwnershipTest {
    private class Fixture {
        val repository = mockk<AuthRepository>()
        val api = mockk<LocalFallbackApiClient>()
        private val store = ViewModelStore()
        val model: AuthViewModel

        init {
            every { repository.getCurrentUserId() } returns null
            every { repository.isUserLoggedIn } returns false
            every { repository.getAuthStateFlow() } returns MutableStateFlow<AuthResponse?>(null)
            every { repository.storedAccounts } returns MutableStateFlow(emptyList<StoredAccountSession>())
            every { repository.forceLocalLogout() } returns Unit
            model = AuthViewModel(repository, api, mockk<GuestModeApi>(),
                ApplicationProvider.getApplicationContext<Application>())
            store.put("recovery-owner", model)
        }

        fun close() = store.clear()
    }

    @Test fun leavingBeforeARecoveryRequestStartsDoesNotBlockTheNextForm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.model.requestAssistedRecovery("fixture", "fixture@example.invalid", "Account recovery fixture", "email", "")
            assertTrue(fixture.model.assistedRecoverySubmitting.value)
            fixture.model.resetAuthState()
            runCurrent()
            assertFalse("Leaving a queued form must release its busy state", fixture.model.assistedRecoverySubmitting.value)
            coVerify(exactly = 0) { fixture.api.requestAssistedRecovery(any(), any(), any(), any(), any(), any()) }
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun leavingBeforeAttachmentMetadataLoadsDoesNotBlockRecoveryAfterLogout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.model.selectRecoveryEvidence(listOf(Uri.parse("content://qa.invalid/cancelled-selection")))
            assertTrue(fixture.model.assistedRecoverySubmitting.value)
            fixture.model.forceLocalLogout()
            runCurrent()
            assertFalse("A cancelled attachment selection cannot keep the next recovery busy", fixture.model.assistedRecoverySubmitting.value)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun aCancelledContactReplyCannotUnlockTheNextContactVerification() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val previous = CompletableDeferred<Result<Unit>>()
        val next = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.api.confirmAssistedRecoveryContact("previous-case", "111111") } coAnswers {
            withContext(NonCancellable) { previous.await() }
        }
        coEvery { fixture.api.confirmAssistedRecoveryContact("next-case", "222222") } coAnswers { next.await() }
        try {
            fixture.model.confirmAssistedContact("previous-case", "111111")
            runCurrent()
            fixture.model.resetAuthState()
            fixture.model.confirmAssistedContact("next-case", "222222")
            runCurrent()
            coVerify(exactly = 1) { fixture.api.confirmAssistedRecoveryContact("next-case", "222222") }
            previous.complete(Result.success(Unit))
            runCurrent()
            assertTrue("The old finally must not release the current operation", fixture.model.assistedRecoverySubmitting.value)
            next.complete(Result.success(Unit))
            runCurrent()
            assertFalse(fixture.model.assistedRecoverySubmitting.value)
            assertTrue(fixture.model.assistedRecoveryMessage.value.orEmpty().startsWith("Contacto confirmado"))
        } finally {
            previous.complete(Result.success(Unit))
            next.complete(Result.success(Unit))
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun aCurrentContactFailureReleasesTheFormAndRemainsRetryable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.api.confirmAssistedRecoveryContact("current-case", "333333") } coAnswers { reply.await() }
        try {
            fixture.model.confirmAssistedContact("current-case", "333333")
            runCurrent()
            assertTrue(fixture.model.assistedRecoverySubmitting.value)
            reply.complete(Result.failure(IllegalStateException("QA temporary failure")))
            runCurrent()
            assertFalse(fixture.model.assistedRecoverySubmitting.value)
            assertTrue(fixture.model.assistedRecoveryMessage.value.orEmpty().isNotBlank())
            coVerify(exactly = 1) { fixture.api.confirmAssistedRecoveryContact("current-case", "333333") }
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}
