package conectate.com.presentation.messages.agreements

import androidx.lifecycle.ViewModelStore
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAgreementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val alice = "11111111-1111-4111-8111-111111111111"
    private val bob = "22222222-2222-4222-8222-222222222222"
    private val chat = "33333333-3333-4333-8333-333333333333"
    private val otherChat = "44444444-4444-4444-8444-444444444444"
    private val sourceId = "55555555-5555-4555-8555-555555555555"
    private fun agreement(version: Int = 1, text: String = "Ensayar el sábado") = ChatAgreement(
        "66666666-6666-4666-8666-666666666666", sourceId, alice, text, version, 1,
        participants = listOf(AgreementParticipant(alice, "Ana"), AgreementParticipant(bob, "Luis")), sourceAvailable = true)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.clear(); Dispatchers.resetMain() }

    private inner class Fixture {
        val guard = AuthSessionMutationGuard()
        val changes = MutableStateFlow(0)
        val events = MutableSharedFlow<AgreementChange>(extraBufferCapacity = 4)
        var owner: String? = alice
        var supported = true
        var reads = 0
        var rows = listOf(agreement())
        var read: suspend (String) -> List<ChatAgreement> = { rows }
        var send: suspend (AgreementCommand) -> ChatAgreement = { agreement(2) }
        var source: suspend () -> AgreementSource? = { AgreementSource(sourceId, "Mensaje original") }
        val commands = mutableListOf<AgreementCommand>()
        val vm = ChatAgreementsViewModel(
            capture = { guard.captureAccountLease { owner } }, changes = changes, events = events,
            capability = { supported }, load = { _, id -> reads++; read(id) },
            send = { _, _, _, command -> commands.add(command); send(command) }, source = { _, _, _ -> source() },
        ).also { models.put("agreements", it) }
        fun switch(next: String?) { guard.invalidate { owner = next }; changes.value++ }
        fun bind() { vm.bind(chat) }
    }
    @Test fun unsupportedBackendAndGroupChatsDoNotExposeTheExperimentOrReadAgreements() = runTest(dispatcher) {
        val f = Fixture(); f.supported = false; f.bind(); advanceUntilIdle(); f.vm.open()
        assertFalse(f.vm.state.value.supported); assertEquals(0, f.reads)
        f.supported = true; f.vm.bind(chat, direct = false); advanceUntilIdle()
        assertNull(f.vm.state.value.chatId); assertFalse(f.vm.state.value.supported)
    }
    @Test fun onlyOpeningTheModuleLoadsItsContentAndAReadFailureKeepsTheConfirmedList() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); assertEquals(0, f.reads)
        f.vm.open(); advanceUntilIdle(); assertEquals(listOf(agreement()), f.vm.state.value.agreements)
        f.read = { throw IOException("offline") }; f.vm.refresh(); advanceUntilIdle()
        assertEquals(listOf(agreement()), f.vm.state.value.agreements); assertNotNull(f.vm.state.value.error)
    }
    @Test fun proposingDoesNotCopyOriginalTextAndDoubleTapDispatchesOneCommand() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); advanceUntilIdle()
        assertEquals("", f.vm.state.value.draft)
        f.vm.changeDraft("Ensayar mañana")
        val ack = CompletableDeferred<ChatAgreement>(); f.send = { ack.await() }
        f.vm.saveDraft(); f.vm.saveDraft(); runCurrent()
        assertEquals(1, f.commands.size); assertEquals(sourceId, f.commands.single().sourceMessageId)
        assertTrue(f.vm.state.value.busy); assertEquals("Ensayar mañana", f.vm.state.value.draft)
        ack.complete(agreement()); advanceUntilIdle()
        assertFalse(f.vm.state.value.busy); assertNull(f.vm.state.value.sourceId)
    }
    @Test fun lostAcknowledgementRetainsTheSameOperationAndPayloadUntilExplicitRetry() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); advanceUntilIdle(); f.vm.changeDraft("Una propuesta")
        f.send = { throw IOException("lost ACK") }; f.vm.saveDraft(); advanceUntilIdle()
        assertTrue(f.vm.state.value.retryPending); val original = f.commands.single()
        f.vm.changeDraft("No cambiar un envío incierto"); f.vm.saveDraft()
        assertEquals("Una propuesta", f.vm.state.value.draft)
        f.vm.refresh(); advanceUntilIdle(); assertTrue(f.vm.state.value.retryPending); assertNotNull(f.vm.state.value.error)
        f.send = { agreement() }; f.vm.retry(); advanceUntilIdle()
        assertEquals(listOf(original, original), f.commands); assertFalse(f.vm.state.value.retryPending)
    }
    @Test fun lateResponseFromAnOldConversationCannotReplaceTheNewOne() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle()
        val old = CompletableDeferred<List<ChatAgreement>>()
        f.read = { id -> if (id == chat) withContext(NonCancellable) { old.await() } else emptyList() }
        f.vm.open(); runCurrent(); f.vm.bind(otherChat); advanceUntilIdle(); f.vm.open(); runCurrent()
        old.complete(listOf(agreement())); advanceUntilIdle()
        assertEquals(otherChat, f.vm.state.value.chatId); assertTrue(f.vm.state.value.agreements.isEmpty())
    }
    @Test fun returningToTheSameAccountDoesNotAcceptAnAnswerFromItsPreviousSession() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle()
        val ack = CompletableDeferred<ChatAgreement>(); f.send = { withContext(NonCancellable) { ack.await() } }
        f.vm.respond(agreement(), "accept"); runCurrent()
        f.switch(bob); runCurrent(); f.switch(alice); runCurrent(); ack.complete(agreement(2)); advanceUntilIdle()
        assertEquals(alice, f.vm.state.value.accountId); assertTrue(f.vm.state.value.agreements.isEmpty()); assertFalse(f.vm.state.value.open)
    }
    @Test fun anEditKeepsItsExpectedRevisionUntilTheUserComparesAndKeepsTheDraft() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.edit(agreement()); f.vm.changeDraft("Mi borrador")
        f.rows = listOf(agreement(2, "Cambio desde otro dispositivo")); f.vm.refresh(); advanceUntilIdle()
        f.send = { throw AgreementApiException(409, "AGREEMENT_VERSION_CONFLICT") }; f.vm.saveDraft(); advanceUntilIdle()
        assertEquals(1, f.commands.single().expectedVersion); assertEquals("Mi borrador", f.vm.state.value.draft)
        assertFalse(f.vm.state.value.retryPending); assertNotNull(f.vm.state.value.error)
        f.vm.keepDraftAgainstLatestRevision(); f.vm.saveDraft(); advanceUntilIdle()
        assertEquals(2, f.commands.last().expectedVersion); assertEquals("Mi borrador", f.commands.last().text)
    }
    @Test fun aRealtimeReferenceReadsCurrentStateOnlyForThisAccountAndConversation() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); val before = f.reads
        f.events.emit(AgreementChange(otherChat, "user:$alice")); f.events.emit(AgreementChange(chat, "user:$bob")); runCurrent()
        assertEquals(before, f.reads)
        f.events.emit(AgreementChange(chat, "user:$alice")); advanceUntilIdle(); assertEquals(before + 1, f.reads)
    }
    @Test fun theSourceIsRecheckedWhenOpeningItAndADeletedSourceHasAnExplicitState() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.source = { null }; f.vm.showSource(agreement()); advanceUntilIdle()
        assertNull(f.vm.state.value.source); assertEquals("El mensaje de origen ya no está disponible.", f.vm.state.value.error)
    }
    @Test fun accessLossRemovesThePreviouslyVisibleSharedState() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle()
        assertFalse(f.vm.state.value.agreements.isEmpty())
        f.read = { throw AgreementApiException(403, "AGREEMENT_ACCESS_DENIED") }; f.vm.refresh(); advanceUntilIdle()
        assertTrue(f.vm.state.value.agreements.isEmpty()); assertNotNull(f.vm.state.value.error)
    }
    @Test fun readsHaveAnOverallDeadlineAndLeaveTheModuleRecoverable() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.read = { awaitCancellation() }; f.vm.open(); runCurrent()
        advanceTimeBy(10_001); runCurrent(); assertFalse(f.vm.state.value.loading); assertNotNull(f.vm.state.value.error)
        f.read = { emptyList() }; f.vm.refresh(); advanceUntilIdle(); assertNull(f.vm.state.value.error)
    }
}
