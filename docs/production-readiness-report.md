# Conectate Ubuntu Production Readiness

> Aviso: este archivo es una captura histórica del 20 de junio. La compuerta
> ejecutada el 24 de junio obtuvo 22 PASS, 0 WARN y 1 FAIL: únicamente TURN.
> Firebase Admin ya está configurado y una prueba FCM real entregó 1/1.

- Fecha: 2026-06-20T18:46:40+00:00
- Carpeta: /home/alvaro/conectate-local-fallback
- API_URL: http://127.0.0.1:3080
- PUBLIC_BASE_URL: https://belief-shelter-him-expenses.trycloudflare.com
- RUN_ID: 20260620184640

## Entorno
- [PASS] docker disponible: Docker version 29.5.2, build 79eb04c
- [PASS] docker compose disponible: 5.1.4
- [PASS] node disponible: v18.19.1
- [PASS] .env existe
- [PASS] POSTGRES_PASSWORD definido
- [PASS] JWT_SECRET definido
- [PASS] ADMIN_SYNC_TOKEN definido
- [PASS] PUBLIC_BASE_URL definido
- [PASS] LEGACY_SUPABASE_IMPORT_ENABLED no esta activo
- [WARN] FCM remoto: los tokens y notificaciones se persisten, pero el servidor de envio push aun no esta configurado

## Docker, puertos y logs

### docker compose ps
```text
NAME                                      IMAGE                                       COMMAND                  SERVICE            CREATED          STATUS                    PORTS
conectate-local-fallback-api-main         conectate-local-fallback-api                "docker-entrypoint.s…"   api                18 minutes ago   Up 18 minutes (healthy)   127.0.0.1:3001->3001/tcp
conectate-local-fallback-db-main          postgres:16-alpine                          "docker-entrypoint.s…"   db                 27 minutes ago   Up 27 minutes (healthy)   127.0.0.1:5433->5432/tcp
conectate-local-fallback-edge-main        nginx:1.27-alpine                           "/docker-entrypoint.…"   edge               18 minutes ago   Up 18 minutes (healthy)   80/tcp, 127.0.0.1:3080->8080/tcp
conectate-local-fallback-realtime-main    conectate-local-fallback-realtime-gateway   "docker-entrypoint.s…"   realtime-gateway   27 minutes ago   Up 27 minutes (healthy)   127.0.0.1:3002->3002/tcp
conectate-local-fallback-signaling-main   conectate-local-fallback-signaling          "docker-entrypoint.s…"   signaling          27 minutes ago   Up 27 minutes (healthy)   3003/tcp
```
- [PASS] docker compose ps

### puertos TCP locales
```text
LISTEN 0      4096       127.0.0.1:3001       0.0.0.0:*                                          
LISTEN 0      4096       127.0.0.1:3002       0.0.0.0:*                                          
LISTEN 0      4096       127.0.0.1:3080       0.0.0.0:*                                          
LISTEN 0      4096       127.0.0.1:5433       0.0.0.0:*                                          
```
- [PASS] puertos TCP locales

### logs api edge signaling realtime-gateway
```text
conectate-local-fallback-realtime-main   | [realtime] conectate-local-fallback-realtime-gateway 0.1.0 listening on 3002
conectate-local-fallback-signaling-main  | Signaling server running on port 3003
conectate-local-fallback-api-main        | conectate-local-fallback 0.1.0 listening on 3001
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/90eb7516-5754-40fc-a604-5340e5ed1d58/9623917f-604d-4bd6-8567-5c922671df22.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/ec8e9151-33ee-49b4-9e9d-21188c06e13e/a20e1727-f55b-484f-a228-0210ba1a4a49.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/e584054d-b1ec-4a19-ada4-afaea9149e5b.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/e59127d1-eae0-4b01-a6eb-1bd7405c90b9.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/13a1cac0-8938-4687-a62b-32d922e62acf.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/5100644b-d55e-42b5-88e5-3672a0bd4796.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/78eccac8-0838-418f-a2bd-326cb187b901/c3d6a971-935c-45b4-856a-6392ba5034fd.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/a934295e-ac79-4db0-9d8c-4b4becd298ac.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/cd3beaea-2e7e-4414-a266-f54808421e1d/27f99e2a-76ee-4ade-ae02-1b35796dd737.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/2d6cf014-f7f7-41c9-9f5e-c8759c353804.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/48b894d8-d5de-4446-8ebd-925245694f5e.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/650d305f-ac9d-4c91-9b7b-bb896cd1a877.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/90d6336a-9ab2-425b-97f9-d6eab79ccbfc.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/9219e462-2400-4b4c-9a1c-3681e5c49b43.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/a232c597-304e-40c4-8947-f0045c353e7f.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/0daa4433-cf98-4167-9ab3-235f5db9f454.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/9d1a06de-7811-452a-898a-13e94aba1b0e/6e19c218-5468-4db3-9f2b-a2cecbcf57a9.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/740fb63e-a3a1-4955-947f-ccd5eb1693f1/e060cff3-9949-4dec-9791-3605be9f6077.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/1e8c5445-84c8-4804-a9af-61b633411c5a.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/5e76526e-7685-4c84-a3c6-06569cc9d2a1.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/b9138693-c6cb-4897-b437-67a4cd7e20ab.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/da05205e-a90e-457d-9de4-f03d18cc7eaf.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/e7aa1f5b-16a3-4a70-8725-51df738d6708.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/3409021c-afb3-498f-9b48-fd743629f309/62bcf257-7db3-4624-a45a-fb2669b7cf35.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/74d923b6-fe46-4ad4-bd2e-39c6bf74b1b3/e2671d92-be77-4977-bac7-7eba62269a7c.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/fb2d97bb-9d2b-46fd-b8a6-a40ec41814f9/5887f4c5-55a1-40b8-8a83-1bcbcbfd281a.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/3409021c-afb3-498f-9b48-fd743629f309/99a1d46a-ebdc-47e2-9351-afad0c03a74b.mp4 HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/production/20260620102009/video.txt HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/production/20260620102404/video.txt HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 172.18.0.7 - - [20/Jun/2026:18:28:30 +0000] "HEAD /storage/v1/object/public/videos/production/20260620182701/video.txt HTTP/1.1" 200 0 "-" "node" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:28:40 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:28:55 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:28:59 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Mozilla/5.0 (Windows NT 10.0; Microsoft Windows 10.0.26200; es-419) PowerShell/7.5.5" "2803:a3e0:1350:7cb0:e133:1572:b0a1:6220"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:29:11 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:29:26 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:29:41 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:29:47 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:29:47 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.19.0" "2803:a3e0:1350:7cb0:e133:1572:b0a1:6220"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:29:50 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:29:57 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:30:12 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:30:27 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:30:42 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:30:58 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:31:13 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:31:28 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:31:43 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:31:59 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:32:01 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:32:05 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:32:14 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:32:29 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:32:45 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:33:00 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:33:15 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:33:30 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:33:46 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:34:01 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:34:06 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:34:12 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:34:16 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:34:31 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:34:47 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:35:02 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:35:18 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:35:33 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:35:48 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:36:04 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:36:16 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:36:19 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:36:21 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:36:34 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:36:50 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:37:05 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:37:20 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:37:35 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:37:51 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:38:06 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:38:21 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:38:26 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:38:31 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:38:36 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:38:52 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:39:08 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:39:23 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:39:38 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:39:54 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:40:09 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:40:25 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:40:26 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:40:32 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:40:40 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:40:55 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:41:12 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:41:25 +0000] "GET /turn-credentials HTTP/1.1" 503 119 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:41:27 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:41:42 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:41:58 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:42:13 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:42:28 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:42:36 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:42:42 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:42:44 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:43:00 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:43:15 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:43:30 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:43:45 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:44:01 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:44:16 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:44:31 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:44:47 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:44:57 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 172.18.0.1 - - [20/Jun/2026:18:45:02 +0000] "GET /ready HTTP/1.1" 200 108 "-" "curl/8.5.0" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:45:02 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:45:18 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:45:33 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:45:48 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:46:03 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:46:19 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
conectate-local-fallback-edge-main       | 127.0.0.1 - - [20/Jun/2026:18:46:34 +0000] "GET /ready HTTP/1.1" 200 108 "-" "Wget" "-"
```
- [PASS] logs api edge signaling realtime-gateway

## Postgres y storage local

### Postgres ready
```text
/var/run/postgresql:5432 - accepting connections
```
- [PASS] Postgres ready

### tablas principales
```text
                       List of relations
 Schema |               Name               | Type  |   Owner   
--------+----------------------------------+-------+-----------
 public | fallback_active_challenges       | table | conectate
 public | fallback_audit_events            | table | conectate
 public | fallback_challenge_submissions   | table | conectate
 public | fallback_challenge_votes         | table | conectate
 public | fallback_challenges_master       | table | conectate
 public | fallback_chat_participants       | table | conectate
 public | fallback_chats                   | table | conectate
 public | fallback_collection_members      | table | conectate
 public | fallback_collection_rooms        | table | conectate
 public | fallback_compat_rows             | table | conectate
 public | fallback_fcm_tokens              | table | conectate
 public | fallback_follows                 | table | conectate
 public | fallback_friends                 | table | conectate
 public | fallback_messages                | table | conectate
 public | fallback_moderation_events       | table | conectate
 public | fallback_moment_deletions        | table | conectate
 public | fallback_moment_views            | table | conectate
 public | fallback_moments                 | table | conectate
 public | fallback_nicknames               | table | conectate
 public | fallback_notifications           | table | conectate
 public | fallback_password_reset_tokens   | table | conectate
 public | fallback_post_comments           | table | conectate
 public | fallback_post_reactions          | table | conectate
 public | fallback_posts                   | table | conectate
 public | fallback_profile_moderation      | table | conectate
 public | fallback_profile_overrides       | table | conectate
 public | fallback_reports                 | table | conectate
 public | fallback_room_messages           | table | conectate
 public | fallback_shares                  | table | conectate
 public | fallback_storage_buckets         | table | conectate
 public | fallback_storage_objects         | table | conectate
 public | fallback_users                   | table | conectate
 public | fallback_video_comments          | table | conectate
 public | fallback_video_deletions         | table | conectate
 public | fallback_video_likes             | table | conectate
 public | fallback_video_metrics           | table | conectate
 public | fallback_video_reports           | table | conectate
 public | fallback_videos                  | table | conectate
 public | fallback_voice_room_participants | table | conectate
 public | fallback_voice_rooms             | table | conectate
 public | fallback_web_login_pairings      | table | conectate
 public | fallback_web_sessions            | table | conectate
 public | sync_jobs                        | table | conectate
(43 rows)

```
- [PASS] tablas principales

### conteos de tablas Ubuntu
```text
           table_name           | count 
--------------------------------+-------
 fallback_users                 |    63
 fallback_storage_objects       |   628
 fallback_posts                 |     4
 fallback_post_comments         |     8
 fallback_moments               |     4
 fallback_messages              |    13
 fallback_chats                 |     6
 fallback_collection_rooms      |     7
 fallback_room_messages         |     7
 fallback_voice_rooms           |     4
 fallback_videos                |     3
 fallback_video_comments        |     2
 fallback_challenges_master     |    16
 fallback_active_challenges     |     1
 fallback_challenge_submissions |     2
 fallback_challenge_votes       |     2
 fallback_notifications         |    56
 fallback_fcm_tokens            |     1
(18 rows)

```
- [PASS] conteos de tablas Ubuntu

### permiso de escritura storage /data/storage
```text
```
- [PASS] permiso de escritura storage /data/storage

## Proxy y health endpoints
- [PASS] /health responde HTTP 200
- [PASS] /ready responde HTTP 200
- [PASS] /realtime/health responde HTTP 200
- [WARN] /turn-credentials responde HTTP 503; llamadas entre NAT restrictivos no estan garantizadas

## Validacion funcional por modulo
- [PASS] validacion funcional por modulo completa

### Matriz endpoint Android Postgres Storage
| Modulo | OK | Endpoint | Android | Postgres | Storage | Detalle |
| --- | --- | --- | --- | --- | --- | --- |
| health | OK | `GET /health` | N/A | N/A | N/A | API viva |
| ready | OK | `GET /ready` | N/A | Postgres | N/A | API + Postgres listos |
| auth registro/login/sesion | OK | `POST /v1/auth/register` | AuthService.register/login/currentSession | fallback_users + fallback_profiles | N/A | [object Object] |
| auth segundo usuario | OK | `POST /v1/auth/register` | AuthService.register | fallback_users + fallback_profiles | N/A | [object Object] |
| auth cambio password | OK | `POST /v1/auth/change-password` | AuthService.changePassword | fallback_users.password_hash | N/A | password cambiado y login nuevo OK |
| perfil ver/editar/buscar | OK | `GET/PATCH /v1/data/profile/me + GET /v1/data/profiles/search` | UserRepository + ProfileViewModel | fallback_profile_overrides/fallback_users | avatars | avatar=avatars/production/20260620184640/avatar.txt; search=8 |
| storage audio | OK | `POST /v1/storage/upload + GET public object` | StorageService + audio players | fallback_storage_objects | chat-media | chat-media/production/20260620184640/audio.txt |
| storage video | OK | `POST /v1/storage/upload + GET public object` | StorageService + VideoRepository | fallback_storage_objects | videos | videos/production/20260620184640/video.txt |
| storage imagen | OK | `POST /v1/storage/upload + GET public object` | StorageService + Post/Moment/Profile | fallback_storage_objects | post-media/moment-media | post-media/production/20260620184640/image.txt |
| publicaciones/comentarios/social | OK | `POST /v1/data/feed/posts + comments/reaction/share/detail` | PostRepository + SocialScreen | fallback_posts + fallback_post_comments + fallback_post_reactions + fallback_post_shares | post-media + chat-media | [object Object] |
| notificaciones | OK | `POST/GET/PATCH /v1/data/notifications` | NotificationRepository + UbuntuRealtimeClient | fallback_notifications | N/A | items=1, unread=1, fcm=ok |
| mensajes directos con audio | OK | `POST /v1/data/messages/chats + /messages` | MessageRepository + ChatViewModel | fallback_chats + fallback_chat_participants + fallback_messages + fallback_notifications | chat-media | chat=3f2ae166-dc93-48f5-b134-e5e341bddb50; messages=1 |
| grupos y comunidades | OK | `POST /v1/data/messages/groups\|communities + messages` | GroupRepository + CommunityRepository | fallback_collection_rooms + fallback_collection_members + fallback_room_messages | group-media/community-media/chat-media | group=88cf6559-db42-472d-8fa2-792582f767cf; community=85b103cc-380c-4354-9a32-6f7212d237ce |
| videos busqueda/reproduccion/comentarios | OK | `POST/GET /v1/data/videos + view/comment/reaction` | VideoRepository + VideoFeedViewModel | fallback_videos + fallback_video_comments + fallback_video_likes | videos + video-comments-media | video=6ebb3141-d8ca-4e99-8a30-2d100bf776b8; search=3 |
| momentos | OK | `POST/GET /v1/data/moments + view` | MomentRepository + MomentViewerScreen | fallback_moments + fallback_moment_views | moment-media/post-media | moment=b9ce3693-9c13-4791-856a-f4b454252402; feed=3 |
| retos y votos | OK | `GET/POST /v1/data/challenges/*` | ChallengeRepository + ChallengeViewModel | fallback_challenges_master + fallback_active_challenges + fallback_challenge_submissions + fallback_challenge_votes | challenges | active=a54376e4-9d40-46de-8c68-ac6800b8260d; votes=1 |
| rankings | OK | `GET profiles/videos/feed` | RankingRepository | fallback_users + fallback_videos + fallback_posts | N/A | users=65; videos=14; posts=38 |
| salas de voz | OK | `POST/GET /v1/data/voice/rooms + join/leave` | VoiceRoomRepository + signaling Socket.IO | fallback_voice_rooms + fallback_voice_participants | N/A | room=830ada59-9ee3-4fb7-a1e9-91a2d7c2c69b; participants=1 |
| storage listado y borrado | OK | `GET/DELETE /v1/storage/objects` | StorageService.delete/list | fallback_storage_objects | chat-media | objects=2; deleted=true |

## Referencias Supabase activas esperadas
- Supabase debe quedar desactivado funcionalmente con FALLBACK_API_URL/Ubuntu como primario.
- LEGACY_SUPABASE_IMPORT_ENABLED solo debe activarse temporalmente para importar usuarios/storage antiguos.
- FCM push remoto todavia requiere proveedor externo si quieres push fuera del proceso local; las notificaciones in-app ya se persisten en fallback_notifications.

## Resumen
- PASS: 20
- WARN: 2
- FAIL: 0
