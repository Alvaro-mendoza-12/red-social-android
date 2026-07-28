package conectate.com.presentation.auth

internal fun passwordChangeValidationError(
    currentPassword: String,
    newPassword: String,
    confirmPassword: String? = null
): String? {
    val current = currentPassword.trim()
    val next = newPassword.trim()
    val confirmation = confirmPassword?.trim()
    return when {
        current.isBlank() -> "Escribe tu contraseña actual."
        next.length < 8 -> "La nueva contraseña debe tener al menos 8 caracteres."
        confirmation != null && next != confirmation -> "Las contraseñas no coinciden."
        current == next -> "La contraseña nueva debe ser diferente de la actual."
        else -> null
    }
}
