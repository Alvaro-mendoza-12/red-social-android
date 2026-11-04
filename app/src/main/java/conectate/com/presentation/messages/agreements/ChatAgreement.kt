package conectate.com.presentation.messages.agreements

import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable
data class AgreementParticipant(val id: String, val name: String = "Usuario", val response: String = "pending", val respondedAt: String? = null)

@Serializable
data class ChatAgreement(
    val id: String,
    val sourceMessageId: String,
    val authorId: String,
    val text: String,
    val version: Int,
    val proposalRevision: Int,
    val status: String = "pending",
    val cancelled: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val participants: List<AgreementParticipant>,
    val sourceAvailable: Boolean = false,
    val archived: Boolean = false,
) {
    fun validated(): ChatAgreement {
        require(id.isNotBlank() && sourceMessageId.isNotBlank() && text.isNotBlank() && text.length <= 1000)
        require(version in 1..10_000 && proposalRevision in 1..version)
        require(participants.size == 2 && participants.map { it.id }.distinct().size == 2)
        require(participants.any { it.id == authorId } && participants.all { it.id.isNotBlank() && it.response in RESPONSES })
        require(status == computedStatus())
        return this
    }
    fun computedStatus(): String = when {
        cancelled -> "cancelled"
        participants.any { it.response == "withdrawn" } -> "withdrawn"
        participants.any { it.response == "changes" } -> "changes"
        participants.all { it.response == "accepted" } -> "accepted"
        else -> "pending"
    }
    companion object { val RESPONSES = setOf("pending", "accepted", "changes", "withdrawn") }
}

@Serializable
data class AgreementCommand(
    val operationId: String,
    val id: String? = null,
    val sourceMessageId: String? = null,
    val text: String? = null,
    val expectedVersion: Int? = null,
    val action: String? = null,
)

@Serializable data class AgreementSource(val id: String, val content: String)
@Serializable internal data class AgreementCatalog(
    val agreements: List<ChatAgreement>,
    val cursor: String? = null, val nextCursor: String? = null, val previousCursor: String? = null,
    val focus: ChatAgreement? = null, val focusMissing: Boolean = false,
    val archived: Boolean = false, val reset: Boolean = false,
) {
    fun validated(accountId: String, requestedArchive: Boolean, requestedFocus: String?): AgreementCatalog {
        require(archived == requestedArchive && agreements.size <= 50 && agreements.map { it.id }.distinct().size == agreements.size)
        require(listOf(cursor, nextCursor, previousCursor).all { it == null || it.matches(Regex("[A-Za-z0-9_-]{1,512}")) })
        agreements.forEach { it.validated(); require(it.archived == archived && it.participants.any { member -> member.id == accountId }) }
        require(!(focusMissing && focus != null) && (requestedFocus != null || (focus == null && !focusMissing)))
        focus?.let { it.validated(); require(it.id == requestedFocus && it.participants.any { member -> member.id == accountId }) }
        return this
    }
}
@Serializable internal data class AgreementReceipt(val agreement: ChatAgreement)
@Serializable internal data class AgreementSourceReceipt(val source: AgreementSource? = null)
@Serializable internal data class AgreementCapability(
    val supported: Boolean = false, val schema: Int = 0, val archive: Boolean = false, val pagination: Boolean = false,
) { val available: Boolean get() = supported && schema == 1 }

data class AgreementNotice(val operationId: String, val text: String)

internal class AgreementApiException(val statusCode: Int, val code: String) : IOException("No se pudo completar la acción.") {
    // Access can change between commit and response hydration. A 403 does not prove no write occurred.
    val definitive: Boolean get() = statusCode in setOf(400, 401, 409, 422) ||
        (statusCode == 404 && code in setOf("AGREEMENT_NOT_FOUND", "AGREEMENT_SOURCE_UNAVAILABLE"))
}

internal data class AgreementChange(val chatId: String, val accountChannel: String)
