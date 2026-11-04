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
@Serializable internal data class AgreementCatalog(val agreements: List<ChatAgreement>)
@Serializable internal data class AgreementReceipt(val agreement: ChatAgreement)
@Serializable internal data class AgreementSourceReceipt(val source: AgreementSource? = null)
@Serializable internal data class AgreementCapability(val supported: Boolean = false, val schema: Int = 0)

internal class AgreementApiException(val statusCode: Int, val code: String) : IOException("No se pudo completar la acción.") {
    // Access can change between commit and response hydration. A 403 does not prove no write occurred.
    val definitive: Boolean get() = statusCode in setOf(400, 401, 404, 409, 422)
}

internal data class AgreementChange(val chatId: String, val accountChannel: String)
