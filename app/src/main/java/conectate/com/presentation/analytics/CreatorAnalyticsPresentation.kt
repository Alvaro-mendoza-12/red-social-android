package conectate.com.presentation.analytics

import conectate.com.data.analytics.CreatorAnalyticsApiException
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.VideoAnalyticsReport

internal fun creatorAnalyticsErrorMessage(error: Throwable): String = when ((error as? CreatorAnalyticsApiException)?.code) {
    "CREATOR_ANALYTICS_DISABLED" ->
        "El servicio no terminó de iniciar. Reintenta para actualizar la conexión."
    "CREATOR_ANALYTICS_OWNER_REQUIRED" ->
        "Solo el creador puede consultar estas estadísticas."
    "CONTENT_NOT_FOUND" ->
        "El contenido ya no está disponible."
    "AUTH_REQUIRED", "USER_NOT_FOUND" ->
        "Tu sesión venció. Inicia sesión nuevamente."
    "BACKEND_UNAVAILABLE" ->
        "No pudimos conectar con el servidor. Revisa tu conexión y vuelve a intentarlo."
    else -> "No pudimos actualizar los datos. Revisa tu conexión y vuelve a intentarlo."
}

internal fun VideoAnalyticsReport.hasConfirmedActivity(): Boolean =
    totals.views > 0 || totals.interactions.run {
        likes > 0 || comments > 0 || shares > 0 || saves > 0 || follows > 0
    }

internal fun LiveAnalyticsReport.hasConfirmedActivity(): Boolean = totals.run {
    peakConcurrent > 0 || follows > 0 || comments > 0 || likes > 0 || gifts > 0 || giftValueCoins > 0
}
