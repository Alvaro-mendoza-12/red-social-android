# Checklist de Sorteos

**Rama:** `feature/live-gifts`. **Fecha:** 4 de octubre de 2026.

`[x]` señala código integrado o una validación efectivamente ejecutada, según el apartado. El alcance de las pruebas está explicado en [IMPLEMENTACION_SORTEOS.md](IMPLEMENTACION_SORTEOS.md).

## Auditoría y arquitectura

- [x] Auditoría del código de salas, miembros, roles, backend, autenticación, realtime, mensajes y navegación antes de implementar.
- [x] Reutilización de HTTP, JWT/sesiones, identidad local/importada, perfiles, Hilt, Compose y gateway existentes.
- [x] Archivos aislados de Sorteos y cambios mínimos en puntos compartidos; cambios de las otras IA conservados.
- [x] Auditoría y documentación de implementación/casos/prueba manual.

## Funcionalidad integrada

- [x] Entrada Sorteos en Grupo y Comunidad y navegación desde tarjeta real de mensaje.
- [x] Listado paginado con nombre, creador, fecha, estado, participantes y rondas.
- [x] Creación preparada o comienzo automático con nombre y descripción.
- [x] Importación paginada de elegibles, avatars/nombres, búsqueda, selección, exclusión y preview inmediato.
- [x] Manuales distinguibles, eliminación del formulario y rechazo de duplicados; no crea usuarios ficticios.
- [x] Snapshots originales y por ronda con identidad/nombre/avatar.
- [x] Rueda Canvas, desaceleración, alineación al resultado del servidor, nombres/números/lista para distintas cantidades.
- [x] Resultado, ranking y eliminación progresiva exclusivamente a partir de resultados confirmados.
- [x] Modo automático persistente con pausa/reanudación y selección manual.
- [x] Finalización, cancelación y nueva ronda conservando resultados anteriores.
- [x] Listas restantes/originales expandibles y rondas históricas paginadas.
- [x] Loading, vacío, errores, solicitud pendiente, retry idempotente y controles de permisos.
- [x] Tema oscuro, roles/estados accesibles, descripciones, números además del color y anuncio del ganador.

## Persistencia, seguridad y recuperación

- [x] Cinco tablas con UUID/timestamps, índices, FK entre entidades nuevas y restricciones únicas.
- [x] Migración idempotente dentro de la inicialización de esquema existente.
- [x] Selección criptográfica backend; ignora ganador/índice aportado por cliente.
- [x] Membresía y gestión verificadas en todos los endpoints; acceso externo denegado incluso si la sala es pública.
- [x] Grupos: cualquier integrante activo crea y gestiona sus propios sorteos; propietario/admin gestionan todos. Comunidades: propietario/admin crean y gestionan. Lectura requiere membresía activa.
- [x] Serialización por transacción/bloqueo, versión esperada, idempotencia y constraints SQL.
- [x] Worker reiniciable, agenda durable, bloqueo por múltiples réplicas y pausa por revocación/suspensión.
- [x] Invalidación WebSocket sin nombres/ganadores privados y lectura HTTP autorizada.
- [x] Lifecycle/reconexión, polling visible de respaldo y recuperación por servidor.
- [x] Cambio de cuenta vacía estado privado y descarta respuesta de otra identidad.
- [x] Límite documentado de 1.000 candidatos y creación horaria; paginación de lista/historial.
- [x] Cancelación/baja/perfiles cambiados conservan snapshots; sala eliminada pierde acceso.

## Validaciones ejecutadas

- [x] Sintaxis y pipeline `npm run check` de API.
- [x] Suite API final: 442 casos, 439 aprobados, cero fallos, tres omitidos ajenos a Sorteos.
- [x] 13 pruebas específicas con PostgreSQL real, sin omisiones, incluyendo concurrencia, clones del worker, replay, snapshots, privado, aliases, 101 resultados únicos y límite de 1.000.
- [x] Gateway existente con aceptación/denegación de canales nuevos.
- [x] Build debug e instrumentación Android correctos sobre copia aislada del código actual.
- [x] 1.300 unitarias Android en 283 suites, cero fallos/errores/omisiones, incluidos seis casos nuevos.
- [x] APK actualizado instalado mediante reemplazo, preservando identidad del directorio de datos.
- [x] HTTPS desde Motorola hacia rutas reales, sesiones JWT QA y PostgreSQL desechable: creación, permisos, concurrencia, recuperación, automático, historial/nueva ronda y conexión fallida.
- [x] Tanda física final: 26/26 aprobadas en Motorola g04, Android 14/API 34; sin fallos ni omisiones, 118,998 s. Incluye UI, HTTPS/SQL de Sorteos, música/letras y llamadas locales.
- [x] Inspección visual de tres capturas QA finales: ruedas de cinco/veinte y formulario con exclusión y participante manual.
- [x] SHA-256 del APK instalado igual al compilado; 14 fuentes Android de integración iguales a las compiladas.
- [x] Fixture HTTPS y PostgreSQL desechable cerrados, esquemas QA eliminados y reverse de ADB retirado.

## Validaciones y entrega pendientes

- [ ] Despliegue de API/gateway y distribución del APK con Sorteos a usuarios.
- [ ] Flujo completo desde chats reales con dos dispositivos contra el entorno desplegado, incluido websocket y expulsión de observador.
- [ ] TalkBack manual y revisión en más tamaños/dispositivos.
- [ ] Carga sostenida de muchas salas automáticas y medición FPS/memoria para 1.000 candidatos; no confundir límites con capacidad certificada.
- [ ] Política operativa de retención/archivo del historial.
- [ ] AAB release firmado y pruebas de producción del conjunto nuevo.

## Continuación de los otros trabajos

- [x] Tres mediciones de arranque del APK anterior verificadas, sin borrar datos ni cambiar artifact.
- [x] Acceso SSH por Tailscale recuperado y nueve lecturas parciales de audio en Linux realizadas sin modificar servidor.
- [x] Nueve pruebas offline del diagnóstico de audio aprobadas.
- [x] Contraste de duración fija corregido en el selector musical; compilación final y 14 pruebas físicas de música/letras aprobadas.
- [x] Invitación vencida y otros cinco casos de llamadas locales aprobados en la tanda final del Motorola; causa de los fallos previos sin demostrar.
- [ ] Causa demostrada y corrección comprobada de los cierres anticipados anteriores de llamada.
- [ ] Medición móvil simultánea de encabezados/preparación/avance de audio para explicar la demora previa.

Detalle de continuidad: [CONTINUIDAD_VALIDACION_MOVIL.md](CONTINUIDAD_VALIDACION_MOVIL.md).
