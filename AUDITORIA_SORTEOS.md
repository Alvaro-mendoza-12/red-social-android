# Auditoría previa: Sorteos en grupos y comunidades

Fecha: 2026-10-04. Rama: `feature/live-gifts`. Inspección del árbol local antes de implementar.

## Arquitectura encontrada

Android usa Kotlin, Jetpack Compose Material 3, Hilt, ViewModels, coroutines/Flow y serialización Kotlin. `AuthService.ensureValidAccessToken` suministra el JWT local. El cliente HTTP Ktor de `SupabaseConfig` es reutilizable aunque conserve el nombre histórico; la URL de Ubuntu se resuelve con `RuntimeBackendEndpoint`. Backend principal: Node/Express y PostgreSQL; `server.js` autentica JWT y suspensiones, `data-routes.js` registra módulos e inicializa esquema. Supabase queda como compatibilidad de datos importados. No se introducirá otro backend.

## Grupos, comunidades y miembros

`GroupRepository` y `CommunityRepository` delegan en API Ubuntu; tablas `fallback_collection_rooms`, `fallback_collection_members`, `fallback_room_messages`; datos antiguos de `supabase_import.groups`, `group_members`, `communities`, `community_members` y mensajes se fusionan en helpers existentes. `loadCollectionRoomAccess` trae sala/miembros con una cota actual de 10 000; `publicProfilesByIds` permite resolver nombres/avatares sin cargar el historial del chat. Se reutilizan ambos para importar participantes reales. La eliminación local se representa mediante `is_deleted`; estados left/removed/banned no son elegibles. Los participantes manuales no serán cuentas falsas.

## Roles y permisos

Roles normalizados: owner/creator, admin, moderator/mod y member. Por indicación del usuario del 4 de octubre, cualquier integrante activo de un **grupo** puede crear sorteos y gestionar los propios; owner/admin pueden gestionar todos. En comunidades se conserva creación/gestión por owner/admin y lectura por miembros. Los permisos `canCreate` y `canManage` son independientes. `loadCollectionRoomAccess` admite exploración pública: esto no basta para Sorteos, cuyo servicio exige membresía activa o propiedad y la comprueba en cada endpoint, mutación y paso automático. Ninguna decisión de ganador o permisos se acepta del APK.

## APIs, tiempo real, notificaciones y navegación

Las APIs existentes son REST `/v1/data/messages/groups|communities/:roomId`. El módulo nuevo seguirá `/v1/data/raffles/:kind/:roomId`. Tiempo real: `realtime-publish.js` publica al gateway interno, y `UbuntuRealtimeClient` consume eventos por canal con JWT y reconexión. Gateway verifica suscripciones mediante URLs API; se añadirá un canal autenticado de Sorteos por espacio. Eventos solo invalidan estado (sin nombres/resultados privados); el cliente recupera el resultado autoritativo por HTTP. Al reconectar, `subscription_ready` dispara recuperación. API incluye notificaciones y mensajes con receipts; se reutilizarán los mensajes de sala para una tarjeta de sorteo, sin un sistema paralelo de publicaciones. No se requieren push masivas para cada giro.

`Screen`, `MainNavigationRoutes`, `GroupChatScreen` y `CommunityChatScreen` permiten integrar una ruta reutilizable y entrada «Sorteos» en el menú del chat. `MessageBubble` existente sigue atendiendo el resto de contenidos; una tarjeta propia solo se aplica a mensajes de tipo raffle. Se usa MaterialTheme, Material 3, avatars y estados Compose.

## Riesgos encontrados y decisiones

- Hay otras IA en la misma rama: cambios limitados a nuevos archivos `raffles`, documentos y puntos mínimos de integración. No revertir, limpiar ni confirmar cambios ajenos.
- Identidades locales/legacy son aliases diferentes: reutilizar `userIdentity`; referencias de participante registrado serán IDs de perfil de texto, compatibles con miembros existentes. Relaciones entre nuevas entidades sí tendrán claves foráneas UUID.
- Giro duplicado entre administradores: transacción, bloqueo de sorteo `FOR UPDATE`, versión esperada, UUID de idempotencia y constraints por ronda/posición/participante.
- Animación no produce aleatoriedad: servidor usa `crypto.randomInt` y persiste antes de publicar; el cliente solo alinea el segmento elegido.
- Histórico no depende de lista/perfil actual: snapshot de nombre/avatar/tipo y copia por ronda; rondas nuevas reutilizan el snapshot original. Dejar/expulsar de sala revoca acceso, conserva la participación histórica.
- Modo automático no dependerá de Activity viva: worker de backend con agenda durable y controles de inicio/pausa. Revalida permisos del iniciador; revocación pausa la ronda hasta intervención de otro admin.
- Crecimiento: límite explícito de 1 000 participantes seleccionados, miembros elegibles paginados y búsqueda; lista/historial paginados. Para ruletas grandes se ven segmentos identificados por número y lista accesible con nombres, sin cambiar probabilidades.
- El esquema local admite grupos importados sin fila de sala nativa: no añadir FK incompatible hacia tablas legacy. Comprobar acceso también para sala eliminada.
- No desplegar ni mutar producción para probar; disponer base PostgreSQL aislada local y fixtures exclusivamente en tests. Compilación Android depende del SDK/JDK del host y de cambios concurrentes.

## Estrategia de implementación

La revisión final confirmó que el propietario de la sala no puede salir ni ser expulsado por el endpoint actual (`CREATOR_CANNOT_BE_REMOVED`). Un creador de sorteo que sea administrador sin ser dueño sí puede perder acceso; el worker debe pausar su automático. Se conserva esta diferencia de permisos, sin inventar transferencia de propiedad.

1. Migración idempotente `fallback_raffles`, participantes, rondas, resultados y comandos; lectura/transacciones en módulo aislado.
2. REST con permisos reutilizados, snapshot, creación/inicio/selección automática/manual/cancelación/nueva ronda; worker durable y eventos de invalidación.
3. Android: DTO/API/repositorio/Hilt ViewModel; listado, creación/exclusión/manuales, ruleta/ranking/historial, recuperación por lifecycle y realtime.
4. Integración mínima con chats/navegación y tarjeta existente de mensajes.
5. Pruebas reales Node/PostgreSQL de concurrencia/privacidad/snapshot, pruebas Android de estado/geometría, suite existente y compilaciones. Documentar resultados concretos en `IMPLEMENTACION_SORTEOS.md` y `CHECKLIST_SORTEOS.md`.

Referencias técnicas: [Node crypto.randomInt](https://nodejs.org/api/crypto.html#cryptorandomintmin-max-callback) evita sesgo modular; [PostgreSQL row locks](https://www.postgresql.org/docs/current/explicit-locking.html) serializa escrituras concurrentes mediante FOR UPDATE. No son una prueba de equidad certificada ni un compromiso verificable contra el propio operador del servidor.
