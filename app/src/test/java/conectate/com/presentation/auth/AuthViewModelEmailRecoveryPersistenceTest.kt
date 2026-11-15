package conectate.com.presentation.auth

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackPasswordResetResponse
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.repository.AuthRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel and IO/mutex boundaries; only storage and transport are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelEmailRecoveryPersistenceTest {
    private class Gate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "QA storage gate was not released" }
        }
        fun awaitEntry() = assertTrue("Expected the real IO boundary", entered.await(10, TimeUnit.SECONDS))
        fun release() = release.countDown()
    }

    private companion object {
        fun pump(scheduler: TestCoroutineScheduler, finished: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!finished() && System.nanoTime() < deadline) {
                scheduler.runCurrent()
                if (!finished()) Thread.sleep(1)
            }
            assertTrue("The observed coroutine must finish", finished())
        }
    }

    private class Fixture(
        initial: EmailRecoveryDraft?,
        save: (EmailRecoveryDraft) -> Unit = {},
        clear: () -> Unit = {},
        read: () -> Unit = {},
        awaitInitial: Boolean = true,
        private val scheduler: TestCoroutineScheduler? = null,
    ) {
        val repository = mockk<AuthRepository>()
        val api = mockk<LocalFallbackApiClient>()
        private val store = ViewModelStore()
        val model: AuthViewModel
        private val root: Job

        init {
            every { anyConstructed<EmailRecoveryDraftStore>().read() } answers { read(); initial }
            every { anyConstructed<EmailRecoveryDraftStore>().save(any()) } answers { save(firstArg()) }
            every { anyConstructed<EmailRecoveryDraftStore>().clear() } answers { clear() }
            every { repository.getCurrentUserId() } returns null
            every { repository.isUserLoggedIn } returns false
            every { repository.forceLocalLogout() } returns Unit
            every { repository.getAuthStateFlow() } returns MutableStateFlow<AuthResponse?>(null)
            every { repository.storedAccounts } returns MutableStateFlow(emptyList<StoredAccountSession>())
            model = AuthViewModel(repository, api, mockk<GuestModeApi>(relaxed = true),
                ApplicationProvider.getApplicationContext<Application>())
            root = model.viewModelScope.coroutineContext.job
            store.put("email-persistence", model)
            if (initial != null && awaitInitial) runBlocking {
                withTimeout(10_000) { model.emailRecovery.first { it == initial } }
            }
        }

        // Await the launched clear even when it must suppress its callback. The
        // other new children are the replacement AccountTasks supervisor jobs.
        fun beginClear(onCleared: () -> Unit): Job {
            val before = root.children.toSet()
            model.clearEmailRecovery(onCleared)
            return root.children.single { it !in before && it !is CompletableJob }
        }

        fun roots(): Set<Job> = root.children.toSet()
        fun entryJob(): Job = root.children.filter { it is CompletableJob }.flatMap { it.children }.single()
        fun clearJobSince(before: Set<Job>): Job = root.children.single { it !in before && it !is CompletableJob }

        // The first ordinary launch in AuthViewModel is its draft restoration.
        // The queued-start test awaits that actual Job, rather than sleeping and
        // assuming the IO continuation has already published its result.
        fun restorationJob(): Job = root.children.first { it !is CompletableJob }

        fun close() {
            store.clear()
            if (scheduler != null) pump(scheduler) { root.isCompleted }
            else runBlocking { withTimeout(10_000) { root.join() } }
        }

        fun clearModel() = store.clear()
    }

    private var fixture: Fixture? = null

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkConstructor(EmailRecoveryDraftStore::class)
    }

    @After fun cleanup() {
        try { fixture?.close() } finally {
            unmockkConstructor(EmailRecoveryDraftStore::class)
            Dispatchers.resetMain()
        }
    }

    private fun draft(code: String = "") = EmailRecoveryDraft(
        email = "qa.mail@example.invalid", code = code,
        expiresAt = System.currentTimeMillis() + 600_000,
    )

    @Test fun clearFromAnOldEntryCannotNavigateAfterReset() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft(), clear = gate::hold).also { fixture = it }
        var navigations = 0
        try {
            val clearing = current.beginClear { navigations++ }
            gate.awaitEntry()
            current.model.resetAuthState()
            gate.release()
            clearing.join()
            assertEquals("Reset invalidates the earlier Back callback", 0, navigations)
        } finally { gate.release() }
    }

    @Test fun aNewClearOwnsNavigationAfterAnOlderClearFinishes() = runBlocking {
        val gate = Gate()
        val writes = AtomicInteger()
        val current = Fixture(draft(), clear = { if (writes.incrementAndGet() == 1) gate.hold() }).also { fixture = it }
        var oldReady = 0
        var newReady = 0
        try {
            val previous = current.beginClear { oldReady++ }
            gate.awaitEntry()
            val next = current.beginClear { newReady++ }
            gate.release()
            previous.join()
            next.join()
            assertEquals("Only the current clear may navigate", 0, oldReady)
            assertEquals(1, newReady)
        } finally { gate.release() }
    }

    @Test fun cancelledVerificationStorageCannotPublishIntoTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft("123456"), save = { if (it.verified) gate.hold() }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.api.verifyEmailPasswordCode(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(resetToken = "qa-reset-permission", expiresInMinutes = 10))
        }
        try {
            current.model.verifyEmailRecoveryCode()
            gate.awaitEntry()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.await().join()
            assertEquals(nextForm, current.model.authState.value)
        } finally { gate.release() }
    }

    @Test fun cancelledDeliveryStorageCannotResetTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, save = { if (it.deliveryConfirmed) gate.hold() }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email"))
        }
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.await().join()
            assertEquals(nextForm, current.model.authState.value)
        } finally { gate.release() }
    }

    @Test fun failedClearKeepsRecoveryAndDoesNotNavigate() = runBlocking {
        val original = draft()
        val current = Fixture(original, clear = { throw IOException("QA local write failed") }).also { fixture = it }
        var navigations = 0
        // Block once to obtain the live operation before the failing write is released.
        val gate = Gate()
        every { anyConstructed<EmailRecoveryDraftStore>().clear() } answers {
            gate.hold()
            throw IOException("QA local write failed")
        }
        try {
            val clearing = current.beginClear { navigations++ }
            gate.awaitEntry()
            gate.release()
            clearing.join()
            assertEquals("A failed durable clear cannot complete Back", 0, navigations)
            assertEquals(original, current.model.emailRecovery.value)
            assertTrue(current.model.authState.value is AuthState.Error)
        } finally { gate.release() }
    }

    @Test fun aClearedViewModelCannotRunThePendingBackCallback() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft(), clear = gate::hold).also { fixture = it }
        var navigations = 0
        try {
            val clearing = current.beginClear { navigations++ }
            gate.awaitEntry()
            current.clearModel()
            gate.release()
            clearing.join()
            assertEquals("A disposed ViewModel cannot navigate", 0, navigations)
        } finally { gate.release() }
    }

    @Test fun aCurrentClearCompletesOnce() = runBlocking {
        val current = Fixture(draft()).also { fixture = it }
        val completed = CompletableDeferred<Unit>()
        var navigations = 0
        current.model.clearEmailRecovery { navigations++; completed.complete(Unit) }
        withTimeout(10_000) { completed.await() }
        assertEquals(1, navigations)
        assertNull(current.model.emailRecovery.value)
    }

    @Test fun aCurrentVerificationWriteFailureRemainsVisible() = runBlocking {
        val current = Fixture(draft("123456"), save = { if (it.verified) throw IOException("QA local write failed") }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.api.verifyEmailPasswordCode(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(resetToken = "qa-reset-permission"))
        }
        current.model.verifyEmailRecoveryCode()
        withTimeout(10_000) { request.await().join() }
        assertTrue(current.model.authState.value is AuthState.Error)
        assertTrue(current.model.emailRecovery.value?.verified == true)
    }

    @Test fun aCurrentVerificationPersistsItsPermission() = runBlocking {
        val saved = CompletableDeferred<EmailRecoveryDraft>()
        val current = Fixture(draft("123456"), save = { if (it.verified) saved.complete(it) }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.api.verifyEmailPasswordCode(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(resetToken = "qa-reset-permission"))
        }
        current.model.verifyEmailRecoveryCode()
        withTimeout(10_000) { request.await().join() }
        assertEquals("qa-reset-permission", saved.await().resetToken)
        assertEquals("", current.model.emailRecovery.value?.code)
        assertEquals(AuthState.Idle, current.model.authState.value)
    }

    @Test fun aCurrentDeliveryAcknowledgementRetainsItsDraft() = runBlocking {
        val current = Fixture(null).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email"))
        }
        current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
        withTimeout(10_000) { request.await().join() }
        assertTrue(current.model.emailRecovery.value?.deliveryConfirmed == true)
        assertFalse(current.model.emailRecovery.value?.verified == true)
        assertEquals(AuthState.Idle, current.model.authState.value)
        coVerify(exactly = 1) { current.repository.requestLocalFallbackPasswordReset(any()) }
    }

    @Test fun queuedRestorationCannotRepublishAClearedDraft() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val reading = Gate()
        val clearing = Gate()
        val current = Fixture(draft(), clear = clearing::hold, read = reading::hold,
            awaitInitial = false, scheduler = scheduler).also { fixture = it }
        val restoring = current.restorationJob()
        var navigations = 0
        try {
            val cleared = current.beginClear { navigations++ }
            scheduler.runCurrent()
            reading.awaitEntry()
            clearing.awaitEntry()
            reading.release()
            pump(scheduler) { restoring.isCompleted }
            clearing.release()
            pump(scheduler) { cleared.isCompleted }
            assertNull("Queued restoration cannot recreate an explicitly cleared draft", current.model.emailRecovery.value)
            assertEquals(1, navigations)
        } finally {
            reading.release()
            clearing.release()
        }
    }

    @Test fun aCurrentDeliveryWriteFailureRemainsVisible() = runBlocking {
        val current = Fixture(null, save = { if (it.deliveryConfirmed) throw IOException("QA acknowledgement write failed") }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email"))
        }
        current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
        withTimeout(10_000) { request.await().join() }
        assertTrue("The local write failure must not look like a fully saved acknowledgement",
            current.model.authState.value is AuthState.Error)
        assertTrue(current.model.emailRecovery.value?.deliveryConfirmed == true)
    }

    @Test fun aDisabledDeliveryKeepsItsExplanationAfterTheClear() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, clear = gate::hold).also { fixture = it }
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } returns
            Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "disabled"))
        val before = current.roots()
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            val clearing = current.clearJobSince(before)
            gate.release()
            clearing.join()
            val error = current.model.authState.value as AuthState.Error
            assertTrue(error.message.contains("correo no está disponible"))
            assertNull(current.model.emailRecovery.value)
        } finally { gate.release() }
    }

    @Test fun aLegacyDeliveryKeepsItsPromptAfterTheClear() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, clear = gate::hold).also { fixture = it }
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } returns
            Result.success(LocalFallbackPasswordResetResponse(resetToken = "qa-legacy-permission", delivery = "email"))
        val before = current.roots()
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            val clearing = current.clearJobSince(before)
            gate.release()
            clearing.join()
            val prompt = current.model.authState.value as AuthState.MigrationPasswordCodeSent
            assertEquals("qa-legacy-permission", prompt.resetToken)
            assertNull(current.model.emailRecovery.value)
        } finally { gate.release() }
    }

    @Test fun cancelledPasswordResetCleanupCannotCompleteTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft().copy(resetToken = "qa-reset-permission"), clear = gate::hold).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.confirmLocalFallbackPasswordReset(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(conectate.com.data.model.User(id = "qa-reset-owner", email = "qa.mail@example.invalid"))
        }
        try {
            current.model.confirmMigratedPasswordCode("qa-reset-permission", "qa-new-password")
            gate.awaitEntry()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.await().join()
            assertEquals(nextForm, current.model.authState.value)
            assertFalse(current.model.isUserLoggedIn.value)
        } finally { gate.release() }
    }

    @Test fun cancelledTwoFactorCleanupCannotPublishIntoTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft().copy(resetToken = "qa-reset-permission"), clear = gate::hold).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.confirmLocalFallbackPasswordReset(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.failure(conectate.com.data.localfallback.LocalFallbackPasswordChangedNeedsTwoFactorException())
        }
        try {
            current.model.confirmMigratedPasswordCode("qa-reset-permission", "qa-new-password")
            gate.awaitEntry()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.await().join()
            assertEquals(nextForm, current.model.authState.value)
            assertFalse(current.model.isUserLoggedIn.value)
        } finally { gate.release() }
    }

    @Test fun aCurrentPasswordResetCompletesAfterCleanup() = runBlocking {
        val current = Fixture(draft().copy(resetToken = "qa-reset-permission")).also { fixture = it }
        val user = conectate.com.data.model.User(id = "qa-reset-owner", email = "qa.mail@example.invalid")
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.confirmLocalFallbackPasswordReset(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.success(user)
        }
        current.model.confirmMigratedPasswordCode("qa-reset-permission", "qa-new-password")
        withTimeout(10_000) { request.await().join() }
        assertEquals(AuthState.Success(user), current.model.authState.value)
        assertTrue(current.model.isUserLoggedIn.value)
        assertNull(current.model.emailRecovery.value)
    }

    @Test fun aCurrentTwoFactorHandoffCompletesAfterCleanup() = runBlocking {
        val current = Fixture(draft().copy(resetToken = "qa-reset-permission")).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.confirmLocalFallbackPasswordReset(any(), any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.failure(conectate.com.data.localfallback.LocalFallbackPasswordChangedNeedsTwoFactorException())
        }
        current.model.confirmMigratedPasswordCode("qa-reset-permission", "qa-new-password")
        withTimeout(10_000) { request.await().join() }
        assertEquals(AuthState.PasswordChangedNeedsTwoFactor, current.model.authState.value)
        assertFalse(current.model.isUserLoggedIn.value)
        assertNull(current.model.emailRecovery.value)
    }

    @Test fun repeatedFailedClearsRetainTheOriginalDraft() = runBlocking {
        val gate = Gate()
        val calls = AtomicInteger()
        val original = draft()
        val current = Fixture(original, clear = {
            if (calls.incrementAndGet() == 1) gate.hold()
            throw IOException("QA local write failed")
        }).also { fixture = it }
        var navigations = 0
        try {
            val previous = current.beginClear { navigations++ }
            gate.awaitEntry()
            val next = current.beginClear { navigations++ }
            gate.release()
            previous.join()
            next.join()
            assertEquals(0, navigations)
            assertEquals(original, current.model.emailRecovery.value)
            assertTrue(current.model.authState.value is AuthState.Error)
        } finally { gate.release() }
    }

    @Test fun loggingOutInvalidatesAPendingRecoveryBack() = runBlocking {
        val gate = Gate()
        val current = Fixture(draft(), clear = gate::hold).also { fixture = it }
        var navigations = 0
        try {
            val clearing = current.beginClear { navigations++ }
            gate.awaitEntry()
            current.model.forceLocalLogout()
            gate.release()
            clearing.join()
            assertEquals(0, navigations)
            assertFalse(current.model.isUserLoggedIn.value)
        } finally { gate.release() }
    }

    @Test fun cancelledLostAcknowledgementStorageCannotOverwriteTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, save = { if (!it.deliveryConfirmed && it.resendAt == 0L) gate.hold() }).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.failure(IOException("QA response lost"))
        }
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.await().join()
            assertEquals(nextForm, current.model.authState.value)
        } finally { gate.release() }
    }

    @Test fun cancelledEarlyFailureCleanupCannotOverwriteTheNextForm() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, save = { throw IOException("QA pending write failed") }, clear = gate::hold).also { fixture = it }
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            val request = current.entryJob()
            current.model.resetAuthState()
            val nextForm = AuthState.Error("QA next form owns this message")
            current.model.setAuthError(nextForm.message)
            gate.release()
            request.join()
            assertEquals(nextForm, current.model.authState.value)
            coVerify(exactly = 0) { current.repository.requestLocalFallbackPasswordReset(any()) }
        } finally { gate.release() }
    }

    @Test fun aLostAcknowledgementKeepsItsAddressAndAllowsRetry() = runBlocking {
        val current = Fixture(null).also { fixture = it }
        val request = CompletableDeferred<Job>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            Result.failure(IOException("QA response lost"))
        }
        current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
        withTimeout(10_000) { request.await().join() }
        val retained = current.model.emailRecovery.value!!
        assertEquals("qa.mail@example.invalid", retained.email)
        assertEquals(0L, retained.resendAt)
        assertFalse(retained.deliveryConfirmed)
        assertTrue((current.model.authState.value as AuthState.Error).message.contains("confirmar el envío"))
    }

    @Test fun aFailureBeforeSendingDoesNotRequestAnEmail() = runBlocking {
        val gate = Gate()
        val current = Fixture(null, save = { throw IOException("QA pending write failed") }, clear = gate::hold).also { fixture = it }
        try {
            current.model.requestMigratedPasswordCode("qa.mail@example.invalid")
            gate.awaitEntry()
            val request = current.entryJob()
            gate.release()
            request.join()
            assertTrue(current.model.authState.value is AuthState.Error)
            assertNull(current.model.emailRecovery.value)
            coVerify(exactly = 0) { current.repository.requestLocalFallbackPasswordReset(any()) }
        } finally { gate.release() }
    }
}
