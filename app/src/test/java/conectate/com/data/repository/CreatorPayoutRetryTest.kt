package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.model.*
import conectate.com.data.payout.CreatorPayoutApiClient
import conectate.com.data.payout.CreatorPayoutApiException
import conectate.com.data.payout.CreatorPayoutRetryStore
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Simulates Android commit(): memory can change even if the disk write fails. */
private class PayoutDisk(private val persisted: MutableMap<String, String> = mutableMapOf()) {
    val memory = persisted.toMutableMap()
    var failedCommits = 0
    var failRemove = false
    var beforeRemove: () -> Unit = {}
    val context = mockk<Context>()
    init {
        val preferences = mockk<SharedPreferences>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getString(any(), any()) } answers { memory[firstArg<String>()] }
        every { preferences.edit() } answers {
            val updates = mutableMapOf<String, String?>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers { updates[firstArg()] = secondArg(); editor }
            every { editor.remove(any()) } answers { updates[firstArg()] = null; editor }
            every { editor.commit() } answers {
                if (updates.values.any { it == null }) beforeRemove()
                for ((key, value) in updates) if (value == null) memory.remove(key) else memory[key] = value
                val failed = failedCommits > 0 || (failRemove && updates.values.any { it == null })
                if (failedCommits > 0) failedCommits--
                if (!failed) { persisted.clear(); persisted.putAll(memory) }
                !failed
            }
            editor
        }
    }
    fun restart() = PayoutDisk(persisted)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreatorPayoutRetryTest {
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val auth = mockk<AuthService>()
    private val api = mockk<CreatorPayoutApiClient>()
    private val keys = mutableListOf<String>()
    private val replies = ArrayDeque<Result<CreatorPayoutRequestResponse>>()
    init {
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("test-token")
        coEvery { api.requestPayout(any(), any(), any()) } coAnswers {
            keys += thirdArg<String>()
            if (replies.isEmpty()) confirmed() else replies.removeFirst()
        }
    }
    private fun confirmed() = Result.success(CreatorPayoutRequestResponse(CreatorPayoutRequest("request", 100, "USD", "PENDING")))
    private fun repository(disk: PayoutDisk) = CreatorPayoutRepository(auth, api, CreatorPayoutRetryStore(disk.context))

    @Test fun aLostConfirmationReusesItsKeyAcrossAProcessRestart() = runTest {
        val disk = PayoutDisk()
        replies += Result.failure(IOException("response lost"))
        assertTrue(repository(disk).request(100).isFailure)
        assertTrue(repository(disk.restart()).request(100).isSuccess)
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
    }

    @Test fun aConfirmedRequestAllowsANewIntentWithANewKey() = runTest {
        val repository = repository(PayoutDisk())
        assertTrue(repository.request(100).isSuccess)
        assertTrue(repository.request(100).isSuccess)
        assertNotEquals(keys[0], keys[1])
    }

    @Test fun anUnknownOutcomeBlocksAChangedAmountUntilTheOriginalIsReconciled() = runTest {
        val repository = repository(PayoutDisk())
        replies += Result.failure(IOException("response lost"))
        repository.request(100)
        val changed = repository.request(200)
        assertEquals("PAYOUT_PREVIOUS_ATTEMPT_UNCONFIRMED", changed.exceptionOrNull()?.message)
        assertEquals(1, keys.size)
        assertTrue(repository.request(100).isSuccess)
        assertTrue(repository.request(200).isSuccess)
        assertEquals(keys[0], keys[1])
        assertNotEquals(keys[1], keys[2])
    }

    @Test fun failedDurabilityNeverSendsEvenIfPreferencesAlreadyChangedInMemory() = runTest {
        val disk = PayoutDisk()
        val repository = repository(disk)
        disk.failedCommits = 1
        assertTrue(repository.request(100).isFailure)
        assertFalse(disk.memory.isEmpty())
        disk.failedCommits = 1
        assertTrue(repository.request(100).isFailure)
        assertTrue(keys.isEmpty())
        assertTrue(repository.request(100).isSuccess)
        assertEquals(1, keys.size)
    }

    @Test fun failedCleanupPreservesConfirmationAndTheRetryKey() = runTest {
        val disk = PayoutDisk()
        val repository = repository(disk)
        disk.failRemove = true
        val response = repository.request(100).getOrThrow()
        assertTrue(response.localCleanupPending)
        assertFalse(disk.memory.isEmpty())
        disk.failRemove = false
        assertTrue(repository.request(100).isSuccess)
        assertEquals(keys[0], keys[1])
    }

    @Test fun anExplicitBusinessRejectionAllowsCorrectingTheAmount() = runTest {
        val repository = repository(PayoutDisk())
        replies += Result.failure(CreatorPayoutApiException("PAYOUT_MINIMUM_NOT_REACHED", 400))
        assertTrue(repository.request(100).isFailure)
        assertTrue(repository.request(200).isSuccess)
        assertNotEquals(keys[0], keys[1])
    }

    @Test fun unreadablePendingDataDoesNotSendOrDeleteIt() = runTest {
        val disk = PayoutDisk()
        disk.memory["pending:a"] = "unreadable"
        assertTrue(repository(disk).request(100).isFailure)
        assertEquals("unreadable", disk.memory["pending:a"])
        assertTrue(keys.isEmpty())
    }

    @Test fun differentAccountsHaveIndependentRetryKeys() = runTest {
        val disk = PayoutDisk()
        val repository = repository(disk)
        replies += Result.failure(IOException("A unknown"))
        repository.request(100)
        guard.invalidate { account = "b" }
        repository.request(200)
        guard.invalidate { account = "a" }
        repository.request(100)
        assertNotEquals(keys[0], keys[1])
        assertEquals(keys[0], keys[2])
    }

    @Test fun cancellationAfterDispatchPreservesTheDurableAttempt() = runTest {
        val disk = PayoutDisk()
        coEvery { api.requestPayout(any(), any(), any()) } coAnswers {
            keys += thirdArg<String>(); throw CancellationException("response interrupted")
        }
        try { repository(disk).request(100); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        coEvery { api.requestPayout(any(), any(), any()) } coAnswers { keys += thirdArg<String>(); confirmed() }
        repository(disk.restart()).request(100).getOrThrow()
        assertEquals(keys[0], keys[1])
    }

    @Test fun anAccountChangeDuringLocalCleanupCannotReturnTheOldResponse() = runTest {
        val disk = PayoutDisk()
        disk.beforeRemove = { guard.invalidate { account = "b" } }
        try {
            repository(disk).request(100).getOrThrow()
            fail("The response must still belong to its initiating visit after disk cleanup")
        } catch (_: CancellationException) { }
    }

    @Test fun aTemporaryServerGateDoesNotDiscardAnOlderUnknownAttempt() = runTest {
        val repository = repository(PayoutDisk())
        replies += Result.failure(IOException("response lost"))
        replies += Result.failure(CreatorPayoutApiException("CREATOR_PAYOUTS_DISABLED", 503))
        assertTrue(repository.request(100).isFailure)
        assertTrue(repository.request(100).isFailure)
        assertTrue(repository.request(100).isSuccess)
        assertEquals(3, keys.size)
        assertEquals(keys[0], keys[1])
        assertEquals(keys[0], keys[2])
    }

    @Test fun aKnownRejectionCanBeClearedAfterStorageRecoversSoTheAmountCanBeCorrected() = runTest {
        val disk = PayoutDisk()
        val repository = repository(disk)
        disk.failRemove = true
        replies += Result.failure(CreatorPayoutApiException("PAYOUT_MINIMUM_NOT_REACHED", 409))
        assertTrue(repository.request(100).isFailure)
        assertTrue(repository.request(200).isFailure)
        assertEquals(1, keys.size)
        disk.failRemove = false
        assertTrue(repository.request(200).isSuccess)
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }
}
