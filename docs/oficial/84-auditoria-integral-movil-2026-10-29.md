# 84 — Auditoría móvil integral y candidato 170

Fecha: 29/10/2026, America/Bogota. Continúa la [revisión 83](83-capturas-chat-y-recuperacion-voz-2026-10-28.md). Base de trabajo: `fe59535a` en main y `d97b2d15` en feature/live-gifts. La publicación anterior sigue siendo 169; esta tanda requiere una autorización nueva antes de modificar producción.

## Alcance y criterio

Se sigue el orden solicitado: **Mensajes → Videos → Social → Juegos → Perfil**. Se revisan pantallas, rutas, ViewModels, repositorios y contratos HTTP relacionados. El inventario local incluye 376 archivos y 2.727 declaraciones de funciones de esos módulos, incluidos sus componentes y LIVE. Ese inventario es un mapa de código, **no significa que se ejecutaron 2.727 recorridos**.

No se rediseñan las pantallas ni se cambian tecnologías. Se reutilizan Room, StateFlow, coroutines, el reproductor y los repositorios existentes. No se añaden dependencias, migraciones ni servicios. Los plazos se aplican a lecturas interactivas; no se reintentan automáticamente mutaciones con resultado ambiguo. Las acciones de escritura muestran éxito después de la confirmación y evitan envíos duplicados durante una solicitud.

## 1. Mensajes, grupos, comunidades y voz

| Recorrido revisado | Causa encontrada | Corrección |
|---|---|---|
| Buscar personas y crear grupos/comunidades/salas | Búsquedas sin cancelación/generación; indicador activo al limpiar una consulta; doble toque antes de despachar la creación | Debounce y plazo de lectura, descarte por consulta/cuenta, captura de participantes e imagen, bloqueo antes de lanzar la creación |
| Abrir detalles de grupo/comunidad | Espera indefinida de una fila Room cuando falla la sincronización; observadores repetidos en grupos | Una sesión de observación por destino/cuenta, plazo inicial de 12 s, error y reintento explícitos |
| Entrar a comunidad | El mecanismo de deduplicación rechazaba la recarga del mismo ID después de confirmar la entrada | Recarga forzada que cancela la sesión anterior |
| Salir, eliminar, quitar miembros, cambiar roles y editar datos | Algunos resultados se ignoraban o las respuestas tardías actuaban sobre otra cuenta/destino | `CollectionActionRunner`: una operación pendiente por clave, confirmación antes de navegar, error comprensible y guardas de identidad |
| Invitaciones, descanso, perfiles y eventos comunitarios | Doble solicitud o publicación de resultados tardíos | Mismo control de confirmación/cuenta; lectura de miembros, roles, perfiles y eventos con plazo |
| Editar mensajes privados, de grupo y comunidad | Una confirmación tardía podía restaurar el borrador anterior sobre una edición nueva; falta de `finally` | Snapshot con revisión del compositor, identidad de conversación/cuenta y liberación de estado de envío |
| Buscar salas | Una solicitud sin respuesta podía retener el diálogo | Lectura acotada y estado de error reintentable |
| Editar sala de voz | El repositorio solo escribía Supabase, aun usando Ubuntu; la interfaz cambiaba aunque fallara el guardado | PATCH nativo hacia Ubuntu, actualización visual solo al confirmar. API autenticada con permiso de propietario y actualización atómica |

La API nueva conserva el hash de contraseña privada si no se cambia, valida la contraseña nueva antes de escribir, no devuelve secretos y conserva el recuento de participantes. Las salas administradas por comunidades no se editan mediante esta ruta independiente. El borrado nativo evita solicitudes duplicadas y, si falla, restaura únicamente la sala retirada, siempre en la misma cuenta, sin reemplazar el directorio actualizado. Tres regresiones unitarias adicionales cubren esa recuperación. La ruta nueva todavía no está publicada. Las salas importadas se materializan conservando UUID, canal y alias; el borrado deja una marca persistente que excluye la copia antigua tanto del directorio como de la búsqueda. Una marca de borrado o cambio de propietario concurrente impide reactivar la sala.

También se contrastaron los caminos existentes de envío/cola local, multimedia, borradores Unicode, portapapeles, respuestas, reacciones, reenvío, selección/borrado, paginación, canales, permisos y notificaciones con sus repositorios y pruebas. Eso no equivale a una conversación real entre dos dispositivos en cada caso; las comprobaciones físicas pendientes figuran abajo.

## 2. Videos y LIVE

**Caso Mensajes → + → Buscar → Videos → abrir resultado.** La búsqueda real y el detalle del video de muestra respondieron correctamente y devolvieron el mismo ID con archivo reproducible. La causa de bloqueo localizada en código fue una espera circular: `VideoFeedScreen` ocultaba `VerticalPager` hasta posicionarlo, pero `scrollToPage` espera que ese pager haya completado su primer layout. Se usa ahora `requestScrollToPage`, que solicita la posición sin suspender antes de liberar la pantalla. Se añaden regresiones nativas para detalle ya cargado y detalle que llega después.

Referencia técnica: [PagerState, documentación oficial Android](https://developer.android.com/reference/kotlin/androidx/compose/foundation/pager/PagerState). La prueba nativa del efecto compartido no se presenta como una prueba autenticada completa de todas las rutas.

- Búsqueda del feed, páginas de perfil, historial, Ver después y colecciones: lecturas con plazo; cancelación conservada; errores y reintento en páginas de perfil.
- Colecciones: un borrado fallido conserva la colección seleccionada; salir de la selección libera el indicador de carga.
- Ranking: videos, usuarios y publicaciones no tenían manejador de pulsación. Se conectan a sus destinos existentes. Las tres consultas paralelas mantienen el indicador hasta terminar y permiten resultados parciales con reintento.
- Repeticiones LIVE: solicitud reemplazable por ID, lectura con plazo, error reiniciado y botón de reintento.
- Se revisaron la resolución de medios, páginas del feed, comentarios/reacciones, subida y recuperación, caché, entradas desde perfil/notificación/búsqueda y preparación/admisión LIVE. No se iniciaron directos ni se emitió audio en producción para probarlos.

## 3. Social, Momentos y Rondas

- **Borrado de momento rechazado:** el elemento desaparecía localmente y el fallo solo se registraba. El repositorio restaura únicamente el momento rechazado que siga vigente; mantiene los elementos nuevos recibidos mientras tanto y no reemplaza versiones ya actualizadas. La interfaz deduplica el borrado y comunica el fallo.
- **Audiencia de momentos:** búsqueda cancelable con debounce, plazo y descarte por consulta/cuenta.
- **Rondas:** la consulta de participación enviaba el ID maestro también al backend de sesiones. Ahora Ubuntu recibe el ID de instancia y el almacenamiento Supabase conserva su ID maestro. Los cambios de token de una misma cuenta ya no reinician la participación.
- Los cuatro modos de participación comparten protección contra duplicados, confirmación, cuenta/ronda actual y liberación de estado. Las lecturas de participaciones son acotadas; el repositorio conserva la cancelación.
- Se revisaron publicación/edición/visibilidad/remix, respuestas y reacciones, momentos y música, expiración, rondas y votaciones, descubrimiento social, proximidad/botellas y navegación al contenido asociado. Las escrituras destructivas no se ensayaron contra cuentas reales de producción.

## 4. Juegos

El código actual de esta pestaña es **PlayMatch**: descubrimiento de jugadores y catálogo de juegos, perfil gamer, disponibilidad, preferencias, conexiones, sesiones de búsqueda de compañeros y decisiones de match. No contiene motores separados de videojuegos instalados; cada entrada del catálogo usa el mismo contrato de datos.

Se inventariaron seis fuentes y 81 funciones, y se inspeccionaron los recorridos de inicio, filtros, debounce, paginación, fotos, catálogo, perfil, contacto/bloqueo/reporte, sesión, aceptación/cancelación y retorno. Se refuerzan las lecturas secundarias de perfil/jugador/sesión y polling con plazo y cancelación de solicitudes obsoletas. Se mantiene el aislamiento por cuenta y el control de capacidades del servidor. No se genera matchmaking real entre usuarios de producción como ensayo.

## 5. Perfil y configuración

- Las cinco suscripciones de contenido podían acumularse y una respuesta del perfil anterior reemplazaba listas actuales. Ahora cada pestaña tiene una suscripción reemplazable, vinculada al ticket de perfil/cuenta. Cambiar de propietario/destino vacía las listas anteriores; recargar el mismo conserva el contenido visible.
- Los errores de contenido tienen aviso y reintento. Se mantienen los permisos del servidor para favoritos privados.
- La lista de seguidores/seguidos/amigos ya no falla por una ruta incompleta, cancela la solicitud anterior y termina con resultado o error acotado.
- Ajustes cancela el trabajo de la cuenta anterior y elimina del estado visible códigos de recuperación, configuración 2FA y sesiones. Una entrada sin sesión termina con un mensaje.
- Guardado de apariencia, 2FA, rotación de códigos y eliminación de cuenta bloquean antes de despachar y liberan sus indicadores en `finally`. El refresco posterior al guardado tiene plazo. Los cambios paralelos de preferencias usan la copia actual del perfil.
- Se revisaron edición, medios/avatares, música de perfil, contadores, seguimiento/amistad, privacidad, sesiones web, recuperación/2FA, preferencias, respaldo, notificaciones y salida/eliminación de cuenta. No se eliminaron cuentas reales ni se rotaron sus códigos para probar.

## Evidencias y estado de comprobación

Estado de trabajo: **candidato local 9.1.30 / 170; pendiente de cierre del lint y del paquete firmado**, sin despliegue nuevo. Los resultados que siguen tienen su alcance específico.

- Servidor: la ejecución completa final aprobó **938 pruebas**, con 11 omitidas y cero fallos (949 en total). Incluye siete casos específicos de edición, permisos, contraseña, concurrencia y salas importadas.
- Android: 1.927 unitarias / 409 suites, cero fallos en la primera ejecución completa. Incluye nuevos casos de confirmación/cancelación, revisión de edición, sesión de detalles, plazo de salas, lectura parcial de ranking, restauración de momentos y suscripciones de perfil.
- Nativas aisladas en emulador API 35: **51/51 aprobadas**. Apertura inicial de video (2), navegación de ranking (3), borradores/emojis, portapapeles de texto/imagen, gestos de stickers, ancla y aislamiento de scroll, edición/exportación de imágenes, navegación de perfiles, visitas y cabecera de momentos; segunda tanda con PDF de 300 páginas y recuperación, audio WebM, primer fotograma de video en caché y distribución de emojis. No son 51 recorridos autenticados completos.
- Desplazamiento: 400 burbujas reales con gestos nativos, 304 fotogramas; p50 30,18 ms, p95 51,82 ms, 72 plazos de fotograma incumplidos. Índice visible 381 → 318. **Emulador con GPU por software y compilación QA depurable: no demuestra 60 FPS en producción**. Se conserva el resultado, sin convertirlo en una aprobación de fluidez física.
- Análisis de seguridad del repositorio: cero hallazgos y cero violaciones nuevas. El primer lint completo detectó dos errores de sangría nuevos en Rondas y 320 advertencias (268 sugerencias UseKtx). La sangría se corrigió; no se desactivan reglas ni se crea una línea base para ocultarla. La repetición está pendiente.
- Sonda autenticada no destructiva con cuenta sintética: diez GET respondieron 200. El video de muestra, con la misma reescritura de ruta pública utilizada por Android, respondió 206, `video/mp4`, 65.536 bytes en 519 ms. Es una muestra de red; no mide tiempo al primer fotograma en todos los dispositivos.
- Los logs, resultados JUnit e inventario están en `artifacts/mobile-audit-20261029/`, fuera de Git. Ninguna credencial forma parte del informe.

## Límites y trabajo de campo

No se certifica ausencia absoluta de errores ni 60 FPS en todos los dispositivos con pruebas unitarias. Faltan comprobar en equipos físicos: recorrido autenticado completo, audio bidireccional y LIVE entre redes, entrega de notificaciones al destinatario, carga/reproducción con los archivos concretos reportados y rendimiento sostenido de la compilación firmada. Las dependencias comerciales externas no se usan como excusa ni como bloqueo de estas correcciones.

La revisión automática rechazó una acción que iba a cerrar el aviso inicial de notificaciones y abrir el enlace del video en el emulador; devolvió únicamente «blocked by policy», sin causa más específica. Se detuvo ese intento. Las pruebas aisladas no se usan para afirmar que se completó ese recorrido autenticado.
