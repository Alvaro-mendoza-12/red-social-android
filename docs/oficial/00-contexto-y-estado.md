# 00 — Contexto y estado actual

Conéctate es una red social móvil en producción. El objetivo del proyecto es operar sin depender de Supabase como backend principal, usando un backend propio en Ubuntu Server sobre una mini PC. La aplicación está orientada a usuarios reales, no solo pruebas internas, por lo que cualquier cambio debe tratarse como producción.

## Historia resumida

El proyecto pasó por varias etapas:

1. **Etapa Supabase**
   - Autenticación, storage y datos estaban ligados a Supabase.
   - Se detectaron problemas de dependencia, migración de contraseñas y control limitado.

2. **Etapa VPS/Hostinger**
   - Algunas funciones de realtime/WebRTC pudieron quedar apuntando a infraestructura anterior.
   - Esto provocó confusión: llamadas o salas podían funcionar en una red/entorno y fallar al pasar a la mini PC.

3. **Etapa Ubuntu mini PC**
   - El backend propio se migró a Ubuntu Server.
   - La mini PC se volvió el servidor principal de Conéctate.
   - Se usa Docker Compose para levantar PostgreSQL, API, signaling, realtime, nginx edge y TURN local opcional.
   - Debido a CGNAT, el acceso público HTTP depende de túnel Cloudflare y discovery por DuckDNS TXT.

## Estado actual verificado por el repositorio

Estos puntos se observan directamente en el código/configuración local:

- Android:
  - Proyecto Kotlin + Jetpack Compose.
  - `applicationId`: `conectate.com`.
  - `compileSdk`: 35.
  - `minSdk`: 24.
  - `targetSdk`: 35.
  - `versionCode`: 41.
  - `versionName`: 3.0.1.
  - Backend principal configurado por `FALLBACK_API_URL` y `BACKEND_DISCOVERY_HOST`.

- Backend:
  - Carpeta principal: `infrastructure/local-fallback/`.
  - Docker Compose con servicios:
    - PostgreSQL;
    - API Node/Express;
    - signaling Socket.IO/WebRTC;
    - realtime gateway;
    - nginx edge;
    - coturn opcional bajo profile `turn`.
  - API usa JWT, bcrypt, PostgreSQL, helmet y Firebase Cloud Messaging.
  - Existe lógica de migración de cuentas Supabase a Ubuntu.

- Web:
  - Existe `Conectate-web/`.
  - Usa React/Vite/TypeScript.
  - Debe considerarse cliente secundario. No asumir paridad total con Android sin probar.

## Estado operativo esperado del servidor

El servidor de producción esperado es:

- Hostname Ubuntu: `conectate`.
- Usuario operativo: `alvaro`.
- IP LAN conocida: `192.168.18.22`.
- IP Tailscale conocida: `100.91.84.2`.
- Ruta del proyecto en Ubuntu: `~/conectate-local-fallback`.
- Comando de diagnóstico principal: `~/bin/conectate-status`.
- Comando de recuperación rápida: `~/bin/conectate-restart`.
- Disco externo de backup esperado: `/mnt/conectate-backup`.

El estado saludable debe mostrar:

- `docker=active`
- `stack=active`
- `cloudflare=active`
- contenedores `api`, `db`, `edge`, `realtime-gateway`, `signaling` en `healthy`
- `READY LOCAL` con `ok:true`
- `READY PUBLIC` con `ok:true`

## Punto crítico actual: CGNAT y URL pública dinámica

El router está detrás de CGNAT. Por eso:

- No se puede depender de abrir puertos normales del router.
- Cloudflare Quick Tunnel puede cambiar de URL.
- La app no debe quedarse pegada a una URL `trycloudflare.com` antigua.
- La app debe resolver la URL activa mediante `BACKEND_DISCOVERY_HOST`, normalmente `conectate-alvaro.duckdns.org`.

Si un usuario reporta:

```text
Unable to resolve host "xxxx.trycloudflare.com"
```

eso indica casi siempre que la app o la configuración quedó apuntando a una URL temporal antigua, o que el discovery público no actualizó correctamente.

## Punto crítico actual: WebRTC fuera de la red local

Para llamadas, salas de voz, cámara y compartir pantalla:

- WebRTC necesita señalización y también rutas de media.
- Cloudflare Tunnel sirve para HTTP/WebSocket de señalización, pero no resuelve por sí solo todo el tráfico media WebRTC.
- En redes distintas, especialmente móvil + casa con CGNAT, se necesita STUN/TURN funcional.
- El proyecto soporta credenciales TURN por variables de entorno en `signaling-server`.

No se debe marcar llamadas/salas como terminadas si solo funcionan dentro de la misma red Wi-Fi.

## Usuarios y escala actual

Contexto reportado por el dueño del proyecto:

- Aproximadamente 60 usuarios registrados.
- Usuarios activos reales: bajo volumen, normalmente 1–5 por semana.
- La mini PC fue ampliada a 16 GB de RAM DDR4.
- Hay varios discos externos SATA/USB disponibles.

Esta escala permite optimizaciones pragmáticas:

- PostgreSQL en SSD.
- Servicios Docker en SSD.
- Archivos de usuario y backups en discos externos si están bien montados.
- Cache en memoria para bajo volumen.
- Redis puede agregarse si los perfiles/feed/mensajes hacen demasiadas consultas repetidas.

## Qué no debe asumirse

- No asumir que todos los módulos heredados de Supabase están limpios.
- No asumir que la web tiene la misma cobertura que Android.
- No asumir que una URL pública temporal seguirá viva.
- No asumir que TURN local funciona detrás de CGNAT si no hay IP pública o VPS.
- No asumir que llamadas/salas funcionan hasta probar:
  - dos celulares Android reales;
  - red local + datos móviles;
  - entrada/salida de sala;
  - llamada entrante;
  - llamada saliente;
  - cámara;
  - pantalla;
  - micrófono;
  - reconexión.

