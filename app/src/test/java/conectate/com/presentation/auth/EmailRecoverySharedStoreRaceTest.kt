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
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real draft store/JSON/preferences and ViewModels; crypto opening and transport are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EmailRecoverySharedStoreRaceTest {
    private class Gate {
        private val entered = CountDownLatch(1)
        private val resumed = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(resumed.await(10, TimeUnit.SECONDS)) { "QA storage boundary was not released" }
        }
        fun awaitEntry() = assertTrue("Expected the observed storage boundary", entered.await(10, TimeUnit.SECONDS))
        fun release() = resumed.countDown()
    }

    private data class SavePause(val matches: (EmailRecoveryDraft) -> Boolean, val gate: Gate)
    private val clearPause = AtomicReference<Gate?>()
    private val savePause = AtomicReference<SavePause?>()
    private val rawReadPause = AtomicReference<Gate?>()
    private val failNextCommit = AtomicBoolean(false)
    private val gates = mutableListOf<Gate>()
    private val models = mutableListOf<Model>()
    private lateinit var context: Application
    private lateinit var backing: SharedPreferences
    private lateinit var fixtureName: String

    private class Model(context: Context, initial: EmailRecoveryDraft) {
        val repository = mockk<AuthRepository>()
        val api = mockk<LocalFallbackApiClient>()
        val value: AuthViewModel
        private val store = ViewModelStore()
        private val root: Job

        init {
            every { repository.getCurrentUserId() } returns null
            every { repository.isUserLoggedIn } returns false
            every { repository.getAuthStateFlow() } returns MutableStateFlow<AuthResponse?>(null)
            every { repository.storedAccounts } returns MutableStateFlow(emptyList<StoredAccountSession>())
            value = AuthViewModel(repository, api, mockk<GuestModeApi>(relaxed = true), context)
            root = value.viewModelScope.coroutineContext.job
            store.put("shared-recovery", value)
            runBlocking { withTimeout(10_000) { value.emailRecovery.first { it == initial } } }
        }

        private fun jobs(parent: Job = root): List<Job> = parent.children.flatMap { child ->
            sequenceOf(child) + jobs(child).asSequence()
        }.toList()

        private fun introduced(before: Set<Job>): Job {
            val candidates = jobs().filter { it !in before && it !is CompletableJob }
            return candidates.firstOrNull { it.children.any() } ?: candidates.first()
        }

        fun clear(onCleared: () -> Unit): Job {
            val before = jobs().toSet()
            value.clearEmailRecovery(onCleared)
            return introduced(before)
        }

        fun editCode(code: String): Job {
            val before = jobs().toSet()
            value.updateEmailRecoveryCode(code)
            return introduced(before)
        }

        fun verify(): CompletableDeferred<Job> {
            val job = CompletableDeferred<Job>()
            coEvery { api.verifyEmailPasswordCode(any(), any()) } coAnswers {
                job.complete(currentCoroutineContext().job)
                Result.success(LocalFallbackPasswordResetResponse(resetToken = "qa-original-permission"))
            }
            value.verifyEmailRecoveryCode()
            return job
        }

        suspend fun request(email: String) {
            val job = CompletableDeferred<Job>()
            coEvery { repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
                job.complete(currentCoroutineContext().job)
                Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email"))
            }
            value.requestMigratedPasswordCode(email)
            withTimeout(10_000) { job.await().join() }
        }

        fun requestBeforeSending(email: String): Job {
            fun descendants(parent: Job): List<Job> = parent.children.flatMap { child ->
                sequenceOf(child) + descendants(child).asSequence()
            }.toList()
            val before = descendants(root).toSet()
            value.requestMigratedPasswordCode(email)
            return descendants(root).first { it !in before && it !is CompletableJob }
        }

        fun close() {
            store.clear()
            runBlocking { withTimeout(10_000) { root.join() } }
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        fixtureName = "qa_recovery_shared_" + UUID.randomUUID()
        backing = context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        editor.remove(key)
                        return this
                    }
                    override fun commit(): Boolean {
                        val applied = editor.commit()
                        return if (failNextCommit.compareAndSet(true, false)) false else applied
                    }
                }
            }
            override fun getString(key: String?, defaultValue: String?): String? {
                val captured = backing.getString(key, defaultValue)
                if (key == "draft") rawReadPause.getAndSet(null)?.hold()
                return captured
            }
        }
        mockkConstructor(MasterKey.Builder::class, EmailRecoveryDraftStore::class)
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk<MasterKey>()
        mockkStatic(EncryptedSharedPreferences::class)
        every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns preferences
        // Pause at entry, then execute the actual store implementation. This
        // leaves its JSON, preference writes and any ownership checks intact.
        every { anyConstructed<EmailRecoveryDraftStore>().clearForVisit(any()) } answers {
            clearPause.getAndSet(null)?.hold()
            callOriginal()
        }
        every { anyConstructed<EmailRecoveryDraftStore>().saveForVisit(any(), any()) } answers {
            val snapshot = firstArg<EmailRecoveryDraft>()
            savePause.get()?.let { pause ->
                if (pause.matches(snapshot) && savePause.compareAndSet(pause, null)) pause.gate.hold()
            }
            callOriginal()
        }
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        try { models.forEach { it.close() } } finally {
            unmockkConstructor(EmailRecoveryDraftStore::class, MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            Dispatchers.resetMain()
            check(fixtureName.startsWith("qa_recovery_shared_"))
            context.deleteSharedPreferences(fixtureName)
        }
    }

    private fun gate() = Gate().also { gates += it }
    private fun draft(email: String = "qa.original@example.invalid", code: String = "") =
        EmailRecoveryDraft(email, code = code, expiresAt = System.currentTimeMillis() + 600_000)
    private fun seed(snapshot: EmailRecoveryDraft) = EmailRecoveryDraftStore(context).save(snapshot)
    private fun readStored() = EmailRecoveryDraftStore(context).read()
    private fun model(initial: EmailRecoveryDraft) = Model(context, initial).also { models += it }

    @Test fun anEarlierClearCannotEraseTheNextModelsRecovery() = runBlocking {
        val original = draft()
        seed(original)
        val previous = model(original)
        val next = model(original)
        val paused = gate().also { clearPause.set(it) }
        var oldNavigations = 0
        try {
            val clearing = previous.clear { oldNavigations++ }
            paused.awaitEntry()
            next.request("qa.next@example.invalid")
            val retained = readStored()!!
            assertEquals("qa.next@example.invalid", retained.email)
            paused.release()
            clearing.join()
            assertEquals("A delayed clear must retain the newer persisted flow", retained, readStored())
            assertEquals(0, oldNavigations)
        } finally { paused.release() }
    }

    @Test fun anEarlierCodeSaveCannotReplaceTheNextModelsRecovery() = runBlocking {
        val original = draft()
        seed(original)
        val previous = model(original)
        val next = model(original)
        val paused = gate().also { savePause.set(SavePause({ it.code == "123" }, it)) }
        try {
            val saving = previous.editCode("123")
            paused.awaitEntry()
            next.request("qa.next@example.invalid")
            val retained = readStored()!!
            assertEquals("qa.next@example.invalid", retained.email)
            paused.release()
            saving.join()
            assertEquals("A delayed code save must retain the newer persisted flow", retained, readStored())
        } finally { paused.release() }
    }

    @Test fun anEarlierVerificationCannotReplaceTheNextModelsRecovery() = runBlocking {
        val original = draft(code = "123456")
        seed(original)
        val previous = model(original)
        val next = model(original)
        val paused = gate().also { savePause.set(SavePause({ it.verified }, it)) }
        try {
            val saving = previous.verify()
            paused.awaitEntry()
            next.request("qa.next@example.invalid")
            val retained = readStored()!!
            assertEquals("qa.next@example.invalid", retained.email)
            paused.release()
            saving.await().join()
            assertEquals("A delayed permission must retain the newer persisted flow", retained, readStored())
        } finally { paused.release() }
    }

    @Test fun anExpiredReadCannotDeleteANewerDraft() = runBlocking {
        seed(draft().copy(expiresAt = 1))
        val paused = gate().also { rawReadPause.set(it) }
        try {
            val reading = async(Dispatchers.IO) { EmailRecoveryDraftStore(context).read() }
            paused.awaitEntry()
            val newer = draft("qa.next@example.invalid")
            seed(newer)
            assertEquals(newer, readStored())
            paused.release()
            reading.await()
            assertEquals("Expiry cleanup must compare the snapshot it actually read", newer, readStored())
        } finally { paused.release() }
    }

    @Test fun additionalReadersKeepTheCurrentRequestUsable() = runBlocking {
        val original = draft()
        seed(original)
        val current = model(original)
        val request = CompletableDeferred<Job>()
        val response = CompletableDeferred<Result<LocalFallbackPasswordResetResponse>>()
        coEvery { current.repository.requestLocalFallbackPasswordReset(any()) } coAnswers {
            request.complete(currentCoroutineContext().job)
            response.await()
        }
        try {
            current.value.requestMigratedPasswordCode("qa.request@example.invalid")
            withTimeout(10_000) { request.await() }
            assertEquals("qa.request@example.invalid", EmailRecoveryDraftStore(context).read()?.email)
            assertEquals("qa.request@example.invalid", EmailRecoveryDraftStore(context).read()?.email)
            response.complete(Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email")))
            withTimeout(10_000) { request.await().join() }
            assertTrue(readStored()?.deliveryConfirmed == true)
            assertEquals(AuthState.Idle, current.value.authState.value)
        } finally { response.complete(Result.success(LocalFallbackPasswordResetResponse(codeLength = 6, delivery = "email"))) }
    }

    @Test fun aDraftSurvivesAnotherStoreInstance() {
        val original = draft(code = "001234").copy(deliveryConfirmed = false)
        seed(original)
        assertEquals(original, readStored())
        val verified = original.copy(code = "", resetToken = "qa-current-permission")
        seed(verified)
        assertEquals(verified, readStored())
    }

    @Test fun anExpiredDraftWithoutAReplacementIsRemoved() {
        seed(draft().copy(expiresAt = 1))
        assertNull(readStored())
        assertNull(backing.getString("draft", null))
    }

    @Test fun anOlderModelCanBeginANewExplicitRequest() = runBlocking {
        val original = draft()
        seed(original)
        val first = model(original)
        val second = model(original)
        first.request("qa.first@example.invalid")
        second.request("qa.second@example.invalid")
        first.request("qa.return@example.invalid")
        assertEquals("qa.return@example.invalid", readStored()?.email)
        assertTrue(readStored()?.deliveryConfirmed == true)
        assertEquals(AuthState.Idle, first.value.authState.value)
    }

    @Test fun aReportedFailedCommitPreservesThePreviousDraft() {
        val original = draft()
        seed(original)
        failNextCommit.set(true)
        val result = runCatching { seed(draft("qa.failed@example.invalid")) }
        assertTrue("The reported commit failure must be returned", result.isFailure)
        assertEquals("A failed replacement must retain the confirmed predecessor", original, readStored())
    }

    @Test fun aFailedInitialWriteDoesNotDeleteAnotherRecovery() = runBlocking {
        val original = draft()
        seed(original)
        val current = model(original)
        val paused = gate().also { savePause.set(SavePause({ it.email == "qa.failed@example.invalid" }, it)) }
        failNextCommit.set(true)
        try {
            val sending = current.requestBeforeSending("qa.failed@example.invalid")
            paused.awaitEntry()
            paused.release()
            sending.join()
            coVerify(exactly = 0) { current.repository.requestLocalFallbackPasswordReset(any()) }
            assertEquals("Failure before sending must retain the preceding flow", original, readStored())
            assertTrue(current.value.authState.value is AuthState.Error)
        } finally { paused.release() }
    }

    @Test fun aReopenedFlowKeepsItsCodeAfterAnEarlierSaveFinishes() = runBlocking {
        val original = draft()
        seed(original)
        val previous = model(original)
        val paused = gate().also { savePause.set(SavePause({ it.code == "123" }, it)) }
        try {
            val oldSave = previous.editCode("123")
            paused.awaitEntry()
            val reopened = model(original)
            val nextPause = gate().also { savePause.set(SavePause({ it.code == "456" }, it)) }
            try {
                val nextSave = reopened.editCode("456")
                nextPause.awaitEntry()
                nextPause.release()
                nextSave.join()
                assertEquals("456", readStored()?.code)
                paused.release()
                oldSave.join()
                assertEquals("Reopening the same flow must retire its earlier writer", "456", readStored()?.code)
                assertEquals("456", reopened.value.emailRecovery.value?.code)
            } finally { nextPause.release() }
        } finally { paused.release() }
    }

    @Test fun anEarlierSaveCannotRecreateAFlowClearedByAnotherScreen() = runBlocking {
        val original = draft()
        seed(original)
        val previous = model(original)
        val paused = gate().also { savePause.set(SavePause({ it.code == "123" }, it)) }
        try {
            val saving = previous.editCode("123")
            paused.awaitEntry()
            val reopened = model(original)
            val cleared = CompletableDeferred<Unit>()
            reopened.value.clearEmailRecovery { cleared.complete(Unit) }
            withTimeout(10_000) { cleared.await() }
            assertNull(readStored())
            paused.release()
            saving.join()
            assertNull("A delayed code must not resurrect an explicitly cleared flow", readStored())
        } finally { paused.release() }
    }

    @Test fun backFromAnObsoleteScreenLeavesTheCurrentFlowUsable() = runBlocking {
        val original = draft()
        seed(original)
        val previous = model(original)
        val current = model(original)
        current.request("qa.current@example.invalid")
        val retained = readStored()
        var navigations = 0
        previous.value.clearEmailRecovery { navigations++ }
        assertEquals(1, navigations)
        assertEquals(retained, readStored())
        val paused = gate().also { savePause.set(SavePause({ it.code == "123" }, it)) }
        try {
            val saving = current.editCode("123")
            paused.awaitEntry()
            paused.release()
            saving.join()
            assertEquals("qa.current@example.invalid", readStored()?.email)
            assertEquals("123", readStored()?.code)
            assertEquals(AuthState.Idle, current.value.authState.value)
        } finally { paused.release() }
    }
}
