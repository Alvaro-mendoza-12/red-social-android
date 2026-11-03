package conectate.com.data.repository

import conectate.com.data.model.StoredAccountSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StoredAccountsStoreTest {
    private fun account(id: String, token: String = "old") = StoredAccountSession(
        id = id, user_id = id, email = "$id@example.test", access_token = token, refresh_token = "refresh-$token",
    )

    @Test fun lateInboxResponsesCannotWriteForRemovedOrReplacedCredentials() {
        val old = account("one")
        val store = StoredAccountsStore(listOf(old)) {}
        var written = false
        store.update { listOf(account("one", "newer")) }
        assertFalse(store.commitIfCredentialsCurrent(old) { written = true })
        val current = store.state.value.single()
        store.update { emptyList() }
        assertFalse(store.commitIfCredentialsCurrent(current) { written = true })
        assertFalse(written)
    }

    @Test fun aProfileEditDoesNotInvalidateTheSameAccountCredentials() {
        val old = account("one")
        val edited = old.copy(display_name = "New display name")
        val store = StoredAccountsStore(listOf(edited)) {}
        var observed: StoredAccountSession? = null
        assertTrue(store.commitIfCredentialsCurrent(old) { observed = it })
        assertEquals(edited, observed)
        assertEquals(listOf(edited), store.readCurrent { it })
    }

    @Test fun removalCannotInterleaveBetweenTheCredentialCheckAndItsLocalWrite() {
        val old = account("one")
        val store = StoredAccountsStore(listOf(old)) {}
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removing = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var writtenFor: String? = null
        try {
            val response = workers.submit<Boolean> {
                store.commitIfCredentialsCurrent(old) {
                    writing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    writtenFor = it.id
                }
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val removal = workers.submit {
                removing.countDown()
                store.update { emptyList() }
                removed.countDown()
            }
            assertTrue(removing.await(5, TimeUnit.SECONDS))
            assertFalse(removed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(response.get(5, TimeUnit.SECONDS))
            removal.get(5, TimeUnit.SECONDS)
            assertEquals("one", writtenFor)
            assertTrue(store.state.value.isEmpty())
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun storageFailureDoesNotPublishUnsavedCredentialsAndCanBeRetried() {
        val original = account("one")
        var available = false
        var persisted = listOf(original)
        val store = StoredAccountsStore(persisted) { next ->
            check(available) { "secure storage unavailable" }
            persisted = next
        }
        assertTrue(runCatching { store.update { listOf(account("one", "new")) } }.isFailure)
        assertEquals(listOf(original), store.state.value)
        assertEquals(listOf(original), persisted)
        available = true
        store.update { listOf(account("one", "new")) }
        assertEquals("new", store.state.value.single().access_token)
        assertEquals(persisted, store.state.value)
    }

    @Test fun concurrentEditsDoNotLoseAccountsOrPersistAnOlderSnapshotLast() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val sizes = mutableListOf<Int>()
        var persisted = emptyList<StoredAccountSession>()
        val store = StoredAccountsStore(persisted) { next -> persisted = next; sizes += next.size }
        try {
            val jobs = (1..40).map { id -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                store.update { it + account(id.toString()) }
            } }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((1..40).toList(), sizes)
            assertEquals(40, store.state.value.map { it.id }.distinct().size)
            assertEquals(persisted, store.state.value)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun removingAnAccountIsPublishedOnlyAfterItsRemovalIsStored() {
        val original = listOf(account("one"), account("two"))
        lateinit var store: StoredAccountsStore
        var disk = original
        store = StoredAccountsStore(original) { next ->
            assertEquals(original, store.state.value)
            disk = next
        }
        store.update { it.filterNot { account -> account.id == "one" } }
        assertEquals(listOf("two"), disk.map { it.id })
        assertEquals(disk, store.state.value)
    }

    @Test fun refreshedCredentialsAreDurableBeforeTheyArePublishedAndKeepTheSavedEntryId() {
        val old = account("one")
        var disk = listOf(old)
        lateinit var store: StoredAccountsStore
        store = StoredAccountsStore(disk) { next ->
            assertEquals("old", store.state.value.single().access_token)
            disk = next
        }
        val retained = store.retainRefreshed(old, old.copy(id = "different-generated-id",
            access_token = "new", refresh_token = "refresh-new"))
        assertEquals(old.id, retained.id)
        assertEquals("new", disk.single().access_token)
        assertEquals("refresh-new", disk.single().refresh_token)
        assertEquals(disk, store.state.value)
    }

    @Test fun aLateRefreshCannotRecreateAnAccountRemovedWhileItWasLoading() {
        val old = account("one")
        val store = StoredAccountsStore(emptyList()) { fail("Removed entry must not be persisted again") }
        val failure = runCatching { store.retainRefreshed(old, old.copy(access_token = "new")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(store.state.value.isEmpty())
    }

    @Test fun aLateRefreshCannotOverwriteANewerLoginOrRefresh() {
        val old = account("one")
        val newer = account("one", "newer")
        val store = StoredAccountsStore(listOf(newer)) { fail("Newer credentials must be kept") }
        assertTrue(runCatching { store.retainRefreshed(old, account("one", "late")) }.exceptionOrNull()
            is IllegalStateException)
        assertEquals(listOf(newer), store.state.value)
    }

    @Test fun refreshStorageFailureKeepsThePreviouslyConfirmedSnapshot() {
        val old = account("one")
        val store = StoredAccountsStore(listOf(old)) { error("disk unavailable") }
        assertTrue(runCatching { store.retainRefreshed(old, account("one", "new")) }.isFailure)
        assertEquals(listOf(old), store.state.value)
    }

    @Test fun anUnrelatedProfileEditDuringRefreshIsPreserved() {
        val old = account("one")
        val profileUpdated = old.copy(display_name = "New profile name", avatar_url = "new-avatar")
        val store = StoredAccountsStore(listOf(profileUpdated)) {}
        val result = store.retainRefreshed(old, old.copy(access_token = "new", refresh_token = "new-refresh"))
        assertEquals("New profile name", result.display_name)
        assertEquals("new-avatar", result.avatar_url)
        assertEquals("new", result.access_token)
    }
}
