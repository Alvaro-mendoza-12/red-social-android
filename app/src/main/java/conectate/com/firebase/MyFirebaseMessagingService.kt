package conectate.com.firebase

/**
 * LEGACY ALIAS (no usar en código nuevo)
 *
 * Este paquete existía en versiones anteriores. Para evitar duplicación y
 * mantener compatibilidad si algún import viejo quedó en el proyecto,
 * lo mapeamos directamente al servicio real:
 * [conectate.com.data.service.MyFirebaseMessagingService].
 *
 * El AndroidManifest.xml debe apuntar a .data.service.MyFirebaseMessagingService.
 */
@Deprecated("Usa conectate.com.data.service.MyFirebaseMessagingService")
typealias MyFirebaseMessagingService = conectate.com.data.service.MyFirebaseMessagingService
