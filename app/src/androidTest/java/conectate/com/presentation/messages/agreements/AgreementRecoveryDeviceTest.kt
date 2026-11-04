package conectate.com.presentation.messages.agreements

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.sync.AgreementRecoveryCleanup
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgreementRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val chat = "33333333-3333-4333-8333-333333333333"
    private val source = "44444444-4444-4444-8444-444444444444"
    private fun journal() = AgreementRecoveryJournal(encryptedAgreementRecoveryPreferences(context))

    @Test fun realKeystoreRestoresTheExactCommandAndRejectsAnOldAccountLease() = runBlocking {
        val alice = UUID.randomUUID().toString(); val bob = UUID.randomUUID().toString()
        var owner = alice
        val guard = AuthSessionMutationGuard(); val lease = guard.captureAccountLease { owner }
        val text = "private-agreement-${UUID.randomUUID()}"
        val draft = AgreementDraft(source, text)
        val id = UUID.randomUUID().toString()
        val operation = AgreementPendingWrite(id, AgreementCommand(UUID.randomUUID().toString(), id = id, sourceMessageId = source, text = text))
        val first = AgreementRecoveryStore(::journal, guard::commitForLease)
        try {
            val saved = first.replace(lease, chat, null, draft, operation)
            assertEquals(saved, AgreementRecoveryStore(::journal, guard::commitForLease).read(lease, chat))
            val xml = withContext(Dispatchers.IO) { File(context.applicationInfo.dataDir, "shared_prefs/$AGREEMENT_RECOVERY_PREFERENCES.xml").readText() }
            assertFalse(xml.contains(text)); assertFalse(xml.contains(alice)); assertFalse(xml.contains(chat)); assertFalse(xml.contains(operation.command.operationId))
            guard.invalidate { owner = bob }
            assertThrows(CancellationException::class.java) { runBlocking { first.replace(lease, chat, saved, draft.copy(text = "Tarde"), operation) } }
            val bobLease = guard.captureAccountLease { owner }
            assertNull(first.read(bobLease, chat))
            guard.invalidate { owner = alice }
            assertEquals(saved, first.read(guard.captureAccountLease { owner }, chat))
        } finally { withContext(Dispatchers.IO) { journal().eraseAccount(alice); journal().eraseAccount(bob) } }
    }

    @Test fun deletingAnAccountRunsOfflineThroughWorkManagerAndPreservesTheOtherAccount() = runBlocking {
        val alice = UUID.randomUUID().toString(); val bob = UUID.randomUUID().toString()
        val recovery = journal()
        try {
            withContext(Dispatchers.IO) {
                recovery.replace(alice, chat, null, AgreementDraft(source, "Borrar"), null)
                recovery.replace(bob, chat, null, AgreementDraft(source, "Conservar"), null)
            }
            val request = AgreementRecoveryCleanup.enqueue(context, alice)
            withTimeout(20_000) {
                while (withContext(Dispatchers.IO) { WorkManager.getInstance(context).getWorkInfoById(request).get()?.state } != WorkInfo.State.SUCCEEDED) delay(100)
            }
            withContext(Dispatchers.IO) {
                assertThrows(conectate.com.data.localfallback.AccountDeletedException::class.java) { recovery.read(alice, chat) }
                assertEquals("Conservar", recovery.read(bob, chat)?.draft?.text)
            }
        } finally { withContext(Dispatchers.IO) { recovery.eraseAccount(bob) } }
    }

    /** Run these two phases separately, with adb force-stop between them. No server is involved. */
    @Test fun preparePendingBeforeProcessDeath() = runBlocking {
        val owner = processOwner()
        val models = ViewModelStore(); val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { owner }
        val persistence = AgreementRecoveryStore(::journal, guard::commitForLease)
        val model = withContext(Dispatchers.Main) {
            ChatAgreementsViewModel({ lease }, MutableStateFlow(0), emptyFlow(), { true }, { _, _ -> emptyList() },
                { _, _, _, _ -> throw IOException("Simulated lost acknowledgement") }, { _, _, _ -> null }, persistence)
                .also { models.put("recovery", it); it.bind(chat) }
        }
        try {
            withTimeout(15_000) { while (!model.state.value.supported || !model.state.value.serverAvailable) delay(25) }
            withContext(Dispatchers.Main) { model.propose(source); model.changeDraft("Proceso:$owner"); model.saveDraft() }
            withTimeout(15_000) { while (!model.state.value.retryPending) delay(25) }
            val saved = persistence.read(lease, chat)
            assertEquals("Proceso:$owner", saved?.draft?.text); assertNotNull(saved?.pending)
            assertFalse(model.state.value.busy)
            withContext(Dispatchers.IO) { File(checkNotNull(context.getExternalFilesDir(null)), "agreements108-process-before.pid").writeText(android.os.Process.myPid().toString()) }
        } finally { withContext(Dispatchers.Main) { models.clear() } }
    }
    @Test fun recoverPendingAfterProcessDeathWithoutAutomaticDispatch() = runBlocking {
        val owner = processOwner()
        val guard = AuthSessionMutationGuard(); val lease = guard.captureAccountLease { owner }
        val persistence = AgreementRecoveryStore(::journal, guard::commitForLease)
        val saved = checkNotNull(persistence.read(lease, chat)); val operation = checkNotNull(saved.pending)
        val calls = mutableListOf<AgreementCommand>(); val models = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            ChatAgreementsViewModel({ lease }, MutableStateFlow(0), emptyFlow(), { true }, { _, _ -> emptyList() },
                { _, _, id, command ->
                    calls.add(command)
                    ChatAgreement(id, source, owner, "Proceso:$owner", 1, 1,
                        participants = listOf(AgreementParticipant(owner), AgreementParticipant("22222222-2222-4222-8222-222222222222")))
                }, { _, _, _ -> null }, persistence).also { models.put("recovery", it); it.bind(chat) }
        }
        try {
            withTimeout(15_000) { while (!model.state.value.supported || !model.state.value.serverAvailable) delay(25) }
            assertTrue(calls.isEmpty()); assertTrue(model.state.value.retryPending)
            assertEquals("Proceso:$owner", model.state.value.draft)
            withContext(Dispatchers.Main) { model.retry() }
            withTimeout(15_000) { while (model.state.value.busy || model.state.value.retryPending) delay(25) }
            assertEquals(listOf(operation.command), calls)
            assertNull(persistence.read(lease, chat)?.pending)
            assertNull(persistence.read(lease, chat)?.draft)
            withContext(Dispatchers.IO) { File(checkNotNull(context.getExternalFilesDir(null)), "agreements108-process-after.pid").writeText(android.os.Process.myPid().toString()) }
        } finally { withContext(Dispatchers.Main) { models.clear() }; withContext(Dispatchers.IO) { journal().eraseAccount(owner) } }
    }
    private fun processOwner(): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowAgreementProcessDeath") == "true")
        return checkNotNull(args.getString("recoveryOwner")).also { require(agreementUuid(it)) }
    }
}
