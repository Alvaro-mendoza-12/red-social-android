package conectate.com.presentation.auth

internal fun passwordChangeValidationError(
    currentPassword: String,
    newPassword: String,
    confirmPassword: String? = null
): String? {
    val current = currentPassword
    val next = newPassword
    val confirmation = confirmPassword
    return when {
        current.isEmpty() -> "Escribe tu contraseña actual."
        next.length < 8 -> "La nueva contraseña debe tener al menos 8 caracteres."
        confirmation != null && next != confirmation -> "Las contraseñas no coinciden."
        current == next -> "La contraseña nueva debe ser diferente de la actual."
        else -> null
    }
}
