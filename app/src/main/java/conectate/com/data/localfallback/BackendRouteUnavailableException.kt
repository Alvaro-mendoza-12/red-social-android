package conectate.com.data.localfallback

/** The connected backend is older than the APK and has not registered this route yet. */
class BackendRouteUnavailableException : IllegalStateException(
    "Esta función requiere una actualización del servidor. Inténtalo más tarde."
)
