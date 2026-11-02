package conectate.com.presentation.messages

import conectate.com.core.security.InputSecurityPolicy

internal const val MAX_INTERNAL_SHARE_COMMENT = 800

/** Refuse before dispatch instead of letting repository sanitation silently cut shared text. */
internal fun sharedTextError(text: String?, internalContent: Boolean = false): String? {
    val limit = if (internalContent) MAX_INTERNAL_SHARE_COMMENT else InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH
    return if (text.orEmpty().trim().length > limit)
        "El texto supera los $limit caracteres. Acórtalo o compártelo como archivo."
    else null
}
