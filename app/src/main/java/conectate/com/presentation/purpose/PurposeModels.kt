package conectate.com.presentation.purpose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val PURPOSE_BASE = "/v1/experimental/purpose"
internal fun purposeUuid(value: String) = runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)

@Serializable
data class PurposeTarget(val kind: String, val id: String? = null, val relatedId: String? = null) {
    init {
        require(kind in setOf("social", "community", "gaming", "plan", "proposal"))
        require(if (kind == "gaming") id == null else id != null && purposeUuid(id))
        require(if (kind == "proposal") relatedId != null && purposeUuid(relatedId) else relatedId == null)
    }
    internal val key get() = "$kind:${id.orEmpty()}:${if(kind == "proposal") "" else relatedId.orEmpty()}"
    internal val readPath get() = when (kind) {
        "social" -> "$PURPOSE_BASE/posts/$id/outcome"
        "community" -> "$PURPOSE_BASE/communities/$id"
        "gaming" -> "$PURPOSE_BASE/gaming"
        "plan" -> "$PURPOSE_BASE/gaming/$id"
        else -> "$PURPOSE_BASE/gaming/with/$id"
    }
    internal fun writePath(part: String?) = when (kind) {
        "social" -> readPath
        "community" -> { require(part in setOf("guide", "community_start")); "$readPath/$part" }
        "plan" -> "$readPath/actions"
        "proposal" -> "$readPath/$relatedId"
        else -> error("Elige una partida antes de responder.")
    }
}
@Serializable internal data class PurposeCapability(val supported: Boolean = false, val schema: Int = 0)
@Serializable data class PurposeContribution(val id: String, val authorId: String? = null, val authorName: String = "Usuario", val content: String = "", val available: Boolean = true)
@Serializable data class PostOutcome(val postId: String, val authorId: String, val status: String, val conclusion: String = "", val contributionIds: List<String> = emptyList(), val revision: Int, val updatedAt: String)
@Serializable data class PostOutcomeCatalog(val outcome: PostOutcome? = null, val postId: String, val postText: String = "", val canEdit: Boolean = false, val eligible: List<PurposeContribution> = emptyList(), val contributions: List<PurposeContribution> = emptyList())
@Serializable data class ContributionTarget(val kind: String, val id: String, val title: String, val startsAt: String? = null, val expiresAt: String? = null, val messageId: String? = null)
@Serializable data class CommunityFirstStep(val targetKind: String? = null, val targetId: String? = null, val opened: Boolean = false, val outcome: String? = null, val revision: Int)
@Serializable data class CommunityGuideOffer(val active: Boolean = false, val expiresAt: String, val revision: Int)
@Serializable data class CommunityPurposeCatalog(val communityId: String, val targets: List<ContributionTarget> = emptyList(), val journey: CommunityFirstStep? = null, val guide: CommunityGuideOffer? = null, val serverTime: String)
@Serializable data class PurposePlayer(val id: String, val name: String)
@Serializable data class PurposeGame(val id: String, val title: String)
@Serializable data class GamingPlan(val id: String, val authorId: String, val participants: List<PurposePlayer>, val gameId: String, val gameTitle: String = "Juego", val objective: String, val startsAt: String, val durationMinutes: Int, val revision: Int, val planRevision: Int, val accepted: Map<String, Int> = emptyMap(), val confirmed: Map<String, Int> = emptyMap(), val status: String, val myOutcome: String? = null, val serverTime: String)
@Serializable data class GamingPlanCatalog(val plans: List<GamingPlan> = emptyList(), val recentLimit: Int = 20)
@Serializable data class GamingPlanReceipt(val plan: GamingPlan, val games: List<PurposeGame> = emptyList())
@Serializable data class GamingProposalContext(val participants: List<PurposePlayer>, val games: List<PurposeGame>)
@Serializable internal data class PurposePending(val path: String, val command: JsonObject)
@Serializable internal data class PurposeRecovery(val revision: Long = 0, val draft: JsonObject? = null, val pending: PurposePending? = null)
internal fun PurposeState.contentRevision(): Int = ((payload?.get(if(target?.kind == "social") "outcome" else "plan")) as? JsonObject)?.get("revision")?.jsonPrimitive?.intOrNull ?: 0
internal val PurposeState.draftStale get() = payload != null && draft?.get("baseRevision")?.jsonPrimitive?.intOrNull?.let { it != contentRevision() } == true
internal val PurposeState.canAct get() = supported && recoveryReady && !loading && !busy && !pending
internal fun purposeContributionChoices(data: PostOutcomeCatalog,selected: Set<String>): List<PurposeContribution> =
    (data.contributions + data.eligible + selected.map { PurposeContribution(it,available = false) }).distinctBy { it.id }

internal class PurposeApiException(val status: Int, val code: String) : java.io.IOException(purposeError(code))
internal class PurposeRecoveryConflict : java.io.IOException("El borrador guardado cambió en otra pantalla. Revísalo antes de continuar.")
internal fun purposeError(code: String) = when (code) {
    "PURPOSE_REVISION_CONFLICT" -> "Alguien actualizó este contenido. Actualiza para revisar los cambios; tu borrador sigue aquí."
    "PURPOSE_REFERENCE_UNAVAILABLE", "PURPOSE_ORIGIN_UNAVAILABLE" -> "El contenido de origen ya no está disponible. Actualiza para revisar las opciones."
    "PURPOSE_ACCESS_DENIED", "PURPOSE_NOT_FOUND" -> "Este contenido ya no está disponible para tu cuenta."
    "PURPOSE_AUTHOR_REQUIRED" -> "Solo quien creó la propuesta puede editar su contenido."
    "PURPOSE_CONNECTION_REQUIRED" -> "Ambos deben aceptar la conexión de PlayMatch antes de acordar una partida."
    "PURPOSE_ADULT_REQUIRED" -> "Confirma el acceso para mayores de edad en tu perfil de Juegos."
    "PURPOSE_GAME_UNAVAILABLE" -> "Elige un juego y una plataforma compatibles en ambos perfiles."
    "PURPOSE_PLAN_EXPIRED" -> "Esta propuesta venció. Quien la creó puede replanificarla."
    "PURPOSE_PLAN_LIMIT" -> "Ya tienes diez partidas futuras propuestas. Revisa o cancela una antes de crear otra."
    "PURPOSE_RECONFIRM_UNAVAILABLE" -> "Podrán reconfirmar cuando ambos acepten y falte una hora o menos."
    "PURPOSE_DATE_INVALID" -> "Elige una fecha entre quince minutos y treinta días a partir de ahora."
    "PURPOSE_TOO_EARLY" -> "Puedes registrar el resultado a partir de la hora acordada."
    else -> "No se pudo completar la acción. Revisa el estado e inténtalo de nuevo."
}
