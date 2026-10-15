package conectate.com.data.local

import android.app.Application
import androidx.room.Room
import conectate.com.data.local.entity.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionAccountVisibilityTest {
    private lateinit var db: AppDatabase
    private val time = "2026-10-14T12:00:00Z"
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    @Test fun formerGroupChatStaysVisibleAndOwnHideKeepsOtherAccount() = runBlocking {
        val dao = db.groupDao()
        dao.insertGroupsIgnore(listOf(GroupEntity("room", "Grupo", null, null, time, "b", null, null, is_member = false)))
        dao.insertMembers(listOf(GroupMemberEntity("room", "a", "former_member", time), GroupMemberEntity("room", "b", "member", time)))
        assertEquals(1, dao.getGroupsForUserFlow("a").first().size)
        assertFalse(dao.isUserMemberFlow("room", "a").first())
        assertTrue(dao.isUserMemberFlow("room", "b").first())
        dao.removeMember("room", "a")
        assertTrue(dao.getGroupsForUserFlow("a").first().isEmpty())
        assertEquals(1, dao.getGroupsForUserFlow("b").first().size)
        assertNotNull(dao.getGroupById("room"))
    }
    private suspend fun community() {
        db.communityDao().insertCommunities(listOf(CommunityEntity("room", "Comunidad", null, null, null, true, time, "b", null, null, is_member = false)))
        db.communityDao().insertMembers(listOf(CommunityMemberEntity("room", "a", "former_member", time), CommunityMemberEntity("room", "b", "member", time)))
    }
    @Test fun communityMembershipUsesOwnRoleEvenWhenAnotherAccountLeft() = runBlocking {
        community(); val dao = db.communityDao()
        assertFalse(dao.isUserMemberSync("room", "a"))
        assertTrue(dao.isUserMemberSync("room", "b"))
        assertTrue(dao.isUserMemberFlow("room", "b").first())
    }
    @Test fun communityOwnHideKeepsOtherAccountAndTheRoom() = runBlocking {
        community(); val dao = db.communityDao()
        assertEquals(1, dao.getCommunitiesForUserFlow("a").first().size)
        dao.removeMember("room", "a")
        assertTrue(dao.getCommunitiesForUserFlow("a").first().isEmpty())
        assertEquals(1, dao.getCommunitiesForUserFlow("b").first().size)
        assertNotNull(dao.getCommunityById("room"))
    }
}
