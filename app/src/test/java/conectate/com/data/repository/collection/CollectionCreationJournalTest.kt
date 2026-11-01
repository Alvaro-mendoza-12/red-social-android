package conectate.com.data.repository.collection

import android.app.Application
import android.util.AtomicFile
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CollectionCreationJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun request(owner: String = "a", kind: String = "group") = CollectionCreationRequest(
        id = UUID.randomUUID().toString(), accountId = owner, kind = kind, name = "Original",
        description = "Draft", imageUrl = "https://example.test/confirmed-icon.png", category = "Friends",
        isPublic = false, memberIds = listOf(owner, "b"), createdAt = "2026-11-01T12:00:00Z",
    )

    @Test fun lostAcknowledgementReplaysTheExactCommandAfterReconstructingTheStore() = runBlocking {
        val directory = temporary.newFolder()
        val original = request()
        val server = mutableMapOf<String, CollectionCreationRequest>()
        val error = runCatching {
            CollectionCreationJournal(directory).execute("a", "group", {}, { original }) {
                server[it.id] = it
                throw IOException("response lost after commit")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        val reopened = CollectionCreationJournal(directory)
        assertEquals(original, reopened.pending("a", "group"))
        val id = reopened.execute("a", "group", {}, { error("Must not prepare/upload a changed selection") }) {
            assertEquals(original, it)
            server[it.id] = it
        }
        assertEquals(original.id, id)
        assertEquals(1, server.size)
        assertNull(reopened.pending("a", "group"))
    }

    @Test fun aFailureSavingTheConfirmedRoomKeepsTheSameRemoteCommand() = runBlocking {
        val journal = CollectionCreationJournal(temporary.newFolder())
        val original = request(kind = "community")
        var requests = 0
        runCatching { journal.execute("a", "community", {}, { original }) { requests++; throw IOException("local DB failed") } }
        assertEquals(original.id, journal.execute("a", "community", {}, { error("Already prepared") }) { requests++ })
        assertEquals(2, requests)
        assertNull(journal.pending("a", "community"))
    }

    @Test fun changingAccountsAfterTheServerReplyRetainsOnlyTheOriginalOwnersRecovery() = runBlocking {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val lease = guard.captureAccountLease { owner }
        val journal = CollectionCreationJournal(temporary.newFolder())
        val original = request()
        val error = runCatching { journal.execute("a", "group", lease::requireCurrent, { original }) {
            guard.invalidate { owner = "c" }
        } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(original, journal.pending("a", "group"))
        assertNull(journal.pending("c", "group"))
    }

    @Test fun cancellationAfterDispatchIsNotARejectionAndDoesNotEraseRecovery() = runBlocking {
        val journal = CollectionCreationJournal(temporary.newFolder())
        val original = request()
        val error = runCatching { journal.execute("a", "group", {}, { original }) { throw CancellationException("Screen closed") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(original, journal.pending("a", "group"))
    }

    @Test fun preparingOrUploadingFailureLeavesNoDispatchedIntent() = runBlocking {
        val journal = CollectionCreationJournal(temporary.newFolder())
        var sent = false
        runCatching { journal.execute("a", "group", {}, { throw IOException("icon failed") }) { sent = true } }
        assertFalse(sent)
        assertNull(journal.pending("a", "group"))
    }

    @Test fun storageFailurePreventsTheNetworkRequest() = runBlocking {
        val directoryIsAFile = temporary.newFile()
        val journal = CollectionCreationJournal(directoryIsAFile)
        var sent = false
        val error = runCatching { journal.execute("a", "group", {}, { request() }) { sent = true } }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(sent)
    }

    @Test fun aConcurrentRetryWaitsAndThenUsesTheFirstRequestsIdentity() = runBlocking {
        val directory = temporary.newFolder()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = request()
        val first = async(Dispatchers.Default) {
            runCatching { CollectionCreationJournal(directory).execute("a", "group", {}, { original }) {
                entered.complete(Unit); release.await(); throw IOException("ACK lost")
            } }
        }
        entered.await()
        val retry = async(Dispatchers.Default) {
            CollectionCreationJournal(directory).execute("a", "group", {}, { error("Do not replace in-flight request") }) {
                assertEquals(original, it)
            }
        }
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        assertEquals(original.id, retry.await())
    }

    @Test fun aConfirmedPrewriteRejectionAllowsTheUserToCorrectTheDraft() = runBlocking {
        val journal = CollectionCreationJournal(temporary.newFolder())
        val error = runCatching { journal.execute("a", "group", {}, { request() }) {
            throw CollectionCreationRejectedException("ID conflict before writes")
        } }.exceptionOrNull()
        assertTrue(error is CollectionCreationRejectedException)
        assertNull(journal.pending("a", "group"))
    }

    @Test fun anUnreadableIntentIsNeverSilentlyReplacedByANewCreation() = runBlocking {
        val directory = temporary.newFolder()
        val journal = CollectionCreationJournal(directory)
        runCatching { journal.execute("a", "group", {}, { request() }) { throw IOException("lost") } }
        directory.listFiles()!!.single().writeText("broken JSON")
        var sent = false
        val error = runCatching { journal.execute("a", "group", {}, { error("No new ID") }) { sent = true } }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(sent)
    }

    @Test fun interruptedAtomicReplacementRestoresTheLastCompleteIntent() = runBlocking {
        val directory = temporary.newFolder()
        val journal = CollectionCreationJournal(directory)
        val original = request()
        runCatching { journal.execute("a", "group", {}, { original }) { throw IOException("lost") } }
        val file = AtomicFile(directory.listFiles()!!.single())
        file.startWrite().also { it.write("incomplete".toByteArray()); it.close() }
        assertEquals(original, CollectionCreationJournal(directory).pending("a", "group"))
    }

    @Test fun groupCommunityAndOtherAccountsHaveSeparateCommands() = runBlocking {
        val journal = CollectionCreationJournal(temporary.newFolder())
        val group = request()
        val community = request(kind = "community")
        val other = request(owner = "c")
        for (intent in listOf(group, community, other)) {
            runCatching { journal.execute(intent.accountId, intent.kind, {}, { intent }) { throw IOException("offline") } }
        }
        assertEquals(group, journal.pending("a", "group"))
        assertEquals(community, journal.pending("a", "community"))
        assertEquals(other, journal.pending("c", "group"))
    }
}
