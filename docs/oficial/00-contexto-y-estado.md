# 00 — Contexto y estado actual

## Actualización del 25/10/2026

El producto se presenta como **Novvin**, con identificador Android `conectate.com`. El candidato actual es **9.1.27 / código 167** en `main` y `feature/live-gifts`. El estado vigente de acceso, regresiones, pruebas físicas y artefactos firmados se detalla en la [auditoría 75](75-cierre-movil-167-2026-10-25.md). Las versiones, mediciones y pendientes escritos debajo corresponden a sus fechas históricas.

Conéctate es una red social móvil orientada a producción y con una versión histórica
distribuida. La rama de trabajo es `feature/live-gifts` en `D:\red social - live-gifts`.
El backend propio en Ubuntu Server sobre una mini PC tiene un ingreso HTTPS comprobado
en `https://conectate.tail9b267c.ts.net` mediante Tailscale Funnel. Las comprobaciones
del 30/09/2026 cubren nueve contenedores saludables y recorridos públicos descritos
en el documento 33. No equivalen a certificar cada opción de todos los clientes. La aplicación está orientada a
usuarios reales, no solo pruebas internas, por lo que cualquier cambio debe tratarse
como producción.

## Actualización del 03/10/2026

- API: encuestas con transacción y publicación de cambios (revisión 58); registro
  de visitas corregido y confirmado desde Android (revisión 59). Release `31c571d`,
  nueve servicios saludables y 100/100 fuentes JavaScript comprobadas.
- Android: encuestas visibles, música, fotos de momentos, formulario de avisos y
  visitas, aislamiento del perfil y consultas LIVE mientras son visibles. La
  regresión completa aprobó 1.221 pruebas unitarias y la APK combinada pasó 24
  casos nativos. El selector reutiliza una respuesta para el salto inicial corto
  de audio; persisten picos de transferencia. Recorridos entre cuentas
  propias de QA y recursos del servidor; matriz de los once requisitos en la
  revisión 60 y mediciones actuales en la [revisión 61](61-perfil-arranque-y-consultas-visibles-2026-10-03.md).
  Se mantienen 117 / 9.0.20.
- Siguen abiertos arranque ≤3 s, rendimiento WebRTC/60 FPS, pruebas físicas entre
  redes, entrega FCM y paridad web completa. No se declara producción certificada.

## Actualización del 01/10/2026

- Servidor: nueve servicios saludables; Docker y containerd migrados al NVMe ext4 autorizado, con respaldo previo y posterior verificados. Detalle en la [revisión 37](37-ssd-llamadas-multimedia-2026-10-01.md).
- Web: bloque de autenticación/recuperación/avisos publicado en Vercel y comprobado desde el origen externo, sin interceptar el frontend. Detalle en la [revisión 38](38-web-autenticacion-avisos-2026-10-01.md); no equivale a paridad completa.
- Móvil es la prioridad actual: cierre de pantalla al cancelar llamada y protección de adjuntos concurrentes, en la [revisión 39](39-movil-llamadas-y-adjuntos-2026-10-01.md). Se conservan versión 117 / 9.0.20.
- Faltan pruebas físicas entre Motorola y tablet para cierre inesperado, timbre fuera de la app, todos los envíos y LIVE/salas en redes distintas. No se declaran certificados 60 FPS ni todos los módulos para producción.

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
   - Debido a CGNAT, el acceso público HTTP entra actualmente por Tailscale Funnel
     y un hostname HTTPS estable. Cloudflare nombrado permanece como alternativa histórica. El descubrimiento firmado por DuckDNS
     queda como compatibilidad de transición y recuperación, no como ruta
     primaria de producción.

## Estado actual verificado por el repositorio

Estos puntos se observan directamente en el código/configuración local:

- Android:
  - Proyecto Kotlin + Jetpack Compose.
  - `applicationId`: `conectate.com`.
  - `compileSdk`: 36.
  - `minSdk`: 24.
  - `targetSdk`: 36.
  - Android Gradle Plugin: 8.13.2.
  - Gradle wrapper: 8.13.
  - `versionCode`: 117.
  - `versionName`: 9.0.20. El incremento está pendiente por indicación del propietario.
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
- ingreso público HTTPS y configuración de `tailscale funnel status` correctos; `cloudflare=active` pertenece al runbook anterior
- contenedores `api`, `db`, `edge`, `realtime-gateway`, `signaling` en `healthy`
- `READY LOCAL` con `ok:true`
- `READY PUBLIC` con `ok:true`

## Punto crítico de transición: CGNAT y origen público

El router está detrás de CGNAT. Por eso:

- No se puede depender de abrir puertos normales del router.
- La arquitectura objetivo usa un hostname HTTPS estable, por ejemplo
  `https://api.novvin.example`, conectado a un Cloudflare Tunnel nombrado.
- Un Quick Tunnel no es una base admisible para producción: su URL puede cambiar
  y no ofrece las garantías operativas requeridas.
- La app no debe aceptar ni conservar una URL `trycloudflare.com` como origen
  final. `BACKEND_DISCOVERY_HOST` sólo se consulta durante la transición o una
  recuperación controlada, y siempre debe verificar firma, vigencia y `/ready`.

> Estado observado el 2026-08-26: el servidor aún tenía túneles rápidos
> manuales. La migración al túnel nombrado está preparada en el repositorio pero
> no se debe declarar aplicada hasta instalarla y validarla en Ubuntu. Ver el
> [runbook de túnel nombrado](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md).

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

