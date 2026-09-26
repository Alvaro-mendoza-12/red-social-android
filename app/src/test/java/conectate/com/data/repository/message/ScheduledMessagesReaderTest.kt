package conectate.com.data.repository.message

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import conectate.com.core.network.ReadDeadlineExceededException
import conectate.com.data.model.ScheduledMessageJob
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledMessagesReaderTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ScheduledMessageStore
    private val key = ScheduledMessagesKey("a", "dm", "chat")
    private var account: String? = "a"
    private var requests = 0
    private var backendOwner: String? = null
    private var token: suspend () -> String? = { "token-a" }
    private var remote: suspend () -> List<ScheduledMessageJob> = { listOf(job()) }
    private val reader get() = ScheduledMessagesReader(store, { account }, { token() }, fetch = { request, token ->
        assertEquals(key, request)
        assertEquals("token-a", token)
        requests++
        remote()
    }, tokenOwner = { backendOwner })
    private fun job(id: String = "remote", owner: String? = "a", target: String = "chat") =
        ScheduledMessageJob(id, "dm", target, "contenido", "text", scheduled_for = "2026-09-10T00:00:00Z", status = "pending", owner_id = owner)
    private fun seed() = store.replaceRemote(key, listOf(job("cached")), store.revision("a"))

    @Before fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("scheduled-reader-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = ScheduledMessageStore(prefs)
    }

    @Test fun `success persists remote snapshot across store recreation`() = runTest {
        assertEquals("remote", reader.refresh(key).getOrThrow().jobs.single().id)
        val reopened = ScheduledMessageStore(prefs).snapshot(key)
        assertTrue(reopened.hasRemoteSnapshot)
        assertEquals("remote", reopened.jobs.single().id)
    }

    @Test fun `first cache lookup does not perform any request`() {
        seed()
        assertEquals("cached", store.snapshot(key).jobs.single().id)
        assertEquals(0, requests)
    }

    @Test fun `empty confirmed response clears old remote but preserves pending local`() = runTest {
        seed()
        store.putLocal("a", job("local-one"))
        remote = { emptyList() }
        val result = reader.refresh(key).getOrThrow()
        assertTrue(result.hasRemoteSnapshot)
        assertEquals(listOf("local-one"), result.jobs.map { it.id })
    }

    @Test fun `five second total deadline preserves cache when response stalls`() = runTest {
        seed()
        remote = { delay(25_000); listOf(job()) }
        val start = testScheduler.currentTime
        assertTrue(reader.refresh(key).exceptionOrNull() is ReadDeadlineExceededException)
        assertEquals(5_000L, testScheduler.currentTime - start)
        assertEquals("cached", store.snapshot(key).jobs.single().id)
    }

    @Test fun `authentication is inside the same total deadline`() = runTest {
        token = { delay(6_000); "token-a" }
        assertTrue(reader.refresh(key).exceptionOrNull() is ReadDeadlineExceededException)
        assertEquals(0, requests)
    }

    @Test fun `authentication failure is not an empty server list`() = runTest {
        seed()
        token = { null }
        assertTrue(reader.refresh(key).isFailure)
        assertEquals("cached", store.snapshot(key).jobs.single().id)
        assertEquals(0, requests)
    }

    @Test fun `http or decoding failure preserves old snapshot`() = runTest {
        seed()
        remote = { error("raw URL must never reach the dialog") }
        assertTrue(reader.refresh(key).isFailure)
        assertEquals("cached", store.snapshot(key).jobs.single().id)
    }

    @Test fun `account change during token lookup stops request`() = runTest {
        token = { account = "b"; "token-b" }
        try { reader.refresh(key); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }

    @Test fun `account change during response cannot publish cache`() = runTest {
        seed()
        remote = { account = "b"; listOf(job()) }
        try { reader.refresh(key); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals("cached", store.snapshot(key).jobs.single().id)
        assertTrue(store.snapshot(key.copy(accountId = "b")).jobs.isEmpty())
    }

    @Test fun `foreign account or conversation response is rejected`() = runTest {
        seed()
        remote = { listOf(job(owner = "b")) }
        assertTrue(reader.refresh(key).isFailure)
        remote = { listOf(job(target = "elsewhere")) }
        assertTrue(reader.refresh(key).isFailure)
        assertEquals("cached", store.snapshot(key).jobs.single().id)
    }

    @Test fun `external cancellation is propagated and cache remains intact`() = runTest {
        seed()
        remote = { throw CancellationException("closed") }
        try { reader.refresh(key); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals("cached", store.snapshot(key).jobs.single().id)
    }

    @Test fun `response started before cancellation cannot resurrect canceled job`() = runTest {
        seed()
        remote = { store.remove("a", "cached"); listOf(job("cached")) }
        assertTrue(reader.refresh(key).getOrThrow().jobs.isEmpty())
        assertTrue(ScheduledMessageStore(prefs).snapshot(key).jobs.isEmpty())
    }

    @Test fun `response started before creation cannot erase new confirmed job`() = runTest {
        remote = { store.rememberRemote(key, job("created")); emptyList() }
        assertEquals("created", reader.refresh(key).getOrThrow().jobs.single().id)
    }

    @Test fun `pending jobs are isolated and ownerless legacy jobs are preserved without replay`() {
        prefs.edit().putString(ScheduledMessageStore.LOCAL_KEY, Json.encodeToString(listOf(job("legacy", owner = null)))).commit()
        store.putLocal("a", job("local-a"))
        store.putLocal("b", job("local-b"))
        assertEquals(listOf("local-a"), store.pending("a").map { it.id })
        assertEquals(listOf("local-b"), store.pending("b").map { it.id })
        assertEquals(listOf("local-a"), store.snapshot(key).jobs.map { it.id })
        assertTrue(store.snapshot(key).legacyJobsNeedOwner)
        store.remove("a", "local-a")
        assertEquals(listOf("local-b"), store.pending("b").map { it.id })
        assertTrue(prefs.getString(ScheduledMessageStore.LOCAL_KEY, "")!!.contains("legacy"))
    }

    @Test fun `cancel cache is isolated to the active owner even for same id and chat`() {
        seed()
        val other = key.copy(accountId = "b")
        store.replaceRemote(other, listOf(job("cached", owner = "b")), store.revision("b"))
        store.remove("a", "cached")
        assertTrue(store.snapshot(key).jobs.isEmpty())
        assertEquals("cached", store.snapshot(other).jobs.single().id)
    }

    @Test fun `invalid pending json is not overwritten by another mutation`() {
        prefs.edit().putString(ScheduledMessageStore.LOCAL_KEY, "{broken").commit()
        try { store.putLocal("a", job()); fail("must fail safely") } catch (_: Exception) { }
        assertEquals("{broken", prefs.getString(ScheduledMessageStore.LOCAL_KEY, null))
    }

    @Test fun `keys normalize account and reject unknown scopes`() {
        assertEquals(key, ScheduledMessagesKey.create(" A ", " DM ", " CHAT "))
        assertNull(ScheduledMessagesKey.create(null, "dm", "chat"))
        assertNull(ScheduledMessagesKey.create("a", "unknown", "chat"))
    }

    @Test fun `migrated account accepts only its captured token subject and caches public identity`() = runTest {
        backendOwner = "ubuntu-a"
        remote = { listOf(job(owner = " UBUNTU-A ")) }
        val result = reader.refresh(key).getOrThrow()
        assertEquals("a", result.jobs.single().owner_id)
        assertEquals("a", ScheduledMessageStore(prefs).snapshot(key).jobs.single().owner_id)
        assertTrue(store.snapshot(key.copy(accountId = "ubuntu-a")).jobs.isEmpty())
    }

    @Test fun `another backend owner remains rejected for migrated accounts`() = runTest {
        seed()
        backendOwner = "ubuntu-a"
        remote = { listOf(job(owner = "ubuntu-b")) }
        assertTrue(reader.refresh(key).isFailure)
        assertEquals("cached", store.snapshot(key).jobs.single().id)
    }

    @Test fun `legacy server without owner is bound to the authenticated request account`() = runTest {
        remote = { listOf(job(owner = null)) }
        assertEquals("a", reader.refresh(key).getOrThrow().jobs.single().owner_id)
    }
}
