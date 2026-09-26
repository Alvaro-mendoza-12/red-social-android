package conectate.com.presentation.messages

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore/storage regression; each run touches only its own unique keys. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatWorkspaceStoreTest {
    @Test
    fun encryptedWorkspaceSurvivesNewStoreAndIsolatesAccountsAndDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountA = "workspace-test-a-${UUID.randomUUID()}"
        val accountB = "workspace-test-b-${UUID.randomUUID()}"
        val scope = "workspace-test-chat-${UUID.randomUUID()}"
        val secretA = "private-workspace-marker-a-${UUID.randomUUID()}"
        val secretB = "private-workspace-marker-b-${UUID.randomUUID()}"
        val task = ChatWorkspaceEntry(kind = ChatWorkspaceKind.TASK, text = secretA, sourceMessageId = UUID.randomUUID().toString())
        val draft = ChatWorkspaceEntry(kind = ChatWorkspaceKind.DRAFT, text = secretB)
        val store = ChatWorkspaceStore(context)
        try {
            store.update(accountA, scope) { it.add(task) }
            val reopened = ChatWorkspaceStore(context)
            assertEquals(listOf(task), reopened.read(accountA, scope).entries)
            assertTrue("Another account must not see account A's private task", reopened.read(accountB, scope).entries.isEmpty())

            reopened.update(accountB, scope) { it.add(draft) }
            assertEquals(listOf(draft), store.read(accountB, scope).entries)
            assertEquals(listOf(task), store.read(accountA, scope).entries)

            // commit() has completed before these reads, so inspect the actual on-disk preferences.
            val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/chat_workspace_private_v1.xml")
            assertTrue("Encrypted workspace preferences were not persisted", preferencesFile.isFile)
            val persisted = preferencesFile.readText()
            assertFalse("Account A's text must not be stored in plain text", persisted.contains(secretA))
            assertFalse("Account B's text must not be stored in plain text", persisted.contains(secretB))
            assertFalse("Account identifiers must not be stored in plain text", persisted.contains(accountA))
            assertFalse("Conversation identity must not be stored in plain text", persisted.contains(scope))

            reopened.update(accountA, scope) { it.remove(task.id) }
            assertTrue(ChatWorkspaceStore(context).read(accountA, scope).entries.isEmpty())
            assertEquals("Deleting account A's entry must preserve account B", listOf(draft), store.read(accountB, scope).entries)
        } finally {
            // Never clear the shared file or delete the master key: other app data may use them.
            try { store.update(accountA, scope) { ChatWorkspace() } }
            finally { store.update(accountB, scope) { ChatWorkspace() } }
        }
    }
}
