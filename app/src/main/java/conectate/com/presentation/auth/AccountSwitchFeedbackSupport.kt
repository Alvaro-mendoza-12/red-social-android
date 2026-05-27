package conectate.com.presentation.auth

import conectate.com.data.supabase.isSupabaseServiceRestrictionMessage

private const val ACCOUNT_SWITCH_SUCCESS_MESSAGE =
    "Cuenta cambiada correctamente."

private const val ACCOUNT_SAVE_SUCCESS_MESSAGE =
    "Cuenta guardada y actualizada para cambio rápido."

internal fun isPositiveAccountSwitchMessage(message: String?): Boolean {
    val normalized = message?.trim().orEmpty()
    if (normalized.isBlank()) return false
    return normalized == ACCOUNT_SWITCH_SUCCESS_MESSAGE ||
        normalized == ACCOUNT_SAVE_SUCCESS_MESSAGE
}

internal fun accountSwitchMessageAutoClearDelayMs(message: String?): Long? {
    val normalized = message?.trim().orEmpty()
    if (normalized.isBlank()) return null
    if (isSupabaseServiceRestrictionMessage(normalized)) return null
    return if (isPositiveAccountSwitchMessage(normalized)) 2_600L else 4_200L
}
