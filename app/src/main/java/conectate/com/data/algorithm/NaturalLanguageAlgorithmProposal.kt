package conectate.com.data.algorithm

import conectate.com.data.model.AlgorithmDimension
import conectate.com.data.model.AlgorithmPreferenceInput
import conectate.com.data.model.AlgorithmSurface
import java.text.Normalizer

/**
 * Local, intentionally conservative interpreter for the optional natural-language
 * helper in "Mi algoritmo".
 *
 * It only produces a *proposal*. It never sends a request, changes a profile, or
 * enables behavioural data. Callers must render the proposal, let the person edit
 * it, and obtain an explicit confirmation before using any of its values in an API
 * request. The parser deliberately knows a small public-topic allowlist instead of
 * treating arbitrary words (or private/sensitive data) as algorithm instructions.
 */
object NaturalLanguageAlgorithmProposalParser {
    private const val POSITIVE_SCORE = 80
    private const val NEGATIVE_SCORE = -100
    private const val MAX_INPUT_LENGTH = 800
    private const val MAX_PREFERENCES = 12

    private val sensitiveRequestTerms = listOf(
        "mensaje privado",
        "mensajes privados",
        "dm",
        "dms",
        "ubicacion",
        "gps",
        "edad",
        "genero",
        "sexo",
    )

    private val topics = listOf(
        Topic("programacion", "Programación", listOf("programacion", "programar", "codigo", "coding", "desarrollo de software")),
        Topic("anime", "Anime", listOf("anime", "manga", "animacion japonesa")),
        Topic("futbol", "Fútbol", listOf("futbol", "football", "soccer")),
        Topic("politica", "Política", listOf("politica", "elecciones", "gobierno")),
        Topic("musica", "Música", listOf("musica", "canciones", "conciertos")),
        Topic("videojuegos", "Videojuegos", listOf("videojuegos", "videojuego", "gaming", "juegos")),
        Topic("cine-series", "Cine y series", listOf("cine", "peliculas", "series")),
        Topic("arte", "Arte", listOf("arte", "ilustracion", "dibujo", "pintura")),
        Topic("cocina", "Cocina", listOf("cocina", "recetas", "gastronomia")),
        Topic("viajes", "Viajes", listOf("viajes", "turismo", "viajar")),
        Topic("ciencia", "Ciencia", listOf("ciencia", "espacio", "astronomia")),
        Topic("educacion", "Educación", listOf("educacion", "aprendizaje", "estudiar")),
        Topic("deportes", "Deportes", listOf("deportes", "basket", "baloncesto", "tenis")),
        Topic("tecnologia", "Tecnología", listOf("tecnologia", "gadgets", "inteligencia artificial")),
        Topic("naturaleza", "Naturaleza", listOf("naturaleza", "animales", "ecologia")),
        Topic("humor", "Humor", listOf("humor", "comedia", "memes")),
    )

    /**
     * Produces only allowlisted, reviewable suggestions. The input text is not
     * retained by this result so UI/analytics code cannot accidentally persist it.
     */
    fun propose(input: String): NaturalLanguageAlgorithmProposal {
        val normalized = normalize(input)
        if (normalized.isBlank()) {
            return NaturalLanguageAlgorithmProposal(
                issues = listOf(NaturalLanguageProposalIssue.EmptyInput),
            )
        }

        val issues = mutableListOf<NaturalLanguageProposalIssue>()
        if (input.length > MAX_INPUT_LENGTH) {
            issues += NaturalLanguageProposalIssue.InputTruncated(MAX_INPUT_LENGTH)
        }
        val bounded = normalized.take(MAX_INPUT_LENGTH)

        sensitiveRequestTerms
            .filter { term -> containsWordOrPhrase(bounded, term) }
            .forEach { term -> issues += NaturalLanguageProposalIssue.SensitiveRequestIgnored(term) }

        val selections = linkedMapOf<String, ParsedTopic>()
        topics.forEach { topic ->
            val matches = topic.matchesIn(bounded)
            if (matches.isEmpty()) return@forEach
            val scoredMatches = matches.map { position -> position to scoreFor(bounded, position) }
            if (scoredMatches.map { it.second }.distinct().size > 1) {
                issues += NaturalLanguageProposalIssue.ConflictingTopicInstruction(topic.label)
            }
            // Last instruction wins, but its inclusion still requires confirmation.
            val (position, score) = scoredMatches.last()
            selections[topic.key] = ParsedTopic(topic, score, position)
        }

        val preferenceInputs = selections.values
            .sortedBy { it.position }
            .take(MAX_PREFERENCES)
            .map { parsed ->
                AlgorithmPreferenceInput(
                    surface = AlgorithmSurface.GLOBAL.wireName,
                    dimension = AlgorithmDimension.TOPIC.wireName,
                    subjectKey = parsed.topic.key,
                    subjectLabel = parsed.topic.label,
                    score = parsed.score,
                )
            }

        if (selections.size > MAX_PREFERENCES) {
            issues += NaturalLanguageProposalIssue.TooManyRecognizedTopics(MAX_PREFERENCES)
        }

        val feedMixChanges = parseFeedMixChanges(bounded, issues)
        if (preferenceInputs.isEmpty() && feedMixChanges.isEmpty() && issues.none { it is NaturalLanguageProposalIssue.EmptyInput }) {
            issues += NaturalLanguageProposalIssue.NoSupportedInstruction
        }

        return NaturalLanguageAlgorithmProposal(
            preferences = preferenceInputs,
            feedMixChanges = feedMixChanges,
            issues = issues.distinct(),
        )
    }

    private fun parseFeedMixChanges(
        text: String,
        issues: MutableList<NaturalLanguageProposalIssue>,
    ): List<NaturalLanguageFeedMixChange> {
        val matches = mutableListOf<FeedMixMatch>()
        FeedMixDimension.entries.forEach { dimension ->
            dimension.aliases.forEach { alias ->
                val escapedAlias = Regex.escape(alias)
                val before = Regex("\\b(\\d{1,3})\\s*%\\s*(?:de\\s+)?$escapedAlias\\b")
                val after = Regex("\\b$escapedAlias\\s+(?:al?|en)\\s*(\\d{1,3})\\s*%")
                before.findAll(text).forEach { match ->
                    matches += FeedMixMatch(dimension, match.groupValues[1].toInt(), match.range.first)
                }
                after.findAll(text).forEach { match ->
                    matches += FeedMixMatch(dimension, match.groupValues[1].toInt(), match.range.first)
                }
            }
        }

        return matches
            .sortedBy { it.position }
            .fold(linkedMapOf<FeedMixDimension, FeedMixMatch>()) { deduplicated, match ->
                if (match.percent !in 0..100) {
                    issues += NaturalLanguageProposalIssue.InvalidPercentage(match.dimension.label, match.percent)
                } else {
                    deduplicated[match.dimension] = match
                }
                deduplicated
            }
            .values
            .map { match -> NaturalLanguageFeedMixChange(match.dimension, match.percent) }
    }

    private fun scoreFor(text: String, position: Int): Int {
        val clauseStart = listOf(
            text.lastIndexOf('.', startIndex = position),
            text.lastIndexOf(',', startIndex = position),
            text.lastIndexOf(';', startIndex = position),
            text.lastIndexOf(':', startIndex = position),
        ).maxOrNull()?.plus(1) ?: 0
        val clause = text.substring(clauseStart, position)

        val explicitNegative = Regex("\\b(no|sin|evita|evitar|menos|bloquea|bloquear|oculta|ocultar)\\b").containsMatchIn(clause)
        val inheritedNegative = Regex("\\bni\\b").containsMatchIn(clause) &&
            Regex("\\bno\\b").containsMatchIn(clause)
        return if (explicitNegative || inheritedNegative) NEGATIVE_SCORE else POSITIVE_SCORE
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun containsWordOrPhrase(text: String, term: String): Boolean =
        Regex("(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])").containsMatchIn(text)

    private data class Topic(
        val key: String,
        val label: String,
        val aliases: List<String>,
    ) {
        fun matchesIn(text: String): List<Int> = aliases
            .flatMap { alias ->
                Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])")
                    .findAll(text)
                    .map { it.range.first }
                    .toList()
            }
            .distinct()
            .sorted()
    }

    private data class ParsedTopic(val topic: Topic, val score: Int, val position: Int)
    private data class FeedMixMatch(val dimension: FeedMixDimension, val percent: Int, val position: Int)
}

/**
 * A proposal from the helper. `requiresConfirmation` is deliberately immutable:
 * no caller should treat parsing as permission to change the server configuration.
 */
data class NaturalLanguageAlgorithmProposal(
    val preferences: List<AlgorithmPreferenceInput> = emptyList(),
    val feedMixChanges: List<NaturalLanguageFeedMixChange> = emptyList(),
    val issues: List<NaturalLanguageProposalIssue> = emptyList(),
    val requiresConfirmation: Boolean = true,
) {
    val hasChanges: Boolean get() = preferences.isNotEmpty() || feedMixChanges.isNotEmpty()

    val confirmationMessage: String
        get() = "Revisa y confirma cada cambio antes de aplicarlo. Esta propuesta no modifica tu algoritmo por sí sola."
}

/** A partial mix only; it must be merged with the currently confirmed mix by the review UI. */
data class NaturalLanguageFeedMixChange(
    val dimension: FeedMixDimension,
    val percent: Int,
)

enum class FeedMixDimension(
    val label: String,
    internal val aliases: List<String>,
) {
    FOLLOWING("Personas que sigues", listOf("personas que sigo", "seguidores", "siguiendo")),
    INTERESTS("Tus intereses", listOf("intereses", "para mi")),
    COMMUNITIES("Comunidades", listOf("comunidades", "comunidad")),
    NEW_CONTENT("Contenido nuevo", listOf("contenido nuevo", "novedades", "nuevos contenidos")),
    TRENDS("Tendencias", listOf("tendencias", "tendencia")),
    EXPLORATION("Exploración", listOf("exploracion", "explorar")),
}

sealed interface NaturalLanguageProposalIssue {
    data object EmptyInput : NaturalLanguageProposalIssue
    data object NoSupportedInstruction : NaturalLanguageProposalIssue
    data class InputTruncated(val maximumCharacters: Int) : NaturalLanguageProposalIssue
    data class SensitiveRequestIgnored(val normalizedTerm: String) : NaturalLanguageProposalIssue
    data class InvalidPercentage(val fieldLabel: String, val providedValue: Int) : NaturalLanguageProposalIssue
    data class ConflictingTopicInstruction(val topicLabel: String) : NaturalLanguageProposalIssue
    data class TooManyRecognizedTopics(val maximumTopics: Int) : NaturalLanguageProposalIssue
}
