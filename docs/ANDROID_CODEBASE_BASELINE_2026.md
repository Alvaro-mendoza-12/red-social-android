# Android Codebase Baseline 2026

Fecha de revisión: 2026-05-06

## Resumen ejecutivo

La base Android de Conéctate ya es funcional y bastante más madura que un proyecto inicial, pero todavía no está en el punto de mantenibilidad de una app grande con equipos múltiples.

Fortalezas actuales:
- Compose + Hilt + navegación moderna ya están consolidados.
- El proyecto compila y hoy pasa `compileDebugKotlin`, `testDebugUnitTest`, `assembleRelease`, auditoría SQL del release manifest y `tools/performance_budget_check.py`.
- Hay reutilización visible en mensajes, perfil y ajustes.
- Varias pantallas críticas ya manejan mejor estados vacíos, errores y offline.

Riesgos estructurales todavía abiertos:
- Persisten repositorios y ViewModels demasiado grandes.
- La cobertura de tests es baja para el tamaño del sistema.
- Hay artefactos SQL/log/documentación histórica todavía presentes, pero la ruta deployable queda acotada por `sql/release-manifest.txt`.
- La documentación principal se actualizó para separar estado activo, backups y archivos legacy.
- La seguridad SQL/RLS deployable pasa gate con baseline; la deuda restante es principalmente histórica/fuera del manifest.

## Métricas actuales del código Android

- Archivos Kotlin principales: ~352
- Líneas Kotlin principales: ~92.5k
- Archivos de test: 11
- Líneas de test: ~857
- Archivos de más de 1000 líneas: 18
- Archivos de más de 500 líneas: 47

## Repositorios más pesados

- `MessageRepository.kt`
- `GroupRepository.kt`
- `CommunityRepository.kt`
- `UserRepository.kt`
- `VideoRepository.kt`

Estos archivos concentran demasiadas responsabilidades a la vez: red, caché, sincronización, normalización, lógica de negocio, fallback legacy y observabilidad.

## Dirección arquitectónica recomendada

### 1. Repositorios más pequeños

Mover lógica a piezas explícitas por responsabilidad:

- `remote/`: llamadas HTTP/RPC/PostgREST
- `local/`: Room, SharedPreferences, caché local
- `sync/`: sincronización, realtime, reconciliación
- `policy/`: validación, normalización, guards
- `media/`: upload, compresión, metadata

Objetivo:
- que cada repositorio principal orqueste;
- que no haga todo directamente.

### 2. Pantallas y ViewModels más legibles

Regla propuesta:
- pantallas Compose: idealmente < 600 líneas
- ViewModels: idealmente < 700 líneas
- repositorios principales: idealmente < 900 líneas

Si un archivo supera eso, debe justificarlo por composición, no por mezcla de responsabilidades.

### 3. Documentación viva

La documentación debe responder tres preguntas:

- qué arquitectura usa hoy el proyecto;
- qué piezas siguen híbridas o en transición;
- qué deuda técnica está aceptada y cuál no.

Documentos mínimos recomendados:
- arquitectura Android actual
- mapa de repositorios y ownership
- guía de flujo de datos por feature
- lista de deuda priorizada

### 4. Orden del repo

La raíz no debería acumular hotfixes, logs, reportes y scripts históricos sin clasificación.

Dirección sugerida:
- `docs/`
- `sql/migrations/`
- `sql/hotfixes/`
- `sql/archive/`
- `tools/`
- `reports/local/` o fuera del repo para artefactos temporales

## Mejoras aplicadas en esta ronda

### Rescate Android 2026-04-26

Se cerró una ronda de saneamiento enfocada en recuperar el baseline completo de build y aislar superficies incompletas:

- Descubrimiento público de hashtags fue reactivado despues del rescate: `HashtagRepository` consulta RPCs Supabase para busqueda/trending/contenido por hashtag y conserva fallback Room.
- `HashtagRepository` ya no queda reducido a extracción local: posts/videos por hashtag se resuelven con `get_posts_by_hashtag` y `get_videos_by_hashtag`.
- `VideoRepository` recuperó fachadas públicas estables para perfil/video (`getUserVideos`, `getSharedVideos`, `hasPublicVideos`).
- `MessageRepository` volvió a orquestar `MessageSessionStateStore`, `MessageMediaResolver` y `MessageReportStore` sin duplicación de inyección.
- El servicio push canónico quedó fijado en `conectate.com.data.service.MyFirebaseMessagingService`.
- Se alineó Hilt a `2.51` entre plugin y dependencias, y se corrigió `jacocoTestReport` para Gradle `8.11`.
- Release quedó desbloqueado en R8 con reglas explícitas para la integración opcional de BlockHound/ Reactor.

### Sprint 1-5 cierre 2026-05-06

Se cerró una pasada de producto enfocada en que la implementación ya no contradiga el contrato de Sprint 1-5:

- `WalletRepository` dejó de simular 1000 monedas locales y ahora lee saldo real desde Supabase.
- `GiftSelectionDialog` carga regalos activos desde `virtual_gifts` y envia con `send_virtual_gift`.
- `LiveViewerScreen` expone la acción `Regalar` contra el host del live.
- `sql/migrations/20260506_wallet_gifts_rpc_security_hardening.sql` agrega `wallet_get_or_create` y endurece `wallet_add_diamonds`, `wallet_spend_diamonds` y `send_virtual_gift`.
- `MessageNotificationService` elimina el `TODO()` ejecutable y publica notificaciones reales con `MessageNotificationFactory`, acciones inline, respeto de mutes y permiso `POST_NOTIFICATIONS`.
- `MessageSessionStateStore` queda inyectado como singleton compartido entre `MessageRepository` y `MessageNotificationService`, por lo que el chat visible ya evita alertas redundantes.
- `MessagePolicyEngine` reemplaza placeholders de anti-spam/deduplicacion por una ventana en memoria para envios recientes.
- `StorageService` corrige la normalizacion de `Content-Type` para uploads reales y `PostRepository.addAudioComment` guarda audios bajo carpeta del usuario.
- `sql/migrations/add_audio_to_post_comments.sql` queda en el manifest con columnas `media_url`/`content_type`, constraint de tipo y policies de storage por carpeta del owner.
- `MonetizationRepository`, `MonetizationCenterViewModel` y `MonetizationCenterScreen` conectan planes premium, suscripcion actual, anuncios activos e impresiones de ads con Supabase.
- `sql/migrations/20260506_monetization_premium_ads_security_hardening.sql` agrega RLS/policies y endurece `subscribe_to_plan` para que el cliente solo pueda suscribirse con su propia cuenta.
- `gradle.properties` sube el heap del daemon a 4 GiB para que `minifyReleaseWithR8` complete el release sin caerse por memoria.
- `sql/release-manifest.txt` incluye el hardening nuevo para que la ruta deployable quede cerrada.
- Documentación viva actualizada: `README.md`, `docs/SPRINT_1_TO_5_IMPLEMENTATION_STATUS_2026.md`, `sql/README.md` y `docs/BACKUP_AND_ARCHIVE_STATUS_2026.md`.

### Cierre de huecos 2026-05-06 noche

Se cerraron rutas que aun se sentian funcionales en UI pero no persistian estado completo:

- `ConversationRepository` deja de ser un stub y opera sobre `ChatDao` para listar, archivar, marcar favorito, fijar, silenciar, marcar como leido y borrar conversaciones locales.
- Chats archivados quedan como preferencia por usuario: `Chat`, `ChatEntity`, Room v39, `MessageRepository.archiveChat`, filtro UI `Archivados` y SQL `20260506_chat_archive_preferences_2026.sql`.
- Favoritos de chat quedan como preferencia por usuario: `Chat`, `ChatEntity`, Room v40, `MessageRepository.favoriteChat`, filtro UI `Favoritos`, estrella visible y SQL `20260506_chat_favorites_preferences_2026.sql`.
- `secure_set_chat_preferences` queda extendido para `is_archived` e `is_favorite`, manteniendo `auth.uid()` y validacion de pertenencia al chat.
- `CustomizeAppScreen` deja de ser mock: guarda layout/orden de barra inferior en `AppSettingsPreferences`, y `HomeScreen` aplica ese orden en runtime.
- Reportar chat desde `ChatViewModel` usa `MessageRepository.reportContent`/`submitReport` y ya no queda como funcion vacia.
- Feed posts/videos cachea JSON real en Upstash y hashtags publicos usan backend con fallback local.

### UserRepository

Se extrajo el estado local de perfil/privacidad a un store dedicado:

- `app/src/main/java/conectate/com/data/repository/user/UserLocalStateStore.kt`

Objetivo:
- sacar de `UserRepository` la persistencia local y los overrides de privacidad/presencia/perfil;
- dejar el repositorio más enfocado en backend y coordinación.

### MessageRepository

Se empezó a partir `MessageRepository` por responsabilidades internas:

- `app/src/main/java/conectate/com/data/repository/message/MessageSessionStateStore.kt`
- `app/src/main/java/conectate/com/data/repository/message/MessageMediaResolver.kt`
- `app/src/main/java/conectate/com/data/repository/message/MessageReportStore.kt`

Objetivo:
- sacar del repositorio principal el estado de sesión, view-once y overrides locales;
- separar resolución multimedia/signed URLs;
- separar reportes/moderación remota y mantener al repositorio como orquestador.

### GroupRepository + CommunityRepository

Se consolidó una base compartida para piezas repetidas de `grupo` y `comunidad`:

- `app/src/main/java/conectate/com/data/repository/collection/CollectionAdminRemoteStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMediaStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionCreationRemoteStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMessageEngagementStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMemberProfileStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMessageForwardingSupport.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMembershipMutationSupport.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMessageSyncSupport.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionMessageReceiptRemoteStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionPollMessageRemoteStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionPollStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionPendingMessageRemoteStore.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionPendingMessageRetrySupport.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionRepositorySupport.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionRealtimeLifecycle.kt`
- `app/src/main/java/conectate/com/data/repository/collection/CollectionSyncSupport.kt`

Objetivo:
- unificar operaciones remotas de membresía, roles, borrado lógico y actualización de info;
- unificar uploads de imagen/audio/media entre colecciones;
- unificar recibos detallados y toggle de reacciones de mensajes;
- unificar reenvío entre colecciones con las mismas reglas de validación;
- unificar creación remota de colección + membresía inicial;
- unificar join/leave del usuario actual con optimistic local update;
- unificar enriquecimiento de miembros con perfiles y fallback local;
- unificar merge del flujo activo, preview y reconciliación de no leídos al sincronizar mensajes;
- unificar receipts remotos de lectura/entrega con confirmación por estado;
- unificar creación de mensajes de encuesta con `RPC + fallback`;
- unificar estado, votos y votantes de encuestas con el mismo fallback remoto;
- unificar envío pendiente con `RPC + fallback legacy` y retry con backoff;
- unificar helpers repetidos de payload, representación, patch ids y retry;
- unificar bootstrap de sesión/realtime y el loop de resuscripción/reintento;
- unificar la lógica de preservación de último mensaje y detección de contenido remoto más nuevo;
- unificar validación de permisos de moderación, edición y borrado entre `group` y `community`;
- bajar duplicación antes de seguir partiendo ambos repositorios en bloques más grandes.

### Mensajes

Se separó el refresh manual del refresh silencioso al volver a la pantalla para evitar el indicador visual innecesario en el hub de mensajes.

### Orden del repo

Se dejó una guía técnica explícita y la estructura base para clasificar SQL nuevo:

- `docs/REPO_STRUCTURE_GUIDE_2026.md`
- `sql/migrations/`
- `sql/hotfixes/`
- `sql/diagnostics/`
- `sql/security/`
- `sql/archive/`

Además, la raíz ya empezó a vaciarse por lotes seguros:

- primera ola: hotfixes `2026` recientes a `sql/hotfixes/`, seguridad a `sql/security/` y verificación puntual a `sql/diagnostics/`;
- segunda ola: scripts claros de diagnóstico/prueba/verificación a `sql/diagnostics/`;
- tercera ola: scripts explícitos de esquema/evolución (`add_*`, `create_*`, `*_schema.sql`, `*_rpc.sql`, `update_*`, `enable_*`, `increment_*`) a `sql/migrations/`.
- cuarta ola: scripts claros de `RLS`, `policies`, hardening y seguridad operativa a `sql/security/`;
- quinta ola: scripts legacy o superseded (`FINAL_*`, `fixer_sql_v*`, `*nuclear*`, operaciones directas de constraint) a `sql/archive/`.
- sexta ola: scripts correctivos restantes de la raíz a `sql/hotfixes/`, y los pocos scripts manuales/mixtos restantes a `sql/archive/`.

Además, ya se movió un primer lote seguro de scripts `2026` desde la raíz hacia `sql/hotfixes/`, `sql/migrations/`, `sql/security/` y `sql/diagnostics/`.
También se movió una segunda ola de scripts explícitos de diagnóstico/prueba/verificación a `sql/diagnostics/`.

## Qué sigue después de esta ronda

Prioridad alta:

1. Extraer bloques internos de `MessageRepository`
2. Subir cobertura de tests en mensajes, perfil, auth y videos
3. Mantener el release manifest limpio y reducir deuda SQL/RLS histórica fuera del deploy path
4. Reducir logging sensible y observabilidad dispersa
5. Evaluar modularización gradual si el alcance del producto sigue creciendo

## Criterio para empezar nuevas funcionalidades

Se recomienda empezar funcionalidades grandes solo cuando:

- los repositorios principales más críticos ya tengan una base reusable y consistente;
- la documentación base ya no contradiga al código;
- el flujo de mensajes, perfil y video tenga tests mínimos de regresión;
- la deuda SQL crítica tenga un plan de cierre o aislamiento claro.

Con la ronda actual, la base Android ya queda razonablemente preparada para retomar nuevas funcionalidades controladas, pero no debe considerarse "cerrada" hasta elevar tests y seguridad backend.
