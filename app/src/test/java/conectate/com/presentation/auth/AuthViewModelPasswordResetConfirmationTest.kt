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
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
import conectate.com.domain.auth.ConfirmedPasswordResets
import conectate.com.domain.auth.PasswordResetConfirmedNeedsSignInException
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
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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

/** Real ViewModel/store/preferences; crypto opening and repository/session effects are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class, qualifiers = "es")
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPasswordResetConfirmationTest {
    private val commits = AtomicInteger()
    private lateinit var backing: SharedPreferences
    private val failNextCommit = AtomicBoolean(false)
    private lateinit var context: Application
    private lateinit var fixtureName: String
    private val stores = mutableListOf<ViewModelStore>()
    private val roots = mutableListOf<Job>()
    private val user = User(id = "qa-confirmed-owner", email = "qa.confirmed@example.invalid")
    private fun draft() = EmailRecoveryDraft(user.email.orEmpty(), resetToken = "qa-ack-" + UUID.randomUUID(),
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
                        commits.incrementAndGet()
                        val applied = editor.commit()
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
        stores.forEach { it.clear() }
        try { runBlocking { withTimeout(10_000) { roots.forEach { it.join() } } } } finally {
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            Dispatchers.resetMain()
            check(fixtureName.startsWith("qa_confirmed_cleanup_"))
            context.deleteSharedPreferences(fixtureName)
        }
    }

    private fun localFailure() = Result.failure<User>(PasswordResetConfirmedNeedsSignInException(IOException("QA local completion")))

    @Test fun localCompletionFailureCleansOnlyTheGrantAndRequiresFreshSignIn() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
        })
        current.confirm(failCleanup = false)
        assertEquals(AuthState.PasswordChangedNeedsSignIn, current.value.authState.value)
        assertTrue(current.value.finishingEmailRecovery.value)
        assertFalse(current.value.isUserLoggedIn.value)
        assertNull(current.value.emailRecovery.value)
        assertNull(backing.getString("draft", null))
        current.attemptAgain()
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun cleanupRetryDoesNotConfuseLocalSignInFailureWithSecondFactor() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
        })
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertFalse(notice.requiresTwoFactor)
        assertTrue(notice.requiresSignIn)
        current.value.retryEmailRecoveryCleanup(notice)
        withTimeout(10_000) { current.value.authState.first { it is AuthState.PasswordChangedNeedsSignIn } }
        assertTrue(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun continuingAfterLocalFailureStillHoldsAnAnnouncedSession() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
        })
        current.confirm()
        current.announceSession()
        current.value.continueAfterEmailRecoveryCleanup(current.value.emailRecoveryCleanupNotice.value!!)
        assertEquals(AuthState.PasswordChangedNeedsSignIn, current.value.authState.value)
        assertTrue(current.value.finishingEmailRecovery.value)
        current.value.resetAuthState()
        assertTrue(current.value.finishingEmailRecovery.value)
        val entry = CompletableDeferred<Job>()
        coEvery { current.repository.signInWithEmail(any(), any(), any()) } coAnswers {
            entry.complete(currentCoroutineContext().job)
            Result.success(user)
        }
        current.value.signInWithEmail(user.email.orEmpty(), "QA-synthetic-password-only", null)
        withTimeout(10_000) { entry.await().join() }
        assertEquals(AuthState.Success(user), current.value.authState.value)
        assertFalse(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun knownAcknowledgementCancellationOffersCleanupWithoutSwallowingCancellation() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
            throw CancellationException("QA cancelled after acknowledgement")
        })
        val before = commits.get()
        val sending = current.beginConfirmation(failCleanup = false)
        withTimeout(10_000) { sending.join() }
        assertTrue(sending.isCancelled)
        assertEquals(before, commits.get())
        assertNull(current.value.emailRecovery.value)
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertTrue(notice.requiresSignIn)
        assertFalse(notice.requiresTwoFactor)
        current.value.retryEmailRecoveryCleanup(notice)
        withTimeout(10_000) { current.value.authState.first { it is AuthState.PasswordChangedNeedsSignIn } }
        assertNull(backing.getString("draft", null))
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun cancellationBeforeAcknowledgementRetainsTheOriginalRecovery() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = { throw CancellationException("QA before acknowledgement") })
        val before = commits.get()
        val sending = current.beginConfirmation(failCleanup = false)
        withTimeout(10_000) { sending.join() }
        assertTrue(sending.isCancelled)
        assertEquals(before, commits.get())
        assertEquals(draftSnapshot, current.value.emailRecovery.value)
        assertEquals(draftSnapshot, EmailRecoveryDraftStore(context).read())
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertFalse(current.value.finishingEmailRecovery.value)
    }

    @Test fun anAcknowledgementForASupersededAccountCannotPublishOrClearItsDraft() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { _ -> false }
        })
        val before = commits.get()
        current.confirm(failCleanup = false)
        assertEquals(before, commits.get())
        assertTrue(current.value.authState.value is AuthState.Error)
        assertFalse(current.value.finishingEmailRecovery.value)
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertFalse(current.value.isUserLoggedIn.value)
        assertNull(EmailRecoveryDraftStore(context).read())
        current.attemptAgain()
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun aRetainedNoticeCannotFinishAfterAnotherAccountVisitBegins() = runBlocking {
        var sameVisit = true
        val current = Fixture(Result.success(user), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> if (sameVisit) { action(); true } else false }
        })
        current.confirm()
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        sameVisit = false
        val before = commits.get()
        current.value.retryEmailRecoveryCleanup(notice)
        assertEquals(before, commits.get())
        current.value.continueAfterEmailRecoveryCleanup(notice)
        assertTrue(current.value.authState.value is AuthState.Error)
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertFalse(current.value.isUserLoggedIn.value)
        assertFalse(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun aNewRecoveryDraftSurvivesAnOldAcknowledgement() = runBlocking {
        val newer = draftSnapshot.copy(email = "qa.next@example.invalid", resetToken = "qa-next-" + UUID.randomUUID())
        val response = CompletableDeferred<Unit>()
        val current = Fixture(localFailure(), beforeResponse = {
            withContext(NonCancellable) { response.await() }
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
        })
        val sending = current.beginConfirmation(failCleanup = false)
        EmailRecoveryDraftStore(context).save(newer)
        response.complete(Unit)
        withTimeout(10_000) { sending.join() }
        assertEquals(newer, EmailRecoveryDraftStore(context).read())
        assertNull(current.value.emailRecoveryCleanupNotice.value)
        assertFalse(current.value.isUserLoggedIn.value)
    }

    @Test fun cancellationAfterAcknowledgedSecondFactorKeepsTheTwoFactorHandoff() = runBlocking {
        val current = Fixture(localFailure(), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, true) { action -> action(); true }
            throw CancellationException("QA acknowledged second factor interrupted")
        })
        val sending = current.beginConfirmation(failCleanup = false)
        withTimeout(10_000) { sending.join() }
        assertTrue(sending.isCancelled)
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertTrue(notice.requiresTwoFactor)
        assertTrue(notice.requiresSignIn)
        current.value.retryEmailRecoveryCleanup(notice)
        withTimeout(10_000) { current.value.authState.first { it is AuthState.PasswordChangedNeedsTwoFactor } }
        assertTrue(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }

    @Test fun aCancelledJobCannotLoseASuccessfulRepositoryAcknowledgement() = runBlocking {
        val current = Fixture(Result.success(user), beforeResponse = {
            ConfirmedPasswordResets.record(draftSnapshot.resetToken, false) { action -> action(); true }
            currentCoroutineContext().job.cancel(CancellationException("QA cancelled after result"))
        })
        val sending = current.beginConfirmation(failCleanup = false)
        withTimeout(10_000) { sending.join() }
        assertTrue(sending.isCancelled)
        val notice = current.value.emailRecoveryCleanupNotice.value!!
        assertTrue(notice.requiresSignIn)
        assertFalse(notice.requiresTwoFactor)
        assertFalse(current.value.isUserLoggedIn.value)
        current.value.continueAfterEmailRecoveryCleanup(notice)
        assertEquals(AuthState.PasswordChangedNeedsSignIn, current.value.authState.value)
        assertTrue(current.value.finishingEmailRecovery.value)
        coVerify(exactly = 1) { current.repository.confirmLocalFallbackPasswordReset(any(), any()) }
    }
}
