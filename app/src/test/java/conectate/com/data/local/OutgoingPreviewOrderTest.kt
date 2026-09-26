package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.model.Community
import conectate.com.data.model.Group
import conectate.com.data.model.Message
import conectate.com.data.repository.message.newestInboxMessage
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class OutgoingPreviewOrderTest {
    @Test fun `out of order acknowledgements preserve latest group and community previews`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val earlier = "2026-09-21T10:00:00Z"
        val later = "2026-09-21T05:00:01-05:00"
        try {
            val groups = db.groupDao()
            val communities = db.communityDao()
            groups.upsertGroup(GroupEntity.fromDomain(Group(id = "g", name = "Group", creator_id = "me", created_at = earlier)))
            communities.insertCommunities(listOf(CommunityEntity.fromDomain(Community(id = "c", name = "Community", creator_id = "me", created_at = earlier))))
            groups.updateMetadataIfNewer("g", "Segundo", later)
            communities.updateMetadataIfNewer("c", "Segundo", later)
            groups.updateMetadataIfNewer("g", "Primero", earlier)
            communities.updateMetadataIfNewer("c", "Primero", earlier)
            assertEquals("Segundo", groups.getGroupById("g")?.last_message)
            assertEquals(later, groups.getGroupById("g")?.last_message_at)
            assertEquals("Segundo", communities.getCommunityById("c")?.last_message)
            assertEquals(later, communities.getCommunityById("c")?.last_message_at)

            // Acknowledgement of the latest message can still refresh its preview.
            groups.updateMetadataIfNewer("g", "Confirmado", later)
            communities.updateMetadataIfNewer("c", "Confirmado", later)
            assertEquals("Confirmado", groups.getGroupById("g")?.last_message)
            assertEquals("Confirmado", communities.getCommunityById("c")?.last_message)

            // Explicit deletion/history recalculation can still move backwards.
            groups.updateMetadata("g", "Anterior", earlier)
            communities.updateMetadata("c", null, null)
            assertEquals("Anterior", groups.getGroupById("g")?.last_message)
            assertNull(communities.getCommunityById("c")?.last_message)
        } finally {
            db.close()
        }
    }

    @Test fun `direct inbox keeps newer pending message when older send is acknowledged`() {
        val older = Message(id = "one", content = "Primero", created_at = "2026-09-21T10:00:00Z", status = "sent")
        val newer = Message(id = "two", content = "Segundo", created_at = "2026-09-21T05:00:01-05:00", status = "sending")
        assertEquals(newer, newestInboxMessage(older, newer))
        val confirmed = newer.copy(status = "sent")
        assertEquals(confirmed, newestInboxMessage(confirmed, newer))
    }
}
