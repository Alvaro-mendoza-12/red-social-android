package conectate.com.data.repository.collection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionGovernanceSupportTest {

    @Test
    fun `resolvePrivileges normalizes ids and owner role`() = runBlocking {
        val privileges = CollectionGovernanceSupport.resolvePrivileges(
            currentUserId = "  USER-1  ",
            creatorId = "creator-1",
            loadMemberRole = { " Owner " },
            loadIsGlobalAdmin = { false }
        )

        assertEquals("user-1", privileges.currentUserId)
        assertEquals("creator-1", privileges.creatorId)
        assertEquals("owner", privileges.memberRole)
        assertTrue(privileges.isElevatedMember)
        assertTrue(privileges.canManageMembers)
    }

    @Test
    fun `resolvePrivileges grants creator management even without explicit role`() = runBlocking {
        val privileges = CollectionGovernanceSupport.resolvePrivileges(
            currentUserId = "creator-1",
            creatorId = "CREATOR-1",
            loadMemberRole = { null },
            loadIsGlobalAdmin = { false }
        )

        assertTrue(privileges.isCreator)
        assertTrue(privileges.canManageMembers)
        assertTrue(privileges.canManageCollection)
    }

    @Test
    fun `resolvePrivileges grants global admin management`() = runBlocking {
        val privileges = CollectionGovernanceSupport.resolvePrivileges(
            currentUserId = "moderator-9",
            creatorId = "creator-1",
            loadMemberRole = { "member" },
            loadIsGlobalAdmin = { true }
        )

        assertTrue(privileges.isGlobalAdmin)
        assertTrue(privileges.canManageMembers)
        assertTrue(privileges.canManageCollection)
    }

    @Test
    fun `resolvePrivileges keeps plain member without governance powers`() = runBlocking {
        val privileges = CollectionGovernanceSupport.resolvePrivileges(
            currentUserId = "member-2",
            creatorId = "creator-1",
            loadMemberRole = { "member" },
            loadIsGlobalAdmin = { false }
        )

        assertFalse(privileges.isCreator)
        assertFalse(privileges.isElevatedMember)
        assertFalse(privileges.canManageMembers)
        assertFalse(privileges.canManageCollection)
    }

    @Test(expected = IllegalStateException::class)
    fun `requireTargetIsNotCreator blocks creator`() {
        CollectionGovernanceSupport.requireTargetIsNotCreator(
            targetUserId = " Creator-1 ",
            creatorId = "creator-1",
            errorMessage = "blocked"
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `requireCanManageMembers blocks unauthorized member`() {
        CollectionGovernanceSupport.requireCanManageMembers(
            privileges = CollectionGovernancePrivileges(
                currentUserId = "member-2",
                creatorId = "creator-1",
                memberRole = "member",
                isGlobalAdmin = false
            ),
            errorMessage = "blocked"
        )
    }

    @Test
    fun `requireCanManageCollection allows elevated member`() {
        CollectionGovernanceSupport.requireCanManageCollection(
            privileges = CollectionGovernancePrivileges(
                currentUserId = "admin-1",
                creatorId = "creator-1",
                memberRole = "admin",
                isGlobalAdmin = false
            ),
            errorMessage = "blocked"
        )
    }
}
