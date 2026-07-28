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

    @Test
    fun pausedAndVoiceChannelsExposeTheirEffectiveState() {
        val paused = CommunityChannel(
            id = "paused",
            communityId = "community",
            name = "Avisos",
            pausedUntil = "2099-01-01T00:00:00Z",
            pauseReason = "Mantenimiento",
            isPaused = true
        )
        val expired = paused.copy(pausedUntil = "2020-01-01T00:00:00Z")
        val voice = paused.copy(id = "voice", type = "voice", isPaused = false)

        assertTrue(paused.isEffectivelyPaused())
        assertFalse(expired.isEffectivelyPaused())
        assertFalse(voice.acceptsMessages())
        assertTrue(paused.acceptsMessages())
    }
}
