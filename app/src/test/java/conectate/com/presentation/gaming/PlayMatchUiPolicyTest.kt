package conectate.com.presentation.gaming

import conectate.com.data.gaming.PlayMatchApiException
import conectate.com.data.model.PlayMatchGameCatalogItem
import conectate.com.data.model.PlayMatchRankDefinition
import conectate.com.data.model.PlayMatchRankSchema
import conectate.com.data.model.PlayMatchRoleDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayMatchUiPolicyTest {
    @Test
    fun `responsive grid never forces two columns on 320dp`() {
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(320))
        assertEquals(2, PlayMatchUiPolicy.gameGridColumns(360))
        assertEquals(2, PlayMatchUiPolicy.gameGridColumns(480))
        assertEquals(3, PlayMatchUiPolicy.gameGridColumns(600))
        assertEquals(4, PlayMatchUiPolicy.gameGridColumns(840))
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(360, 1.5f))
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(480, 2.0f))
        assertEquals(3, PlayMatchUiPolicy.gameGridColumns(600, 2.0f))
    }

    @Test
    fun `backend errors are mapped without leaking internal details`() {
        val message = PlayMatchUiPolicy.safeError(PlayMatchApiException("RATE_LIMITED", 429))
        assertEquals("Has realizado varias acciones. Espera un momento y vuelve a intentarlo.", message)
        assertFalse(message.contains("429"))
    }

    @Test
    fun `unknown long error is replaced by safe generic copy`() {
        val message = PlayMatchUiPolicy.safeError(IllegalStateException("x".repeat(500)))
        assertEquals("No se pudo completar la acción. Inténtalo nuevamente.", message)
    }

    @Test
    fun `catalog supported game uses PlayMatch level and hides roles`() {
        val game = PlayMatchGameCatalogItem(
            id = "dynamic-1",
            name = "Juego dinámico",
            platforms = listOf("PC"),
            supportLevel = "CATALOG_SUPPORTED",
            rankedSupported = false,
        )

        assertEquals(emptyList<Pair<String, String>>(), PlayMatchUiPolicy.rankOptions(game, "PC"))
        assertEquals(emptyList<Pair<String, String>>(), PlayMatchUiPolicy.roleOptions(game))
        assertEquals(true, PlayMatchUiPolicy.showsPlayMatchLevel(game))
        assertEquals(false, PlayMatchUiPolicy.showsOfficialRank(game))
    }

    @Test
    fun `fully supported game uses backend rank and role definitions`() {
        val game = PlayMatchGameCatalogItem(
            id = "competitive-1",
            name = "Competitivo",
            platforms = listOf("PC", "PlayStation 5"),
            supportLevel = "FULLY_SUPPORTED",
            rankedSupported = true,
            rolesSupported = true,
            rankSchemas = listOf(
                PlayMatchRankSchema(
                    id = "schema-1",
                    key = "ranked",
                    displayName = "Clasificatoria",
                    version = "2026",
                    platform = "PC",
                    ranks = listOf(PlayMatchRankDefinition("rank-1", "gold", displayName = "Oro", order = 1)),
                ),
            ),
            roleDefinitions = listOf(PlayMatchRoleDefinition("role-1", "support", "Soporte")),
        )

        assertEquals(listOf("gold" to "Oro"), PlayMatchUiPolicy.rankOptions(game, "PC"))
        assertEquals(listOf("support" to "Soporte"), PlayMatchUiPolicy.roleOptions(game))
        assertEquals(true, PlayMatchUiPolicy.showsOfficialRank(game))
        assertEquals(false, PlayMatchUiPolicy.showsPlayMatchLevel(game))
    }
}
