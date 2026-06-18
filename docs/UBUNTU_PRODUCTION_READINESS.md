# Ubuntu como backend primario: matriz de produccion

Fecha de creacion: 2026-06-15.
Fecha de ultima revision: 2026-06-18.

Este documento separa lo que ya corre por Ubuntu de lo que todavia conserva
dependencia funcional o legacy de Supabase. Supabase queda permitido solo para
importacion/migracion temporal de usuarios, perfiles, tablas antiguas y storage.

## Validacion en VM

Desde Windows, primero copia los cambios:

```powershell
ssh -p 2222 alvaro@127.0.0.1 "mkdir -p ~/conectate-local-fallback ~/signaling-server"
scp -P 2222 -r infrastructure/local-fallback/* alvaro@127.0.0.1:~/conectate-local-fallback/
scp -P 2222 -r signaling-server/* alvaro@127.0.0.1:~/signaling-server/
```

Dentro de Ubuntu:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
PUBLIC_BASE_URL="https://tu-url-ngrok-o-dominio" bash scripts/repair-ubuntu-primary.sh
API_URL="http://127.0.0.1:3080" bash scripts/verify-ubuntu-modules.sh
PUBLIC_BASE_URL="https://tu-url-ngrok-o-dominio" bash scripts/verify-ubuntu-storage.sh
API_URL="http://127.0.0.1:3080" STRICT_PRODUCTION=true bash scripts/verify-ubuntu-production.sh
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

Resultado esperado:

- `verify-ubuntu-modules.sh` termina con `LISTO: smoke test Ubuntu completo.`
- `verify-ubuntu-production.sh` genera `production-readiness-report.md` y debe
  terminar sin `FAIL`.
- `verify-ubuntu-storage.sh` confirma que no quedan URLs de Supabase Storage en
  las columnas revisadas.
- `docker compose ps` muestra servicios estables.

## Modulos cubiertos por Ubuntu en Android

| Modulo | Endpoints Ubuntu | Consumidor Android | Postgres Ubuntu | Storage local |
| --- | --- | --- | --- | --- |
| Registro/login/sesion | `/v1/auth/register`, `/v1/auth/login`, `/v1/auth/me` | `AuthService`, `AuthRepository` | `fallback_users`, perfil derivado | N/A |
| Login web por QR y cierre remoto | `/v1/auth/web-login-pairing`, `/v1/auth/web-sessions` | `WebLoginScannerViewModel`, `WebLoginPairingRepository`, `SettingsViewModel` | `fallback_web_login_pairings`, `fallback_web_sessions` | N/A |
| Cambio/reset password | `/v1/auth/change-password`, `/v1/auth/password-reset/request`, `/v1/auth/password-reset/confirm`, `/v1/auth/complete-migrated-password-reset` | `AuthService`, `LocalFallbackApiClient` | `fallback_users`, tokens reset | N/A |
| Perfil ver/editar/foto | `/v1/data/profile/me`, `/v1/data/profiles/search`, `/v1/data/profiles/:id` | `UserRepository`, `ProfileViewModel` | `fallback_profile_overrides`, `fallback_users`, import legacy | `avatars` |
| Storage | `/v1/storage/upload`, `/v1/storage/objects`, `/storage/v1/object/public/...`, API compatible `/storage/v1/object/...` | `StorageService`, `UbuntuStorageUrl`, `UbuntuMedia` | `fallback_storage_buckets`, `fallback_storage_objects` | todos los buckets locales |
| Feed social | `/v1/data/feed`, `/v1/data/feed/posts`, detalle, editar, borrar, comentar, reaccionar, compartir | `PostRepository`, `SocialScreen` | `fallback_posts`, `fallback_post_comments`, `fallback_post_reactions`, `fallback_shares` | `post-media`, `post-comments-media`, `chat-media` |
| Momentos | `/v1/data/moments`, `/v1/data/moments/feed`, `/v1/data/moments/:id/view` | `MomentRepository`, `MomentViewerScreen` | `fallback_moments`, `fallback_moment_views` | `moment-media` |
| Videos | `/v1/data/videos`, detalle, subir, buscar, vista, reaccion, comentarios | `VideoRepository`, `VideosViewModel`, `VideoFeedViewModel` | `fallback_videos`, `fallback_video_comments`, `fallback_video_likes` | `videos`, `thumbnails`, `video-comments-media` |
| Retos | `/v1/data/challenges/active`, master, submissions, participation, vote | `ChallengeRepository`, `ChallengeViewModel` | `fallback_challenges_master`, `fallback_active_challenges`, `fallback_challenge_submissions`, `fallback_challenge_votes` | `challenges` |
| Rankings | fuentes `/v1/data/profiles/search`, `/v1/data/videos`, `/v1/data/feed` | `RankingRepository` | usuarios, videos, posts locales/importados | N/A |
| Mensajes DM basicos | `/v1/data/messages/chats`, `/v1/data/messages/chats/:id`, `/messages` | `MessageRepository`, `ChatViewModel`, `MessageRealtimeSync` | `fallback_chats`, `fallback_chat_participants`, `fallback_messages`, `fallback_notifications` | `chat-media` |
| Grupos/comunidades basicos | `/v1/data/messages/groups`, `/communities`, join/leave/messages | `GroupRepository`, `CommunityRepository` | `fallback_collection_rooms`, `fallback_collection_members`, `fallback_room_messages` | `group-media`, `community-media`, `chat-media` |
| Notificaciones in-app | `/v1/data/notifications`, read, read-all | `NotificationRepository`, `UbuntuRealtimeClient` | `fallback_notifications` | N/A |
| Tokens FCM | `/v1/data/notifications/fcm-tokens` | `NotificationRepository.registerFcmToken/unregisterFcmToken` | `fallback_fcm_tokens` | N/A |
| Salas de voz | `/v1/data/voice/rooms`, detalle, join, leave; `/socket.io/` por edge | `VoiceRoomRepository`, `SignalingConfig` | `fallback_voice_rooms`, `fallback_voice_participants` | N/A |
| Realtime Ubuntu | `/realtime/health`, WebSocket `/realtime/` | `UbuntuRealtimeClient`, `MessageRealtimeSync`, `MomentRepository` | eventos publicados desde API | N/A |

## Parcialmente migrado

- Mensajeria avanzada: carpetas, preferencias remotas, encuestas, reacciones de
  mensajes, view-once, traduccion, recibos avanzados y programacion siguen con
  repositorios/RPC Supabase en varias clases.
- Llamadas 1:1 y algunas señales de llamada siguen usando `CallRepository` con
  canales Supabase Realtime; salas de voz basicas usan Ubuntu/signaling.
- Amigos/friend requests tienen endpoints Ubuntu, pero `FriendRepository` aun
  conserva llamadas directas Supabase.
- Stickers, badges, creator program, wallet/monetizacion y partes admin siguen
  usando tablas/RPC Supabase.
- Web aun contiene multiples pantallas con `supabase.from`, `supabase.rpc`,
  `supabase.storage` y canales Realtime legacy, aunque el build ya exige
  `VITE_FALLBACK_API_URL`.
- Las sesiones web nuevas ya son revocables desde Android. Las sesiones web
  antiguas creadas antes de esta migracion no llevan `sid` en el JWT y deben
  cerrarse manualmente en la web o expirar por rotacion de token/secreto.

## Documentacion oficial

- Runbook de produccion: `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md`.
- Handoff para equipo nuevo: `docs/TEAM_HANDOFF_UBUNTU_2026.md`.
- Migracion de multimedia: `docs/SUPABASE_STORAGE_TO_UBUNTU.md`.
- Migracion a mini PC: `docs/MINI_PC_MIGRATION_UBUNTU_2026.md`.

## Dependencias Supabase que deben quedarse solo como migracion

- `LEGACY_SUPABASE_IMPORT_ENABLED=true` solo durante importacion puntual.
- `SUPABASE_SECRET_KEY` o `SUPABASE_SERVICE_ROLE_KEY` solo en `.env` del
  servidor Ubuntu mientras se importan usuarios/storage.
- Scripts permitidos temporalmente: importacion CSV, importacion storage y
  reescritura de URLs antiguas.

## Limpieza controlada antes de quitar SDKs

1. Ejecutar `verify-ubuntu-production.sh` sin `FAIL` contra VM y luego mini PC.
2. Probar con cuentas reales: registro, login, reset, editar perfil, foto,
   feed, comentarios, audio, videos, momentos, retos, rankings, mensajes,
   notificaciones y salas de voz.
3. Migrar o desactivar los modulos parciales listados arriba.
4. Solo entonces quitar SDKs Supabase de Android/web y borrar rutas legacy que
   ya no tengan cobertura funcional.

## Si algo falla en Ubuntu

```bash
cd ~/conectate-local-fallback
docker compose ps
docker compose logs --tail=200 api
docker compose logs --tail=120 edge
docker compose logs --tail=120 signaling realtime-gateway
docker compose exec -T db psql -U conectate -d conectate_fallback -c "\dt"
docker compose exec -T api node --check src/data-routes.js
```

Si el fallo es de secretos:

```bash
openssl rand -hex 32
nano ~/conectate-local-fallback/.env
docker compose up -d --build
```

Valores obligatorios: `POSTGRES_PASSWORD`, `JWT_SECRET`,
`ADMIN_SYNC_TOKEN`, `PUBLIC_BASE_URL`.
