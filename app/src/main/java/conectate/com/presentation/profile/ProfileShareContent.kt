package conectate.com.presentation.profile

internal fun buildProfileInvitation(displayName: String, handle: String, profileUrl: String): String {
    val name = displayName.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(80).ifBlank { "Usuario" }
    val username = handle.removePrefix("@").trim().take(24)
    return "Hola, soy $name (@$username) y estoy en Novvin.\n" +
        "Mira mis publicaciones y conversemos:\n$profileUrl\n\n" +
        "¿No tienes Novvin? Descárgala aquí:\nhttps://play.google.com/store/apps/details?id=conectate.com"
}
