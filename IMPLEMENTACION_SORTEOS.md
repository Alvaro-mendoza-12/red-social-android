# Implementación de Sorteos en Grupos y Comunidades

**Fecha:** 4 de octubre de 2026. **Rama:** `feature/live-gifts`. **Directorio:** `D:\red social - live-gifts`.

## 1. Arquitectura implementada

La auditoría previa está en [AUDITORIA_SORTEOS.md](AUDITORIA_SORTEOS.md). Se reutilizan Kotlin, Compose Material 3, Hilt, ViewModel, coroutines, el cliente HTTP Ktor compartido, AuthService y UbuntuRealtimeClient. El servidor es Express/Node con PostgreSQL y el gateway WebSocket existente. No se añadió Firebase, otra autenticación ni una tabla de usuarios manuales.

Recorrido: chat de grupo/comunidad → menú Sorteos → lista → creación → selección de miembros/manuales → snapshot persistente → ronda → selección del servidor → animación → ranking → historial → nueva ronda. Una tarjeta del mensaje abre el mismo detalle.

El backend es la fuente de resultados y estados. La rueda solo representa el resultado que ya se confirmó en PostgreSQL. El modo automático funciona sin que Android permanezca abierto.

## 2. Archivos creados

| Área | Archivos |
| --- | --- |
| Auditoría y entrega | `AUDITORIA_SORTEOS.md`, `IMPLEMENTACION_SORTEOS.md`, `CHECKLIST_SORTEOS.md` |
| Evidencia y continuidad | `VALIDACION_SORTEOS.json`, `CONTINUIDAD_VALIDACION_MOVIL.md`, `VALIDACION_CONTINUIDAD_ADICIONAL.json`; tres capturas en `docs/oficial/capturas/sorteos-moto-g04/` |
| API | `infrastructure/local-fallback/api/src/raffles/schema.sql`, `service.js`, `routes.js` |
| Pruebas API | `infrastructure/local-fallback/api/tests/raffles.test.js`, `scripts/start-raffles-device-fixture.cjs` |
| Datos Android | `app/src/main/java/conectate/com/data/raffles/RaffleModels.kt`, `RaffleRepository.kt` |
| Presentación Android | `app/src/main/java/conectate/com/presentation/raffles/RafflesViewModel.kt`, `RafflesScreen.kt`, `RaffleWheel.kt`, `RaffleMessageCard.kt` |
| Navegación | `app/src/main/java/conectate/com/presentation/navigation/RaffleNavigation.kt` |
| Pruebas Android | `app/src/test/java/conectate/com/presentation/raffles/RaffleStateTest.kt`; `app/src/androidTest/java/conectate/com/presentation/raffles/RaffleWheelUiTest.kt`, `RaffleApiIntegrationTest.kt` |

Los certificados y bases desechables de validación están en `.codex-tmp`, ignorados por Git. Sus claves privadas no forman parte del producto.

## 3. Archivos modificados

- `api/src/data-routes.js`: registra esquema/endpoints y reutiliza acceso, identidades, perfiles, canal General y publicación de mensajes. Reserva `content_type=raffle` para el flujo autorizado, impidiendo crear tarjetas falsas desde el endpoint general.
- `api/src/server.js`: inicia el worker después de la inicialización de esquema; lo detiene al cerrar el servidor.
- `api/package.json`: agrega `test:raffles` y `check:raffles`.
- `realtime-gateway/src/server.js` y `tests/server.test.js`: autorización de canales de sorteos mediante HTTP; pruebas de aceptación y denegación.
- `MainNavigationRoutes.kt`: registra las rutas nuevas.
- `GroupChatScreen.kt` y `CommunityChatScreen.kt`: entrada en menú y tarjeta de mensaje navegable.

En la continuación del trabajo móvil anterior también se corrigió el contraste de la duración fija del fragmento en `MomentMusicPickerDialog.kt`. Sus mediciones y pendientes se documentan por separado en `CONTINUIDAD_VALIDACION_MOVIL.md`. No se atribuyen al módulo de sorteos los resultados anteriores de otros APK.

## 4. Base de datos y migración

`src/raffles/schema.sql` es la migración idempotente, aplicada por `ensureRaffleTables` dentro del bloqueo de inicialización del servidor. La imagen Docker copia el árbol `src`; no necesita un servicio adicional. Para una instalación existente se debe distribuir la API que contiene esta migración y permitir que complete su inicialización antes de servir peticiones.

| Tabla | Información y garantías |
| --- | --- |
| `fallback_raffles` | Sala, creador, nombre, descripción, estado, versión, conteos, mensaje y clave/hash de creación. Única `(creator_local_id, creation_key)`. Índice para paginar por sala/fecha/ID. |
| `fallback_raffle_participants` | UUID propio, tipo, referencia registrada nullable, nombre/avatar congelados, ordinal. Única identidad registrada por sorteo. Manual requiere `user_id=NULL`. |
| `fallback_raffle_rounds` | Número, fechas, iniciador, estado, snapshot JSONB, automático y próxima selección. Una sola ronda en curso por sorteo. Índice parcial de tareas pendientes. |
| `fallback_raffle_results` | Posición, participante y fecha. PK por ronda/posición y unicidad por ronda/participante. Claves foráneas compuestas impiden cruzar participantes/rondas de otro sorteo. |
| `fallback_raffle_commands` | UUID de solicitud, actor, hash y respuesta guardada. PK por sorteo/solicitud. |

Se usan UUID para entidades nuevas y TIMESTAMPTZ para fechas. Sala y usuario se referencian por texto porque el sistema mezcla IDs locales e importados; una FK hacia una sola tabla de salas/usuarios excluiría registros legacy válidos. La existencia, sala y autorización se comprueban con los servicios actuales. El snapshot sobrevive a la desaparición del perfil y a la eliminación lógica de la sala; el acceso a una sala eliminada se deniega. La cancelación conserva rondas y resultados. No existe un endpoint público para borrar el historial.

La migración se ejecutó dos veces en PostgreSQL 16 local sin duplicar esquema. No se ha aplicado a producción en esta entrega.

## 5. Backend y contrato HTTP

Base: `/v1/data/raffles/{kind}/{roomId}`, donde `kind` es `group` o `community` y `roomId` es UUID.

| Método/ruta relativa | Función |
| --- | --- |
| GET `/access` | Revalida membresía; responde `allowed`, `canCreate` y administración de sala `canManage`. Lo utiliza el gateway. |
| GET `/members?offset=0&limit=500&q=...` | Miembros elegibles con avatar/nombre/identidad; `total`, `nextOffset`, `canCreate`, `canManage`. Límite por página 500. |
| GET `?cursor=...` | Lista por fecha/ID, 20 sorteos por página y cursor validado. |
| POST base | Cualquier integrante activo de grupo crea snapshot y mensaje; opcionalmente inicia una ronda. En comunidades requiere owner/admin. |
| GET `/{id}?beforeRound=...` | Sorteo, participantes originales, ronda actual y diez rondas históricas, con cursor `nextRound`; `canManage` del sorteo incluye al creador activo de grupo. |
| POST `/{id}/start` | Inicia un sorteo preparado. |
| POST `/{id}/spin` | Selecciona la siguiente posición en modo manual. |
| POST `/{id}/automatic` | Activa/pausa el automático; revalida creador activo del sorteo de grupo o administrador, e iniciador. |
| POST `/{id}/new-round` | Crea una ronda nueva después de completar; no sustituye la anterior. |
| POST `/{id}/cancel` | Cancela preparado/en curso y conserva resultados. |

Creación: `requestId`, `name`, `description`, `memberIds`, `manualNames`, `start`, `automatic`. Comandos: `requestId`, `expectedVersion`, `automatic`. El UUID debe conservarse al reintentar la misma operación. Cambiar la carga bajo la misma clave provoca 409.

Errores principales: 401 sesión inválida, 403 acceso o administración denegados, 404 sorteo ajeno/inexistente, 409 versión/estado/membresía/cooldown/idempotencia incompatible, 400 entrada inválida y 429 límite de creación. No se aceptan ganadores, posiciones ni índices calculados por Android.

Límites explícitos: 1–1.000 participantes por sorteo, nombre hasta 120 caracteres, descripción hasta 500, manuales hasta 80, sin controles y sin duplicados normalizados NFKC/case-insensitive; 30 creaciones por hora y creador. Las identidades importadas/locales se deduplican; dos personas distintas con el mismo nombre siguen siendo dos candidatos. Los perfiles inexistentes quedan fuera de nuevas importaciones.

## 6. Selección segura, rondas y concurrencia

1. Se comprueba JWT/sesión mediante el middleware existente y acceso actual a la sala.
2. Una transacción obtiene el sorteo `FOR UPDATE` y comprueba la versión esperada.
3. Una clave ya confirmada reproduce su respuesta guardada para el mismo actor/carga.
4. Se excluyen del snapshot todos los IDs ya seleccionados en esa ronda.
5. `crypto.randomInt(remaining.length)` elige un candidato y se inserta inmediatamente la posición.
6. Se actualizan ronda, versión, estado y tarjeta cuando corresponda; se confirma la transacción.
7. Se notifica y Android consulta la representación actual.

Dos administradores que parten de la misma versión no crean dos posiciones: el segundo espera el bloqueo y obtiene 409 al observar la nueva versión. También existen restricciones SQL de posición y participante. Crear usa un bloqueo asesor por creador para serializar idempotencia y límite horario. No hay aleatoriedad local ni eliminación determinada por la animación.

Cada ronda copia los participantes originales, conserva iniciador y tiempos, y comienza con todos esos candidatos disponibles. Una nueva ronda no vuelve a importar miembros actuales. Para cambiar la población se crea otro sorteo. Los resultados finalizados no se reescriben mediante la API.

Worker: tick de un segundo, hasta 25 sorteos pendientes por pasada; agenda persistida en `next_draw_at`; primera selección después de aproximadamente un segundo, siguientes después de seis segundos. Varias réplicas usan `FOR UPDATE SKIP LOCKED`, revalidan agenda bajo bloqueo y no consumen el mismo turno. Si el iniciador pierde acceso, capacidad de gestión, cuenta o recibe suspensión, se pausa el automático. El miembro que creó un sorteo de grupo conserva gestión mientras siga activo, aunque no sea admin. Otro administrador autorizado puede continuarlo. Reiniciar el servidor recupera agenda y resultados sin empezar de cero.

La aleatoriedad criptográfica evita el control del ganador desde un APK modificado. No constituye una certificación de juego regulado ni una prueba pública contra un operador que controle el servidor/base de datos.

## 7. Android, UI y accesibilidad

El repositorio usa el endpoint dinámico existente, cliente Ktor compartido, token vigente, serialización y operaciones IO fuera del hilo principal. No agrega credenciales ni URLs privadas fijas al producto.

La creación importa páginas de miembros, permite buscar, marcar/desmarcar, seleccionar/deseleccionar y añadir/quitar nombres manuales. Solo habilita guardar cuando termina la importación, hay permiso, nombre y 1–1.000 participantes. Hasta el límite comienzan seleccionados; grupos mayores muestran todos los miembros importados y el límite seleccionado. La rueda refleja inmediatamente la selección local antes de guardar. Un estado vacío permite manuales sin crear usuarios.

La lista presenta creador, fecha, estado, participantes y rondas. El detalle muestra sala, descripción, rueda, resultados numerados, restantes/originales expandibles e historial. Listas largas utilizan LazyColumn; historial paginado. El formato `Borrador` corresponde al formulario local todavía no enviado; los estados persistidos creados por este flujo comienzan en Preparado o En curso.

La rueda usa Canvas y los colores/contrastes de MaterialTheme, con segmentos equiprobables. Hasta 12 participantes muestra nombres abreviados; entre 13 y 50 muestra números asociados a la lista; por encima muestra segmentos y nombres en la lista, evitando texto ilegible. No carga cientos de avatares dentro de Canvas. El ranking y las filas sí reutilizan CircularProfileImage.

La animación dura cuatro segundos, desacelera y alinea el centro del segmento del ganador bajo el puntero fijo. El resultado recién recibido se anuncia al terminar. Al reabrir no se vuelven a animar todos los resultados históricos. Si llegan varias posiciones mientras el dispositivo estuvo fuera, se presenta el estado actual completo. La animación no bloquea el hilo principal ni dispara el siguiente resultado.

Antes de cada giro se normaliza la orientación a 0–360 grados: evita pérdida de precisión de Float al acumular miles de vueltas y rondas. La prueba de geometría incluye valores acumulados muy grandes y 1.000 segmentos. El formulario reserva espacio para el teclado y lo oculta después de añadir un participante manual.

Se incluyen descripción accesible de rueda, checkbox con estado/rol, números/nombres además del color, live region para ganador/errores, controles Material, modo oscuro y texto escalable. La rueda conserva rótulos pequeños dentro de Canvas; la información completa está disponible en texto accesible fuera de ella.

## 8. Tiempo real, tarjetas y recuperación

Canales: `raffles:group:{roomId}` y `raffles:community:{roomId}`. El gateway consulta `/access` antes de suscribir y reutiliza su revalidación periódica. Evento `raffle_changed`: únicamente ID y acción, sin nombres/resultados privados; el receptor vuelve a consultar HTTP con permisos actuales.

Las tarjetas son filas reales de `fallback_room_messages`, `content_type=raffle`, `media_url` igual al ID del sorteo. Comunidad utiliza General. Se publican en los canales de usuario mediante `publishChatMessageEvent`, como los mensajes actuales. La finalización/cancelación/nueva ronda actualiza la misma tarjeta. El acceso al detalle vuelve a comprobar membresía aunque alguien conserve una tarjeta vieja.

El ViewModel se suscribe mientras la pantalla está STARTED y se desuscribe al detenerse. Reconsulta al reconectar y cada tres segundos mientras está visible para cubrir una invalidación perdida. Conserva páginas históricas ya cargadas. SavedStateHandle conserva ID y operación pendiente con su UUID/carga; reintentar no crea otro giro. El servidor conserva el resultado si la respuesta se pierde. No se inventa un éxito al fallar la red.

Cambio de cuenta/salida: se vacía estado privado y solicitudes pendientes; respuestas de una cuenta anterior no rellenan el estado de otra. Se revalidan HTTP, token, acceso y botones. No hay historial privado persistido en un caché Android nuevo. SavedStateHandle cubre recreación/restauración de tareas de Android; si el usuario elimina completamente la tarea vuelve a localizar el sorteo en la lista del servidor.

## 9. Casos especiales

| Caso | Comportamiento |
| --- | --- |
| Sin elegibles / cero seleccionados | No se admite sorteo vacío; se pueden añadir manuales. |
| Un candidato | Una posición completa la ronda; no divide por cero la rueda. |
| Nombres iguales de usuarios distintos | UUID diferentes; no se deduplican por nombre. |
| Manual duplicado | Se rechaza con error visible; no crea cuentas. |
| Usuario abandona/es expulsado/cambia nombre/elimina cuenta | Snapshot y ranking conservados; acceso revocado si corresponde; perfil ausente no entra en nuevas importaciones. |
| Creador del sorteo abandona / administrador pierde rol | Si deja de tener acceso, no puede emitir comandos; automático pausa al revalidar iniciador; otro admin autorizado puede continuar. El dueño de la sala conserva la política actual: `CREATOR_CANNOT_BE_REMOVED` impide su abandono/expulsión por esos endpoints. |
| Grupo/comunidad eliminados | HTTP deniega; automático deja de seleccionar al revalidar. Se conserva registro, sin exposición pública. |
| Cancelación | Detiene ronda sin borrar resultados; no permite nueva ronda sobre un sorteo cancelado. |
| Doble request / dos administradores | Replay idempotente o 409 por actor/carga/versión; una sola posición. |
| App cerrada / Activity detenida / red perdida | La agenda sigue en servidor; al volver se recupera estado confirmado. |
| 100+ / 1.000 participantes | Límite y listas perezosas; segmentos equiprobables; nombres fuera de la rueda. |
| Muchas rondas | Páginas de diez, historial previo conservado al refrescar; no carga todas desde el inicio. |

## 10. Tests y compilación

**Backend final:** `npm run check` y verificación sintáctica del fixture correctas. `npm test` con PostgreSQL aislado: **442 pruebas, 439 aprobadas, cero fallos, tres omitidas** por requisitos ajenos a Sorteos. `npm run test:raffles`: **13 aprobadas, cero omitidas**, ejecutando transacciones reales en PostgreSQL 16. Gateway: suite existente con comprobaciones nuevas de canales, **una suite aprobada**.

Cobertura específica: importación/exclusión/manual sin cuentas falsas, snapshots, nombres iguales, aliases, idempotencia de creación, ganador enviado por cliente ignorado, simultaneidad de admins/replicas, constraints, ranking sin repetición para 101 participantes, creación de 1.000 y rechazo de 1.001, suspensión/pérdida de rol, privado, cancelación, borrado lógico, reinicio de servicio, doce rondas y paginación, HTTP y perfiles borrados.

Las pruebas Node aíslan resolución de miembros/autenticación con SQL de fixture; no certifican por sí solas toda la importación legacy ni un despliegue real. El fixture móvil reutiliza rutas/servicio/DDL y la política `authenticateClientSession`, con sesiones JWT en registro QA propio. Su publicación realtime se aísla; el gateway tiene su prueba separada.

Pruebas Android nuevas: geometría y estado derivado; UI de rueda en 2/5/10/20/50/101/1.000, animación, tema oscuro/fuente grande, tarjeta y creación/exclusión/manuales; transporte HTTPS desde teléfono, permisos, giro concurrente, recuperación por cliente nuevo, automático, ranking, nueva ronda y fallo real de conexión.

**Compilación Android final:** `assembleDebug`, `assembleDebugAndroidTest` y `testDebugUnitTest` finalizaron correctamente en una copia aislada de las fuentes actuales; el build tardó 7 min 15 s. Pasaron **1.300 pruebas unitarias en 283 suites**, sin fallos, errores ni omisiones, incluidos seis casos nuevos de Sorteos. Se verificó que los 14 archivos Android de esta integración y del ajuste musical coinciden byte por byte con las fuentes compiladas.

**Motorola g04, Android 14/API 34:** la tanda final pasó **26 de 26 pruebas físicas**, sin fallos ni omisiones, en **118,998 s**: cinco de UI de Sorteos, una integración HTTPS/PostgreSQL de grupos y comunidades, doce del selector musical, dos de letras y seis de invitaciones de llamadas locales. Se instaló el APK debug por reemplazo, se comprobó que su SHA-256 coincide con el artifact construido y se conservó la identidad del directorio de datos; no se borraron datos del usuario.

La primera copia aislada omitió `app/schemas/54.json` y causó un fallo de fixture; se repitió con los esquemas copiados. Dos tandas móviles previas tuvieron un fallo de desplazamiento del test del formulario: se corrigió el test para desplazarse a la clave estable del elemento de LazyColumn y se conservaron las comprobaciones de selección y visibilidad. Los logs de los fallos se preservaron. La tanda final completa utiliza esa corrección y la rueda con orientación normalizada. Estos casos no acreditan una llamada con otra persona ni el flujo WebSocket completo de dos teléfonos en producción.

Evidencia con hashes, casos y alcance: [VALIDACION_SORTEOS.json](VALIDACION_SORTEOS.json). Capturas QA del dispositivo inspeccionadas visualmente: [rueda de cinco](docs/oficial/capturas/sorteos-moto-g04/ruleta-5.png), [rueda de veinte](docs/oficial/capturas/sorteos-moto-g04/ruleta-20.png) y [formulario con exclusión y manual](docs/oficial/capturas/sorteos-moto-g04/creacion-qa.png). El formulario está desplazado para mostrar el participante manual y los controles de creación; su rueda queda parcialmente fuera de la vista.

Al terminar se cerraron el servidor HTTPS QA y el clúster PostgreSQL desechable, se eliminaron sus esquemas de prueba y se retiró el reverse de ADB. No se modificó la base de producción.

## 11. Cómo probar manualmente

1. Distribuir API, gateway y Android de esta implementación en un entorno de prueba; confirmar que las cinco tablas existen. La app de producción y servidor antiguo no incorporan estas rutas automáticamente.
2. Con dos cuentas miembro de un grupo privado, una owner/admin y otra normal, abrir menú Sorteos. Ambas muestran Crear; el miembro normal gestiona sus propios sorteos. El admin gestiona todos. Repetir en comunidad para comprobar su política owner/admin.
3. Crear «Equipos», desmarcar un miembro, comprobar rueda y contador, volver a marcarlo. Añadir Pedro, intentar Pedro otra vez y comprobar rechazo. Crear preparado.
4. Abrir la tarjeta desde ambos teléfonos. Iniciar automático; comparar UUID, posiciones y nombres. Pausar y girar manualmente; no debe repetirse ningún candidato.
5. Desde dos admins emitir giro con la misma versión: una operación válida y otra actualización requerida. Reintentar una petición cuya respuesta se perdió con el mismo UUID.
6. Cerrar app/red durante una selección. Volver; comprobar ranking desde servidor y ausencia de reinicio. Recrear Activity/cambiar orientación según configuración de la app.
7. Completar, iniciar Nueva ronda y revisar que Ronda 1 mantiene participantes, tiempos y orden. Cargar más de diez rondas.
8. Expulsar observador o retirar admin; comprobar 403, datos vacíos y pausa automática. Cancelar y comprobar conservación de posiciones. Eliminar sala de prueba y verificar denegación.
9. Revisar 2/5/10/20/50/100+ candidatos, modo oscuro, fuente 1,5 y TalkBack; comparar rueda/número/lista.

Para PostgreSQL automatizado: asignar `RAFFLE_TEST_DATABASE_URL` a una instancia desechable en localhost y ejecutar `npm run test:raffles` dentro de la API. La prueba crea y elimina únicamente su esquema aleatorio. Nunca apuntarla a producción.

Para el teléfono: levantar `scripts/start-raffles-device-fixture.cjs` con las variables de base, `RAFFLE_QA_CERT_PATH`, `RAFFLE_QA_KEY_PATH`; certificado QA con SAN 127.0.0.1. Ejecutar `adb reverse tcp:55441 tcp:55441` y pasar a instrumentación `raffle_api_url=https://127.0.0.1:55441` y el certificado PEM público en `raffle_cert_base64`. Cerrar fixture mediante `stop` en stdin; elimina solo su esquema. No necesita cambiar sesión/servidor del producto.

## 12. Pendientes reales y límites

- Despliegue de API/gateway y distribución del APK que contiene Sorteos; esta entrega no modifica producción.
- Validación completa de navegación/tiempo real con dos dispositivos y membresías del entorno desplegado. Las pruebas locales no sustituyen esa comprobación.
- Perfil de FPS/memoria sostenido bajo muchas salas automáticas, historial y participantes máximos; el límite de 1.000 y el lote de 25 son controles de recurso, no una certificación de capacidad.
- Prueba manual TalkBack y comparación visual con dispositivos distintos del Motorola disponible.
- Operación administrativa de retención/archivo según la política del producto. No se borran automáticamente resultados históricos.

No se incorporan apuestas, pagos, premios monetarios ni garantías regulatorias al módulo.
