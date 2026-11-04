package conectate.com.presentation.purpose

import androidx.lifecycle.ViewModelStore
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurposeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val post = "33333333-3333-4333-8333-333333333333"
    private val plan = "44444444-4444-4444-8444-444444444444"
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.clear(); Dispatchers.resetMain() }
    private fun social(revision: Int = 1,text: String = "Origen") = buildJsonObject {
        put("postId",post); put("postText",text); put("canEdit",true)
        put("outcome",buildJsonObject { put("postId",post); put("authorId",a); put("status","open"); put("revision",revision); put("updatedAt","2026-11-04T22:00:00Z") })
    }
    private fun proposal() = purposeJson.encodeToJsonElement(GamingProposalContext(listOf(PurposePlayer(a,"Ana"),PurposePlayer(b,"Bruno")),listOf(PurposeGame("chess","Ajedrez")))).jsonObject
    private fun plan(id: String = plan,revision: Int = 1) = purposeJson.encodeToJsonElement(GamingPlanReceipt(GamingPlan(id,a,listOf(PurposePlayer(a,"Ana"),PurposePlayer(b,"Bruno")),"chess","Ajedrez","Practicar","2026-11-05T00:00:00Z",45,revision,1,status = "pending",serverTime = "2026-11-04T22:00:00Z"))).jsonObject
    private inner class Memory {
        val rows = mutableMapOf<Pair<String?,String>,PurposeRecovery>()
        var fail = false
        suspend fun read(lease: AuthSessionLease,target: PurposeTarget): PurposeRecovery { lease.requireCurrent(); return rows[lease.accountId to target.key] ?: PurposeRecovery() }
        suspend fun write(lease: AuthSessionLease,target: PurposeTarget,expected: PurposeRecovery,next: PurposeRecovery): PurposeRecovery {
            lease.requireCurrent(); if(fail) throw IOException("storage failure")
            val key = lease.accountId to target.key
            require((rows[key] ?: PurposeRecovery()) == expected)
            return next.copy(revision = expected.revision+1).also { rows[key] = it }
        }
    }
    private inner class Fixture(val memory: Memory = Memory()) {
        val guard = AuthSessionMutationGuard()
        val changes = MutableStateFlow(0)
        val events = MutableSharedFlow<Pair<String,String>>(extraBufferCapacity = 4)
        var owner: String? = a
        var supported = true
        var reader: suspend (AuthSessionLease,PurposeTarget) -> JsonObject = { lease,target -> when(target.kind) { "proposal" -> proposal(); "plan" -> plan(checkNotNull(target.id)); else -> social(text = checkNotNull(lease.accountId)) } }
        var sender: suspend (PurposePending) -> JsonObject = { social(2) }
        val commands = mutableListOf<PurposePending>()
        var reads = 0
        val vm = PurposeViewModel({guard.captureAccountLease { owner }},changes,events,{supported},
            { lease,target -> reads++; reader(lease,target) },{ _,pending -> commands += pending; sender(pending) },memory::read,memory::write)
            .also { models.put(java.util.UUID.randomUUID().toString(),it) }
    }
    @Test fun lostAcknowledgementKeepsTheCommandAndAnExplicitRetryReusesItsOperationAfterRecreation() = runTest(dispatcher) {
        val first = Fixture(); val target = PurposeTarget("social",post)
        first.vm.open(target); advanceUntilIdle()
        first.vm.draft(buildJsonObject { put("conclusion","Sí funcionó"); put("contributionIds",JsonArray(emptyList())) }); advanceUntilIdle()
        first.sender = { throw IOException("ACK lost") }
        first.vm.act("conclude",checkNotNull(first.vm.state.value.draft)); advanceUntilIdle()
        assertTrue(first.vm.state.value.pending)
        val original = first.commands.single()
        var closed = false; first.vm.close { closed = true }; advanceUntilIdle(); assertTrue(closed)
        val reopened = Fixture(first.memory); reopened.vm.open(target); advanceUntilIdle()
        assertTrue(reopened.vm.state.value.pending); assertEquals(0,reopened.commands.size)
        reopened.vm.retry(); advanceUntilIdle()
        assertEquals(original,reopened.commands.single()); assertFalse(reopened.vm.state.value.pending); assertNull(reopened.vm.state.value.draft)
    }
    @Test fun aNewProposalRestoresTheOriginalPlanIdInsteadOfCreatingAnotherAfterProcessLoss() = runTest(dispatcher) {
        val first = Fixture(); val target = PurposeTarget("proposal",b,plan)
        first.vm.open(target); advanceUntilIdle(); first.sender = { throw IOException("ACK lost") }
        first.vm.act("propose",buildJsonObject { put("gameId","chess"); put("objective","Practicar"); put("startsAt","2026-11-05T00:00:00Z"); put("durationMinutes",45) }); advanceUntilIdle()
        val original = first.commands.single()
        val reopened = Fixture(first.memory)
        reopened.vm.open(PurposeTarget("proposal",b,"55555555-5555-4555-8555-555555555555")); advanceUntilIdle()
        assertEquals(plan,reopened.vm.state.value.target?.relatedId)
        reopened.sender = { plan() }; reopened.vm.retry(); advanceUntilIdle()
        assertEquals(original,reopened.commands.single()); assertEquals(PurposeTarget("plan",plan),reopened.vm.state.value.target)
    }
    @Test fun storageFailurePreventsTheHttpWriteAndKeepsTheVisibleDraft() = runTest(dispatcher) {
        val fixture = Fixture(); fixture.vm.open(PurposeTarget("social",post)); advanceUntilIdle()
        val draft = buildJsonObject { put("conclusion","Guardar esto") }
        fixture.vm.draft(draft); advanceUntilIdle(); fixture.memory.fail = true
        fixture.vm.act("conclude",draft); advanceUntilIdle()
        assertEquals(0,fixture.commands.size); assertEquals(draft,fixture.vm.state.value.draft); assertFalse(fixture.vm.state.value.recoveryReady)
    }
    @Test fun aResponseFromThePreviousAccountCannotReplaceTheNewAccountsStateOrRecovery() = runTest(dispatcher) {
        val fixture = Fixture(); val delayed = CompletableDeferred<JsonObject>()
        fixture.vm.open(PurposeTarget("social",post)); advanceUntilIdle()
        fixture.sender = { withContext(NonCancellable) { delayed.await() } }
        fixture.vm.act("start"); runCurrent()
        fixture.owner = b; fixture.changes.value++; runCurrent()
        delayed.complete(social(8,"Old account")); advanceUntilIdle()
        assertEquals(b,fixture.vm.state.value.accountId)
        assertEquals(b,fixture.vm.state.value.payload?.get("postText")?.jsonPrimitive?.content)
        assertTrue(fixture.memory.rows.entries.filter { it.key.first == b }.all { it.value.pending == null })
    }
    @Test fun aConflictClearsOnlyTheUnappliedCommandAndAnUnrelatedResponseKeepsTheDraft() = runTest(dispatcher) {
        val fixture = Fixture(); fixture.vm.open(PurposeTarget("plan",plan)); advanceUntilIdle()
        val draft = buildJsonObject { put("objective","Otra duración") }
        fixture.vm.draft(draft); advanceUntilIdle()
        fixture.sender = { throw PurposeApiException(409,"PURPOSE_REVISION_CONFLICT") }
        fixture.vm.act("accept"); advanceUntilIdle()
        assertFalse(fixture.vm.state.value.pending); assertEquals(draft,fixture.vm.state.value.draft)
        fixture.sender = { plan(revision = 2) }; fixture.vm.refresh(); advanceUntilIdle(); fixture.vm.act("accept"); advanceUntilIdle()
        assertEquals(draft,fixture.vm.state.value.draft); assertEquals(draft,fixture.memory.rows.values.last().draft)
    }
    @Test fun unsupportedServersDoNotReadOrWriteTheFeatureAndClosingFlushesTheLastDraft() = runTest(dispatcher) {
        val fixture = Fixture(); fixture.supported = false
        fixture.vm.open(PurposeTarget("social",post)); advanceUntilIdle(); fixture.vm.act("start"); advanceUntilIdle()
        assertEquals(0,fixture.reads); assertEquals(0,fixture.commands.size)
        fixture.supported = true; fixture.vm.refresh(); advanceUntilIdle()
        val draft = buildJsonObject { put("conclusion","Último cambio") }
        fixture.vm.draft(draft)
        var closed = false; fixture.vm.close { closed = true }; advanceUntilIdle()
        assertTrue(closed); assertEquals(draft,fixture.memory.rows.values.last().draft)
    }
}
