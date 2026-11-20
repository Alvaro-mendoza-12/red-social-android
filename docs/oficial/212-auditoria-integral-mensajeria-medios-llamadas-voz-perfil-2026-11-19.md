# 212 · Auditoría integral de mensajería (~2 s), medios/video, llamadas multidispositivo, salas de voz, perfil y ajustes

19 de noviembre de 2026. Esta intervención aborda de forma transversal las causas raíz detectadas en Android (`app/`), Web (`NoWing-web/`), API y Realtime Gateway (`infrastructure/local-fallback/`), Nginx y servidor de señalización (`signaling-server/`) sobre la base común `e43080c7`. Conserva intactos los cambios locales ajenos y no realiza despliegue sobre el host Ubuntu activo ni modifica el plan experimental 105.

## 1. Causa raíz y corrección del retraso (~2 s) al enviar mensajes

La demora percibida de ~2 segundos al enviar mensajes provenía de cinco cuellos de botella encadenados entre cliente y servidor:

1. **Android (`PendingMessageSyncScheduler`, `SyncManager`, `MessageRepository`, `GroupRepository`, `CommunityRepository`, `AdvancedMessagingRepository`)**:
   - Cada envío encolaba inmediatamente un `OneTimeWorkRequest` de WorkManager (`initialDelaySeconds = 0L`) mientras el mismo cliente ya enviaba en línea, disparando un `SyncManager.syncNow()` completo que descargaba en paralelo 3 catálogos íntegros (`chats`, `groups`, `communities`) y competía por SQLite/Room y el enlace HTTP con la propia escritura del mensaje.
   - La actualización optimista de UI esperaba la programación en WorkManager y una verificación redundante en el DAO de borrados para IDs recién generados; `applyAutoTranslation` ejecutaba suspensión innecesaria cuando no había traducción pendiente.
   - **Corrección**: `PendingMessageSyncScheduler` retrasa 3 s el worker de respaldo cuando el dispositivo está en línea (`initialDelaySeconds = 3L`, `0L` sin conexión); `SyncManager` entra en vía rápida de *outbox* para los motivos `*_message_pending`, `*_poll_pending`, `*_delete_pending` y `manual_message_retry` sin recargar catálogos completos; los repositorios emiten el mensaje optimista y `onQueued()` antes de encolar WorkManager y omiten la consulta redundante de borrado; `AdvancedMessagingRepository.applyAutoTranslation` añade salida síncrona rápida.
2. **Web (`webOutbox.ts`, `ChatDetailPage.tsx`, `RoomChatPage.tsx`)**:
   - Cada envío disparaba hasta dos recargas HTTP completas del historial tras recibir el `201`.
   - **Corrección**: inserción/actualización directa en memoria del mensaje canónico devuelto por el servidor sin recargar todo el historial.
3. **Backend API (`data-routes.js`, `realtime-publish.js`, `nginx/default.conf`, `realtime-gateway/src/server.js`)**:
   - El envío en grupos y comunidades cargaba el historial completo (`loadCollectionRoomPayload`) en lugar de validar únicamente membresía/permisos (`loadCollectionRoomAccess`); el envío DM bloqueaba la respuesta `201` esperando `refreshChatSummary`, la limpieza de ocultos y toda la difusión Realtime + FCM; las difusiones grupales realizaban peticiones HTTP individuales secuenciales al gateway; Nginx abría conexiones TCP efímeras hacia los upstreams.
   - **Corrección**: vía rápida de acceso local en DM (`includeImportedPreferences: false`) y `loadCollectionRoomAccess` en grupos/comunidades; paralelización de `DELETE fallback_chat_hidden` + `refreshChatSummary`; difusión ordenada no bloqueante post-commit (Realtime autoritativo antes que FCM); soporte de alias `clientMessageId` / `client_message_id` en la inserción idempotente (`insertMessageOnce`); agrupamiento en lote (`/internal/publish-batch`) cuando hay más de 5 destinatarios con respaldo automático 404/405; `upstream` con `keepalive 32` en `nginx/default.conf`.

## 2. Medios, Reels, Cine y WebRTC

- **Galería de fotos Web (`postPhotoGallery.css`)**: contenedor con relación reservada (`aspect-ratio: 4 / 3`, `object-fit: contain`) que elimina el desplazamiento acumulativo de diseño (CLS) y el recorte de imágenes.
- **Reels / Video (`VideoPerformanceProfile.kt`, `VideoFeedScreen.kt`, `ReelsPage.tsx`)**: ajuste de búfer de arranque inicial y reserva de precarga N+1 sensible a red/ahorro de datos, conservando intacta la verificación previa de seguridad `HEAD` de `PlaybackCacheAdmission` y liberando decodificadores lejanos en Web.
- **Cine en salas de voz (`CinemaPlayer.kt`)**: atributo `C.USAGE_MEDIA`, volumen base `0.55f` y detección de auriculares cableados/Bluetooth/USB con guardas por nivel de API.
- **Cámara y bloqueo de reposo (`WebRTCManager.kt`, `ActiveMediaWakeLocks.kt`, `CallForegroundService.kt`, `VoiceForegroundService.kt`, `WebCallManager.ts`)**: `CameraEventsHandler` real en Android con recuperación por reconstrucción si `switchCamera` falla en OEMs; adquisición y liberación limpia de `PowerManager.PARTIAL_WAKE_LOCK` + `WifiManager.WifiLock` durante llamadas y salas de voz activas; soporte `switchCamera` en Web.

## 3. Llamadas multidispositivo (continuación directa de 211) e invitación en llamada

- **Servidor (`call-access-store.js`, `data-routes.js`, `realtime-publish.js`, `push-notifications.js`)**: al confirmarse una aceptación o cierre en `call-access-store.js`, `onCallSessionResolved` difunde el evento de resolución (`answered_elsewhere` / `handled_elsewhere`) por Realtime excluyendo `excludeSessionId` y envía cancelación silenciosa FCM filtrando el `auth_session_id` ganador por SQL (sin exponerlo en la carga útil).
- **Android y Web (`CallControlClient.kt`, `CallRepository.kt`, `CallInvitationOverlayHost.kt`, `CallViewModel.kt`, `CallScreen.kt`, `callControl.ts`, `WebCallManager.ts`, `CallInterface.tsx`, `AppLayout.tsx`)**:
  - Manejo de `CALL_ANSWERED_ELSEWHERE`, `CALL_INVITE_EXPIRED` y eventos `answered_elsewhere` / `handled_elsewhere` que detienen timbre, servicio en primer plano y superposición en los dispositivos secundarios mostrando «Llamada respondida en otro dispositivo» sin interrumpir al dispositivo ganador.
  - Botón e interfaz para invitar participantes adicionales durante una llamada activa (`POST /v1/data/calls/:callId/invite`), endureciendo `pendingInvitees` con `ConcurrentHashMap.newKeySet()`.
  - Reacciones en llamadas limitadas a 1 activa por usuario con enfriamiento de 700 ms y transición suave de entrada/salida.

## 4. Salas de voz: desincronización de micrófono, indicador de habla y compartir

- **Sincronización autoritativa de micrófono (`signaling-server/index.js`, `VoiceRoomAdmissionState.kt`, `VoiceRoomRepository.kt`, `VoiceRoomPage.tsx`)**: `user-joined` publica `isMuted`, `role` y `handRaised` autoritativos; `mute-state` persiste en base de datos, aplica guarda estricta `!nextMuted` para audiencia y respeta ventana de gracia de 5 s; clientes reconcilian `reconcileSelfVoiceMuteState` con `webRTCManager.setAudioEnabled(!effectiveMuted)` en `applyVoiceRoomState`, `voice-stage-state` y `mute-state`.
- **Indicador de habla (`VoiceRoomActiveScreen.kt`, `VoiceRoomPage.tsx`, `voice.css`)**: sustituye el aro tipo Momentos por un indicador independiente verde vivo (`#22C55E`) con pulso controlado, diferenciándolo visualmente del estado de historias/Momentos.
- **Compartir / invitar (`VoiceRoomActiveViewModel.kt`, `VoiceRoomActiveScreen.kt`, `ShareVoiceRoomDialog.tsx`)**: hoja/diálogo unificado con enlace externo y copia en la parte superior y selección múltiple con búsqueda sobre chats recientes, grupos, comunidades y usuarios en la parte inferior.
- **Reacciones (`VoiceRoomActiveViewModel.kt`, `useReactionBursts.ts`, `signaling-server/index.js`)**: tope de 1 reacción simultánea por usuario, enfriamiento de 700 ms en cliente y 500 ms (30/min) con campos en lista blanca en señalización.

## 5. Perfil, insignia de verificación y ajustes de sesión

- **Avatar e insignia (`CircularProfileImage.kt`, `screens.css`)**: anillo de Momentos aislado al contenedor interno e insignia de verificación elevada por encima (`zIndex(10f)` en Compose, `z-index: 6` en Web).
- **Visitantes de perfil (`ProfileVisitorsLoader.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`)**: `clearTransientState()` limpia el estado efímero al cerrar o cambiar de perfil.
- **Ajustes y cierre de sesión (`SettingsScreen.kt`, `SettingsDirectory.kt`, `AccountSettingsPage.tsx`, `AuthRepository.kt`)**: botón independiente «Cerrar sesión» con diálogo de confirmación en Android y Web; en cierre de sesión contra Ubuntu, se marca estado desconectado antes de revocar el token.

## 6. Verificación ejecutada

- **Android (`:app:testDebugUnitTest`)**: compilación Kotlin/Hilt y 7 clases JVM objetivo aprobadas — **48/48 pruebas aprobadas, 0 fallos** (`SyncManagerOutboxFastPathTest`, `CallControlAccountTest`, `VoiceRoomAdmissionStateTest`, `VoiceRoomEntryLifetimeTest`, `ProfileVisitorsLoaderTest`, `SettingsDirectoryTest`, `CallOutcomePersistenceTest`).
- **Web (`NoWing-web`)**: `npx tsc --noEmit` (0 errores), `npx vite build` (1.959 módulos, éxito), `npm run test:media` (**21/21 aprobadas**), `npm test` (**208/208 suites, 1.344/1.344 pruebas aprobadas**).
- **Backend / Realtime / Signaling**:
  - `infrastructure/local-fallback/api`: **16/16 suites dirigidas aprobadas** (incluidas `message-send-fastpath.test.js`, `call-access-store.test.js` con 76 casos, `push-notifications.test.js`, `realtime-publish.test.js`, `data-routes.test.js`).
  - `infrastructure/local-fallback/realtime-gateway`: `npm test` aprobado.
  - `signaling-server`: `npm test` **59 aprobadas, 0 fallos** (2 omitidas por requerir Redis vivo).

## 7. Pendientes honestos fuera del alcance de esta verificación local

- Ensayo físico con dos dispositivos reales en red móvil/Wi-Fi para validar entrega de cancelación silenciosa FCM en segundo plano/Doze y timbre simultáneo.
- Activación controlada en el servidor Ubuntu de producción y compilación/entrega de nueva APK firmada una vez aprobada por el propietario.
