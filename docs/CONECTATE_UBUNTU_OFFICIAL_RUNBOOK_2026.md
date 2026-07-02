# Conectate Ubuntu - Documentacion oficial de produccion

Fecha de creacion: 2026-06-18
Fecha de ultima revision: 2026-06-19
Estado: Ubuntu es backend primario operativo para los flujos migrados. Supabase queda permitido solo como fuente temporal de migracion/importacion.

## 0. Como usar esta documentacion

Este es el documento principal de operacion. Para onboarding de un equipo nuevo,
leer primero `docs/TEAM_HANDOFF_UBUNTU_2026.md` y volver aqui para ejecutar
despliegues, validar modulos y revisar contratos de produccion.

Convencion de fechas: todos los documentos oficiales llevan fecha de creacion o
fecha de ultima revision en formato `YYYY-MM-DD`.

Para exponer la VM Ubuntu o la futura mini PC gratis con dominio HTTPS estable,
usar `docs/HOME_SERVER_PUBLIC_ACCESS_UBUNTU_2026.md`. Esa guia cubre DuckDNS,
Caddy, puertos del router, CGNAT, Android `FALLBACK_API_URL` y migracion a mini PC.

Para backups verificados, timers, watchdog, restauracion aislada y endpoint dinamico,
usar `docs/UBUNTU_OPERATIONS_BACKUP_RUNBOOK_2026.md`.

## 1. Objetivo

Este documento define el contrato oficial para operar Conectate con Ubuntu como corazon del sistema:

- Android usa `FALLBACK_API_URL`.
- Web usa `VITE_FALLBACK_API_URL`.
- API, Postgres, storage, realtime, signaling y edge proxy corren en Ubuntu.
- Supabase no debe ser dependencia funcional de runtime en los modulos ya cubiertos.

## 2. Arquitectura de produccion

| Capa | Componente | Responsabilidad |
| --- | --- | --- |
| Android APK | Jetpack Compose + repositorios Kotlin | UI movil, cache local, subida de media, consumo de API Ubuntu |
| Web | Vite/React | Login web por QR, pantallas web migradas, consumo de API Ubuntu |
| Edge Ubuntu | nginx en `edge` | URL publica unica en `:3080`, proxy a API/storage/signaling/realtime |
| API Ubuntu | Node/Express en `api` | Auth, datos sociales, storage metadata, retos, voz, notificaciones |
| Postgres Ubuntu | `db` | Datos persistentes locales |
| Storage Ubuntu | volumen `conectate-fallback-storage` | Avatares, audios, videos, imagenes, momentos y adjuntos |
| Realtime Ubuntu | `realtime-gateway` | Eventos WebSocket para Android/web |
| Signaling | `signaling` | WebRTC, salas de voz, llamadas y sesiones de voz |

## 3. Variables obligatorias

Android `secrets.properties`:

```properties
FALLBACK_API_URL=https://TU_DOMINIO_O_NGROK
LOCAL_FALLBACK_API_URL=http://10.0.2.2:3080
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Web `.env` o Vercel:

```env
VITE_FALLBACK_API_URL=https://TU_DOMINIO_O_NGROK
VITE_SUPABASE_URL=
VITE_SUPABASE_ANON_KEY=
```

Ubuntu `.env`:

```env
POSTGRES_PASSWORD=CAMBIAR_EN_PRODUCCION
JWT_SECRET=MINIMO_32_CARACTERES
ADMIN_SYNC_TOKEN=MINIMO_32_CARACTERES
PUBLIC_BASE_URL=https://TU_DOMINIO_O_NGROK
LEGACY_SUPABASE_IMPORT_ENABLED=false
```

## 4. Seguridad: login web por QR y cierre remoto

### Flujo oficial

1. Web crea un pairing con `POST /v1/auth/web-login-pairing` accion `create`.
2. Android escanea el QR y consulta/aprueba/rechaza el pairing.
3. Web consume el pairing aprobado.
4. Ubuntu crea una fila en `fallback_web_sessions`.
5. El JWT web incluye `sid`.
6. Cada request autenticado valida que `sid` exista y que `revoked_at IS NULL`.
7. La APK puede cerrar una sesion web individual o todas las sesiones.
8. Si la web usa una sesion revocada, Ubuntu responde `WEB_SESSION_REVOKED` y la web limpia su sesion local.

### Endpoints oficiales

| Metodo | Ruta | Uso |
| --- | --- | --- |
| `POST` | `/v1/auth/web-login-pairing` | `create`, `status`, `approve`, `reject`, `consume` |
| `GET` | `/v1/auth/web-sessions` | Listar sesiones web activas del usuario movil |
| `DELETE` | `/v1/auth/web-sessions/:sessionId` | Cerrar una sesion web |
| `DELETE` | `/v1/auth/web-sessions` | Cerrar todas las sesiones web activas |

### Tablas

| Tabla | Uso |
| --- | --- |
| `fallback_web_login_pairings` | QR temporal, estado del pairing y aprobacion |
| `fallback_web_sessions` | Sesiones web activas/revocadas con `device_label`, `ip_address`, `user_agent`, `last_seen_at` |

## 5. Matriz funcional Ubuntu

| Modulo | Estado Ubuntu | Endpoints principales | Storage |
| --- | --- | --- | --- |
| Registro/login/sesion | Cubierto | `/v1/auth/register`, `/v1/auth/login`, `/v1/auth/me` | N/A |
| Password reset/cambio | Cubierto | `/v1/auth/change-password`, `/v1/auth/password-reset/*` | N/A |
| QR web + cierre remoto | Cubierto | `/v1/auth/web-login-pairing`, `/v1/auth/web-sessions` | N/A |
| Perfil/foto | Cubierto en flujos principales | `/v1/data/profile/me`, `/v1/data/profiles/:id` | `avatars` |
| Busqueda de personas | Cubierto | `/v1/data/profiles/search` | N/A |
| Storage local | Cubierto | `/storage/v1/object/...`, `/v1/storage/objects` | todos los buckets locales |
| Mensajes y audio | Cubierto en DM/grupos/comunidades basicos | `/v1/data/messages/*` | `chat-media`, `group-media`, `community-media` |
| Videos | Cubierto en feed/subida/vistas/likes/comentarios/share | `/v1/data/videos/*` | `videos`, `thumbnails`, `video-comments-media` |
| Social/posts | Cubierto | `/v1/data/feed/*` | `post-media`, `post-comments-media` |
| Momentos | Cubierto en crear/feed/view/delete | `/v1/data/moments/*` | `moment-media` |
| Retos | Cubierto en ciclo base | `/v1/data/challenges/*` | `challenges` |
| Rankings | Cubierto con datos locales | perfiles, videos, feed | N/A |
| Notificaciones in-app | Cubierto | `/v1/data/notifications/*` | N/A |
| FCM tokens | Cubierto | `/v1/data/notifications/fcm-tokens` | N/A |
| Salas de voz | Cubierto en crear/listar/join/leave/delete | `/v1/data/voice/rooms/*`, `/socket.io/` | N/A |
| Web runtime | Parcial migrado | `fallbackApiJson`, `ubuntuDataClient` | storage Ubuntu |

## 6. Modulos con compatibilidad legacy pendiente

No eliminar SDKs ni rutas legacy hasta cerrar estos puntos:

- Repositorios Android que aun conservan llamadas Supabase en rutas avanzadas.
- Pantallas web que todavia contienen `supabase.from`, `supabase.rpc`, `supabase.storage` o realtime legacy.
- Llamadas 1:1 y funciones avanzadas que dependan de canales legacy.
- Stickers, badges, creator program, wallet/monetizacion y admin avanzado si no tienen endpoint Ubuntu equivalente completo.

## 7. Despliegue a VM Ubuntu

Desde Windows CMD:

```bat
cd /d "D:\red social - copia"
scp -P 2222 "infrastructure\local-fallback\api\src\server.js" alvaro@127.0.0.1:/home/alvaro/conectate-local-fallback/api/src/server.js
scp -P 2222 "infrastructure\local-fallback\api\src\data-routes.js" alvaro@127.0.0.1:/home/alvaro/conectate-local-fallback/api/src/data-routes.js
scp -P 2222 "infrastructure\local-fallback\api\src\moments-routes.js" alvaro@127.0.0.1:/home/alvaro/conectate-local-fallback/api/src/moments-routes.js
scp -P 2222 "infrastructure\local-fallback\realtime-gateway\src\server.js" alvaro@127.0.0.1:/home/alvaro/conectate-local-fallback/realtime-gateway/src/server.js
```

Dentro de Ubuntu:

```bash
cd ~/conectate-local-fallback
docker compose run --rm api node --check src/server.js
docker compose run --rm api node --check src/data-routes.js
docker compose run --rm api node --check src/moments-routes.js
docker compose build api realtime-gateway
docker compose up -d api edge signaling realtime-gateway
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

## 8. Verificacion oficial

Local Windows:

```powershell
node --check infrastructure/local-fallback/api/src/server.js
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
cd Conectate-web
npm run build
```

Ubuntu:

```bash
cd ~/conectate-local-fallback
API_URL="http://127.0.0.1:3080" bash scripts/verify-ubuntu-modules.sh
API_URL="http://127.0.0.1:3080" STRICT_PRODUCTION=true bash scripts/verify-ubuntu-production.sh
PUBLIC_BASE_URL="https://TU_DOMINIO_O_NGROK" bash scripts/verify-ubuntu-storage.sh
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

Resultado esperado:

- APK compila sin errores.
- Web compila sin errores.
- API pasa `node --check`.
- `verify-ubuntu-modules.sh` termina con `LISTO`.
- `verify-ubuntu-production.sh` no muestra `FAIL`.
- `verify-ubuntu-storage.sh` confirma que los objetos se sirven desde Ubuntu.

## 9. Prueba manual de cierre remoto web

1. Abre Conectate Web.
2. Inicia sesion por QR desde la APK.
3. En la APK entra a `Perfil > Configuracion > Dispositivos vinculados`.
4. Verifica que aparezca la sesion web.
5. Pulsa cerrar sesion web.
6. En la web intenta navegar a una pantalla autenticada.

Resultado esperado:

- Ubuntu responde `WEB_SESSION_REVOKED`.
- La web borra la sesion local.
- El usuario vuelve al login.

## 10. Operacion diaria

Comandos base:

```bash
cd ~/conectate-local-fallback
docker compose ps
docker compose logs --tail=200 api
docker compose logs --tail=120 edge
docker compose exec -T db psql -U conectate -d conectate_fallback -c "\dt"
docker compose exec -T db psql -U conectate -d conectate_fallback -c "SELECT bucket_id, COUNT(*) FROM fallback_storage_objects GROUP BY bucket_id ORDER BY bucket_id;"
```

Backup recomendado:

```bash
cd ~/conectate-local-fallback
docker compose exec -T db pg_dump -U conectate conectate_fallback > conectate_fallback.sql
docker run --rm -v conectate-fallback-storage:/data/storage -v "$PWD":/backup alpine tar -czf /backup/conectate-storage.tar.gz -C /data/storage .
```

## 11. Criterios para eliminar Supabase legacy

Se puede limpiar Supabase solo cuando:

1. `verify-ubuntu-production.sh` pase sin `FAIL` en VM y mini PC.
2. APK y web funcionen con cuentas reales usando solo `FALLBACK_API_URL`.
3. Cada modulo legacy tenga endpoint Ubuntu equivalente documentado.
4. Los datos historicos necesarios esten importados a Postgres/storage Ubuntu.
5. El codigo web/Android ya no use SDK Supabase en rutas funcionales activas.

Hasta cumplir eso, Supabase permanece como compatibilidad/migracion, no como backend principal.
