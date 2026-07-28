package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRoleCapabilitiesTest {

    @Test
    fun normalizesTheFourPredefinedRoles() {
        assertEquals("owner", normalizeCommunityRole("creator"))
        assertEquals("admin", normalizeCommunityRole("ADMIN"))
        assertEquals("moderator", normalizeCommunityRole("mod"))
        assertEquals("member", normalizeCommunityRole("unknown"))
    }

    @Test
    fun moderatorCanModerateAndPauseWithoutManagingRoles() {
        val permissions = communityRoleCapabilities("moderator")

        assertTrue(permissions.canPost)
        assertTrue(permissions.canModerateMessages)
        assertTrue(permissions.canPauseChannels)
        assertFalse(permissions.canManageMembers)
        assertFalse(permissions.canManageRoles)
        assertFalse(permissions.canManageChannels)
    }

    @Test
    fun catalogKeepsStableRoleOrderForThePreview() {
        val catalog = defaultCommunityRoleCatalog("admin")

        assertEquals("admin", catalog.currentRole)
        assertEquals(
            listOf("owner", "admin", "moderator", "member"),
            catalog.roles.map { it.role }
        )
    }
}
