# Mensajes programados: timeout que interrumpía el chat

Fecha: 2026-09-09. Rama de trabajo: `feature/live-gifts`.
Estado: puerta de cuentas migradas aprobada (551 pruebas, assemble/lint).
La renovación de token posterior está en validación junto a las vistas previas.
Monitor desplegado y verificado.

## Evidencia y causa delimitada

La captura del usuario muestra el timeout de 25.000 ms de
`POST /rest/v1/rpc/secure_get_chat_scheduled_jobs`. Abrir un chat ejecutaba
esa consulta opcional; la excepción se copiaba íntegra a `advancedMessage`
y el Snackbar general terminaba tapando la conversación. No demuestra por
sí sola que el servidor estuviera apagado ni explica la advertencia del video.

El RPC existe: valida cuenta/ámbito/destino y consulta una tabla indexada.
No crea tablas durante esta lectura. La comprobación autenticada de lectura
del mismo RPC devolvió HTTP 200: 23 ms local, 997 ms público y 460 ms al
repetirlo. Son muestras desde esta PC/servidor, no mediciones del teléfono.
No se reprodujo el timeout histórico ni se atribuyó a la conexión del usuario.

Se agregó la lectura al monitor de producción para que deje evidencia cuando
vuelva a fallar. Muestra manual: 217 ms; última muestra verificada:
2026-09-10 03:41:54 UTC (9 de septiembre 22:41 -05:00), HTTP 200, 221 ms
hasta completar el cuerpo, 2 bytes, lista válida vacía. Estado global del
monitor: `ok`, cero fallos consecutivos. Una lista vacía es válida únicamente
si el servidor contestó correctamente con un arreglo.

## Cambios Android separados por responsabilidad

- `ScheduledMessageStore`: pendientes con propietario y caché persistente
  por cuenta, ámbito y conversación. Conserva registros antiguos sin dueño,
  pero no los muestra ni reproduce desde una cuenta elegida por inferencia.
  Las modificaciones preservan otras cuentas y datos pendientes ilegibles.
- `ScheduledMessagesReader`: límite total de cinco segundos para token,
  descubrimiento/conexión y lectura del cuerpo. HTTP fallido/JSON inválido
  no equivalen a una lista vacía. No reemplaza la caché al fallar.
  Comprueba la cuenta antes y después de cada lectura suspendible y evita
  que una respuesta anterior a una creación/cancelación restaure datos viejos.
- `ScheduledMessageIdentity`: vincula el propietario de la respuesta con
  la cuenta visible o el `sub` del token capturado. Normaliza a la identidad
  usada por la caché tanto al leer como al crear/sincronizar. No confunde
  una cuenta migrada de Supabase con un propietario ajeno.
- `ScheduledMessagesController`: estado independiente con lista guardada,
  actualización y error. Cancela lecturas obsoletas al cerrar/cambiar contexto.
  Nunca publica excepciones de red en el aviso general de la conversación.
- `ScheduledMessagesDialog`: muestra lo guardado mientras actualiza, permite
  reintentar y distingue “vacío confirmado” de “no se pudo comprobar”.
  La ventana está en su propio archivo, fuera del componente principal.
  Informa si hay registros antiguos sin dueño sin mostrar su contenido;
  deshabilita “Cancelar” mientras se confirma esa misma acción.
- `ChatViewModel`: la apertura habitual lee sólo la copia local de programados.
  El RPC se consulta al abrir su opción o después de una acción explícita.
  Limpia este estado al cambiar cuenta; no modifica la carga normal del historial.
- Reproducción/cancelación de pendientes: usa su cuenta original, conserva
  otras filas ante modificaciones concurrentes y mantiene la misma clave
  de idempotencia del primer intento al sincronizar con Ubuntu.
  Rechazos permanentes al programar ya no se presentan como aceptados localmente.

Los datos locales anteriores no se eliminan. No se cambia la versión de Room
para este bloque (sigue 55). No desinstalar ni borrar datos para “solucionarlo”.

## Monitor del servidor y rollback

Archivos:

- `api/scripts/verify-scheduled-message-read.js`: RPC de lectura con token
  diagnóstico de corta duración; no crea/cancela mensajes de usuarios.
- `verify-social-read-paths.js`: comprobación JSON acepta cuerpo POST y mide
  hasta terminar la respuesta, no sólo las cabeceras.
- `verify-messaging-read-paths.js`: incluye programados después del historial.

Se ejecutaron seis pruebas TAP en Windows y Ubuntu: petición/cuerpo/autorización,
lista vacía real, rechazo de respuesta malformada, cuerpo lento y verificadores
existentes. Repetición local de las seis pruebas: aprobada a las 22:43 -05:00.
El despliegue del monitor no requirió reiniciar API, túnel ni base de datos.

Respaldo en Ubuntu:
`/home/alvaro/conectate-scheduled-read-check-20260909T2252/`.
Contiene los scripts anteriores `verify-social-read-paths.before.js` y
`verify-messaging-read-paths.before.js`. Para revertir sólo el monitor,
restaurar esos dos archivos en `api/scripts/`; el helper nuevo puede quedar
sin referencias. No revertir servicios ni rotar el túnel por este cambio.

## Validación Android y límites pendientes

La primera puerta enfocada terminó a las 22:46 -05:00: 26 pruebas del
lector/almacenamiento y del controlador, cero fallos, errores u omitidas.
Cubre cuenta cambiada, sesión inválida, cinco segundos, cancelación, caché
reabierta, respuesta tardía, reintento y errores sanitizados. Se agregaron
después dos casos de aviso de registros sin dueño y cancelación repetida.
La suite completa del estado previo al ajuste de cuentas migradas terminó
a las 22:49 -05:00: 548 pruebas
en 136 suites, cero fallos, errores u omitidas (28 casos de este bloque).
Assemble y lint aprobaron a las 23:03 -05:00, `BUILD SUCCESSFUL`.
La revisión posterior del contrato detectó un caso no cubierto: el servidor
guarda `owner_id = JWT.sub` (usuario local de Ubuntu), mientras Android
puede usar `supabaseUserId` como identidad visible de una cuenta migrada.
La correspondencia ya se implementó contra el token capturado, normalizada
a la cuenta local de caché sin aceptar un propietario ajeno. Se agregaron
tres pruebas (alias propio, rechazo de otro owner y respuesta legada sin
owner). La suite final pasó a las 23:06 -05:00: 551 pruebas en 136 suites,
cero fallos, errores u omitidas (31 casos de este bloque).
Assemble y lint aprobaron a las 23:18 -05:00 del 9 de septiembre.
El reporte tiene cero errores y 194 advertencias, incluidas sugerencias de
estilo KTX en el almacenamiento de programados; no son fallos de ejecución.
APK de las 23:08 -05:00: 272.064.588 bytes, SHA-256
`E45E5599ECDD2DBB45ADD74702E5BF678A35BBB2D91FCE3200E69081D110D236`.

Revisión del 10 de septiembre: la lectura usaba `getAccessToken()`, que
sólo lee el token guardado y no garantiza renovación. Se conecta a
`getAccessTokenAwait()` dentro del deadline existente del lector. Creación,
cancelación y sincronización de programados también reutilizan esa renovación
y conservan la comprobación de cuenta después de esperar. Ese cambio posterior
no está incluido en el APK de las 23:08. La nueva validación terminó a las
03:50 -05:00 del 10 de septiembre: 606 pruebas en 139 suites, assemble y lint
aprobados (cero errores, 194 advertencias). El APK y su hash están registrados
en [visibilidad de bandeja](19-visibilidad-bandeja-2026-09-10.md).

Monitor comprobado el 10 de septiembre 06:59:57 UTC (01:59 -05:00):
estado ok, cero fallos consecutivos; RPC HTTP 200, 205 ms y cuerpo válido de
2 bytes. No se reinició el servidor por la captura.

Contrato de identidad comprobado en el código:

| Dato | Identidad que utiliza |
| --- | --- |
| `server.js: signUserToken`, claim `sub` | `user.id` interno de Ubuntu |
| `scheduled-message-jobs.js: owner_id` | `auth.sub` del token verificado |
| `AuthService.localFallbackDisplayUserId` | `supabaseUserId` si existe; de lo contrario `user.id` |

No sustituir esta relación por “aceptar cualquier owner_id”. La comprobación
usa exclusivamente el token de la petición y la cuenta capturados, nunca una
sesión nueva consultada después de recibir la respuesta.
El APK previo de las 17:48 **no incluye** esta corrección.

ADB no detecta dispositivos a las 22:42 -05:00. No se recrearon los emuladores
eliminados por solicitud del usuario. Falta probar físicamente: abrir un chat
en modo avión (sin aviso de programados), abrir esa opción (caché y reintentar),
restaurar red, cambiar entre dos cuentas y comprobar cancelación/entrega en
otro dispositivo.

La cola completa de programación todavía necesita pruebas de proceso muerto,
cancelación durable sin red y confirmación/cancelación mientras el worker está
entregando. Los pendientes antiguos sin propietario requieren un mecanismo
explícito de recuperación que no adivine su autor. No afirmar equivalencia
completa con WhatsApp ni que el problema histórico de reproducción de videos
quedó demostrado resuelto por esta corrección del aviso.

Un rollback Android debe conservar el aislamiento por dueño de estos
pendientes: una versión antigua que ignora `owner_id` puede volver a
seleccionar filas de otras cuentas. No recomendar instalar indiscriminadamente
un APK anterior ni eliminar los registros para resolver esa incompatibilidad.
