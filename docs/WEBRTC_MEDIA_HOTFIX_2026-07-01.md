# Hotfix WebRTC llamadas y salas de voz — 2026-07-01

## Objetivo

Evitar cierres de la APK al entrar a salas de voz, llamar o contestar; habilitar negociación WebRTC estable para micrófono, cámara, pantalla y reacciones en tiempo real usando la mini PC Ubuntu como servidor principal.

## Tecnología elegida

- Señalización: Socket.IO sobre HTTPS/WebSocket detrás de Cloudflare Quick Tunnel.
- Media WebRTC: P2P con STUN/TURN.
- TURN externo gratuito temporal: OpenRelay/Metered (`openrelayproject`).
- Módulos separados:
  - salas de voz: namespace Socket.IO `/`
  - llamadas privadas: namespace `/call`
  - lives: namespace `/live`

Cloudflare Tunnel solo transporta HTTP/WebSocket; no es un relay de audio/video WebRTC. Por eso se añadió TURN externo. Ngrok tampoco resuelve por sí solo el media relay WebRTC para todos los NAT; ayuda al HTTP/WebSocket, pero para cámara/mic/pantalla se necesita STUN/TURN o SFU.

## Cambios aplicados

- Backend signaling reconstruido y desplegado en mini PC.
- `/health` del signaling ahora reporta:
  - `turnConfigured=true`
  - `mode=static-turn`
  - namespaces separados `/`, `/call`, `/live`
- DuckDNS TXT corregido:
  - `conectate-alvaro.duckdns.org` apunta a la URL pública actual de Cloudflare.
- APK:
  - `versionCode=38`
  - `versionName=2.2.8`
  - `secrets.properties` corrupto de 1.1 GB fue respaldado y reemplazado por configuración mínima limpia.
  - Se reducen crashes por cámara/pantalla nulas y foreground service de llamadas.
  - Reacciones usan identificador estable y expiración local.

## Verificación realizada

- `node --check signaling-server/index.js`
- `docker compose config --quiet`
- `docker compose build signaling`
- `docker compose up -d --force-recreate signaling edge`
- `curl http://127.0.0.1:3080/ready`
- `curl URL_PUBLICA/ready`
- `curl https://dns.google/resolve?name=conectate-alvaro.duckdns.org&type=TXT`
- `gradlew :app:compileReleaseKotlin :app:bundleRelease`

## Pendiente obligatorio antes de marcar “100%”

No se pudo ejecutar prueba Android real desde esta máquina porque `adb` no está instalado/disponible. Antes de considerar el módulo 100% validado, probar con dos teléfonos reales:

1. Entrar a una sala desde ambos teléfonos.
2. Hablar y escuchar ida/vuelta.
3. Activar/desactivar micrófono.
4. Activar/desactivar cámara.
5. Compartir pantalla y verla en el otro teléfono.
6. Enviar reacción y verla animada en el otro teléfono.
7. Salir y volver a entrar.
8. Llamada privada: llamar, contestar, colgar.

## Ruta del AAB generado

`app/build/outputs/bundle/release/app-release.aab`

## Fuentes técnicas consultadas

- WebRTC TURN/STUN: https://webrtc.org/getting-started/turn-server
- Cloudflare Tunnel: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/
- LiveKit self-hosting / puertos: https://docs.livekit.io/home/self-hosting/ports-firewall/
- Jitsi self-hosting: https://jitsi.github.io/handbook/docs/devops-guide/devops-guide-docker/
- OpenRelay: https://www.metered.ca/tools/openrelay/
