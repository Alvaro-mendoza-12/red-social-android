# WebRTC, CGNAT y opción gratuita viable para llamadas/salas

Actualizado: 2026-07-01.

## Resumen ejecutivo

Para la mini PC detrás de CGNAT, la opción gratuita más viable hoy es:

1. Mantener la mini PC + Cloudflare Tunnel solo para API, signaling HTTP/WebSocket, descubrimiento y administración.
2. No depender de coturn local en la mini PC para usuarios externos: con CGNAT no hay puertos UDP/TCP relay públicamente alcanzables.
3. Para llamadas/salas actuales basadas en WebRTC P2P, usar un TURN público gestionado con cuota gratuita/limitada como fallback de ICE. La opción gratuita más útil para pruebas es Open Relay de Metered, porque declara 20 GB/mes de TURN, STUN, TCP/UDP, TURNS y puertos 80/443.
4. Si el producto necesita reuniones reales con audio, cámara y screen share multiparte, migrar la capa media a un SFU gestionado con free tier. La opción gratuita más fuerte por cuota publicada es Cloudflare Realtime SFU/TURN: 1,000 GB/mes gratis compartidos entre SFU y TURN, luego USD 0.05/GB de egreso. Requiere integración nueva de API/SDK; no es un simple reemplazo de coturn.

Recomendación honesta:

- Corto plazo sin presupuesto: WebRTC P2P + TURN externo gratuito/limitado. Es lo menos invasivo y aprovecha el código actual, pero no se debe prometer calidad tipo Zoom para grupos grandes.
- Mediano plazo con reuniones web/Android reales: Cloudflare Realtime SFU/TURN o LiveKit Cloud free tier, según cuál encaje mejor con el SDK y el control de UI. Para esta app, LiveKit suele ser más directo si se quiere SDK Android/Web maduro; Cloudflare Realtime parece más barato por GB gratis, pero exige más trabajo de integración si se usa el SFU bajo nivel.
- Producción seria: VPS público barato con coturn propio o SFU gestionado. “Gratis” sirve para beta, no para garantía de disponibilidad.

## Por qué Cloudflare Tunnel no basta

Cloudflare Tunnel es excelente para publicar el backend detrás de CGNAT sin abrir puertos: `cloudflared` crea conexiones salientes hacia Cloudflare y permite publicar hostnames públicos para HTTP/HTTPS, TCP, SSH/RDP y otros casos de uso. Fuente: Cloudflare Tunnel docs: https://developers.cloudflare.com/tunnel/

Pero WebRTC media necesita conectividad ICE: normalmente UDP, fallback TCP/TLS, y un relay TURN o SFU públicamente alcanzable. El túnel resuelve API/signaling, no convierte automáticamente la mini PC en un TURN/SFU público para navegadores y apps móviles anónimas.

Cloudflare One sí soporta escenarios privados con WARP/Zero Trust y tráfico TCP/UDP por túneles privados, pero eso exige que los clientes estén enrolados/instalados como parte de la red privada; no es aceptable para usuarios finales de una red social pública.

## Matriz de opciones

| Opción | Gratis | Sirve detrás de CGNAT | Audio/cámara/screen share | Encaje con app actual | Riesgo principal |
|---|---:|---:|---:|---:|---|
| WebRTC P2P + STUN público | Sí | Parcial | 1:1 y grupos pequeños, si ICE conecta | Alto | Falla con NAT simétrico, carrier mobile, firewalls corporativos |
| WebRTC P2P + TURN externo gratuito | Sí, limitado | Sí | 1:1 y salas pequeñas; relay cuando P2P falla | Alto | Cuota, SLA, abuso, latencia/costo al crecer |
| coturn en mini PC | Software gratis | No, si sigue CGNAT | No confiable fuera de LAN | Alto | Sin IP pública/puertos entrantes no hay relay público real |
| coturn en VPS público | Software gratis, VPS pago | Sí | Confiable para P2P y salas pequeñas | Alto | Costo de VPS y ancho de banda; operación propia |
| Cloudflare Tunnel | Sí | Sí para HTTP/signaling | No como solución media pública WebRTC | Alto para backend | No sustituye TURN/SFU para media |
| ngrok | Sí/dev, límites | Sí para HTTP/TCP | No ideal para media WebRTC; docs oficiales listan HTTP/S, TLS y TCP | Medio | No hay UDP TURN público simple; útil para webhooks/dev |
| Jitsi Meet / JaaS | meet.jit.si gratis; JaaS free limitado | Sí si se usa Jitsi alojado | Sí, con UI completa de reuniones | Medio/bajo si se acepta UI Jitsi | Menos control de UX; dependencia de plataforma/ToS; self-host requiere IP pública |
| LiveKit Cloud | Free tier, cuota | Sí | Sí; SDK Android/Web, cámara, micrófono y screen share | Medio | Cuotas/costos; cambio de arquitectura a SFU |
| Cloudflare Realtime SFU/TURN | 1,000 GB/mes free tier | Sí | Sí como SFU/TURN gestionado | Medio/bajo | Integración WebRTC/SFU más técnica; RealtimeKit tiene pricing por minuto separado |
| mediasoup self-host | Software gratis | No en mini PC con CGNAT; sí en VPS | Sí, SFU potente | Bajo | Mucha implementación propia; requiere servidor público |
| Janus self-host | Software gratis | No en mini PC con CGNAT; sí en VPS | Sí mediante plugins | Bajo | Curva DevOps/API; requiere servidor público |

## Evaluación por alternativa

### 1. WebRTC P2P + TURN/coturn

WebRTC requiere un servidor TURN para muchas redes porque una conexión directa entre peers no siempre es posible. Fuente oficial WebRTC: https://webrtc.org/getting-started/turn-server

coturn es una implementación libre y open source de STUN/TURN. Fuente oficial: https://github.com/coturn/coturn

Para esta app es el camino más compatible con lo que ya existe:

- El repo ya tiene endpoint `GET /turn-credentials`.
- Android y web ya consumen ICE servers.
- Se puede cambiar de coturn local a TURN gestionado sin rehacer todo el sistema de salas.

Limitación clave: coturn en la mini PC detrás de CGNAT no sirve como TURN público confiable. TURN debe estar en una IP pública alcanzable desde ambos clientes. Puede estar en VPS, proveedor TURN, o plataforma SFU/TURN.

### 2. OpenRelay / Metered / ExpressTURN / Xirsys

Open Relay de Metered declara 20 GB/mes gratis, STUN/TURN, TCP/UDP, TURNS y puertos 80/443. Fuente: https://www.metered.ca/tools/openrelay/

Metered TURN comercial declara free trial de 500 MB/mes y planes pagos. Fuente: https://www.metered.ca/stun-turn

ExpressTURN declara plan gratis con 1000 GB/mes en puerto 3478 TCP/UDP, y plan premium USD 9/mes con 5000 GB. Fuente: https://www.expressturn.com/

Xirsys ofrece STUN/TURN global, API, regiones y SLA para producción, pero hay que validar el free tier/costo vigente en su dashboard. Fuente: https://xirsys.com/

Uso recomendado en Conéctate:

- Beta: probar OpenRelay o ExpressTURN como `TURN_STATIC_URLS`, `TURN_STATIC_USERNAME`, `TURN_STATIC_CREDENTIAL`, o vía API dinámica si el proveedor lo exige.
- No meter secretos TURN permanentes en el APK. Mantenerlos en `signaling-server` y entregar credenciales temporales/autorizadas.
- Mantener STUN primero y TURN después para ahorrar relay.

### 3. Cloudflare Tunnel

Útil y recomendable para:

- backend Ubuntu;
- endpoints HTTP;
- Socket.IO/WebSocket signaling;
- health checks;
- descubrimiento de URL bajo CGNAT.

No recomendable como solución única para:

- TURN público;
- SFU self-hosted;
- RTP/WebRTC media pública;
- prometer cámara/pantalla global.

Fuente oficial: Cloudflare Tunnel establece conexiones salientes y publica aplicaciones sin IP pública ni puertos entrantes: https://developers.cloudflare.com/tunnel/

### 4. ngrok

ngrok es muy útil para exponer localhost sin abrir puertos, pruebas externas y webhooks. Su documentación de túneles soportados lista HTTP/S, TLS y TCP. Fuente: https://ngrok.com/docs/guides/share-localhost/tunnels

Para WebRTC media no es la mejor solución gratuita porque el camino natural de media es UDP/TURN/SFU. ngrok puede servir para API/signaling temporal, igual que Cloudflare Tunnel, pero no reemplaza un TURN/SFU público.

### 5. Jitsi

Jitsi Meet es open source y ofrece reuniones completas con audio, video, chat y compartir escritorio. Fuente: https://jitsi.org/jitsi-meet/

Tiene SDK Android embebible y requiere Android 7.0+ según su handbook. Fuente: https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-android-sdk/

También tiene IFrame API para web. Fuente: https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-iframe/

JaaS declara un developer/free package con hasta 25 endpoints y minutos ilimitados. Fuente: https://jitsi.org/jaas/

Cuándo elegirlo:

- si se acepta una experiencia de reunión casi “lista” y menos integrada a la UI propia;
- si lo prioritario es salir rápido con video/screen share, no construir una experiencia social propia a bajo nivel.

Riesgo:

- menor control de UX y lógica de sala;
- self-host de Jitsi requiere servidor público real, no mini PC CGNAT;
- hay que revisar términos/branding/uso de meet.jit.si o JaaS antes de producción.

### 6. LiveKit

LiveKit tiene SDKs para web y Android, y soporta cámara, micrófono y screen share en múltiples plataformas. Fuente SDKs: https://docs.livekit.io/transport/sdk-platforms/ y screen sharing: https://docs.livekit.io/transport/media/screenshare/

Self-hosting de LiveKit requiere puertos públicos para ICE/UDP, ICE/TCP y opcional TURN. Fuente: https://docs.livekit.io/transport/self-hosting/ports-firewall/

Cuándo elegirlo:

- si queremos una capa de reuniones nativa e integrada en Android/web;
- si se quiere escalar de P2P a SFU sin escribir un SFU propio;
- si se acepta free tier para beta y pago si crece.

Riesgo:

- migración de arquitectura: tokens de sala, servidor LiveKit, SDK nuevo, manejo de participantes/tracks;
- revisar pricing vigente antes de abrirlo a usuarios.

### 7. Cloudflare Realtime SFU/TURN

Cloudflare Realtime ofrece RealtimeKit, Realtime SFU y TURN. La documentación describe Realtime SFU como media server global para audio/video y TURN como relay para NAT/firewalls. Fuente: https://developers.cloudflare.com/realtime/

Pricing SFU/TURN: USD 0.05/GB de egreso, con 1,000 GB/mes gratis compartidos entre SFU y TURN. Fuente: https://developers.cloudflare.com/realtime/sfu/pricing/

Cuándo elegirlo:

- si se busca el mejor free tier por transferencia publicada;
- si el equipo está dispuesto a integrar primitivas WebRTC/SFU de Cloudflare;
- si se quiere aprovechar Cloudflare global sin manejar regiones.

Riesgo:

- el SFU bajo nivel requiere experiencia WebRTC;
- RealtimeKit tiene pricing distinto por participante/minuto;
- validar madurez, límites y SDK Android/Web antes de migrar flujo principal.

### 8. mediasoup

mediasoup es un SFU potente para videoconferencia multiparte y streaming, como módulo Node.js o crate Rust, con simulcast/SVC/BWE. Fuente: https://mediasoup.org/

No es una solución plug-and-play: hay que construir señalización, salas, permisos, control de tracks, operación, TURN y despliegue público.

Para Conéctate solo conviene si se decide tener equipo/tiempo para construir media infrastructure propia. En mini PC con CGNAT no resuelve nada sin VPS o IP pública.

### 9. Janus

Janus es un servidor WebRTC general-purpose con plugins para funcionalidades como conference bridges, recorders y gateways. Fuente: https://janus.conf.meetecho.com/

Es liviano y flexible, pero más de bajo nivel que LiveKit/Jitsi. Conviene para casos especiales o cuando se quiere control fino y se acepta operar infraestructura pública.

## Recomendación de producto para Conéctate

### Fase A: beta gratuita realista

Mantener arquitectura actual:

- Android/web usan WebRTC P2P.
- `signaling-server` entrega ICE servers.
- Cloudflare Tunnel publica backend y signaling.
- Proveedor TURN gratuito/limitado cubre NAT difíciles.

Config conceptual:

```txt
Usuario Android/Web
  -> HTTPS/WSS por Cloudflare Tunnel
  -> signaling-server en mini PC
  -> /turn-credentials entrega STUN/TURN externo
  -> media WebRTC directa si se puede; relay TURN externo si no
```

Promesa pública permitida:

- llamadas y salas en beta;
- audio más estable que cámara;
- cámara/pantalla disponibles en redes compatibles;
- calidad sujeta a red y cuota gratuita.

Promesa que NO debe hacerse:

- “videollamadas globales 100% confiables gratis desde mini PC CGNAT”.

### Fase B: reuniones serias con cámara/pantalla

Elegir una de estas dos rutas:

1. LiveKit Cloud si se prioriza SDK Android/Web, integración de tracks y experiencia de producto.
2. Cloudflare Realtime SFU/TURN si se prioriza free tier grande por GB y bajo costo de transferencia, aceptando más integración técnica.

Jitsi/JaaS es el atajo si se acepta UI de reunión embebida y menos control de experiencia social.

### Fase C: producción con control propio

Comprar VPS público y correr:

- coturn para P2P actual; o
- LiveKit/mediasoup/Janus si se migra a SFU.

Esto elimina dependencia de cuotas gratuitas y permite monitoreo propio, pero ya no es gratis.

## Sección corta para pegar en documentación de producción

### Decisión WebRTC bajo CGNAT

La mini PC opera detrás de CGNAT. Cloudflare Tunnel resuelve backend HTTP/WebSocket y signaling, pero no convierte coturn local en un TURN público para media WebRTC. Para llamadas/salas externas confiables se necesita un relay público: TURN externo, VPS con coturn, o SFU gestionado.

Decisión actual:

- usar Cloudflare Tunnel para API/signaling;
- usar WebRTC P2P con STUN/TURN externo para beta;
- no prometer cámara/pantalla global hasta validar relay público;
- evaluar LiveKit Cloud o Cloudflare Realtime SFU/TURN para reuniones multiparte con cámara y screen share.

Fuentes oficiales:

- WebRTC TURN: https://webrtc.org/getting-started/turn-server
- coturn: https://github.com/coturn/coturn
- Cloudflare Tunnel: https://developers.cloudflare.com/tunnel/
- Cloudflare Realtime pricing: https://developers.cloudflare.com/realtime/sfu/pricing/
- LiveKit screen sharing/SDKs: https://docs.livekit.io/transport/media/screenshare/
- Jitsi SDK/API: https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-android-sdk/
