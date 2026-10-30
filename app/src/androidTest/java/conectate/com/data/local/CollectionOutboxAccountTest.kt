package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionOutboxAccountTest {
    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
    ).build()

    @Test fun inactiveAccountsAndDeletedGroupMessagesCannotStarveTheActiveAccount() = runBlocking {
        val db = database()
        try {
            val dao = db.groupDao()
            dao.insertGroupsIgnore(listOf(GroupEntity("qa-group", "QA", null, null, "000", "active", null, null)))
            fun message(id: String, actor: String, time: String, deleted: Boolean = false) = GroupMessageEntity(
                id = id, group_id = "qa-group", sender_id = actor, content = "QA", content_type = "text",
                created_at = time, status = "sending", is_synced = false, pending_action = "send", deleted_for_all = deleted
            )
            dao.insertMessages((0 until 40).map { message("inactive-$it", "inactive", "001") } + listOf(
                message("deleted", "active", "000", true),
                message("active-b", "active", "002"), message("active-a", " ACTIVE ", "002")
            ))
            assertEquals(listOf("active-a"), dao.getPendingOutgoingMessages("active", 1).map { it.id })
            assertEquals(listOf("active-a", "active-b"), dao.getPendingOutgoingMessages("active", 40).map { it.id })
            assertEquals(40, dao.getPendingOutgoingMessages("inactive", 40).size)
            assertEquals(true, dao.getMessageById("deleted")?.deleted_for_all)
        } finally { db.close() }
    }

    @Test fun inactiveAccountsAndDeletedCommunityMessagesCannotStarveTheActiveAccount() = runBlocking {
        val db = database()
        try {
            val dao = db.communityDao()
            dao.insertCommunities(listOf(CommunityEntity("qa-community", "QA", null, null, null, false, "000", "active", null, null)))
            fun message(id: String, actor: String, time: String, deleted: Boolean = false) = CommunityMessageEntity(
                id = id, community_id = "qa-community", sender_id = actor, content = "QA", content_type = "text",
                created_at = time, status = "sending", is_synced = false, pending_action = "send", deleted_for_all = deleted
            )
            dao.insertMessages((0 until 40).map { message("inactive-$it", "inactive", "001") } + listOf(
                message("deleted", "active", "000", true),
                message("active-b", "active", "002"), message("active-a", " ACTIVE ", "002")
            ))
            assertEquals(listOf("active-a"), dao.getPendingOutgoingMessages("active", 1).map { it.id })
            assertEquals(listOf("active-a", "active-b"), dao.getPendingOutgoingMessages("active", 40).map { it.id })
            assertEquals(40, dao.getPendingOutgoingMessages("inactive", 40).size)
            assertEquals(true, dao.getMessageById("deleted")?.deleted_for_all)
        } finally { db.close() }
    }
}
