# Mensajería, recomendaciones y avisos de Novvin

Fecha: 2026-10-01. Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Versión mantenida: `versionCode 117`, `versionName 9.0.20`.

## Correcciones comprobadas

| Hallazgo | Corrección | Evidencia y alcance |
|---|---|---|
| Una cuenta nueva recibía el primer mensaje, pero rechazaba fotos posteriores | El trigger ya permite continuar la conversación mientras la solicitud de amistad está pendiente. Bloqueo y rechazo explícito siguen vigentes | Dos cuentas temporales: foto y PDF con comentario, recepción, reintento con el mismo ID y una sola solicitud de amistad. HTTP 201/200; bloqueos y medios ajenos rechazados |
| Mensajes fallidos permanecían con reloj | Estado de error actualizado en la conversación y acción Reintentar que conserva archivo, comentario e ID | Pruebas de controles nativos y envío real por API; no certifica todas las combinaciones de fabricantes |
| Archivos pesados sin información del envío | Porcentaje real durante la carga, estado de procesamiento al terminar la transferencia | Control nativo de progreso probado; no se inventa un porcentaje mientras se espera respuesta |
| Fotos compartidas desde otra app sin comentario | Campo opcional antes de seleccionar/enviar al destino | Implementación conectada con el envío; recepción del comentario comprobada por API. Flujo completo desde WhatsApp físico pendiente |
| Encuestas difíciles de cambiar y votantes con ID | Quitar voto, cambiar opción, consultar votantes con nombre público y avatar; cambios de voto serializados | Controles nativos comprobados. Los mensajes de encuesta y sticker tienen vistas previas específicas |
| Sobre de perfil poco claro | Botón Mensaje con icono de conversación y etiqueta visible | Pantalla de componente a 280 dp, texto grande y ambos temas |
| Social volvía a Seguidos | Descubrir al entrar; ya no se restaura esa selección entre aperturas | Cambio de preferencia y navegación; sin certificación física de todos los estados restaurados |
| Videos iguales en distintas cuentas | Solicitud personalizada también desde instalación fría; SHA-256 para orden local; exploración ponderada en el servidor | Tres cuentas nuevas, 24 páginas y tres repeticiones estables. La muestra real solo tenía dos videos locales elegibles: se observaron sus dos órdenes posibles. Test de 20 candidatos prueba tres secuencias distintas |
| Las categorías de videos no se guardaban | Una sola actualización final de la fila en el CTE de PostgreSQL; bloqueo y reescritura de temas | Prueba SQL real con tablas temporales: creación, reescritura del mismo tema y retirada de temas antiguos. Reparación de clasificaciones: 51 correctas, 1 excluida, 0 fallos |

## Avisos de actualización, novedades y mantenimiento

En **Panel de Administrador → Avisos** se puede:

1. Elegir Novedad, Mantenimiento o Actualización.
2. Escribir título y mensaje. Para mantenimiento, elegir inicio y fin; se muestra la zona horaria. Para actualización, indicar el código de versión que ya está publicado en Google Play.
3. Revisar la vista previa y confirmar la publicación para todas las cuentas.
4. Consultar avisos anteriores y retirar un aviso.

Cada publicación crea una notificación interna por cuenta y una entrega persistente para push/realtime. El trabajador reintenta las entregas que fallan y conserva la cola tras reiniciar. Repetir la misma publicación con el mismo ID no duplica las notificaciones. El servidor exige permiso `admin.system.manage`; un usuario normal recibe HTTP 403, y un visitante sin sesión recibe HTTP 401 al acceder a administración.

Al abrir o regresar a Android se consultan los avisos. Se conserva caché válida y se recuerdan los descartados por cuenta. Los avisos de actualización solo aparecen a versiones anteriores, con **Actualizar / Más tarde**. Actualizar abre la ficha oficial de Google Play; no descarga ni instala un APK dentro de Novvin. Los avisos de mantenimiento informan el horario; no suspenden automáticamente los servicios.

La notificación del sistema depende de que el usuario haya permitido notificaciones y de las condiciones de Android/FCM. El aviso interno sigue disponible. No es posible garantizar una notificación inmediata a un teléfono sin conexión. Un dispositivo que nunca descargó el aviso tampoco puede obtenerlo con el servidor apagado: publicar antes del mantenimiento.

La prueba de publicación y entrega se ejecutó con tres usuarios en **tablas temporales**, no con usuarios reales. Comprobó notificaciones persistidas, idempotencia, filtrado por versión, retirada y entrega simulada a push/realtime. No se publicó ningún aviso global de QA ni se midió recepción física de este nuevo tipo de push en Motorola.

## Pruebas y servidor

- Android: APK y APK de instrumentación compiladas; **1.033 pruebas unitarias, cero fallos**.
- Backend: **343 pruebas, 341 correctas, 2 omitidas por plataforma, cero fallos**.
- Pruebas nativas: avisos de mantenimiento/actualización, vista previa administrativa, progreso/reintento, encuesta y botón Mensaje; capturas en [evidencias](../evidencias-mensajeria-web-2026-10-01/).
- Mensajería nueva: cargas y envíos de muestra entre 50 y 259 ms; no es una garantía para archivos grandes, datos móviles ni todas las regiones.
- Recomendaciones: páginas de muestra entre 28 y 157 ms; repetición de sesión hasta 329 ms.
- Backend actualizado mediante reconstrucción de imagen y recreación del servicio API; `/ready` confirmó base de datos y push configurado. Se conservó el respaldo verificado `20261001T190829Z`.
- Todas las cuentas temporales creadas para estas pruebas se borraron. Las pruebas SQL aisladas no escribieron registros de producción.

## Límites y trabajo que continúa

El Motorola no aparece en ADB durante esta revisión. Las capturas son del emulador y demuestran componentes concretos, no cada flujo de la aplicación completa. Las llamadas físicas que el usuario informó como correctas se registran como observación del usuario. Este documento no certifica 60 FPS de LIVE, latencia cero de voz, todas las funciones ni preparación completa para producción.

Continúan la paridad web, revisión de recuperación por correo en navegador, LIVE web y publicación en Vercel. También deben contrastarse reintentos de grupos/comunidades bajo concurrencia y las encuestas contra el backend, además de pruebas físicas de archivos compartidos. Se conserva la versión de Android solicitada; el incremento sigue pendiente.

## Criterios de interacción

Se aplican estado visible del sistema, lenguaje familiar, control del usuario, prevención de errores, reconocimiento y recuperación del error de las [heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/). La opción de actualización abre Google Play; una integración futura de actualización dentro de la app debe seguir los [flujos oficiales de Android](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java).
