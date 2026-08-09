package conectate.com.presentation.gaming

import conectate.com.data.gaming.PlayMatchApiException
import conectate.com.data.model.PlayMatchGameCatalogItem

object PlayMatchUiPolicy {
    fun gameGridColumns(widthDp: Int, fontScale: Float = 1f): Int = when {
        fontScale >= 1.5f && widthDp < 600 -> 1
        widthDp >= 840 -> 4
        widthDp >= 600 -> 3
        widthDp >= 360 -> 2
        else -> 1
    }

    fun rankOptions(game: PlayMatchGameCatalogItem?, platform: String): List<Pair<String, String>> {
        val schema = game?.rankSchemas.orEmpty().firstOrNull {
            it.platform.isNullOrBlank() || it.platform.equals(platform, ignoreCase = true)
        }
        return schema?.ranks?.map { it.key to it.displayName }
            ?: game?.ranks.orEmpty().map { it to it }
    }

    fun roleOptions(game: PlayMatchGameCatalogItem?): List<Pair<String, String>> =
        game?.roleDefinitions.orEmpty().map { it.key to it.displayName }
            .ifEmpty { game?.roles.orEmpty().map { it to it } }

    fun showsOfficialRank(game: PlayMatchGameCatalogItem?): Boolean =
        game?.rankedSupported == true && (
            game.rankSchemas.any { it.ranks.isNotEmpty() } || game.ranks.isNotEmpty()
        )

    fun showsPlayMatchLevel(game: PlayMatchGameCatalogItem?): Boolean =
        game != null && !showsOfficialRank(game)

    fun safeError(error: Throwable): String = when ((error as? PlayMatchApiException)?.code) {
        "PLAYMATCH_DISABLED" -> "Juegos todavía no está habilitado para esta cuenta de prueba."
        "PLAYMATCH_NOT_READY" -> "Juegos está temporalmente en preparación."
        "BACKEND_UNAVAILABLE" -> "No hay conexión con el servidor. Comprueba Internet e inténtalo otra vez."
        "ADULT_CONFIRMATION_REQUIRED" -> "Confirma que eres mayor de edad antes de buscar compañeros."
        "PROFILE_NOT_DISCOVERABLE" -> "Activa la visibilidad del perfil gamer para comenzar la búsqueda."
        "ADD_GAME_REQUIRED" -> "Agrega al menos un juego antes de buscar compañeros."
        "PLAYER_BLOCKED" -> "Esta interacción no está disponible."
        "RATE_LIMITED" -> "Has realizado varias acciones. Espera un momento y vuelve a intentarlo."
        "VERSION_CONFLICT" -> "Tu perfil cambió en otro dispositivo. Se cargará la versión más reciente."
        "GAME_NOT_FOUND" -> "Ese juego ya no está disponible en el catálogo."
        "PLATFORM_NOT_SUPPORTED" -> "La plataforma seleccionada no está disponible para ese juego."
        "RANK_NOT_SUPPORTED" -> "El rango seleccionado ya no está disponible."
        "ROLE_NOT_SUPPORTED" -> "El rol seleccionado ya no está disponible."
        "SESSION_NOT_CANCELLABLE" -> "La búsqueda ya terminó y no se puede cancelar."
        else -> error.message?.takeIf { it.isNotBlank() && it.length <= 160 }
            ?: "No se pudo completar la acción. Inténtalo nuevamente."
    }
}
