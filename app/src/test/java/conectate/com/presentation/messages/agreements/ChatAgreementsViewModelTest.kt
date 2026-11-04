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

    private class MemoryRecovery : AgreementRecoveryPersistence {
        val rows = mutableMapOf<Pair<String?, String>, AgreementRecovery>()
        var failRead = false
        var failWrite = false
        var failCompletion = false
        override suspend fun read(lease: conectate.com.data.supabase.AuthSessionLease, chatId: String): AgreementRecovery? {
            lease.requireCurrent(); if (failRead) throw IOException("storage read")
            return rows[lease.accountId to chatId]
        }
        override suspend fun replace(lease: conectate.com.data.supabase.AuthSessionLease, chatId: String,
            expected: AgreementRecovery?, draft: AgreementDraft?, pending: AgreementPendingWrite?): AgreementRecovery {
            lease.requireCurrent()
            if (failWrite || (failCompletion && expected?.pending != null && pending == null)) throw IOException("storage write")
            val key = lease.accountId to chatId
            if (rows[key] != expected) throw AgreementRecoveryConflict()
            return AgreementRecovery(revision = (expected?.revision ?: 0) + 1, draft = draft, pending = pending).validate().also { rows[key] = it }
        }
    }
    private inner class Fixture(val persistence: MemoryRecovery = MemoryRecovery()) {
        val guard = AuthSessionMutationGuard()
        val changes = MutableStateFlow(0)
        val events = MutableSharedFlow<AgreementChange>(extraBufferCapacity = 4)
        var owner: String? = alice
        var supported = true
        var capabilityRead: suspend () -> Boolean = { supported }
        var archiving = true
        var reads = 0
        var rows = listOf(agreement())
        var read: suspend (String) -> List<ChatAgreement> = { rows }
        var pageRead: suspend (String, Boolean, String?, String?) -> AgreementCatalog = { id, archived, _, focus ->
            val found = read(id)
            AgreementCatalog(found.filter { it.archived == archived }, archived = archived, focus = found.find { it.id == focus })
        }
        var send: suspend (AgreementCommand) -> ChatAgreement = { agreement(2) }
        var source: suspend () -> AgreementSource? = { AgreementSource(sourceId, "Mensaje original") }
        val commands = mutableListOf<AgreementCommand>()
        val vm = ChatAgreementsViewModel(
            capture = { guard.captureAccountLease { owner } }, changes = changes, events = events,
            capability = { AgreementCapability(capabilityRead(), 1, archiving, archiving) },
            load = { _, id, archived, cursor, focus -> reads++; pageRead(id, archived, cursor, focus) },
            send = { _, _, _, command -> commands.add(command); send(command) }, source = { _, _, _ -> source() },
            recovery = persistence,
        ).also { models.put(java.util.UUID.randomUUID().toString(), it) }
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
    @Test fun closingTheModuleInvalidatesALateSourcePreviewWithoutDiscardingTheDraft() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); advanceUntilIdle(); f.vm.changeDraft("Conservar")
        val old = CompletableDeferred<AgreementSource?>()
        f.source = { withContext(NonCancellable) { old.await() } }; f.vm.showSource(agreement()); runCurrent()
        f.vm.close(); old.complete(AgreementSource(sourceId, "Anterior")); advanceUntilIdle()
        assertNull(f.vm.state.value.source); assertFalse(f.vm.state.value.open); assertFalse(f.vm.state.value.loading)
        assertEquals("Conservar", f.vm.state.value.draft)
    }
    @Test fun readsHaveAnOverallDeadlineAndLeaveTheModuleRecoverable() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.read = { awaitCancellation() }; f.vm.open(); runCurrent()
        advanceTimeBy(10_001); runCurrent(); assertFalse(f.vm.state.value.loading); assertNotNull(f.vm.state.value.error)
        f.read = { emptyList() }; f.vm.refresh(); advanceUntilIdle(); assertNull(f.vm.state.value.error)
    }
    @Test fun aNewModelRestoresItsSavedDraftWithoutSendingOrCopyingAnotherAccountsDraft() = runTest(dispatcher) {
        val first = Fixture(); first.bind(); advanceUntilIdle(); first.vm.propose(sourceId); first.vm.changeDraft("Volver mañana"); advanceUntilIdle()
        assertTrue(first.vm.state.value.draftSaved)
        val reopened = Fixture(first.persistence); reopened.bind(); advanceUntilIdle()
        assertEquals("Volver mañana", reopened.vm.state.value.draft); assertEquals(sourceId, reopened.vm.state.value.sourceId)
        assertTrue(reopened.commands.isEmpty()); assertFalse(reopened.vm.state.value.open)
        reopened.switch(bob); advanceUntilIdle()
        assertEquals("", reopened.vm.state.value.draft); assertNull(reopened.vm.state.value.sourceId)
        reopened.switch(alice); advanceUntilIdle(); assertEquals("Volver mañana", reopened.vm.state.value.draft)
    }
    @Test fun anUncertainOperationSurvivesANewModelAndRetriesTheExactCommandOnlyWhenRequested() = runTest(dispatcher) {
        val first = Fixture(); first.bind(); advanceUntilIdle(); first.vm.propose(sourceId); first.vm.changeDraft("Propuesta persistente")
        first.send = { throw IOException("ACK lost") }; first.vm.saveDraft(); advanceUntilIdle()
        val original = first.commands.single()
        val reopened = Fixture(first.persistence); reopened.bind(); advanceUntilIdle(); reopened.vm.open(); advanceUntilIdle()
        assertTrue(reopened.commands.isEmpty()); assertTrue(reopened.vm.state.value.retryPending)
        assertEquals("Propuesta persistente", reopened.vm.state.value.draft)
        reopened.vm.retry(); advanceUntilIdle()
        assertEquals(listOf(original), reopened.commands); assertFalse(reopened.vm.state.value.retryPending)
        assertNull(reopened.persistence.rows[alice to chat]?.pending)
        assertNull(reopened.persistence.rows[alice to chat]?.draft)
    }
    @Test fun failureToPersistTheOperationPreventsTheNetworkWriteAndKeepsTheDraft() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("No perder")
        f.persistence.failWrite = true; f.vm.saveDraft(); advanceUntilIdle()
        assertTrue(f.commands.isEmpty()); assertEquals("No perder", f.vm.state.value.draft); assertTrue(f.vm.state.value.retryPending)
        f.persistence.failWrite = false; f.vm.retry(); advanceUntilIdle()
        assertEquals(1, f.commands.size); assertFalse(f.vm.state.value.retryPending)
    }
    @Test fun failureToRetireThePendingRecordAfterAnAcknowledgementKeepsTheSameRecoveryKey() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("No duplicar")
        f.persistence.failCompletion = true; f.vm.saveDraft(); advanceUntilIdle()
        val original = f.commands.single(); assertTrue(f.vm.state.value.retryPending)
        assertEquals(original, f.persistence.rows[alice to chat]?.pending?.command)
        f.persistence.failCompletion = false; f.vm.retry(); advanceUntilIdle()
        assertEquals(listOf(original, original), f.commands)
    }
    @Test fun anUnreadableLocalRecordIsNeverOverwrittenAndCanBeRetried() = runTest(dispatcher) {
        val f = Fixture(); f.persistence.failRead = true; f.bind(); advanceUntilIdle(); f.vm.propose(sourceId)
        assertNull(f.vm.state.value.sourceId); assertTrue(f.vm.state.value.recoveryUnavailable)
        assertTrue(f.persistence.rows.isEmpty()); assertTrue(f.commands.isEmpty())
        f.persistence.failRead = false; f.vm.open(); advanceUntilIdle(); f.vm.propose(sourceId); advanceUntilIdle()
        assertEquals(sourceId, f.vm.state.value.sourceId); assertFalse(f.vm.state.value.recoveryUnavailable)
    }
    @Test fun aStaleScreenCannotOverwriteNewerLocalStateOrSendUntilTheUserRecoversIt() = runTest(dispatcher) {
        val first = Fixture(); first.bind(); advanceUntilIdle(); first.vm.propose(sourceId); first.vm.changeDraft("Primero"); advanceUntilIdle()
        val second = Fixture(first.persistence); second.bind(); advanceUntilIdle(); second.vm.changeDraft("Más reciente"); advanceUntilIdle()
        first.vm.changeDraft("Pantalla antigua"); advanceUntilIdle()
        assertTrue(first.vm.state.value.recoveryConflict); assertEquals("Más reciente", first.persistence.rows[alice to chat]?.draft?.text)
        first.vm.saveDraft(); advanceUntilIdle(); assertTrue(first.commands.isEmpty())
        first.vm.refresh(); advanceUntilIdle(); assertEquals("Pantalla antigua", first.vm.state.value.draft)
        first.vm.recoverSavedState(); advanceUntilIdle()
        assertEquals("Más reciente", first.vm.state.value.draft); assertFalse(first.vm.state.value.recoveryConflict)
    }
    @Test fun proposingAnotherMessageNeverSilentlyReplacesARestoredDraft() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("Conservar este contexto"); advanceUntilIdle()
        f.vm.propose(otherChat); advanceUntilIdle()
        assertEquals(sourceId, f.vm.state.value.sourceId); assertEquals("Conservar este contexto", f.vm.state.value.draft)
        f.vm.dismissEditor(); advanceUntilIdle(); f.vm.propose(otherChat); advanceUntilIdle()
        assertEquals(otherChat, f.vm.state.value.sourceId)
    }
    @Test fun aResponseLostAfterAnAccessChangeRetainsTheOperationForLaterReconciliation() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle()
        f.send = { throw AgreementApiException(403, "AGREEMENT_CHAT_UNAVAILABLE") }; f.vm.respond(agreement(), "accept"); advanceUntilIdle()
        assertTrue(f.vm.state.value.retryPending)
        assertEquals(f.commands.single(), f.persistence.rows[alice to chat]?.pending?.command)
    }
    @Test fun aSavedDraftRemainsEditableOfflineAndReconnectingNeverSendsItAutomatically() = runTest(dispatcher) {
        val first = Fixture(); first.bind(); advanceUntilIdle(); first.vm.propose(sourceId); first.vm.changeDraft("Guardado"); advanceUntilIdle()
        val offline = Fixture(first.persistence); offline.capabilityRead = { throw IOException("server off") }; offline.bind(); advanceUntilIdle()
        assertTrue(offline.vm.state.value.supported); assertFalse(offline.vm.state.value.serverAvailable)
        assertEquals("Guardado", offline.vm.state.value.draft)
        offline.vm.open(); offline.vm.changeDraft("Continué sin conexión"); advanceUntilIdle()
        assertEquals("Continué sin conexión", offline.persistence.rows[alice to chat]?.draft?.text)
        offline.vm.saveDraft(); advanceUntilIdle(); assertTrue(offline.commands.isEmpty())
        offline.capabilityRead = { true }; offline.vm.refresh(); advanceUntilIdle()
        assertTrue(offline.vm.state.value.serverAvailable); assertTrue(offline.commands.isEmpty())
        offline.vm.saveDraft(); advanceUntilIdle(); assertEquals(1, offline.commands.size)
    }
    @Test fun aDisabledBackendCannotClearAnOperationPreviouslySavedBeforeALostAcknowledgement() = runTest(dispatcher) {
        val first = Fixture(); first.bind(); advanceUntilIdle(); first.send = { throw IOException("ACK lost") }
        first.vm.respond(agreement(), "accept"); advanceUntilIdle(); val operation = first.commands.single()
        val offline = Fixture(first.persistence); offline.supported = false; offline.bind(); advanceUntilIdle()
        assertTrue(offline.vm.state.value.supported); assertFalse(offline.vm.state.value.serverAvailable)
        offline.vm.retry(); offline.vm.open(); advanceUntilIdle()
        assertTrue(offline.commands.isEmpty()); assertEquals(operation, offline.persistence.rows[alice to chat]?.pending?.command)
        offline.supported = true; offline.vm.refresh(); advanceUntilIdle(); assertTrue(offline.commands.isEmpty())
        offline.vm.retry(); advanceUntilIdle(); assertEquals(listOf(operation), offline.commands)
    }
    @Test fun returningToTheChatWaitsForADurableDraftAndNeverTreatsBackAsDiscard() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("Guardar al volver")
        f.vm.close(); assertTrue(f.vm.state.value.open); assertTrue(f.vm.state.value.busy)
        advanceUntilIdle(); assertFalse(f.vm.state.value.open); assertTrue(f.vm.state.value.draftSaved)
        assertEquals("Guardar al volver", f.persistence.rows[alice to chat]?.draft?.text)
        f.vm.open(); advanceUntilIdle(); assertEquals("Guardar al volver", f.vm.state.value.draft)
    }
    @Test fun aStorageFailureWhileLeavingKeepsTheEditorVisibleAndItsTextRecoverable() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("Conservar visible")
        f.persistence.failWrite = true; f.vm.close(); advanceUntilIdle()
        assertTrue(f.vm.state.value.open); assertFalse(f.vm.state.value.busy); assertFalse(f.vm.state.value.draftSaved)
        assertEquals("Conservar visible", f.vm.state.value.draft)
        f.persistence.failWrite = false; f.vm.close(); advanceUntilIdle()
        assertFalse(f.vm.state.value.open); assertEquals("Conservar visible", f.persistence.rows[alice to chat]?.draft?.text)
    }
    @Test fun proposingFromAnotherMessageOpensTheExistingPendingOperationInsteadOfDoingNothing() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.send = { throw IOException("lost ACK") }
        f.vm.respond(agreement(), "accept"); advanceUntilIdle(); val original = f.commands.single()
        f.vm.close(); assertFalse(f.vm.state.value.open)
        f.vm.propose(sourceId); advanceUntilIdle()
        assertTrue(f.vm.state.value.open); assertTrue(f.vm.state.value.retryPending)
        assertEquals(listOf(original), f.commands)
        assertEquals(original, f.persistence.rows[alice to chat]?.pending?.command)
    }
    @Test fun anUnsupportedSourceHasAnExplicitVisibleResultWithoutSendingAnything() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose("pending-local-message"); advanceUntilIdle()
        assertTrue(f.vm.state.value.open); assertNotNull(f.vm.state.value.error)
        assertNull(f.vm.state.value.sourceId); assertTrue(f.commands.isEmpty())
    }
    @Test fun anOlderCapabilityKeepsBasicAgreementsWithoutExposingArchiveActions() = runTest(dispatcher) {
        val f = Fixture(); f.archiving = false; f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle()
        assertTrue(f.vm.state.value.serverAvailable); assertFalse(f.vm.state.value.archivingSupported)
        f.vm.selectArchive(true); f.vm.respond(agreement(), "archive"); advanceUntilIdle()
        assertFalse(f.vm.state.value.showArchived); assertTrue(f.commands.isEmpty())
        f.vm.respond(agreement(), "accept"); advanceUntilIdle(); assertEquals("accept", f.commands.single().action)
    }
    @Test fun parkingADraftAndRespondingToAnotherAgreementNeverDeletesIt() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("Seguir mañana")
        f.vm.parkDraft(); advanceUntilIdle()
        assertTrue(f.vm.state.value.open); assertFalse(f.vm.state.value.editorVisible); assertTrue(f.vm.state.value.draftSaved)
        f.vm.respond(agreement(), "accept"); advanceUntilIdle()
        assertEquals("Seguir mañana", f.vm.state.value.draft)
        assertEquals("Seguir mañana", f.persistence.rows[alice to chat]?.draft?.text)
        f.vm.resumeDraft(); advanceUntilIdle(); assertTrue(f.vm.state.value.editorVisible)
        val reopened = Fixture(f.persistence); reopened.bind(); advanceUntilIdle()
        assertEquals("Seguir mañana", reopened.vm.state.value.draft); assertFalse(reopened.vm.state.value.editorVisible)
    }
    @Test fun anUncertainArchiveRestoresItsExactActionAndKeepsTheSeparateDraft() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.propose(sourceId); f.vm.changeDraft("Mi siguiente propuesta")
        f.vm.parkDraft(); advanceUntilIdle(); f.send = { throw IOException("lost archive ACK") }
        f.vm.respond(agreement(), "archive"); advanceUntilIdle()
        val command = f.commands.single(); assertEquals("archive", command.action)
        val reopened = Fixture(f.persistence); reopened.bind(); advanceUntilIdle(); reopened.vm.open(); advanceUntilIdle()
        assertTrue(reopened.commands.isEmpty()); assertEquals("archive", reopened.vm.state.value.pendingAction)
        reopened.rows = listOf(agreement(2).copy(archived = true)); reopened.send = { reopened.rows.single() }
        reopened.vm.retry(); advanceUntilIdle()
        assertEquals(listOf(command), reopened.commands); assertTrue(reopened.vm.state.value.agreements.isEmpty())
        assertEquals("Mi siguiente propuesta", reopened.persistence.rows[alice to chat]?.draft?.text)
        assertNull(reopened.persistence.rows[alice to chat]?.pending)
        assertEquals("Acuerdo archivado en el chat", reopened.vm.state.value.notice?.text)
        reopened.vm.selectArchive(true); advanceUntilIdle(); assertTrue(reopened.vm.state.value.agreements.single().archived)
    }
    @Test fun anUnrecognized404CannotDiscardAWriteThatMayAlreadyBeCommitted() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.send = { throw AgreementApiException(404, "") }
        f.vm.respond(agreement(), "accept"); advanceUntilIdle(); val original = f.commands.single()
        assertTrue(f.vm.state.value.retryPending); assertFalse(f.vm.state.value.serverAvailable)
        assertEquals(original, f.persistence.rows[alice to chat]?.pending?.command)
        f.vm.retry(); advanceUntilIdle(); assertEquals(1, f.commands.size)
        f.supported = false; f.vm.open(); advanceUntilIdle(); assertFalse(f.vm.state.value.serverAvailable)
        f.supported = true; f.send = { agreement(2) }; f.vm.refresh(); advanceUntilIdle()
        assertEquals(1, f.commands.size); f.vm.retry(); advanceUntilIdle(); assertEquals(listOf(original, original), f.commands)
    }
    @Test fun pagingReplacesTheVisiblePageAndAFailurePreservesItsOriginalPosition() = runTest(dispatcher) {
        val f = Fixture(); val cursors = mutableListOf<String?>()
        val second = agreement().copy(id = sourceId, text = "Más antiguo")
        f.pageRead = { _, archived, cursor, _ -> cursors.add(cursor)
            if (cursor == "older") AgreementCatalog(listOf(second), cursor = cursor, previousCursor = "newer", archived = archived)
            else AgreementCatalog(listOf(agreement()), nextCursor = "older", archived = archived)
        }
        f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.page("older"); advanceUntilIdle()
        assertEquals(listOf(second), f.vm.state.value.agreements); assertEquals("older", f.vm.state.value.cursor)
        f.pageRead = { _, _, _, _ -> throw IOException("offline page") }; f.vm.page("newer"); advanceUntilIdle()
        assertEquals(listOf(second), f.vm.state.value.agreements); assertEquals("older", f.vm.state.value.cursor)
        assertEquals("newer", f.vm.state.value.previousCursor); assertNotNull(f.vm.state.value.error)
        f.vm.page("forged"); advanceUntilIdle(); assertEquals(listOf(null, "older"), cursors)
    }
    @Test fun changingFiltersInvalidatesALatePageAndDoesNotMixActiveAndArchivedRows() = runTest(dispatcher) {
        val f = Fixture(); val late = CompletableDeferred<AgreementCatalog>()
        f.pageRead = { _, archived, _, _ ->
            if (!archived) withContext(NonCancellable) { late.await() } else AgreementCatalog(listOf(agreement().copy(archived = true)), archived = true)
        }
        f.bind(); advanceUntilIdle(); f.vm.open(); runCurrent(); f.vm.selectArchive(true); runCurrent()
        assertTrue(f.vm.state.value.showArchived); assertTrue(f.vm.state.value.agreements.single().archived)
        late.complete(AgreementCatalog(listOf(agreement()))); advanceUntilIdle()
        assertTrue(f.vm.state.value.showArchived); assertTrue(f.vm.state.value.agreements.single().archived)
    }
    @Test fun anInvalidCursorCanBeRefreshedFromTheBeginningWithoutAnEndlessRetry() = runTest(dispatcher) {
        val f = Fixture(); val cursors = mutableListOf<String?>()
        f.pageRead = { _, _, cursor, _ -> cursors.add(cursor)
            if (cursor != null) throw AgreementApiException(400, "AGREEMENT_INVALID_CURSOR")
            AgreementCatalog(listOf(agreement()), nextCursor = "older")
        }
        f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.page("older"); advanceUntilIdle()
        assertNull(f.vm.state.value.cursor); assertNull(f.vm.state.value.nextCursor); assertNotNull(f.vm.state.value.error)
        f.vm.refresh(); advanceUntilIdle(); assertEquals(listOf(null, "older", null), cursors); assertNull(f.vm.state.value.error)
    }
    @Test fun anEditorUsesItsFocusedAgreementEvenWhenTheCurrentPageNoLongerContainsIt() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.edit(agreement()); f.vm.changeDraft("Mi revisión")
        f.pageRead = { _, _, _, focus -> assertEquals(agreement().id, focus); AgreementCatalog(emptyList(), focus = agreement(2, "Otro dispositivo")) }
        f.vm.refresh(); advanceUntilIdle(); assertTrue(f.vm.state.value.agreements.isEmpty())
        f.vm.keepDraftAgainstLatestRevision(); f.vm.saveDraft(); advanceUntilIdle()
        assertEquals(2, f.commands.single().expectedVersion); assertEquals("Mi revisión", f.commands.single().text)
    }
    @Test fun anArchivedOrCancelledFocusedAgreementNeverSendsAnEditAndCanPreserveTextForANewProposal() = runTest(dispatcher) {
        val f = Fixture(); f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.edit(agreement()); f.vm.changeDraft("No perder")
        f.pageRead = { _, _, _, _ -> AgreementCatalog(emptyList(), focus = agreement(2).copy(archived = true)) }
        f.vm.refresh(); advanceUntilIdle(); f.vm.saveDraft(); advanceUntilIdle(); assertTrue(f.commands.isEmpty())
        f.pageRead = { _, _, _, _ -> AgreementCatalog(emptyList(), focus = agreement(3).copy(cancelled = true, status = "cancelled")) }
        f.vm.refresh(); advanceUntilIdle(); f.vm.makeNewProposal(); advanceUntilIdle()
        assertNull(f.vm.state.value.editingId); assertEquals("No perder", f.vm.state.value.draft)
        f.vm.saveDraft(); advanceUntilIdle(); assertNull(f.commands.single().action); assertEquals(sourceId, f.commands.single().sourceMessageId)
    }
    @Test fun accessLossClearsBothCachedPagesBeforeAnotherFilterCanDisplayThem() = runTest(dispatcher) {
        val f = Fixture(); f.rows = listOf(agreement(), agreement().copy(id = sourceId, archived = true))
        f.bind(); advanceUntilIdle(); f.vm.open(); advanceUntilIdle(); f.vm.selectArchive(true); advanceUntilIdle()
        assertTrue(f.vm.state.value.agreements.isNotEmpty())
        f.read = { throw AgreementApiException(403, "AGREEMENT_ACCESS_DENIED") }; f.vm.selectArchive(false); advanceUntilIdle()
        assertTrue(f.vm.state.value.agreements.isEmpty()); f.vm.selectArchive(true)
        assertTrue(f.vm.state.value.agreements.isEmpty()); advanceUntilIdle()
    }
}
