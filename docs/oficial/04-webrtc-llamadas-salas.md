# 04 — WebRTC: llamadas, salas de voz, cámara y pantalla

Este documento define cómo deben funcionar llamadas privadas, salas de voz, cámara, micrófono, compartir pantalla y reacciones en tiempo real.

## Objetivo

Que usuarios reales puedan:

- entrar a salas de voz;
- salir sin romper estado;
- hablar y escucharse;
- prender/apagar micrófono;
- prender/apagar cámara;
- compartir pantalla;
- ver pantalla/cámara de otros;
- reaccionar en tiempo real;
- iniciar llamada privada;
- responder llamada privada;
- reconectar si la red cae;
- nunca ser expulsados de la app por crash.

## Arquitectura WebRTC esperada

```text
Android A
  |
  | Socket.IO signaling
  v
signaling-server en mini PC
  ^
  | Socket.IO signaling
Android B

Android A <---- WebRTC media ICE/STUN/TURN ----> Android B
```

La señalización pasa por backend/túnel. La media WebRTC no siempre puede pasar directo; puede requerir TURN.

## Tecnología actual

En Android:

- WebRTC Native;
- `WebRTCManager.kt`;
- `CallViewModel.kt`;
- pantallas de llamadas/salas;
- servicios foreground.

En backend:

- `signaling-server/index.js`;
- Socket.IO;
- endpoints de ICE/TURN;
- API Ubuntu para validar salas/usuarios.

Infraestructura:

- Cloudflare Tunnel nombrado para HTTP/WebSocket.
- TURN externo recomendado si hay CGNAT.
- coturn local disponible solo si puede exponerse públicamente.

## Por qué Cloudflare Tunnel no basta para WebRTC media

Cloudflare Tunnel es útil para:

- API HTTPS;
- WebSocket;
- discovery;
- signaling.

Pero WebRTC media usa ICE candidates, UDP/TCP y rutas peer-to-peer o relay. En CGNAT, dos clientes en redes distintas pueden no conectarse si no existe TURN accesible públicamente.

Por eso, si funciona en la misma Wi-Fi pero falla con datos móviles, casi siempre es problema de ICE/TURN, no solo de UI.

## TURN/STUN

El servidor signaling soporta:

- STUN estático;
- TURN estático;
- coturn REST credentials;
- TURN por usuario/clave.

Variables importantes:

```env
STUN_STATIC_URLS=stun:stun.l.google.com:19302
TURN_STATIC_URLS=turn:...,turns:...
TURN_STATIC_USERNAME=...
TURN_STATIC_CREDENTIAL=...
```

Si se usa OpenRelay u otro proveedor gratis:

- documentar límites;
- no commitear credenciales;
- probar cada release;
- tener fallback.

Si se usa coturn local:

- detrás de CGNAT no basta con Docker;
- necesita IP pública o VPS;
- necesita puertos UDP/TCP abiertos;
- requiere `external-ip` correcto.

## Reglas por módulo

### Llamadas privadas

Debe validar:

- usuario origen autenticado;
- usuario destino existe;
- sala/call session existe;
- ambos entran al mismo room signaling;
- se envía offer/answer;
- se intercambian ICE candidates;
- si una parte acepta, la otra recibe estado;
- si falla media, mostrar estado y reintentar.

No debe:

- cerrar la app al responder;
- cerrar la app al llamar;
- quedarse eternamente en “Conectando...” sin diagnóstico;
- mostrar participantes conectados si ICE realmente falló.

### Salas de voz

Debe validar:

- sala existe;
- usuario tiene permiso;
- participant join persistido;
- lista de participantes realtime;
- audio local inicializado;
- audio remoto recibido;
- reconexión al volver de background;
- salida limpia.

No debe:

- botar usuarios al entrar;
- duplicar participantes;
- mostrar “escuchando” inventado;
- dejar micrófono activo después de salir;
- perder mensajes/reacciones de sala.

### Cámara

Debe validar:

- permiso `CAMERA`;
- capturer inicializado;
- Surface/renderer asignado;
- track enviado al peer;
- track remoto renderizado;
- dispose correcto al apagar.

Si se ve negro:

- revisar permiso;
- revisar capturer;
- revisar renderer;
- revisar si el track está enabled;
- revisar si hay ICE conectado;
- revisar si se está mostrando el stream local y no solo remoto.

### Compartir pantalla

Debe validar:

- permiso MediaProjection;
- foreground service compatible con Android moderno;
- capturer de pantalla inicializado;
- track enviado;
- renderer remoto;
- stop limpio.

Si se ve negro:

- revisar si el usuario concedió MediaProjection;
- revisar foreground service type;
- revisar si el stream se reemplazó correctamente;
- revisar si el peer recibió renegotiation;
- revisar logs WebRTC.

### Reacciones

Debe:

- enviarse por signaling/realtime;
- mostrarse localmente al emisor;
- mostrarse a otros participantes;
- animarse suave;
- durar lo suficiente para verse;
- no bloquear UI.

Animación recomendada:

- salida desde zona inferior;
- subida lenta;
- aumento leve de escala;
- fade out al final;
- duración sugerida: 1800–2600 ms.

## Pruebas obligatorias

No marcar este módulo como listo sin probar:

1. Android A en Wi-Fi casa + Android B en Wi-Fi casa.
2. Android A en Wi-Fi casa + Android B en datos móviles.
3. Android A en datos móviles + Android B en Wi-Fi casa.
4. Android A y B ambos en datos móviles si es posible.

Casos:

- entrar sala;
- salir sala;
- apagar/prender micrófono;
- apagar/prender cámara;
- compartir pantalla;
- detener pantalla;
- reacción local;
- reacción remota;
- mensaje de sala;
- llamada saliente;
- llamada entrante;
- aceptar llamada;
- rechazar llamada;
- colgar;
- app en segundo plano;
- pérdida de red;
- retorno de red.

## Logging mínimo recomendado

Android debe loguear sin exponer secretos:

- roomId/callId;
- userId;
- estado signaling;
- estado ICE;
- permiso concedido/denegado;
- cámara inicializada/fallida;
- screen capture inicializado/fallido;
- error stacktrace controlado.

Backend signaling debe loguear:

- conexión socket;
- join room;
- leave room;
- offer/answer;
- ICE candidate count;
- turn credentials emitidas sin mostrar secret;
- errores API;
- disconnect reason.

## Criterio real de terminado

El módulo está terminado solo si:

- no crashea;
- conecta fuera de la red local;
- audio se escucha;
- cámara se ve;
- pantalla se ve;
- reacciones llegan;
- errores se muestran sin cerrar app;
- se probó en Android real;
- se documentó qué TURN/STUN está activo.

