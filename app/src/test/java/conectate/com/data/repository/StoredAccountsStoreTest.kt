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
}
