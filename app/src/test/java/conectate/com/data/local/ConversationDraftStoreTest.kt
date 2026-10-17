package conectate.com.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ConversationDraftStoreTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("conversation-drafts-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun reopeningStoreRestoresTextWithLineBreaks() {
        ConversationDraftStore(context).write("owner", "chat", "room", "hola\nmañana")
        assertEquals("hola\nmañana", ConversationDraftStore(context).read("owner", "chat", "room"))
    }
    @Test fun accountsRoomsAndKindsRemainSeparate() {
        val store = ConversationDraftStore(context)
        store.write("owner", "chat", "room", "privado")
        assertEquals("", store.read("other", "chat", "room"))
        assertEquals("", store.read("owner", "group", "room"))
        assertEquals("", store.read("owner", "chat", "other"))
        store.write("owner", "group", "room", "grupo")
        store.write("owner", "chat", "room", "")
        assertEquals("grupo", store.read("owner", "group", "room"))
        assertEquals("", store.read("owner", "chat", "room"))
    }
    @Test fun invalidIdentitiesCannotCreateDrafts() {
        val store = ConversationDraftStore(context)
        store.write(null, "chat", "room", "texto")
        assertNull(ConversationDraftStore.key("owner", "other", "room"))
        assertEquals("", store.read(null, "chat", "room"))
    }
    @Test fun migrationUsesStableConversationAndCannotResurrectSentLegacyDraft() {
        val store = ConversationDraftStore(context)
        store.write("owner", "chat", "old-profile-alias", "borrador")
        assertEquals("borrador", store.readAndMigrate("owner", "chat", "conversation-id", "old-profile-alias"))
        assertEquals("", store.read("owner", "chat", "old-profile-alias"))
        assertEquals("borrador", store.readAndMigrate("owner", "chat", "conversation-id", "new-profile-alias"))
        store.write("owner", "chat", "conversation-id", "")
        assertEquals("", store.readAndMigrate("owner", "chat", "conversation-id", "old-profile-alias"))
    }
    @Test fun migrationPreservesCurrentDraftAndAccountIsolation() {
        val store = ConversationDraftStore(context)
        store.write("owner", "chat", "conversation-id", "nuevo")
        store.write("owner", "chat", "legacy", "anterior")
        assertEquals("nuevo", store.readAndMigrate("owner", "chat", "conversation-id", "legacy"))
        assertEquals("", store.readAndMigrate("other-owner", "chat", "conversation-id", "legacy"))
        assertEquals("nuevo", store.read("owner", "chat", "conversation-id"))
    }
}
