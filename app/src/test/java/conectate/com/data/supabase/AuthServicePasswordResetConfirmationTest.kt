package conectate.com.data.supabase

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import conectate.com.core.media.StorageReadSession
import conectate.com.data.local.AccountOutboxVault
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.localfallback.LocalFallbackPasswordChangedNeedsTwoFactorException
import conectate.com.data.localfallback.LocalFallbackUser
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.repository.AccountSessionRepository
import conectate.com.data.repository.AuthRepository
import conectate.com.presentation.auth.EmailRecoveryDraft
import conectate.com.presentation.auth.EmailRecoveryDraftStore
import conectate.com.domain.auth.ConfirmedPasswordResets
import conectate.com.domain.auth.PasswordResetConfirmedNeedsSignInException
import conectate.com.test.UnitTestApplication
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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

/** Service, repository and preferences are real; crypto opening, HTTP and SDK facade are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class, qualifiers = "es")
class AuthServicePasswordResetConfirmationTest {
    private lateinit var context: Application
    private lateinit var authPreferences: SharedPreferences
    private lateinit var draftPreferences: SharedPreferences
    private lateinit var authName: String
    private lateinit var draftName: String
    private lateinit var original: EmailRecoveryDraft
    private val failNextAuthCommit = AtomicBoolean(false)
    private val services = mutableListOf<AuthService>()
    private val repositories = mutableListOf<AuthRepository>()
    private val remoteUser = LocalFallbackUser(id = "11111111-1111-4111-8111-111111111111",
        email = "qa.confirmation@example.invalid", displayName = "QA confirmation")
    private val remoteSession = LocalFallbackAuthResponse(remoteUser, "qa-access-only", "qa-refresh-only")

    private inner class Fixture {
        val api = mockk<LocalFallbackApiClient>()
        val compatibility = mockk<Auth>()
        val vault = mockk<AccountOutboxVault>()
        val accounts = mockk<AccountSessionRepository>()
        val compatibilityFails = AtomicBoolean(false)
        val vaultFails = AtomicBoolean(false)
        val service: AuthService
        val repository: AuthRepository

        init {
            val config = mockk<SupabaseConfig>()
            val client = mockk<SupabaseClient>()
            every { config.client } returns client
            every { client.auth } returns compatibility
            every { api.isConfigured } returns true
            coEvery { api.confirmPasswordReset(any(), any()) } returns Result.success(remoteSession)
            coEvery { compatibility.importSession(any(), any()) } coAnswers {
                if (compatibilityFails.get()) throw IOException("QA compatibility import failed")
                Unit
            }
            coEvery { compatibility.clearSession() } returns Unit
            coEvery { vault.requireCompatibleAccount(any()) } coAnswers {
                if (vaultFails.get()) throw IllegalStateException("QA outbox still belongs to another account")
                Unit
            }
            coEvery { accounts.captureCurrentAccount(any(), any(), any()) } returns
                Result.success(mockk<StoredAccountSession>())
            service = AuthService(config, api, vault, context).also { services += it }
            runBlocking { withTimeout(10_000) { service.isInitializing.first { !it } } }
            repository = AuthRepository(service, dagger.Lazy { accounts }, config,
                dagger.Lazy { mockk(relaxed = true) }, dagger.Lazy { mockk(relaxed = true) },
                dagger.Lazy { mockk(relaxed = true) }, dagger.Lazy { mockk(relaxed = true) },
                dagger.Lazy { mockk(relaxed = true) }, dagger.Lazy { mockk(relaxed = true) }, api)
                .also { repositories += it }
        }

        suspend fun serviceResult(): Result<Unit> = try {
            service.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
        } catch (error: Exception) { Result.failure(error) }

        suspend fun repositoryResult() = try {
            repository.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
        } catch (error: Exception) { Result.failure(error) }
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        authName = "qa_reset_service_auth_$suffix"
        draftName = "qa_reset_service_draft_$suffix"
        authPreferences = context.getSharedPreferences(authName, Context.MODE_PRIVATE)
        draftPreferences = context.getSharedPreferences(draftName, Context.MODE_PRIVATE)
        val auth = object : SharedPreferences by authPreferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = authPreferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value); return this
                    }
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean {
                        val applied = editor.commit()
                        return if (failNextAuthCommit.compareAndSet(true, false)) false else applied
                    }
                }
            }
        }
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk<MasterKey>()
        mockkStatic(EncryptedSharedPreferences::class)
        every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } answers {
            when (secondArg<String>()) {
                "auth_cache" -> auth
                "novvin_email_recovery" -> draftPreferences
                else -> error("Unexpected encrypted preference in isolated QA fixture")
            }
        }
        mockkStatic("io.github.jan.supabase.gotrue.AuthKt")
        original = EmailRecoveryDraft(remoteUser.email, resetToken = "qa-acknowledged-grant-$suffix",
            expiresAt = System.currentTimeMillis() + 600_000)
        EmailRecoveryDraftStore(context).save(original)
    }

    @After fun cleanup() {
        val scopes = services.map { service ->
            AuthService::class.java.getDeclaredField("authScope").apply { isAccessible = true }.get(service) as CoroutineScope
        } + repositories.map { repository ->
            AuthRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }.get(repository) as CoroutineScope
        }
        scopes.forEach { it.cancel() }
        try { runBlocking { withTimeout(10_000) { scopes.forEach { it.coroutineContext.job.join() } } } } finally {
            StorageReadSession.install(credentials = { null })
            unmockkStatic("io.github.jan.supabase.gotrue.AuthKt")
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkConstructor(MasterKey.Builder::class)
            check(authName.startsWith("qa_reset_service_auth_") && draftName.startsWith("qa_reset_service_draft_"))
            context.deleteSharedPreferences(authName)
            context.deleteSharedPreferences(draftName)
        }
    }

    private fun assertConfirmedFailure(result: Result<*>) {
        assertTrue("The classified result must preserve the confirmed remote change", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty().lowercase()
        assertTrue("Local completion failure must say the password already changed",
            message.contains("contraseña") && message.contains("cambi"))
    }

    @Test fun aFailedSessionCommitPreservesTheConfirmedRemoteResult() = runBlocking {
        val current = Fixture()
        failNextAuthCommit.set(true)
        assertConfirmedFailure(current.serviceResult())
        coVerify(exactly = 1) { current.api.confirmPasswordReset(any(), any()) }
    }

    @Test fun aCompatibilityFailurePreservesTheConfirmedRemoteResult() = runBlocking {
        val current = Fixture()
        current.compatibilityFails.set(true)
        assertConfirmedFailure(current.serviceResult())
        assertEquals(remoteUser.id, current.service.currentSession.value?.user?.id)
        coVerify(exactly = 1) { current.compatibility.importSession(any(), false) }
    }

    @Test fun anOutboxActivationFailurePreservesTheConfirmedRemoteResult() = runBlocking {
        val current = Fixture()
        current.vaultFails.set(true)
        assertConfirmedFailure(current.serviceResult())
        assertFalse(authPreferences.getBoolean("local_fallback_session", false))
    }

    @Test fun aFailedSessionCommitCannotRestoreAnAcknowledgedGrant() = runBlocking {
        val current = Fixture()
        failNextAuthCommit.set(true)
        current.serviceResult()
        assertNull("Session storage failure cannot make a consumed grant active", EmailRecoveryDraftStore(context).read())
    }

    @Test fun aTwoFactorAcknowledgementCannotRestoreTheConsumedGrant() = runBlocking {
        val current = Fixture()
        coEvery { current.api.confirmPasswordReset(any(), any()) } returns
            Result.failure(LocalFallbackPasswordChangedNeedsTwoFactorException())
        current.serviceResult()
        assertNull("Second-factor continuation must retain the acknowledgement", EmailRecoveryDraftStore(context).read())
    }

    @Test fun theRepositoryPreservesAnAcknowledgementAfterCompatibilityFails() = runBlocking {
        val current = Fixture()
        current.compatibilityFails.set(true)
        assertConfirmedFailure(current.repositoryResult())
    }

    @Test fun aRejectedRemoteRequestKeepsItsOriginalGrant() = runBlocking {
        val current = Fixture()
        coEvery { current.api.confirmPasswordReset(any(), any()) } returns Result.failure(IOException("QA remote rejection"))
        assertTrue(current.serviceResult().isFailure)
        assertEquals(original, EmailRecoveryDraftStore(context).read())
        assertNull(current.service.currentSession.value)
        coVerify(exactly = 0) { current.compatibility.importSession(any(), any()) }
    }

    @Test fun aServerConfirmedChangeWithoutSessionCannotRestoreTheConsumedGrant() = runBlocking {
        val current = Fixture()
        val continuation = PasswordResetConfirmedNeedsSignInException(IOException("QA169 server confirmed; new sign-in required"))
        coEvery { current.api.confirmPasswordReset(any(), any()) } returns Result.failure(continuation)
        assertTrue(current.serviceResult().exceptionOrNull() === continuation)
        assertNull("A server confirmation must retire its consumed grant before local session IO", EmailRecoveryDraftStore(context).read())
        coVerify(exactly = 0) { current.compatibility.importSession(any(), any()) }
        coVerify(exactly = 0) { current.vault.requireCompatibleAccount(any()) }
    }

    @Test fun anUnconfiguredEndpointDoesNotSendOrConsumeTheGrant() = runBlocking {
        val current = Fixture()
        every { current.api.isConfigured } returns false
        assertTrue(current.serviceResult().isFailure)
        assertEquals(original, EmailRecoveryDraftStore(context).read())
        coVerify(exactly = 0) { current.api.confirmPasswordReset(any(), any()) }
    }

    @Test fun aSuccessfulRepositoryEntryStillImportsItsCompatibilitySession() = runBlocking {
        val current = Fixture()
        val result = current.repositoryResult()
        assertTrue(result.isSuccess)
        assertEquals(remoteUser.id, result.getOrNull()?.id)
        assertEquals(remoteUser.id, current.service.currentSession.value?.user?.id)
        coVerify(exactly = 1) { current.compatibility.importSession(any(), false) }
        coVerify(exactly = 1) { current.accounts.captureCurrentAccount(any(), any(), remoteUser.id) }
    }

    @Test fun aCancellationBeforeAcknowledgementPropagatesWithoutConsumingTheGrant() = runBlocking {
        val current = Fixture()
        val cancelled = CancellationException("QA before acknowledgement")
        coEvery { current.api.confirmPasswordReset(any(), any()) } returns Result.failure(cancelled)
        try {
            current.service.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
            error("Cancellation must propagate")
        } catch (actual: CancellationException) { assertTrue(actual === cancelled) }
        assertNull(ConfirmedPasswordResets.confirmation(original.resetToken))
        assertEquals(original, EmailRecoveryDraftStore(context).read())
    }

    @Test fun aCancellationAfterAcknowledgementRetainsSingleUseAndPropagates() = runBlocking {
        val current = Fixture()
        val cancelled = CancellationException("QA compatibility cancelled")
        coEvery { current.compatibility.importSession(any(), any()) } throws cancelled
        try {
            current.service.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
            error("Cancellation must propagate")
        } catch (actual: CancellationException) { assertTrue(actual === cancelled) }
        assertNull(EmailRecoveryDraftStore(context).read())
        assertTrue(ConfirmedPasswordResets.confirmation(original.resetToken)!!.mayPublishCompletion())
        assertFalse(ConfirmedPasswordResets.confirmation(original.resetToken)!!.requiresTwoFactor)
        coVerify(exactly = 1) { current.api.confirmPasswordReset(any(), any()) }
    }

    @Test fun anAccountTransitionBeforeAcknowledgementCannotBeAdopted() = runBlocking {
        val current = Fixture()
        coEvery { current.api.confirmPasswordReset(any(), any()) } coAnswers {
            current.service.forceLocalLogout()
            Result.success(remoteSession)
        }
        try {
            current.service.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
            error("The previous account visit must cancel")
        } catch (_: CancellationException) { }
        assertNull(current.service.currentSession.value)
        assertNull(EmailRecoveryDraftStore(context).read())
        assertFalse(ConfirmedPasswordResets.confirmation(original.resetToken)!!.mayPublishCompletion())
        coVerify(exactly = 0) { current.compatibility.importSession(any(), any()) }
    }

    @Test fun aLaterLogoutInvalidatesCompletionButNotSingleUse() = runBlocking {
        val current = Fixture()
        assertTrue(current.serviceResult().isSuccess)
        val receipt = ConfirmedPasswordResets.confirmation(original.resetToken)!!
        assertTrue(receipt.mayPublishCompletion())
        current.service.forceLocalLogout()
        assertFalse(receipt.mayPublishCompletion())
        assertNull(EmailRecoveryDraftStore(context).read())
    }

    @Test fun aRepositoryHydrationSideEffectFailureKeepsTheKnownAcknowledgement() = runBlocking {
        val current = Fixture()
        coEvery { current.accounts.captureCurrentAccount(any(), any(), any()) } throws IOException("QA account capture failed")
        val result = current.repositoryResult()
        assertTrue(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
        assertNull(EmailRecoveryDraftStore(context).read())
        coVerify(exactly = 1) { current.compatibility.importSession(any(), false) }
    }

    @Test fun aRepositoryCaptureCancellationStillPropagates() = runBlocking {
        val current = Fixture()
        val cancelled = CancellationException("QA account capture cancelled")
        coEvery { current.accounts.captureCurrentAccount(any(), any(), any()) } returns Result.failure(cancelled)
        try {
            current.repository.confirmLocalFallbackPasswordReset(original.resetToken, "QA-synthetic-password-only")
            error("Cancellation must propagate through the repository")
        } catch (actual: CancellationException) { assertTrue(actual === cancelled) }
        assertNull(EmailRecoveryDraftStore(context).read())
    }
}
