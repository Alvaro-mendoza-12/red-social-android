package conectate.com.data.repository

import android.app.Application
import conectate.com.core.media.InteractiveMediaGate
import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallHistoryEntry
import conectate.com.data.model.CallHistoryStatus
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CallOutcomePersistenceTest {
    private class Fixture(scope: TestScope) {
        private val guard = AuthSessionMutationGuard()
        private var accountId = "account-a"
        val lease = guard.captureAccountLease { accountId }
        val messages = mockk<MessageRepository>(relaxed = true)
        val groups = mockk<GroupRepository>(relaxed = true)
        val communities = mockk<CommunityRepository>(relaxed = true)
        val entries = mutableListOf<Pair<CallHistoryEntry, String?>>()
        private val history = mockk<CallHistoryRepository>()
        private val auth = mockk<AuthService>()
        private val repository = CallRepository(
            RuntimeEnvironment.getApplication(), mockk(), auth, mockk(), messages, groups,
            communities, mockk(), history, mockk(relaxed = true), mockk(), mockk(),
            mockk(), InteractiveMediaGate(),
        )

        init {
            every { auth.getCurrentUserId() } answers { accountId }
            coEvery { history.record(any(), any()) } coAnswers {
                entries += firstArg<CallHistoryEntry>() to secondArg<String?>()
            }
            coEvery { messages.getOrCreateChat(any<String>()) } returns Result.failure(IllegalStateException("offline"))
            coEvery { messages.getOrCreateChat(any(), any()) } returns Result.failure(IllegalStateException("offline"))
            ReflectionHelpers.setField(repository, "scope", scope)
        }

        fun switchTo(account: String) = guard.invalidate { accountId = account }

        fun close(
            state: CallConnectionState = CallConnectionState.CONNECTED,
            startedAt: Long? = System.currentTimeMillis() - 65_000,
            contextType: String = "dm",
            reason: String = "Llamada finalizada",
            beforeEnd: () -> Unit = {},
        ) {
            val call = ActiveCall(
                callId = "call-a", title = "Llamada QA", initiatorUserId = "account-a",
                connectionState = state, startedAtEpochMs = startedAt, contextType = contextType,
                contextId = "conversation-a", peerUserId = "peer-a",
            )
            ReflectionHelpers.setField(repository, "_activeCall", MutableStateFlow<ActiveCall?>(call))
            ReflectionHelpers.setField(repository, "notificationVisit", CallNotificationVisit.capture(call.callId, lease))
            beforeEnd()
            end(reason)
        }

        fun end(reason: String = "Llamada finalizada") {
            ReflectionHelpers.callInstanceMethod<Unit>(repository, "endCallInternal",
                ClassParameter.from(String::class.java, reason),
                ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        }

        fun verifyNoPublicSend() {
            coVerify(exactly = 0) { messages.sendMessage(chatId = any(), content = any(), mediaType = "call", onQueued = any()) }
            coVerify(exactly = 0) { groups.sendGroupMessage(groupId = any(), content = any(), contentType = "call", onQueued = any()) }
            coVerify(exactly = 0) { communities.sendCommunityMessage(communityId = any(), content = any(), contentType = "call", onQueued = any()) }
        }
    }

    @Test fun endingDuringReconnectionPreservesTheAnsweredOutcomeAndDuration() = runTest {
        val fixture = Fixture(this)
        fixture.close(state = CallConnectionState.RECONNECTING)
        runCurrent()
        val entry = fixture.entries.single().first
        assertEquals(CallHistoryStatus.ANSWERED, entry.status)
        assertEquals("Duración 01:05", entry.subtitle)
        assertEquals("Llamada completada • 01:05", entry.statusLabel)
    }

    @Test fun anUnansweredCallKeepsItsOriginalOutcome() = runTest {
        val fixture = Fixture(this)
        fixture.close(state = CallConnectionState.CONNECTING, startedAt = null, reason = "Llamada no contestada")
        runCurrent()
        val entry = fixture.entries.single().first
        assertEquals(CallHistoryStatus.NO_ANSWER, entry.status)
        assertEquals("Llamada no contestada", entry.statusLabel)
    }

    @Test fun lookupLatencyDoesNotExtendTheCallOrMoveItsHistoryTimestamp() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val lookup: suspend () -> Unit = { ready.await() }
        coEvery { fixture.messages.getOrCreateChat(any<String>()) } coAnswers {
            lookup()
            Result.failure(IllegalStateException("offline"))
        }
        coEvery { fixture.messages.getOrCreateChat(any(), any()) } coAnswers {
            lookup()
            Result.failure(IllegalStateException("offline"))
        }
        val beforeClose = System.currentTimeMillis()
        val startedAt = beforeClose - 65_000
        fixture.close(startedAt = startedAt)
        val afterClose = System.currentTimeMillis()
        runCurrent()
        Thread.sleep(1_100)
        ready.complete(Unit)
        runCurrent()
        val entry = fixture.entries.single().first
        assertTrue("The history timestamp must be captured at hangup",
            entry.timestampEpochMs in beforeClose..afterClose)
        val elapsedSeconds = (entry.timestampEpochMs - startedAt) / 1000
        assertEquals("Duración %02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60), entry.subtitle)
    }

    @Test fun switchingBeforeHistoryRunsKeepsTheOriginalOwnerWithoutOpeningADirectChat() = runTest {
        val fixture = Fixture(this)
        fixture.close()
        fixture.switchTo("account-b")
        runCurrent()
        assertEquals("account-a", fixture.entries.single().second)
        coVerify(exactly = 0) { fixture.messages.getOrCreateChat(any<String>()) }
        coVerify(exactly = 0) { fixture.messages.getOrCreateChat(any(), any()) }
        fixture.verifyNoPublicSend()
    }

    @Test fun changingAccountsBeforeHangingUpCannotStoreHistoryUnderTheNewAccount() = runTest {
        val fixture = Fixture(this)
        fixture.close(beforeEnd = { fixture.switchTo("account-b") })
        runCurrent()
        assertEquals("account-a", fixture.entries.single().second)
        coVerify(exactly = 0) { fixture.messages.getOrCreateChat(any<String>()) }
        coVerify(exactly = 0) { fixture.messages.getOrCreateChat(any(), any()) }
    }

    @Test fun changingVisitsDuringDirectChatLookupKeepsLocalHistoryAndSkipsTheChatMessage() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        coEvery { fixture.messages.getOrCreateChat(any<String>()) } coAnswers {
            ready.await()
            Result.failure(IllegalStateException("offline"))
        }
        coEvery { fixture.messages.getOrCreateChat(fixture.lease, "peer-a") } coAnswers {
            ready.await()
            throw CancellationException("Session changed")
        }
        fixture.close()
        runCurrent()
        fixture.switchTo("account-b")
        fixture.switchTo("account-a")
        ready.complete(Unit)
        runCurrent()
        assertEquals("conversation-a", fixture.entries.single().first.contextId)
        assertEquals("account-a", fixture.entries.single().second)
        fixture.verifyNoPublicSend()
    }

    @Test fun aCallClosedDuringTheSameVisitSendsTheSummaryWithItsAccountLease() = runTest {
        val fixture = Fixture(this)
        fixture.close()
        runCurrent()
        assertEquals("account-a", fixture.entries.single().second)
        coVerify(exactly = 1) { fixture.messages.getOrCreateChat(fixture.lease, "peer-a") }
        coVerify(exactly = 1) { fixture.messages.sendMessage(fixture.lease, "conversation-a", any(), mediaType = "call", onQueued = any()) }
        fixture.verifyNoPublicSend()
    }

    @Test fun aGroupCallCannotPostAfterReturningToTheSameAccount() = checkCollectiveAccountChange("group")

    @Test fun aCommunityCallCannotPostAfterReturningToTheSameAccount() = checkCollectiveAccountChange("community")

    private fun checkCollectiveAccountChange(contextType: String) = runTest {
        val fixture = Fixture(this)
        fixture.close(contextType = contextType)
        fixture.switchTo("account-b")
        fixture.switchTo("account-a")
        runCurrent()
        assertEquals("account-a", fixture.entries.single().second)
        fixture.verifyNoPublicSend()
        coVerify(exactly = 0) {
            fixture.groups.sendGroupMessage(accountLease = any(), groupId = any(), content = any(),
                contentType = "call", onQueued = any())
        }
        coVerify(exactly = 0) {
            fixture.communities.sendCommunityMessage(accountLease = any(), communityId = any(), content = any(),
                contentType = "call", onQueued = any())
        }
    }
}
