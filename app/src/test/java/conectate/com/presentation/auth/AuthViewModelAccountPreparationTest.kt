package conectate.com.presentation.auth

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.repository.AuthRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real ViewModel owns navigation/feedback; controlled repositories only delay replies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelAccountPreparationTest {
    private class Fixture {
        val repository = mockk<AuthRepository>()
        val guest = mockk<GuestModeApi>(relaxed = true)
        private val store = ViewModelStore()
        private var activeId: String? = "qa-account-owner"
        private val visits = AuthSessionMutationGuard()
        fun activate(accountId: String?) = visits.invalidate { activeId = accountId }
        val model: AuthViewModel

        init {
            every { repository.getCurrentUserId() } answers { activeId }
            every { repository.isUserLoggedIn } answers { activeId != null }
            every { repository.getAuthStateFlow() } returns MutableStateFlow<AuthResponse?>(null)
            every { repository.storedAccounts } returns MutableStateFlow(emptyList<StoredAccountSession>())
            every { repository.forceLocalLogout() } answers { activate(null) }
            every { repository.captureAccountLease() } answers { visits.captureAccountLease { activeId } }
            coEvery { repository.enforceCurrentSessionIsActive() } returns Result.success(Unit)
            model = AuthViewModel(repository, mockk<LocalFallbackApiClient>(), guest,
                ApplicationProvider.getApplicationContext<Application>())
            store.put("account-preparation", model)
        }

        fun close() = store.clear()
    }

    @Test fun leavingBeforePreparationStartsRejectsOldNavigationAndAdmitsTheNextAttempt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        coEvery { fixture.repository.rememberCurrentAccount() } returns Result.success(Unit)
        var previousReady = 0
        var nextReady = 0
        try {
            fixture.model.prepareAddAccount { previousReady++ }
            fixture.model.resetAuthState()
            fixture.model.prepareAddAccount { nextReady++ }
            runCurrent()
            assertEquals("A queued previous visit cannot navigate", 0, previousReady)
            assertEquals("Leaving must release preparation before the next visit", 1, nextReady)
            coVerify(exactly = 1) { fixture.repository.rememberCurrentAccount() }
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun aPreparationReplyAfterLogoutDoesNotClearTheGuestOrNavigate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { reply.await() }
        var navigations = 0
        try {
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            fixture.model.forceLocalLogout()
            reply.complete(Result.success(Unit))
            runCurrent()
            assertEquals("A former owner cannot open add-account after logout", 0, navigations)
            verify(exactly = 0) { fixture.guest.clear() }
            assertFalse(fixture.model.isUserLoggedIn.value)
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun aSavedAccountReplyDoesNotPublishFeedbackIntoTheNextEntryVisit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { reply.await() }
        try {
            fixture.model.saveCurrentAccount()
            runCurrent()
            fixture.model.resetAuthState()
            reply.complete(Result.success(Unit))
            runCurrent()
            assertNull("A completed former save cannot overwrite the new visit", fixture.model.accountSwitchMessage.value)
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }
    @Test fun aCurrentPreparationCompletesOnceAndRejectsDuplicateClicks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { reply.await() }
        var navigations = 0
        try {
            fixture.model.prepareAddAccount { navigations++ }
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            coVerify(exactly = 1) { fixture.repository.rememberCurrentAccount() }
            reply.complete(Result.success(Unit))
            runCurrent()
            assertEquals(1, navigations)
            verify(exactly = 1) { fixture.guest.clear() }
            assertEquals(AuthState.Idle, fixture.model.authState.value)
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun aCurrentFailureRemainsVisibleAndAllowsRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        var response = Result.failure<Unit>(IllegalStateException("QA temporary storage failure"))
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { response }
        var navigations = 0
        try {
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            assertTrue(fixture.model.accountSwitchMessage.value.orEmpty().isNotBlank())
            assertEquals(0, navigations)
            response = Result.success(Unit)
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            assertEquals(1, navigations)
            assertNull(fixture.model.accountSwitchMessage.value)
            coVerify(exactly = 2) { fixture.repository.rememberCurrentAccount() }
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun anOldFinallyCannotAdmitAnotherClickWhileTheNewPreparationIsPending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val previous = CompletableDeferred<Result<Unit>>()
        val next = CompletableDeferred<Result<Unit>>()
        var requests = 0
        var previousReady = 0
        var nextReady = 0
        var duplicateReady = 0
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers {
            when (++requests) {
                1 -> withContext(NonCancellable) { previous.await() }
                else -> next.await()
            }
        }
        try {
            fixture.model.prepareAddAccount { previousReady++ }
            runCurrent()
            fixture.model.resetAuthState()
            fixture.model.prepareAddAccount { nextReady++ }
            runCurrent()
            assertEquals("The new visit must be admitted before comparing finalizers", 2, requests)
            previous.complete(Result.success(Unit))
            runCurrent()
            fixture.model.prepareAddAccount { duplicateReady++ }
            runCurrent()
            assertEquals("The current preparation must keep its duplicate-click guard", 2, requests)
            next.complete(Result.success(Unit))
            runCurrent()
            assertEquals(0, previousReady)
            assertEquals(1, nextReady)
            assertEquals(0, duplicateReady)
        } finally {
            previous.complete(Result.success(Unit))
            next.complete(Result.success(Unit))
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun queuedPreparationDoesNotCaptureTheReplacementAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        coEvery { fixture.repository.rememberCurrentAccount() } returns Result.success(Unit)
        var navigations = 0
        try {
            fixture.model.prepareAddAccount { navigations++ }
            fixture.activate("qa-replacement-owner")
            runCurrent()
            coVerify(exactly = 0) { fixture.repository.rememberCurrentAccount() }
            assertEquals(0, navigations)
            verify(exactly = 0) { fixture.guest.clear() }
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun oldPreparationCannotNavigateAfterAccountReturnsThroughAnotherAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { reply.await() }
        var navigations = 0
        try {
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            fixture.activate("qa-replacement-owner")
            fixture.activate("qa-account-owner")
            reply.complete(Result.success(Unit))
            runCurrent()
            assertEquals("Returning to the same account does not restore the old visit", 0, navigations)
            verify(exactly = 0) { fixture.guest.clear() }
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun oldSaveDoesNotPublishFeedbackIntoAnotherAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers { reply.await() }
        try {
            fixture.model.saveCurrentAccount()
            runCurrent()
            fixture.activate("qa-replacement-owner")
            reply.complete(Result.success(Unit))
            runCurrent()
            assertNull(fixture.model.accountSwitchMessage.value)
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun leavingSettingsReleasesOnlyItsAccountOperations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        var requests = 0
        var previousReady = 0
        var nextReady = 0
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers {
            if (++requests == 1) reply.await() else Result.success(Unit)
        }
        try {
            fixture.model.prepareAddAccount { previousReady++ }
            runCurrent()
            fixture.model.cancelAccountEntryOperations()
            reply.complete(Result.success(Unit))
            runCurrent()
            assertEquals(0, previousReady)
            assertTrue("Leaving settings must preserve the active login", fixture.model.isUserLoggedIn.value)
            assertNull(fixture.model.accountSwitchMessage.value)
            verify(exactly = 0) { fixture.repository.forceLocalLogout() }
            fixture.model.prepareAddAccount { nextReady++ }
            runCurrent()
            assertEquals(1, nextReady)
            assertEquals(2, requests)
            verify(exactly = 1) { fixture.guest.clear() }
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun repositoryCancellationReleasesPreparationWithoutLeavingProgress() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        var cancelled = true
        var navigations = 0
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers {
            if (cancelled) throw kotlinx.coroutines.CancellationException("QA local capture interrupted")
            Result.success(Unit)
        }
        try {
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            assertEquals(0, navigations)
            assertNull("Cancelled preparation must not leave a saving message", fixture.model.accountSwitchMessage.value)
            cancelled = false
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            assertEquals(1, navigations)
            verify(exactly = 1) { fixture.guest.clear() }
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun cancelledPreparationKeepsFeedbackFromAnotherCurrentSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val reply = CompletableDeferred<Result<Unit>>()
        var requests = 0
        var navigations = 0
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers {
            if (++requests == 1) reply.await() else Result.success(Unit)
        }
        try {
            fixture.model.prepareAddAccount { navigations++ }
            runCurrent()
            fixture.model.saveCurrentAccount()
            runCurrent()
            val savedFeedback = fixture.model.accountSwitchMessage.value
            assertTrue(savedFeedback.orEmpty().isNotBlank())
            reply.complete(Result.failure(kotlinx.coroutines.CancellationException("QA capture interrupted")))
            runCurrent()
            assertEquals(savedFeedback, fixture.model.accountSwitchMessage.value)
            assertEquals(0, navigations)
            assertEquals(2, requests)
        } finally {
            reply.complete(Result.success(Unit))
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun leavingAnOlderSettingsVisitCannotCancelTheNewPreparation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val previous = CompletableDeferred<Result<Unit>>()
        val next = CompletableDeferred<Result<Unit>>()
        val previousVisit = Any()
        val nextVisit = Any()
        var requests = 0
        var previousReady = 0
        var nextReady = 0
        coEvery { fixture.repository.rememberCurrentAccount() } coAnswers {
            if (++requests == 1) withContext(NonCancellable) { previous.await() } else next.await()
        }
        try {
            fixture.model.prepareAddAccount(previousVisit) { previousReady++ }
            runCurrent()
            fixture.model.prepareAddAccount(nextVisit) { nextReady++ }
            runCurrent()
            fixture.model.cancelAccountEntryOperations(previousVisit)
            previous.complete(Result.success(Unit))
            runCurrent()
            next.complete(Result.success(Unit))
            runCurrent()
            assertEquals(0, previousReady)
            assertEquals(1, nextReady)
            assertEquals(2, requests)
            verify(exactly = 1) { fixture.guest.clear() }
        } finally {
            previous.complete(Result.success(Unit))
            next.complete(Result.success(Unit))
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun anOlderSettingsCleanupCannotDiscardTheNewSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val previousVisit = Any()
        val nextVisit = Any()
        coEvery { fixture.repository.rememberCurrentAccount() } returns Result.success(Unit)
        try {
            fixture.model.saveCurrentAccount(previousVisit)
            fixture.model.saveCurrentAccount(nextVisit)
            fixture.model.cancelAccountEntryOperations(previousVisit)
            runCurrent()
            coVerify(exactly = 1) { fixture.repository.rememberCurrentAccount() }
            assertTrue(fixture.model.accountSwitchMessage.value.orEmpty().isNotBlank())
            fixture.model.cancelAccountEntryOperations(nextVisit)
            assertNull(fixture.model.accountSwitchMessage.value)
            assertTrue(fixture.model.isUserLoggedIn.value)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}
