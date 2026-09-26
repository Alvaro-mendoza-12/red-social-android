package conectate.com.presentation.profile

import androidx.compose.runtime.compositionLocalOf
import java.text.Normalizer
import java.util.Locale

internal data class SettingsDirectoryState(
    val query: String,
    val expanded: String?,
    val toggle: (String) -> Unit,
)

internal val LocalSettingsDirectory = compositionLocalOf<SettingsDirectoryState?> { null }

internal object SettingsDirectory {
    private val keywords = mapOf(
        "Actividad" to "mensajes llamadas chats sonidos lectura notificaciones videos datos descargas reproduccion multimedia social radar directos comunidades perfil apariencia tema accesibilidad preferencias contenido sensible ofensivos algoritmo creadores mezcla monedas pagos saldo recargas montos historial",
        "Centro de actividad" to "estadisticas creador audiencia retencion rendimiento videos directos notificaciones menciones seguidores seguidos reacciones comentarios",
        "Privacidad de la Cuenta" to "cuenta privada favoritos publicos",
        "Visibilidad del Perfil" to "quien puede verme todos amigos ocultar",
        "Correos de Inicio de Sesión" to "email correo login acceso secundario",
        "Cuenta y seguridad" to "contraseña telefono correo autenticacion datos editar",
        "Ambiente" to "personalizar tema colores apariencia",
        "Copia de seguridad" to "backup exportar importar restaurar preferencias google android nube",
        "Tomar un descanso" to "silencio notificaciones pausar ocultar perfil bienestar",
        "Pantalla" to "oscuro claro idioma lenguaje avatar animado sonido accesibilidad",
        "Privacidad y legales" to "politicas terminos condiciones datos derechos",
        "Soporte y Ayuda" to "problemas errores ayuda contacto administrador",
        "Novvin Web" to "computadora navegador qr vincular dispositivos sesiones cerrar",
        "Cambio rápido de cuenta" to "cuentas agregar cambiar sesion",
        "Administración" to "admin moderacion reportes",
        "Información de la versión" to "version actualizacion compilacion",
        "General" to "cerrar sesion salir",
    )

    fun matches(title: String, query: String): Boolean {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        val haystack = normalize("$title ${keywords[title].orEmpty()}")
        return terms.all(haystack::contains)
    }

    fun hasMatches(query: String, includeAdmin: Boolean): Boolean = keywords.keys
        .filter { includeAdmin || it != "Administración" }
        .any { matches(it, query) }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
}
