# Auditoria Ubuntu primary backend - 2026-05-30

## Estado real

Ubuntu ya puede actuar como backend principal para los flujos basicos de acceso, datos restaurados, storage local, mensajeria base, grupos/comunidades base y salas de voz base. No es todavia una migracion completa de todas las capacidades historicas de Supabase: aun quedan modulos web y Android que llaman Supabase directamente para funciones avanzadas.

## Funciona con Ubuntu

- Registro, login, sesion local, cambio de password y recuperacion de cuentas migradas.
- Perfil propio: lectura y edicion basica desde `PATCH /v1/data/profile/me`.
- Storage local: buckets, upload, listado, descarga publica y eliminacion por propietario.
- Mensajeria privada: bandeja, detalle de chat y envio de mensajes.
- Grupos y comunidades: listado por bandeja, crear, entrar, salir, detalle y envio de mensajes.
- Feed basico: lectura de posts restaurados/locales y creacion simple de posts.
- Salas de voz base: listado, detalle, crear, entrar y salir.
- Signaling via Ubuntu/ngrok: disponible desde el servicio `signaling` y expuesto por `edge` en el puerto 3080.

## Parcialmente migrado

- Realtime: existe `realtime-gateway`, pero no todos los flujos publican eventos ahi. La web y Android aun conservan suscripciones Supabase en varias pantallas legacy.
- Multimedia historico: los metadatos de `storage.objects` se restauraron, pero los binarios anteriores de Supabase Storage no se pudieron descargar por la restriccion de cuota.
- Perfil avanzado: Ubuntu guarda campos basicos y algunas preferencias, pero no todo el modelo extendido historico.
- Feed/social: lectura y post simple funcionan; comentarios, likes, shares, follows y detalle avanzado siguen mezclados con Supabase.
- Voz/llamadas: signaling basico existe; llamadas completas y sincronizacion historica aun tienen rutas legacy.

## Aun depende de Supabase o esta incompleto

- Videos/reels, comentarios de video y subida completa de videos.
- Retos/challenges y ranking de retos.
- Monetizacion, wallets, gifts, creator program y pagos.
- Badges/stickers avanzados.
- Friend requests, follows avanzados, busqueda global completa.
- Notificaciones persistentes y push completo.
- Moderacion/admin y metricas avanzadas.
- Momentos/stories y highlights completos.
- Reportes avanzados y seguridad operacional historica.

## Problemas corregidos en esta revision

- El build de Vercel fallaba porque el prebuild exigia `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY`. Ahora acepta `VITE_FALLBACK_API_URL` como backend principal.
- CSP de Vercel no aceptaba dominios dinamicos de ngrok. Ahora permite `*.ngrok-free.dev` y `*.ngrok.app`.
- Android intentaba probar realtime de Supabase aunque Ubuntu estuviera configurado como backend principal. Ahora omite realtime Supabase en modo local.
- Storage Android todavia listaba/eliminaba contra Supabase en sesion local. Ahora usa rutas Ubuntu.
- La inferencia MIME de avatar/momentos tenia `substringBefore("")`, que puede dejar el tipo vacio. Ahora usa `substringBefore(";")`.
- Web perfil local ya puede subir avatar/banner a Ubuntu Storage y guardar la URL local.

## Verificaciones locales

- `npm run build:web`: OK.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain`: OK.
- `node --check` en backend local-fallback, storage y signaling: OK.
- `docker compose -f infrastructure/local-fallback/docker-compose.yml config --quiet`: OK.

## Nota de infraestructura

Para web y APK publica, ngrok debe apuntar al edge unificado:

```bash
ngrok http 3080
```

No usar el puerto `3001` para la URL publica principal si tambien se necesitan storage, signaling, llamadas y voz por el mismo dominio.
