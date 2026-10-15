package conectate.com.presentation.messages
import conectate.com.presentation.common.toUserFacingMessage

internal fun conversationMarkerFailureFeedback(error: Throwable, userInitiated: Boolean): String? =
    if (userInitiated) error.toUserFacingMessage("No se pudo actualizar el mensaje guardado. Reintenta.") else null
