package conectate.com.data.repository.message

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.ChatFolder
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class ChatFolderStoreTest {
    private lateinit var prefs: SharedPreferences
    private var account: String? = "a"
    private lateinit var store: ChatFolderStore
    private fun folder(id: String = "folder", name: String = "Privada") = ChatFolder(id, name, chat_ids = listOf("chat-a"))

    @Before fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("chat-folder-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = ChatFolderStore(prefs) { account }
    }

    @Test fun `switching accounts never displays the previous folder names or chat ids`() {
        store.replace("a", listOf(folder()))
        account = "b"
        assertTrue(store.read("b").isEmpty())
        assertTrue(store.read("a").isEmpty())
        store.replace("b", listOf(folder("folder-b", "Cuenta B")))
        account = "a"
        assertEquals(listOf(folder()), store.read("a"))
    }

    @Test fun `folders persist across process and store recreation for their owner`() {
        store.replace("a", listOf(folder()))
        assertEquals(listOf(folder()), ChatFolderStore(prefs) { "a" }.read("a"))
        assertTrue(ChatFolderStore(prefs) { "b" }.read("b").isEmpty())
    }

    @Test fun `ownerless legacy folders remain untouched and cannot be assigned to the next login`() {
        val old = """[{"id":"local-old","name":"Legado","chat_ids":["old-chat"]}]"""
        prefs.edit().putString("folders_json", old).commit()
        assertTrue(store.read("a").isEmpty())
        account = "b"
        assertTrue(store.read("b").isEmpty())
        store.replace("b", listOf(folder("new")))
        assertEquals(old, prefs.getString("folders_json", null))
    }

    @Test fun `late response from old account cannot overwrite either owner cache`() {
        store.replace("a", listOf(folder()))
        account = "b"
        store.replace("b", listOf(folder("folder-b")))
        try { store.replace("a", emptyList()); fail("must reject") } catch (_: CancellationException) { }
        assertEquals("folder-b", store.read("b").single().id)
        account = "a"
        assertEquals("folder", store.read("a").single().id)
    }

    @Test fun `logout hides folders and rejects a late mutation`() {
        store.replace("a", listOf(folder()))
        account = null
        assertTrue(store.read("a").isEmpty())
        assertTrue(store.read(null).isEmpty())
        try { store.replace("a", emptyList()); fail("must reject") } catch (_: CancellationException) { }
    }

    @Test fun `account normalization preserves the same cache without merging different owners`() {
        store.replace(" A ", listOf(folder()))
        assertEquals(listOf(folder()), store.read("a"))
        account = " A "
        assertEquals(listOf(folder()), store.read(" A "))
    }

    @Test fun `the observed list is selected from the currently active account`() = runTest {
        val sessions = MutableStateFlow<String?>("a")
        store.replace("a", listOf(folder()))
        assertEquals(listOf(folder()), store.observe(sessions).first())
        account = "b"; sessions.value = "b"
        assertTrue(store.observe(sessions).first().isEmpty())
        store.replace("b", listOf(folder("b")))
        assertEquals("b", store.observe(sessions).first().single().id)
    }

    @Test fun `a corrupt account cache does not crash or remove the retained legacy data`() {
        prefs.edit().putString("folders_by_account_v1:a", "{broken").putString("folders_json", "legacy").commit()
        assertTrue(store.read("a").isEmpty())
        assertEquals("{broken", prefs.getString("folders_by_account_v1:a", null))
        assertEquals("legacy", prefs.getString("folders_json", null))
    }

    @Test fun `queued edits cannot change into edits of the next account while waiting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.withAccount { gate.await() } }
        var executed = false
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            store.withAccount { executed = true; store.replace(it, listOf(folder("wrong-owner"))) }
        }
        account = "b"; gate.complete(Unit)
        for (pending in listOf(first, queued)) {
            try { pending.await(); fail("must cancel") } catch (_: CancellationException) { }
        }
        assertFalse(executed)
        assertTrue(store.read("b").isEmpty())
    }

    @Test fun `serialized refresh and edit preserve the newly confirmed folder list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            store.withAccount { gate.await(); store.replace(it, listOf(folder("remote"))) }
        }
        val edit = async(start = CoroutineStart.UNDISPATCHED) {
            store.withAccount { store.replace(it, store.read(it) + folder("created")) }
        }
        gate.complete(Unit)
        refresh.await().getOrThrow(); edit.await().getOrThrow()
        assertEquals(listOf("remote", "created"), store.read("a").map { it.id })
    }

    @Test fun `a rejected edit leaves the cache intact and returns its failure`() = runTest {
        store.replace("a", listOf(folder()))
        val error = IllegalStateException("No se pudo confirmar el cambio.")
        val result = store.withAccount<Unit> { throw error }
        assertSame(error, result.exceptionOrNull())
        assertEquals(listOf(folder()), store.read("a"))
    }

    @Test fun `cancellation remains cancellation instead of becoming an edit error`() = runTest {
        val cancellation = CancellationException("closed")
        try { store.withAccount<Unit> { throw cancellation }; fail("must cancel") }
        catch (caught: CancellationException) { assertSame(cancellation, caught) }
    }
}
