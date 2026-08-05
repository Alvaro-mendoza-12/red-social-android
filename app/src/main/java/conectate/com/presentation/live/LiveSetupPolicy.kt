package conectate.com.presentation.live

import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource

internal const val LIVE_TITLE_MAX_LENGTH = 80
internal const val LIVE_HASHTAG_LIMIT = 10

internal val LIVE_CATEGORIES = listOf(
    "Charlando",
    "Gaming",
    "Música",
    "Educación",
    "Tecnología",
    "Arte",
    "Deportes",
    "Noticias"
)

internal data class LiveSetupReadiness(
    val canStart: Boolean,
    val titleMessage: String?,
    val checks: List<LivePreflightCheck>
)

internal data class LivePreflightCheck(
    val label: String,
    val ready: Boolean,
    val detail: String
)

internal fun evaluateLiveSetup(
    title: String,
    category: String,
    source: LiveSource,
    micEnabled: Boolean,
    quality: LiveQuality,
    networkAvailable: Boolean
): LiveSetupReadiness {
    val cleanTitle = title.trim()
    val titleMessage = when {
        cleanTitle.isBlank() -> "Escribe un título para que la gente sepa de qué trata el live."
        cleanTitle.length < 4 -> "El título debe tener al menos 4 caracteres."
        cleanTitle.length > LIVE_TITLE_MAX_LENGTH -> "El título admite hasta $LIVE_TITLE_MAX_LENGTH caracteres."
        else -> null
    }
    val categoryReady = category in LIVE_CATEGORIES
    val sourceDetail = when (source) {
        LiveSource.CAMERA -> "La cámara se solicitará al entrar al estudio."
        LiveSource.SCREEN -> "Android pedirá permiso para compartir en cada sesión."
        LiveSource.AUDIO -> "Solo se publicará tu voz; se mostrará tu foto de perfil."
    }
    val qualityDetail = when (quality) {
        LiveQuality.P480 -> "Ahorra datos y tolera redes inestables."
        LiveQuality.P720 -> "Equilibrio recomendado entre nitidez y estabilidad."
        LiveQuality.P1080 -> "Requiere buena red y un dispositivo con margen térmico."
    }

    return LiveSetupReadiness(
        canStart = titleMessage == null && categoryReady && networkAvailable,
        titleMessage = titleMessage,
        checks = listOf(
            LivePreflightCheck(
                label = "Información",
                ready = titleMessage == null && categoryReady,
                detail = if (categoryReady) "Título y categoría completos." else "Selecciona una categoría."
            ),
            LivePreflightCheck(
                label = "Fuente",
                ready = true,
                detail = sourceDetail
            ),
            LivePreflightCheck(
                label = "Audio",
                ready = true,
                detail = if (micEnabled) "Micrófono activo al comenzar." else "Comenzarás con el micrófono silenciado."
            ),
            LivePreflightCheck(
                label = "Calidad ${quality.label}",
                ready = true,
                detail = qualityDetail
            ),
            LivePreflightCheck(
                label = "Conexión",
                ready = networkAvailable,
                detail = if (networkAvailable) "Hay una red disponible." else "Conéctate a internet antes de iniciar."
            )
        )
    )
}

internal fun parseLiveHashtags(raw: String, category: String): List<String> = buildList {
    if (category in LIVE_CATEGORIES) {
        add(category.lowercase().replace('ú', 'u').replace('í', 'i').replace('ó', 'o'))
    }
    addAll(
        raw.split(',', ' ', '\n', '\t')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.removePrefix("#").trim().lowercase() }
            .filter(String::isNotBlank)
    )
}.distinct().take(LIVE_HASHTAG_LIMIT)
