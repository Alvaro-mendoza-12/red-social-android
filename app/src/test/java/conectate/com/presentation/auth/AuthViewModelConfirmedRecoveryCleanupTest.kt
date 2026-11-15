package conectate.com.presentation.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackPasswordChangedNeedsTwoFactorException
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/** Real ViewModel/store/preferences; crypto opening and repository/session effects are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class, qualifiers = "es")
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelConfirmedRecoveryCleanupTest {
    private class Gate {
        private val entered = CountDownLatch(1)
        private val resumed = CountDownLatch(1)
        fun hold() { entered.countDown(); check(resumed.await(10, TimeUnit.SECONDS)) { "QA cleanup gate not released" } }
        fun awaitEntry() = assertTrue("Expected local cleanup IO", entered.await(10, TimeUnit.SECONDS))
        fun release() = resumed.countDown()
    }
    private val gates = mutableListOf<Gate>()
    private val pauseNextCommit = AtomicReference<Gate?>()
    private val commits = AtomicInteger()
    private lateinit var backing: SharedPreferences
    private val failNextCommit = AtomicBoolean(false)
    private val cancelNextCommit = AtomicBoolean(false)
    private lateinit var context: Application
    private lateinit var fixtureName: String
    private val stores = mutableListOf<ViewModelStore>()
    private val roots = mutableListOf<Job>()
    private val user = User(id = "qa-confirmed-owner", email = "qa.confirmed@example.invalid")
    private fun draft() = EmailRecoveryDraft(user.email.orEmpty(), resetToken = "qa-consumed-grant-only",
        expiresAt = System.currentTimeMillis() + 600_000)

    private inner class Fixture(result: Result<User>, beforeResponse: suspend () -> Unit = {}) {
        val repository = mockk<AuthRepository>()
        private val invocations = Channel<Job>(Channel.UNLIMITED)
        private val sessions = MutableStateFlow<AuthResponse?>(null)
        private var sessionUserId: String? = null
        private val ownerStore = ViewModelStore().also { stores += it }
        val value: AuthViewModel
        init {
            every { repository.getCurrentUserId() } answers { sessionUserId }
            every { repository.isUserLoggedIn } returns false
            every { repository.getAuthStateFlow() } returns sessions
            every { repository.storedAccounts } returns MutableStateFlow(emptyList<StoredAccountSession>())
            coEvery { repository.confirmLocalFallbackPasswordReset(any(), any()) } coAnswers {
                invocations.trySend(currentCoroutineContext().job).getOrThrow()
                beforeResponse()
                result
            }
            value = AuthViewModel(repository, mockk<LocalFallbackApiClient>(), mockk<GuestModeApi>(relaxed = true), context)
            ownerStore.put("confirmed-cleanup", value)
            roots += value.viewModelScope.coroutineContext.job
            runBlocking { withTimeout(10_000) { value.emailRecovery.first { it == draftSnapshot } } }
        }

        suspend fun beginConfirmation(failCleanup: Boolean = true): Job {
            failNextCommit.set(failCleanup)
            value.confirmMigratedPasswordCode(draftSnapshot.resetToken, "QA-synthetic-password-only")
            return withTimeout(10_000) { invocations.receive() }
        }

        suspend fun confirm(failCleanup: Boolean = true) {
            withTimeout(10_000) { beginConfirmation(failCleanup).join() }
        }

        fun announceSession() {
            sessionUserId = user.id
            sessions.value = mockk<AuthResponse>()
        }

        fun dispose() = ownerStore.clear()

        suspend fun attemptAgain() {
            // Main is Unconfined: an admitted retry reaches the repository
            // synchronously before its first IO suspension. Await that Job if it exists.
            value.confirmMigratedPasswordCode(draftSnapshot.resetToken, "QA-synthetic-password-only")
            invocations.tryReceive().getOrNull()?.let { withTimeout(10_000) { it.join() } }
        }
    }

    private lateinit var draftSnapshot: EmailRecoveryDraft

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        fixtureName = "qa_confirmed_cleanup_" + UUID.randomUUID()
        backing = context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean {
                        pauseNextCommit.getAndSet(null)?.hold()
                        commits.incrementAndGet()
                        val applied = editor.commit()
                        if (cancelNextCommit.compareAndSet(true, false)) throw CancellationException("QA cleanup cancelled locally")
                        return if (failNextCommit.compareAndSet(true, false)) false else applied
                    }
                }
            }
        }
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk<MasterKey>()
        mockkStatic(EncryptedSharedPreferences::class)
        every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns preferences
        draftSnapshot = draft()
        EmailRecoveryDraftStore(context).save(draftSnapshot)
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        stores.forEach { it.clear() }
        try { runBlocking { withTimeout(10_000) { roots.forEach { it.join() } } } } finally {
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            Dispatchers.resetMain()
            check(fixtureName.startsWith("qa_confirmed_cleanup_"))
            context.deleteSharedPreferences(fixtureName)
        }
    }

    private fun assertConfirmedLocalError(state: AuthState) {
        assertTrue("A completed remote change must expose the failed local cleanup", state is AuthState.Error)
        val text = (state as AuthState.Error).message.lowercase()
        assertTrue("The message must say the password already changed", text.contains("contraseña") && text.contains("cambi"))
    }

    @Test fun aConfirmedPasswordChangeExposesTheLocalCleanupFailure() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        assertConfirmedLocalError(current.value.authState.value)
    }

    @Test fun aConfirmedTwoFactorHandoffExposesTheLocalCleanupFailure() = runBlocking {
        val current = Fixture(Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException()))
        current.confirm()
        assertConfirmedLocalError(current.value.authState.value)
    }

    @Test fun retryingAfterAConfirmedChangeCannotSubmitThePasswordAgain() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        current.attemptAgain()
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun retryingAfterAConfirmedTwoFactorChangeCannotSubmitThePasswordAgain() = runBlocking {
        val current = Fixture(Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException()))
        current.confirm()
        current.attemptAgain()
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun anotherReaderCannotRestoreTheConfirmedChangesConsumedGrant() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        assertNull("A consumed grant cannot be restored as an active recovery", EmailRecoveryDraftStore(context).read())
    }

    @Test fun anotherReaderCannotRestoreTheTwoFactorChangesConsumedGrant() = runBlocking {
        val current = Fixture(Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException()))
        current.confirm()
        assertNull("A consumed grant cannot be restored as an active recovery", EmailRecoveryDraftStore(context).read())
    }

    @Test fun aConfirmedChangeWithSuccessfulCleanupStillCompletes() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm(failCleanup = false)
        assertEquals(AuthState.Success(user), current.value.authState.value)
        assertNull(EmailRecoveryDraftStore(context).read())
    }

    @Test fun aRejectedChangeRetainsTheExistingPermission() = runBlocking {
        val current = Fixture(Result.failure(IOException("QA rejected confirmation")))
        current.confirm(failCleanup = false)
        assertTrue(current.value.authState.value is AuthState.Error)
        assertEquals(draftSnapshot, EmailRecoveryDraftStore(context).read())
    }

    @Test fun repositoryCancellationBeforeAClassifiedResponseDoesNotLeaveProgressRunning() = runBlocking {
        val current = Fixture(Result.failure(CancellationException("QA confirmation cancelled")))
        current.confirm(failCleanup = false)
        assertTrue("The current form must not remain loading after repository cancellation",
            current.value.authState.value !is AuthState.Loading)
        assertEquals(draftSnapshot, EmailRecoveryDraftStore(context).read())
    }

    @Test fun cancellationDuringCleanupStillExplainsTheConfirmedChange() = runBlocking {
        val current = Fixture(Result.success(user))
        cancelNextCommit.set(true)
        current.confirm(failCleanup = false)
        assertConfirmedLocalError(current.value.authState.value)
    }

    @Test fun retryingCleanupFinishesWithoutAnotherPasswordRequest() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        current.value.retryEmailRecoveryCleanup(notice)
        withTimeout(10_000) {
            current.value.authState.first { it is AuthState.Success }
            current.value.finishingEmailRecovery.first { !it }
        }
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertNull(EmailRecoveryDraftStore(context).read())
        assertNull(backing.getString("draft", null))
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun retryingCleanupKeepsTwoFactorUntilAFreshSignInCompletes() = runBlocking {
        val current = Fixture(Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException()))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertTrue(notice.requiresTwoFactor)
        current.value.retryEmailRecoveryCleanup(notice)
        withTimeout(10_000) { current.value.authState.first { it is AuthState.PasswordChangedNeedsTwoFactor } }
        assertTrue(current.value.finishingEmailRecovery.value)
        current.value.resetAuthState() // Editing a failed entry must not accept an old session.
        assertTrue(current.value.finishingEmailRecovery.value)
        val entry = CompletableDeferred<Job>()
        coEvery { current.repository.signInWithEmail(any(), any(), any()) } coAnswers {
            entry.complete(currentCoroutineContext().job)
            Result.success(user)
        }
        current.value.signInWithEmail(user.email.orEmpty(), "QA-synthetic-password-only", "123456")
        withTimeout(10_000) { entry.await().join() }
        assertEquals(AuthState.Success(user), current.value.authState.value)
        assertFalse(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun continuingAcknowledgesTheFailedCleanupWithoutResending() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertNotNull(backing.getString("draft", null))
        current.value.continueAfterEmailRecoveryCleanup(notice)
        assertEquals(AuthState.Success(user), current.value.authState.value)
        assertFalse(current.value.finishingEmailRecovery.value)
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertNull(EmailRecoveryDraftStore(context).read())
        assertNotNull("Acknowledging an error is not durable deletion", backing.getString("draft", null))
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun continuingStillRequiresTheConfirmedChangesSecondFactor() = runBlocking {
        val current = Fixture(Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException()))
        current.confirm()
        current.value.continueAfterEmailRecoveryCleanup(current.value.emailRecoveryCleanupNotice.value!!)
        assertEquals(AuthState.PasswordChangedNeedsTwoFactor, current.value.authState.value)
        assertTrue(current.value.finishingEmailRecovery.value)
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertNull(EmailRecoveryDraftStore(context).read())
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun sessionAnnouncementCannotReleaseTheRecoveryTransitionBeforeCleanup() = runBlocking {
        val response = CompletableDeferred<Unit>()
        val current = Fixture(Result.success(user), beforeResponse = { response.await() })
        try {
            val sending = current.beginConfirmation()
            assertTrue(current.value.finishingEmailRecovery.value)
            current.announceSession() // Controlled repository signal, real ViewModel collector.
            withTimeout(10_000) { current.value.isUserLoggedIn.first { it } }
            assertTrue(current.value.finishingEmailRecovery.value)
            response.complete(Unit)
            withTimeout(10_000) { sending.join() }
            assertConfirmedLocalError(current.value.authState.value)
            assertTrue(current.value.finishingEmailRecovery.value)
            current.value.continueAfterEmailRecoveryCleanup(current.value.emailRecoveryCleanupNotice.value!!)
            assertFalse(current.value.finishingEmailRecovery.value)
        } finally { response.complete(Unit) }
    }

    @Test fun repeatedCleanupAndContinueAreIgnoredWhileTheWriteIsRunning() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        val paused = Gate().also { gates += it; pauseNextCommit.set(it) }
        val before = commits.get()
        try {
            current.value.retryEmailRecoveryCleanup(notice)
            paused.awaitEntry()
            current.value.retryEmailRecoveryCleanup(notice)
            current.value.continueAfterEmailRecoveryCleanup(notice)
            assertEquals(AuthState.Loading, current.value.authState.value)
            assertTrue(current.value.finishingEmailRecovery.value)
            paused.release()
            withTimeout(10_000) {
                current.value.authState.first { it is AuthState.Success }
                current.value.finishingEmailRecovery.first { !it }
            }
            assertEquals(before + 1, commits.get())
            coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
        } finally { paused.release() }
    }

    @Test fun retainedNoticeAndConsumedGrantCannotActOnTheNextForm() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        current.value.resetAuthState()
        val next = AuthState.Error("QA next form")
        current.value.setAuthError(next.message)
        current.value.retryEmailRecoveryCleanup(notice)
        current.value.continueAfterEmailRecoveryCleanup(notice)
        current.attemptAgain()
        assertEquals(next, current.value.authState.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun aNewRecoveryKeepsItsGrantAfterAnOlderNoticeIsUsed() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        val newer = draftSnapshot.copy(email = "qa.next@example.invalid", resetToken = "qa-new-grant-only")
        EmailRecoveryDraftStore(context).save(newer)
        current.value.retryEmailRecoveryCleanup(notice)
        current.value.continueAfterEmailRecoveryCleanup(notice)
        assertEquals(newer, EmailRecoveryDraftStore(context).read())
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun aDisposedScreenCannotUseItsRetainedCleanupActions() = runBlocking {
        val current = Fixture(Result.success(user))
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        current.dispose()
        current.value.retryEmailRecoveryCleanup(notice)
        current.value.continueAfterEmailRecoveryCleanup(notice)
        current.attemptAgain()
        assertTrue(current.value.authState.value is AuthState.Error)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }
}
