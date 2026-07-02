# Android Ubuntu Production Gate

- Fecha de revision: 2026-06-24
- Alcance: aplicacion Android y backend Ubuntu
- Fuera de alcance de esta fase: frontend web
- Backend validado: `https://belief-shelter-him-expenses.trycloudflare.com`

## Estado verificado

| Modulo | Estado | Ubuntu | Persistencia | Evidencia |
| --- | --- | --- | --- | --- |
| Registro, login, sesiones y cambio de clave | OK | `/v1/auth/*` | `fallback_users` | Prueba HTTP completa |
| Perfil, avatar y busqueda de personas | OK | `/v1/data/profile/*`, `/v1/data/profiles/search` | Postgres + `avatars` | Prueba HTTP completa |
| Mensajes directos y audio | OK | `/v1/data/messages/*`, `/v1/storage/upload` | Postgres + `chat-media` | Prueba HTTP completa |
| Grupos y comunidades | OK | `/v1/data/messages/groups|communities` | Postgres + storage local | Prueba HTTP completa |
| Videos, busqueda, vistas, likes y comentarios | OK backend | `/v1/data/videos/*` | Postgres + `videos` | Prueba HTTP completa; falta prueba instrumental Android |
| Social, posts, comentarios y reacciones | OK backend | `/v1/data/feed/*` | Postgres + `post-media` | Prueba HTTP completa; falta prueba instrumental Android |
| Momentos | OK backend | `/v1/data/moments/*` | Postgres + `moment-media` | Prueba HTTP completa; falta prueba audiovisual Android |
| Retos, participaciones y votos | OK backend | `/v1/data/challenges/*` | Postgres + `challenges` | Prueba HTTP completa |
| Rankings | OK | perfiles, videos y feed Ubuntu | Postgres | Prueba HTTP completa |
| Salas de voz | Parcial | room CRUD, realtime y signaling Ubuntu | Postgres | Flujo join/leave OK; TURN pendiente |
| Notificaciones in-app | OK | `/v1/data/notifications/*` | `fallback_notifications` | Persistencia y lectura verificadas |
| Push con app cerrada | OK servidor | Firebase Admin en Ubuntu | `fallback_fcm_tokens` | Prueba FCM real: 1 enviado, 0 fallos |
| Stickers avanzados | Parcial | Compatibilidad `/rest/v1` | `fallback_compat_rows` + storage | Busqueda remota devuelve lista vacia |
| Badges | Parcial | Compatibilidad `/rest/v1/rpc` | Compatibilidad generica | RPC dedicados y respuesta tipada pendientes |
| Encuestas y receipts avanzados | Parcial | Compatibilidad `/rest/v1/rpc` | Compatibilidad generica | Estado/voto/receipts aun contienen respuestas de compatibilidad |
| Programa de creadores | Parcial | Compatibilidad `/rest/v1` | `fallback_compat_rows` | Falta API dedicada y prueba funcional completa |

## Supabase Android

Las claves `SUPABASE_URL` y `SUPABASE_ANON_KEY` se compilan vacias. Ubuntu es la fuente de verdad y el destino de red. Los nombres `SupabaseConfig`, `supabaseUrl` y `/rest/v1` que permanecen son compatibilidad de codigo, no una conexion al servicio Supabase.

El cliente compatible ahora:

1. considera Ubuntu como backend configurado;
2. apunta las llamadas PostgREST legacy a Ubuntu;
3. se recrea cuando cambia el endpoint descubierto en runtime;
4. mantiene Auth y Realtime de Supabase fuera del flujo Ubuntu.

No se debe eliminar todavia el SDK hasta migrar los modulos parciales de la tabla.

## Bloqueos para Play Store

1. **URL publica estable:** el Quick Tunnel funciona, pero cambia al reiniciar. Falta guardar el token DuckDNS y publicar la URL nueva por TXT para que la APK la descubra sin recompilar.
2. **Firma release:** no existe `keystore.properties` ni una clave release en el workspace. Se necesita la clave de carga usada en Play Console o completar el cambio de clave de carga.
3. **AAB release:** debug compila y las pruebas pasan. El bundle release debe repetirse despues de configurar firma y conservar `mapping.txt`.
4. **TURN:** `/turn-credentials` responde 503 en la VM actual. Coturn gratuito ya está preparado para la mini PC; falta abrir TCP/UDP 3478 y UDP 49160-49200 en el router y ejecutar `configure-mini-pc-turn.sh`.
5. **Versionado:** confirmar en Play Console que `versionCode = 27` sea mayor que el ultimo publicado; incrementarlo si no lo es.

## Evidencia de esta revision

- Docker: cinco servicios `healthy`.
- Compuerta estricta backend: 22 PASS, 1 FAIL (solo TURN).
- Advertencia real pendiente: TURN y apertura de puertos en el router.
- Storage: 13 buckets, 628 objetos servidos desde Ubuntu, sin URLs antiguas de Supabase.
- Android: 70 tests, 0 fallos; `assembleDebug` correcto para cuatro ABI.
- Operaciones Ubuntu: health watchdog, backup diario y restore drill instalados.

## Comandos de revalidacion

```powershell
Set-Location "D:\red social - copia"
powershell -ExecutionPolicy Bypass -File ".\scripts\start-cloudflare-quick-tunnel.ps1" -HostName 127.0.0.1 -Port 2222
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

```bash
cd ~/conectate-local-fallback
PUBLIC_BASE_URL="https://URL-ACTUAL.trycloudflare.com" bash scripts/verify-ubuntu-production.sh
PUBLIC_BASE_URL="https://URL-ACTUAL.trycloudflare.com" bash scripts/verify-ubuntu-storage.sh
docker compose ps
```
