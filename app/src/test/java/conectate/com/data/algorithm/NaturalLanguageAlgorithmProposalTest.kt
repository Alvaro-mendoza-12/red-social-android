package conectate.com.data.algorithm

import conectate.com.data.model.AlgorithmDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalLanguageAlgorithmProposalTest {
    @Test
    fun `spanish request becomes explicit reviewable topic and mix proposals`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose(
            "Quiero programación y anime, no fútbol ni política, 20% contenido nuevo.",
        )

        assertTrue(proposal.requiresConfirmation)
        assertTrue(proposal.hasChanges)
        assertEquals(
            mapOf(
                "programacion" to 80,
                "anime" to 80,
                "futbol" to -100,
                "politica" to -100,
            ),
            proposal.preferences.associate { it.subjectKey to it.score },
        )
        assertTrue(proposal.preferences.all { it.dimension == AlgorithmDimension.TOPIC.wireName })
        assertEquals(
            listOf(NaturalLanguageFeedMixChange(FeedMixDimension.NEW_CONTENT, 20)),
            proposal.feedMixChanges,
        )
    }

    @Test
    fun `sensitive data requests are ignored and never become preferences`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose(
            "Usa mis mensajes privados, mi GPS, edad y género; quiero anime.",
        )

        assertEquals(listOf("anime"), proposal.preferences.map { it.subjectKey })
        assertTrue(
            proposal.issues.filterIsInstance<NaturalLanguageProposalIssue.SensitiveRequestIgnored>().isNotEmpty(),
        )
        assertFalse(proposal.preferences.any { preference ->
            preference.subjectKey.contains("mensaje") ||
                preference.subjectKey.contains("gps") ||
                preference.subjectKey.contains("edad") ||
                preference.subjectKey.contains("genero")
        })
    }

    @Test
    fun `unsupported words do not become arbitrary server instructions`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose("Muéstrame criptomonedas y cualquier cosa secreta")

        assertFalse(proposal.hasChanges)
        assertTrue(proposal.issues.contains(NaturalLanguageProposalIssue.NoSupportedInstruction))
        assertTrue(proposal.requiresConfirmation)
    }

    @Test
    fun `out of range percentages are surfaced for review instead of coerced`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose("Quiero 130% contenido nuevo y anime")

        assertEquals(listOf("anime"), proposal.preferences.map { it.subjectKey })
        assertTrue(proposal.feedMixChanges.isEmpty())
        assertTrue(
            proposal.issues.contains(
                NaturalLanguageProposalIssue.InvalidPercentage("Contenido nuevo", 130),
            ),
        )
    }

    @Test
    fun `conflicting topic instructions keep the last one for review`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose(
            "Quiero anime, pero no quiero anime por ahora.",
        )

        assertEquals(-100, proposal.preferences.single { it.subjectKey == "anime" }.score)
        assertTrue(
            proposal.issues.contains(
                NaturalLanguageProposalIssue.ConflictingTopicInstruction("Anime"),
            ),
        )
        assertTrue(proposal.requiresConfirmation)
    }

    @Test
    fun `blank input cannot produce a change`() {
        val proposal = NaturalLanguageAlgorithmProposalParser.propose("   ")

        assertFalse(proposal.hasChanges)
        assertEquals(listOf(NaturalLanguageProposalIssue.EmptyInput), proposal.issues)
        assertTrue(proposal.requiresConfirmation)
    }
}
