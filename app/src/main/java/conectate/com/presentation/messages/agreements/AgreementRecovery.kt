package conectate.com.presentation.messages.agreements

import conectate.com.data.supabase.AuthSessionLease
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
internal data class AgreementDraft(
    val sourceId: String, val text: String = "", val editingId: String? = null, val editingVersion: Int? = null,
) {
    fun validate() {
        require(agreementUuid(sourceId) && text.length <= 1000)
        require(if (editingId == null) editingVersion == null else agreementUuid(editingId) && editingVersion != null && editingVersion in 1..10_000)
    }
}

@Serializable
internal data class AgreementPendingWrite(val agreementId: String, val command: AgreementCommand) {
    fun validate() {
        require(agreementUuid(agreementId) && agreementUuid(command.operationId))
        if (command.action == null) {
            require(command.id == agreementId && agreementUuid(command.sourceMessageId) && command.expectedVersion == null)
            require(!command.text.isNullOrBlank() && command.text.length <= 1000)
        } else {
            require(command.id == null && command.sourceMessageId == null && command.expectedVersion != null && command.expectedVersion in 1..10_000)
            require(command.action in setOf("accept", "changes", "withdraw", "cancel", "revise", "archive", "restore"))
            require(if (command.action == "revise") !command.text.isNullOrBlank() && command.text.length <= 1000 else command.text == null)
        }
    }
}

@Serializable
internal data class AgreementRecovery(
    val schema: Int = 1, val revision: Long = 1,
    val draft: AgreementDraft? = null, val pending: AgreementPendingWrite? = null,
) {
    fun validate(): AgreementRecovery = apply {
        require(schema == 1 && revision in 1 until Long.MAX_VALUE)
        draft?.validate(); pending?.validate()
        pending?.let { write ->
            if (write.command.action == null) requireNotNull(draft).let {
                require(it.sourceId == write.command.sourceMessageId && it.text == write.command.text && it.editingId == null)
            }
            if (write.command.action == "revise") requireNotNull(draft).let {
                require(it.editingId == write.agreementId && it.editingVersion == write.command.expectedVersion && it.text == write.command.text)
            }
        }
    }
}

internal interface AgreementRecoveryPersistence {
    suspend fun read(lease: AuthSessionLease, chatId: String): AgreementRecovery?
    suspend fun replace(lease: AuthSessionLease, chatId: String, expected: AgreementRecovery?,
                        draft: AgreementDraft?, pending: AgreementPendingWrite?): AgreementRecovery
}

internal class AgreementRecoveryConflict : IOException("El borrador cambió en otra pantalla. Recupera el estado guardado antes de continuar.")

internal fun agreementUuid(value: String?): Boolean = value != null &&
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
