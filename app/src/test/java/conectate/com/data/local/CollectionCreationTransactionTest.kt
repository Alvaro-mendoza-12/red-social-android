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
}
