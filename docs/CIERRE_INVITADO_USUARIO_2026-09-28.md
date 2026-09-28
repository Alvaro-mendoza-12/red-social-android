# Continuidad móvil: invitado, registro y conexión — 28/09/2026

Checkout: `D:\red social - live-gifts`. Rama: `feature/live-gifts`.

## Correcciones adicionales

| Hallazgo | Cambio |
| --- | --- |
| La disponibilidad del @usuario se guardaba como un booleano separado del texto comprobado. | La autorización para continuar compara el texto actual con el valor exacto validado por el servidor. Una recreación exige comprobarlo de nuevo. |
| Una sesión de invitado podía terminar mientras su alta o renovación seguía en curso. | Las solicitudes conservan una revisión de sesión. Salir o convertir la cuenta invalida las respuestas tardías; no vuelven a guardar el token. Renovar por vencimiento conserva la revisión de esa visita. |
| Búsquedas y vistas podían conservar datos de la consulta anterior. | Se vacían resultados al cambiar la búsqueda y se incluye el debounce en el estado de carga. Perfil, contenido y comentarios cancelan su consulta anterior o cerrada. |
| La identidad mostrada podía seguir siendo la anterior después de renovar el token. | Las lecturas actualizan la identidad visible desde la sesión vigente. |
| El reproductor de invitado no informaba de errores ni pausaba al dejar la app. | Indicador de carga, mensaje accesible, reintento manual, pausa al perder el estado RESUMED y liberación del reproductor al cerrar. |
| Las acciones de una tarjeta podían ocupar más ancho con texto grande. | Los botones pasan a otra línea cuando hace falta mediante FlowRow. |
| La revisión visual al 200 % mostró botones superpuestos en el aviso de invitado. | Las acciones se apilan con separación explícita. El contenido del aviso y del video puede desplazarse; la lista de comentarios deja espacio para su acción. |
| El destino de conversión admitía cadenas de 36 guiones, sin un UUID real. | Se valida y normaliza el UUID al guardar y al consumir; se usa una sola vez. |
| Un enlace recibido con la pantalla de invitado ya abierta guardaba su acción sin mostrar el aviso. | La vista observa el destino pendiente mediante StateFlow y lo consume una sola vez; también procesa enlaces nuevos sin recrear la sesión. |
| Al cambiar tamaño de texto se repetía el enlace ya consumido por Activity.intent. | Al terminar la navegación se limpia también el intent de la actividad, comprobando que siga siendo esa entrega. Los archivos compartidos conservan su ciclo separado. |
| El registro rechazaba un @usuario elegido que coincidiese con el prefijo del correo. | El servidor registra `username_origin` al crear la cuenta. Se conserva la elección explícita en perfil, búsqueda y vista previa; los identificadores heredados derivados del correo siguen ocultos. Las actualizaciones del cliente no cambian ese origen. |
| Una solicitud sintética antigua seguía en administración. | Se comprobó el ID y su identificador de prueba, la cuenta ya ausente, y se eliminó únicamente esa solicitud. Documentado en `IMPLEMENTACION_LIVE_AUTH_2026-09-26.md`. |
| Dos daemons quedaron esperando el bloqueo de compilación incluso después de terminar la tarea anterior. | Se sustituye la captura del canal en `buildFinished` por un BuildService AutoCloseable, con uso declarado en las tareas y espera limitada. Gradle administra la liberación también para consultas del IDE y compilaciones fallidas. |

## Comprobaciones de la primera entrega de este informe

La entrega más reciente, 3.3.22-debug/84, y la revisión física posterior se describen en [VERIFICACION_REQUISITOS_MOVIL_2026-09-28.md](VERIFICACION_REQUISITOS_MOVIL_2026-09-28.md). Esa entrega pasó 871 pruebas Android y 103 comprobaciones del servidor por HTTPS. Los resultados siguientes se conservan como evidencia de la iteración anterior, no como el conteo actual.

- Android: 23 pruebas correctas, sin errores, fallos u omisiones, de `UsernamePolicyTest`, `GuestModeSessionTest`, `ProfileIdentityTest` y `ReadDeadlineTest`. Cubren identidad conservada al reabrir, vencimiento, conversión, consumo único de destinos, UUID inválido, salida, respuesta tardía posterior a la salida, observación de enlaces posteriores y origen del @usuario elegido.
- Se repitió `verify-username-guest-e2e.js` contra el HTTPS público: 78 comprobaciones correctas; 22 rutas de escritura rechazaron el JWT de invitado. Incluye registro, carrera por username, contraseña correcta/incorrecta, recuperación manual, sustitución y consumo del código, segundo factor perdido y nuevo acceso. También comprueba la elección explícita coincidente con el prefijo de correo, búsqueda, vista previa, protección del origen y privacidad de identidades heredadas. Acceso de la cuenta sintética en 680 ms; no es una garantía de latencia para otras conexiones.
- Las lecturas reales de invitado devolvieron 12 videos, 30 publicaciones, cero momentos vigentes y dos resultados en una búsqueda. El estado vacío de momentos corresponde a esa consulta del servidor.
- `/ready` respondió correctamente con base de datos accesible. API, PostgreSQL, edge, realtime, señalización, Redis, coturn y chatbot estaban saludables. Se desplegó `52f948e5c2e5` como `conectate-mini-pc-20260928T105528Z`, con copia de seguridad verificada en `/home/alvaro/conectate-backups/20260928T105534Z`. La suite del backend tuvo 249 pruebas correctas y una omitida por plataforma.

## Ciclo de sesión

El invitado conserva su identidad al cerrar y abrir mientras el token es válido. La duración es de 30 días; al acercarse al vencimiento se genera otra identidad temporal. Borrar datos, reinstalar sin datos restaurados o salir elimina la identidad local. Las reglas de backup incluyen explícitamente solo preferencias generales: no incluyen `guest_session`, `guest_conversion` ni `guest_preview`. Convertir la sesión elimina el token, conserva el destino público validado hasta consumirlo y mantiene idioma y tema.

## Límites y solicitudes anteriores que no se declaran cerradas

- El Motorola no aparecía en ADB durante la primera iteración de este informe. Posteriormente se instaló 3.3.22-debug/84 sin borrar datos y se revisaron pantallas reales a escala normal y al 200 %; su alcance y las capturas figuran en el informe de verificación enlazado.
- TalkBack no está instalado en el emulador actual; la revisión de etiquetas, regiones accesibles y foco no equivale a una sesión manual con ese lector.
- El selector de idioma y `locales_config.xml` tienen ocho variantes: español, inglés, portugués, francés, alemán, japonés y chino simplificado/tradicional. La solicitud previa de **todos los idiomas de Android** no está completada; existen además textos nuevos en español que requieren recursos traducidos. No se configura un proveedor de pago ni se presenta un fallback como traducción completa.
- Las pruebas de salud y señalización no certifican audio bidireccional de salas de voz. Sigue haciendo falta comprobarlo con dos participantes y audio real.
- El informe de videos mantiene pruebas pendientes del editor con más filtros, clips combinados y medios dañados. La validación actual del invitado no cierra esos casos.
- La web continúa después de la prioridad móvil. El informe previo señala que su retorno tras acceso estaba compilado localmente y pendiente de despliegue; no se presenta esta entrega Android como su despliegue.
- No se incluye una entrega release firmada para sustituir instalaciones de producción. La APK de esta iteración es de depuración.

El retiro continúa suspendido según la indicación del usuario.
