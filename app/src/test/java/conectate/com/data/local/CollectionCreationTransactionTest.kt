package conectate.com.data.local

import android.app.Application
import androidx.room.Room
import conectate.com.data.local.entity.*
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CollectionCreationTransactionTest {
    private lateinit var db: AppDatabase
    private val time = "2026-11-01T12:00:00Z"
    private fun group() = GroupEntity("group", "Created", null, null, time, "a", null, null, is_member = true)
    private fun community() = CommunityEntity("community", "Created", null, null, null, false, time, "a", null, null, is_member = true)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }

    @Test fun groupCreationStoresTheRoomAndItsMembershipsTogether() = runBlocking {
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { "a" }
        db.groupDao().saveCreatedGroup(group(), listOf(GroupMemberEntity("group", "a", "owner", time), GroupMemberEntity("group", "b", "member", time)), lease::requireCurrent)
        assertEquals("a", db.groupDao().getGroupById("group")?.creator_id)
        assertEquals(setOf("a", "b"), db.groupDao().getMembersFlow("group").first().map { it.user_id }.toSet())
    }

    @Test fun groupCreationRollsBackBothWritesIfTheSessionChangesBeforeCommit() = runBlocking {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val lease = guard.captureAccountLease { owner }
        var checks = 0
        val error = runCatching {
            db.groupDao().saveCreatedGroup(group(), listOf(GroupMemberEntity("group", "a", "owner", time))) {
                if (++checks == 2) guard.invalidate { owner = "b" }
                lease.requireCurrent()
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(db.groupDao().getGroupById("group"))
        assertTrue(db.groupDao().getMembersFlow("group").first().isEmpty())
    }

    @Test fun privateCommunityCreationStoresItsPrivacyAndOwnerTogether() = runBlocking {
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { "a" }
        db.communityDao().saveCreatedCommunity(community(), listOf(CommunityMemberEntity("community", "a", "owner", time)), lease::requireCurrent)
        assertEquals(false, db.communityDao().getCommunityById("community")?.is_public)
        assertEquals("owner", db.communityDao().getMembersFlow("community").first().single().role)
    }

    @Test fun communityCreationRollsBackTheParentAndOwnerOnSessionChange() = runBlocking {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val lease = guard.captureAccountLease { owner }
        var checks = 0
        val error = runCatching {
            db.communityDao().saveCreatedCommunity(community(), listOf(CommunityMemberEntity("community", "a", "owner", time))) {
                if (++checks == 2) guard.invalidate { owner = "b" }
                lease.requireCurrent()
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(db.communityDao().getCommunityById("community"))
        assertTrue(db.communityDao().getMembersFlow("community").first().isEmpty())
    }

    @Test fun groupRecoveryKeepsMessagesPreviewAndUnreadCountButReplacesOldMemberships() = runBlocking {
        val dao = db.groupDao()
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        dao.saveCreatedGroup(group().copy(last_message = "Keep preview", last_message_at = time, unread_count = 3),
            listOf(GroupMemberEntity("group", "a", "owner", time), GroupMemberEntity("group", "removed", "member", time)), lease::requireCurrent)
        dao.insertMessages(listOf(GroupMessageEntity.fromDomain(conectate.com.data.model.GroupMessage(
            id = "kept", group_id = "group", sender_id = "a", content = "Keep preview", created_at = time))))
        dao.saveCreatedGroup(group().copy(name = "Edited on server"), listOf(GroupMemberEntity("group", "a", "owner", time)), lease::requireCurrent)
        val cached = dao.getGroupById("group")!!
        assertEquals("Edited on server", cached.name)
        assertEquals("Keep preview", cached.last_message)
        assertEquals(3, cached.unread_count)
        assertEquals(listOf("a"), dao.getMembersFlow("group").first().map { it.user_id })
        assertEquals(listOf("kept"), dao.getMessagesSync("group").map { it.id })
    }

    @Test fun communityRecoveryUpdatesWithoutReplacingTheParentAndErasingItsMembersOrMessages() = runBlocking {
        val dao = db.communityDao()
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        dao.saveCreatedCommunity(community().copy(last_message = "Keep community", last_message_at = time, unread_count = 2),
            listOf(CommunityMemberEntity("community", "a", "owner", time), CommunityMemberEntity("community", "removed", "member", time)), lease::requireCurrent)
        dao.insertMessages(listOf(CommunityMessageEntity.fromDomain(conectate.com.data.model.CommunityMessage(
            id = "kept", community_id = "community", sender_id = "a", content = "Keep community", created_at = time))))
        dao.saveCreatedCommunity(community().copy(name = "Edited community", is_public = true),
            listOf(CommunityMemberEntity("community", "a", "owner", time)), lease::requireCurrent)
        val cached = dao.getCommunityById("community")!!
        assertEquals("Edited community", cached.name)
        assertTrue(cached.is_public)
        assertEquals("Keep community", cached.last_message)
        assertEquals(2, cached.unread_count)
        assertEquals(listOf("a"), dao.getMembersFlow("community").first().map { it.user_id })
        assertEquals(listOf("kept"), dao.getMessagesSync("community").map { it.id })
    }
}
