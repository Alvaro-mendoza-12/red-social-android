# Implementación del benchmark funcional de Novvin

Fecha: 26 de julio de 2026  
Documento analizado: `Benchmark_funcional_y_hoja_de_ruta_Novvin.pdf` (21 páginas)

## Criterio aplicado

El documento se trató como una hoja de ruta de producto, no como una orden para
activar treinta funciones simultáneamente. Cada función se implementará con
contrato, estados de error, pruebas y despliegue verificable. No se eliminó
ninguna funcionalidad existente: cuando dos capacidades se solapen se
integrarán en una sola experiencia conservando sus datos y permisos.

Antes de comenzar se creó y verificó el respaldo local
`backups/pre-roadmap-20260726-2b2c81d` y el respaldo del servidor
`/home/alvaro/conectate-backups/20260726T191221Z`.
Antes de reanudar la Fase 2 también se congeló el hotfix 3.0.9 en
`backups/post-admin-auth-20260728-11c8a2b`, con fuente, bundle Git, APK Debug
firmado, APK Release sin firma y hashes SHA-256 verificados.
El cierre de comunidades B08–B13 se congeló después en
`backups/post-community-roadmap-20260728-7441aca` con los mismos artefactos y
una copia íntegra transportable del repositorio.
El cierre del escenario de voz B14 quedó respaldado en
`backups/post-voice-stage-20260728-a4b2a86`; su bundle contiene el historial
completo y los hashes de fuente y APK fueron verificados.

## Incrementos implementados

### Fase 0 — estabilidad y trazabilidad

- **B26, pruebas de contrato:** la CI valida sintaxis y contratos del backend,
  salud del sistema, feed, administración y seguridad. Los endpoints nuevos
  tienen pruebas independientes.
- **B27, identidad de versión:** Android muestra versión, código, commit y
  entorno. Cada solicitud envía esos mismos datos junto con un identificador de
  petición para correlacionar errores entre aplicación y servidor.
- **B29, panel de salud:** el servidor expone el estado independiente de API,
  base de datos, almacenamiento, tiempo real, voz y directos. El panel de
  administración lo presenta con estados y mensajes comprensibles.
- El despliegue registra el commit activo en el servidor y `/ready` publica las
  capacidades compatibles. Los errores HTTP incluyen un `requestId` sin
  exponer tokens ni contenido privado.

### Fase 1 — control del feed

- **B01, Seguidos/Descubrir:** hay dos vistas independientes y la elección
  predeterminada persiste en el dispositivo. `Seguidos` es cronológico y
  `Descubrir` excluye la repetición de cuentas ya seguidas. El servidor conserva
  el modo heredado para pantallas de perfil y clientes anteriores.
- **B02, “¿Por qué veo esto?”:** una publicación recomendada muestra entre una
  y tres razones basadas en conexión, intereses, afinidad, tipo de contenido,
  conversación o actualidad. Desde el mismo panel se puede pedir ver menos de
  una cuenta o silenciar un tema.
- **B03, temas bloqueados:** palabras, temas y cuentas ocultas se guardan de
  forma persistente. La persona puede revisar y revertir sus preferencias desde
  el selector del feed.

### Fase 1.1 — biblioteca personal

- **B04, colecciones de guardados:** cualquier publicación visible se puede
  guardar en la colección predeterminada o en una colección creada por la
  persona. Cada elemento admite etiquetas normalizadas y una nota privada.
- La biblioteca sincronizada permite buscar por colección, autor, contenido,
  etiqueta o nota. También permite abrir la publicación y quitarla de la
  colección sin borrar la publicación original.
- El servidor vuelve a comprobar permisos al guardar y al leer. Si la
  publicación fue eliminada, pasó a ser privada o dejó de ser visible, no
  expone su contenido y muestra un elemento no disponible.
- La eliminación de una publicación limpia sus referencias guardadas. La
  eliminación completa de una cuenta también borra colecciones e items para no
  dejar registros huérfanos.

### Fase 1.2 — decisiones explicables y apelaciones

- **B06, suspensiones explicables:** cada suspensión administrativa registra una
  norma del catálogo, explicación redactada para la persona, severidad, resumen
  de evidencias, responsable, vigencia y plazo de apelación. La pantalla de
  cuenta suspendida muestra esos datos sin exponer referencias internas o
  información privada.
- **B07, apelación con revisión humana:** una cuenta suspendida recibe un token
  restringido que solo permite consultar su decisión y presentar una apelación;
  no sirve para acceder al perfil, mensajes ni otros endpoints normales. Solo
  puede existir una apelación abierta por decisión.
- El panel administrativo incluye una cola de apelaciones con el contexto de la
  decisión. El revisor debe escribir un fundamento y puede mantener o revocar
  la decisión. Si la revoca, la decisión, la apelación y la reactivación de la
  cuenta se guardan de forma atómica.
- La reactivación manual también marca como revertida la decisión activa. El
  borrado completo de cuenta elimina apelaciones y decisiones relacionadas para
  evitar registros huérfanos.

### Fase 1.3 — resúmenes de notificaciones

- **B05, resúmenes configurables:** cada persona puede activar o desactivar
  resúmenes diarios o semanales, escoger la hora local, elegir día de la semana
  y seleccionar categorías: menciones, mensajes personales no urgentes,
  actividad de comunidades y próximos directos.
- Las llamadas, seguridad de cuenta, administración, moderación, solicitudes
  urgentes y mensajes personales marcados como urgentes se mantienen
  inmediatos. Un resumen nunca reemplaza una notificación crítica.
- El servidor guarda preferencias persistentes, marca las notificaciones
  diferidas sin enviarlas por FCM y genera una notificación resumen cuando
  corresponde. La app muestra el estado del resumen y permite cambiarlo desde
  la bandeja de notificaciones.

### Fase 2.1 — roles de comunidades

- **B08, roles predefinidos:** las comunidades conservan cuatro roles
  coherentes en servidor y Android: propietario, administrador, moderador y
  miembro. El rol propietario se deriva de la persona creadora y no se puede
  asignar ni degradar desde la gestión de miembros.
- Administradores y propietarios pueden asignar administrador, moderador o
  miembro. Un moderador puede moderar mensajes y pausar futuros canales, pero
  no puede administrar integrantes, roles, canales ni configuración.
- Volver a unirse a una comunidad no degrada accidentalmente un rol existente.
  El servidor normaliza roles históricos (`creator` y `mod`) sin perder su
  significado.
- **B09, vista como rol:** la administración de una comunidad incluye una
  previsualización de permisos para los cuatro roles. La vista indica
  explícitamente que no modifica permisos y obtiene el catálogo del servidor
  en sesiones Ubuntu; conserva un catálogo equivalente para el modo heredado.
- Los errores de rol inválido, permiso insuficiente y rol propietario inmutable
  se muestran como mensajes comprensibles.

### Fase 2.2 — canales, identidad y eventos de comunidades

- **B10, canales temáticos:** cada comunidad tiene un canal General compatible
  con mensajes históricos y puede añadir canales de texto, multimedia y voz.
  Los permisos del servidor se derivan de los roles B08; un cliente no puede
  crear, editar o borrar canales elevando permisos desde la interfaz.
- Los mensajes guardan su canal. Un canal de voz abre la experiencia de sala y
  no acepta mensajes de texto. Al borrar un canal temático, sus mensajes se
  reasignan a General para conservar el historial.
- **B13, pausa temporal:** propietario, administrador o moderador pueden pausar
  un canal con motivo y duración. La API rechaza escrituras durante la pausa y
  Android presenta el motivo en modo de solo lectura; no se usa un retraso ni
  un bloqueo exclusivamente visual.
- **B11, perfil por comunidad:** cada miembro puede definir alias, biografía y
  distintivo exclusivos para una comunidad. La actualización es parcial en el
  servidor: cambiar un solo campo no borra los restantes. El alias y distintivo
  se reutilizan en la lista de integrantes.
- **B12, eventos y asistencia:** propietarios y administradores crean o
  cancelan eventos; cualquier miembro confirma o retira asistencia y elige un
  recordatorio. Un trabajador idempotente genera la notificación una sola vez
  en el momento configurado.
- La eliminación de una comunidad o cuenta limpia canales, perfiles, eventos,
  asistentes y recordatorios antes de borrar sus referencias principales.

### Fase 3.1 — sala casual y escenario moderado

- **B14, dos plantillas de voz:** al crear una sala se puede conservar la
  modalidad casual, donde todas las personas pueden hablar, o elegir escenario.
  La modalidad queda persistida en el servidor y se muestra en el listado y en
  la sala activa; no reemplaza ni altera las salas casuales existentes.
- En un escenario, la persona creadora entra como moderadora y las demás entran
  como público con micrófono, cámara y pantalla desactivados. El público puede
  solicitar o cancelar la palabra y la moderación puede aceptar, rechazar,
  silenciar o devolver una persona a la audiencia.
- Los roles `moderator`, `speaker` y `audience`, el estado de silencio y la mano
  levantada se validan en la API. El signaling vuelve a comprobar el permiso
  antes de publicar voz, cámara o pantalla, por lo que modificar únicamente el
  cliente no permite autoascenderse.
- La persona creadora queda protegida y solo ella puede conceder el rol de
  moderador. Un moderador delegado no puede degradar a otro moderador ni
  entregar privilegios equivalentes.

### Fase 3.2 — reconexión de voz y medios

- **B15, cambio de red:** Android observa la red predeterminada del sistema.
  Cuando una sesión activa cambia entre Wi‑Fi y datos móviles, o recupera la
  conexión después de quedar sin red, renueva las credenciales TURN y solicita
  un reinicio ICE a cada par sin destruir el motor de medios.
- La recuperación conserva las pistas locales y su estado: micrófono
  silenciado, cámara, pantalla y reproducción de audio. Las notificaciones
  consecutivas del sistema se agrupan para evitar varias renegociaciones por
  un mismo cambio de red.
- Las llamadas conceden 30 segundos para que el participante remoto vuelva a
  señalización antes de terminar automáticamente. Las salas reconstruyen la
  lista autoritativa de participantes y los directos conservan la emisión
  durante su ventana existente de 90 segundos para que el anfitrión reingrese.
- La prueba automática pública corta y vuelve a crear el socket de voz,
  llamada y anfitrión de directo; en los tres casos recompone participantes y
  vuelve a intercambiar SDP e ICE. Esta prueba valida servidor y señalización,
  pero no sustituye la matriz física Wi‑Fi↔datos con dos teléfonos.

### Corrección operativa 3.0.9 — administración y contraseñas

- El host temporal que llevaba el APK había caducado. El túnel administrado
  publicó `genres-twice-trainer-stamps.trycloudflare.com`, se actualizó el
  descriptor web y se confirmó que el TXT de
  `conectate-alvaro.duckdns.org` apunta al mismo servidor.
- Inicio de sesión, registro, recuperación, confirmación de contraseña,
  migración y comprobación administrativa ahora resuelven el endpoint dinámico
  antes de enviar la solicitud. Una URL Quick Tunnel guardada o compilada ya no
  se usa a ciegas después de rotar.
- El botón administrativo navega con el controlador que contiene la ruta en el
  grafo activo. Una ruta ausente queda contenida, se registra para diagnóstico
  y presenta un mensaje normal en vez de terminar el proceso.
- El inicio de sesión incorpora `Olvidé mi contraseña`, conserva el permiso
  seguro devuelto por Ubuntu y permite completar la contraseña nueva sin
  exponer el token en pantalla.
- El cambio autenticado solicita contraseña actual, contraseña nueva y
  confirmación. Ubuntu verifica la anterior, persiste la nueva, renueva el
  token y mantiene la sesión activa.
- Los errores de contraseña actuales, permisos vencidos y reutilización de la
  misma clave se traducen a mensajes comprensibles.

## Estado del backlog B01–B30

| ID | Estado | Decisión |
|---|---|---|
| B01 | Implementado en este incremento | Dos feeds, preferencia persistente y compatibilidad API |
| B02 | Implementado en este incremento | Motivos comprensibles y controles inmediatos |
| B03 | Implementado en este incremento | Temas/cuentas silenciados y reversibles |
| B04 | Implementado en este incremento | Colecciones, etiquetas, notas, búsqueda y privacidad sincronizadas |
| B05 | Implementado en este incremento | Resumen diario/semanal configurable con urgentes inmediatas |
| B06 | Implementado en este incremento | Norma, explicación, evidencia segura, severidad, vigencia y auditoría |
| B07 | Implementado en este incremento | Token restringido, apelación única, cola humana y reactivación atómica |
| B08 | Implementado en este incremento | Propietario, administrador, moderador y miembro con permisos coherentes |
| B09 | Implementado en este incremento | Vista segura de permisos sin alterar el rol real |
| B10 | Implementado en este incremento | Canales General, texto, multimedia y voz con historial conservado |
| B11 | Implementado en este incremento | Alias, biografía y distintivo persistentes por comunidad |
| B12 | Implementado en este incremento | Eventos, asistencia y recordatorio idempotente |
| B13 | Implementado en este incremento | Pausa temporal con motivo, duración y bloqueo en servidor |
| B14 | Implementado en este incremento | Sala casual y escenario con público, solicitud de palabra y moderación validada por servidor |
| B15 | Implementado técnicamente; falta matriz física | Detectar cambio de red, renovar TURN, reiniciar ICE y restaurar estado |
| B16–B17 | Fase 3/4 | Solo opt-in, consentimiento visible y política de retención |
| B18–B20 | Fase 3 | Moderación en vivo, repeticiones y remix con atribución |
| B21–B25 | Fase 4 | IA aislada, opcional, auditable y con borrado |
| B26 | Implementado en este incremento | Contratos en CI |
| B27 | Implementado en este incremento | Versión/commit/entorno visibles |
| B28 | Parcial | CI y artefactos internos listos; activación porcentual requiere Play Console |
| B29 | Implementado en este incremento | Salud API/DB/storage/realtime/voz/live |
| B30 | Planificado | Exportación de perfil, posts y futuras colecciones |

## Verificación de este incremento

- Backend: comprobación sintáctica y pruebas de salud, feed, administración y
  seguridad, todas aprobadas.
- Android: 36 suites, 148 pruebas, 0 fallos, 0 errores y 0 omitidas. Kotlin
  aprobó; Debug, Release con R8 y `lintVitalRelease` terminaron correctamente
  en 17 min 48 s. `lintDebug` completo no se declaró aprobado porque se detuvo
  por tiempo sin errores visibles.
- Debug: `app-debug.apk`, firmado con la clave Debug, SHA-256
  `E79CAE2025A7B649C0187026EABE46680E598051A42F53CE7132A22CDA02B86D`.
- Release: `app-release-unsigned.apk`, R8 y reducción de recursos aprobados,
  SHA-256
  `587561E8F8DD1EF91F8C47732B7BB528EE843554E87CBD84B1275FCF2E07BEEA`.
  Continúa sin firma porque la clave oficial de Play Store no está disponible
  en este entorno.
- Servidor: despliegue `conectate-mini-pc-20260728T160010Z`, commit
  `5653a1836bef`, precedido por el respaldo
  `/home/alvaro/conectate-backups/20260728T160018Z`.
- La comprobación pública de `/ready` y `/v1/system/health` resultó operativa
  para API, base de datos, almacenamiento, tiempo real, voz y directos.
- B04: prueba integral pública aprobada con la cuenta ficticia
  `module-saved.1785101637041@example.local`. Cubrió crear, guardar, buscar,
  quitar y limpiar referencias al borrar una publicación. La autoeliminación
  terminó sin error y una consulta posterior confirmó `0` cuentas con ese
  correo.
- B06/B07: canario público `20260726b07a` aprobado. Cubrió suspensión con norma
  y evidencia, bloqueo de login, token exclusivo de apelación, rechazo de ese
  token en la API normal, consulta de decisión, prevención de apelación
  duplicada, cola administrativa, revisión humana y reactivación atómica.
  Después de la prueba PostgreSQL confirmó `users=0`, `moderation=0`,
  `decisions=0` y `appeals=0` para sus dos cuentas ficticias.
- B05: canario público `1785116839398` aprobado. Cubrió preferencia apagada por
  defecto, rechazo de selección vacía, diferimiento de menciones y mensajes
  personales normales, envío inmediato de mensaje urgente, conteos del resumen
  y publicación de `notification_digest_v1`. Después de la prueba PostgreSQL
  confirmó `users=0` y `notifications=0` para sus cuentas y contenidos
  ficticios.
- B08/B09: canario público aprobado con dos cuentas y una comunidad ficticias.
  Cubrió catálogo de cuatro roles, persistencia del moderador, rechazo de
  asignación del rol propietario, denegación de la vista a moderadores y acceso
  de propietario y administrador. La limpieza posterior confirmó `users=0` y
  `rooms=0`.
- B10/B13: canario público aprobado para creación de canales temáticos,
  permisos, pausa por moderador, rechazo de escritura pausada, persistencia por
  canal y protección del canal de voz. La limpieza posterior confirmó
  `users=0`, `rooms=0`, `channels=0` y `messages=0`.
- B11/B12: canario público aprobado para perfil persistente, actualización
  parcial sin pérdida, permisos de creación de eventos, asistencia y
  recordatorio entregado exactamente una vez. La limpieza confirmó `users=0`,
  `rooms=0`, `profiles=0`, `events=0` y `notifications=0`.
- B14: canario público aprobado con dos cuentas ficticias. Cubrió modalidad
  inválida rechazada, creador moderador, entrada del público en silencio,
  solicitud de palabra, autoascenso denegado, promoción a orador, silencio,
  regreso a audiencia y compatibilidad de la sala casual. La limpieza confirmó
  `users=0`, `rooms=0` y `participants=0`.
- B15: canario público `1785257177571` aprobado para señalización de voz,
  llamada y directo. En cada flujo desconectó una sesión, creó un socket nuevo,
  reconstruyó la lista autoritativa y volvió a intercambiar SDP e ICE. También
  rechazó el intento de sustituir al anfitrión del directo. La autoeliminación
  terminó correctamente y PostgreSQL confirmó `0` cuentas ficticias de esa
  ejecución.
- Administración: canario de solo lectura aprobado contra la URL pública para
  acceso, catálogo, búsqueda de usuarios, estadísticas, suspendidos,
  apelaciones, reportes de usuarios, reportes de videos y salud del sistema.
- Contraseñas: prueba integral con cuenta ficticia aprobada. Cubrió solicitud,
  consumo único del permiso, rechazo de la clave anterior, aceptación de la
  nueva, cambio autenticado posterior, renovación de sesión y persistencia.
  La cuenta y sus tokens de prueba terminaron en `0`.
- Dispositivos: las llamadas, salas y directos seguirán requiriendo la matriz
  física entre dos teléfonos y redes distintas antes de declararse totalmente
  verificados. No había ningún dispositivo visible por ADB durante este cierre.
