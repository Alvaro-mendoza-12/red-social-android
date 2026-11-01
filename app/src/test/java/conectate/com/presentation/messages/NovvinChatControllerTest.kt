package conectate.com.presentation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NovvinChatControllerTest {
    private class Fixture(val scope: TestScope) {
        var owner: String? = "a"
        var sequence = 0
        val disk = mutableMapOf<String, List<NovvinConversation>>()
        val writes = mutableListOf<Pair<String, List<NovvinConversation>>>()
        val requests = mutableListOf<List<String>>()
        var load: suspend (String) -> List<NovvinConversation> = { disk[it].orEmpty() }
        var save: suspend (String, List<NovvinConversation>) -> Unit = { owner, snapshot -> disk[owner] = snapshot }
        var ask: suspend (String, String, String, String) -> NovvinReply = { _, _, _, _ -> NovvinReply("Respuesta") }
        fun conversation(id: String, text: String = "Bienvenido") = NovvinConversation(id, id, 1, 1,
            listOf(NovvinMessage(id = "$id-welcome", text = text, fromNovvin = true)))
        fun controller() = NovvinChatController(scope.backgroundScope, { owner },
            { load(it) }, { owner, snapshot -> save(owner, snapshot); writes += owner to snapshot },
            { owner, id, message, history -> requests += listOf(owner, id, message, history); ask(owner, id, message, history) },
            newConversation = { conversation("new-${++sequence}") }, readTimeoutMillis = 100, requestTimeoutMillis = 200,
        )
    }

    @Test fun historyMustLoadBeforeAnyActionCanOverwriteIt() = runTest {
        val f = Fixture(this)
        val loaded = CompletableDeferred<List<NovvinConversation>>()
        f.load = { loaded.await() }
        val c = f.controller()
        c.bindAccount(); runCurrent()
        assertTrue(c.state.value.historyLoading)
        c.startNewChat(); c.deleteConversation("saved"); c.selectConversation("saved")
        assertFalse(c.send("Do not lose saved chats"))
        assertTrue(f.writes.isEmpty())
        loaded.complete(listOf(f.conversation("saved"))); runCurrent()
        assertEquals("saved", c.state.value.currentConversationId)
        assertFalse(c.state.value.historyLoading)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun aFailedHistoryReadPreservesDiskAndAllowsRetry() = runTest {
        val f = Fixture(this)
        f.disk["a"] = listOf(f.conversation("saved"))
        f.load = { error("storage temporarily unavailable") }
        val c = f.controller(); c.bindAccount(); runCurrent()
        assertNotNull(c.state.value.historyError)
        assertFalse(c.send("must not overwrite"))
        assertTrue(f.writes.isEmpty())
        f.load = { f.disk[it].orEmpty() }
        c.retryHistory(); runCurrent()
        assertEquals("saved", c.state.value.currentConversationId)
        assertNull(c.state.value.historyError)
    }

    @Test fun outgoingHistoryIsHiddenBeforeTheAccountCollectorRunsAndCannotReturnLate() = runTest {
        val f = Fixture(this)
        val late = CompletableDeferred<List<NovvinConversation>>()
        f.load = { if (it == "a") withContext(NonCancellable) { late.await() } else listOf(f.conversation("b-chat")) }
        val c = f.controller(); c.bindAccount(); runCurrent()
        f.owner = "b"
        assertTrue(c.state.value.forAccount("b").messages.isEmpty())
        c.bindAccount(); runCurrent()
        late.complete(listOf(f.conversation("a-private"))); runCurrent()
        assertEquals("b", c.state.value.accountId)
        assertEquals("b-chat", c.state.value.currentConversationId)
    }

    @Test fun aLateAnswerCannotReachAnotherAccountOrItsHistoryFile() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<NovvinReply>()
        f.ask = { _, _, _, _ -> withContext(NonCancellable) { answer.await() } }
        val c = f.controller(); c.bindAccount(); runCurrent()
        assertTrue(c.send("private A")); runCurrent()
        assertEquals("a", f.requests.single()[0])
        f.owner = "b"; c.bindAccount(); runCurrent()
        answer.complete(NovvinReply("private answer A")); runCurrent()
        assertFalse(c.state.value.messages.any { it.text.contains("private") })
        assertFalse(f.disk["b"].orEmpty().flatMap { it.messages }.any { it.text.contains("private") })
        assertFalse(f.writes.any { (owner, items) -> owner == "b" && items.any { it.messages.any { it.text.contains("private") } } })
    }

    @Test fun deletingAConversationInvalidatesItsPendingAnswer() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<NovvinReply>()
        f.ask = { _, _, _, _ -> withContext(NonCancellable) { answer.await() } }
        val c = f.controller(); c.bindAccount(); runCurrent()
        val deleted = c.state.value.currentConversationId
        c.send("Question"); runCurrent()
        c.deleteConversation(deleted); runCurrent()
        answer.complete(NovvinReply("obsolete")); runCurrent()
        assertTrue(c.state.value.conversations.none { it.id == deleted })
        assertTrue(c.state.value.messages.none { it.text == "obsolete" })
        assertFalse(c.state.value.isThinking)
    }

    @Test fun repliesAreAttachedToTheirOriginalConversationWhenBrowsingHistory() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<NovvinReply>()
        f.ask = { _, _, _, _ -> answer.await() }
        val c = f.controller(); c.bindAccount(); runCurrent()
        val first = c.state.value.currentConversationId
        c.send("Question"); runCurrent()
        c.startNewChat(); runCurrent()
        val second = c.state.value.currentConversationId
        assertFalse(c.state.value.isThinking)
        answer.complete(NovvinReply("Answer for the first chat")); runCurrent()
        assertEquals(second, c.state.value.currentConversationId)
        assertFalse(c.state.value.messages.any { it.text.contains("first chat") })
        c.selectConversation(first)
        assertTrue(c.state.value.messages.any { it.text.contains("first chat") })
    }

    @Test fun aNewQuestionInAnotherChatInvalidatesTheOldReplyAndKeepsItsRetry() = runTest {
        val f = Fixture(this)
        val late = CompletableDeferred<NovvinReply>()
        f.ask = { _, _, message, _ -> if (message == "First") withContext(NonCancellable) { late.await() } else NovvinReply("Second answer") }
        val c = f.controller(); c.bindAccount(); runCurrent()
        val first = c.state.value.currentConversationId
        c.send("First"); runCurrent()
        c.startNewChat(); c.send("Second"); runCurrent()
        late.complete(NovvinReply("obsolete first")); runCurrent()
        assertTrue(c.state.value.messages.any { it.text == "Second answer" })
        c.selectConversation(first)
        assertTrue(c.state.value.canRetry)
        assertFalse(c.state.value.messages.any { it.text == "obsolete first" })
    }

    @Test fun timeoutUnblocksTheComposerAndRetryDoesNotDuplicateTheQuestion() = runTest {
        val f = Fixture(this)
        f.ask = { _, _, _, _ -> awaitCancellation() }
        val c = f.controller(); c.bindAccount(); runCurrent()
        c.send("Question"); runCurrent(); advanceTimeBy(201); runCurrent()
        assertFalse(c.state.value.isThinking)
        assertTrue(c.state.value.canRetry)
        assertNotNull(c.state.value.errorMessage)
        f.ask = { _, _, _, _ -> NovvinReply("Answer") }
        c.retryLastMessage(); runCurrent()
        assertEquals(1, c.state.value.messages.count { !it.fromNovvin })
        assertTrue(c.state.value.messages.any { it.text == "Answer" })
        assertFalse(c.state.value.canRetry)
    }

    @Test fun aBlankAnswerIsAFailureInsteadOfASyntheticSuccessfulReply() = runTest {
        val f = Fixture(this).apply { ask = { _, _, _, _ -> NovvinReply(" ") } }
        val c = f.controller(); c.bindAccount(); runCurrent(); c.send("Question"); runCurrent()
        assertTrue(c.state.value.canRetry)
        assertEquals(2, c.state.value.messages.size)
    }

    @Test fun persistenceFailuresKeepTheLiveConversationAndCanSaveTheLatestSnapshotAgain() = runTest {
        val f = Fixture(this).apply { save = { _, _ -> error("disk unavailable") } }
        val c = f.controller(); c.bindAccount(); runCurrent(); c.send("Question"); runCurrent()
        assertNotNull(c.state.value.saveError)
        assertTrue(c.state.value.messages.any { it.text == "Respuesta" })
        f.save = { owner, snapshot -> f.disk[owner] = snapshot }
        c.retrySave(); runCurrent()
        assertNull(c.state.value.saveError)
        assertEquals(c.state.value.conversations, f.disk["a"])
    }

    @Test fun logoutClearsTheVisibleHistoryAndRejectsNewActions() = runTest {
        val f = Fixture(this); val c = f.controller(); c.bindAccount(); runCurrent(); c.send("private"); runCurrent()
        f.owner = null
        assertTrue(c.state.value.forAccount(null).messages.isEmpty())
        c.bindAccount(); runCurrent()
        assertTrue(c.state.value.messages.isEmpty())
        assertFalse(c.send("must not send"))
        assertFalse(c.state.value.historyLoading)
    }

    @Test fun invalidAndDuplicatedStoredIdsCannotProduceDuplicateUiKeys() = runTest {
        val f = Fixture(this)
        val original = f.conversation("same")
        f.disk["a"] = listOf(original.copy(messages = original.messages + original.messages), original, f.conversation(""))
        val c = f.controller(); c.bindAccount(); runCurrent()
        assertEquals(1, c.state.value.conversations.size)
        assertEquals(1, c.state.value.messages.size)
    }

    @Test fun anUnansweredQuestionSurvivesRestartAndCanBeRetriedWithoutDuplicatingIt() = runTest {
        val f = Fixture(this)
        val saved = f.conversation("saved")
        f.disk["a"] = listOf(saved.copy(messages = saved.messages + NovvinMessage(text = "Pending question", fromNovvin = false)))
        val c = f.controller(); c.bindAccount(); runCurrent()
        assertTrue(c.state.value.canRetry)
        c.retryLastMessage(); runCurrent()
        assertEquals(1, c.state.value.messages.count { it.text == "Pending question" })
        assertTrue(c.state.value.messages.any { it.text == "Respuesta" })
        assertFalse(c.state.value.canRetry)
    }

    @Test fun aHistoryReadDeadlineKeepsActionsClosedUntilARetryLoadsConfirmedHistory() = runTest {
        val f = Fixture(this).apply { load = { awaitCancellation() } }
        val c = f.controller(); c.bindAccount(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertNotNull(c.state.value.historyError)
        assertFalse(c.state.value.historyLoading)
        c.startNewChat()
        assertTrue(c.state.value.conversations.isEmpty())
        assertTrue(f.writes.isEmpty())
        f.load = { listOf(f.conversation("confirmed")) }
        c.retryHistory(); runCurrent()
        assertEquals("confirmed", c.state.value.currentConversationId)
    }

    @Test fun anOldDiskFailureDoesNotPolluteTheNewAccountAndItsLatestSnapshotIsSaved() = runTest {
        val f = Fixture(this)
        val finishOldSave = CompletableDeferred<Unit>()
        f.save = { owner, snapshot ->
            if (owner == "a") withContext(NonCancellable) { finishOldSave.await(); error("old account disk failure") }
            else f.disk[owner] = snapshot
        }
        val c = f.controller(); c.bindAccount(); runCurrent(); c.send("private A"); runCurrent()
        f.owner = "b"; c.bindAccount(); runCurrent(); c.send("private B"); runCurrent()
        finishOldSave.complete(Unit); runCurrent()
        assertEquals("b", c.state.value.accountId)
        assertNull(c.state.value.saveError)
        assertEquals(c.state.value.conversations, f.disk["b"])
        assertFalse(f.disk["b"].orEmpty().flatMap { it.messages }.any { it.text == "private A" })
    }
}
