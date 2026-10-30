# 00 — Contexto y estado actual

## Continuación del 30/10: colas y cuentas

La [revisión 98](98-colas-web-y-carpetas-por-cuenta-2026-10-30.md) separa las carpetas por cuenta, conserva cancelación entre etapas y corrige acciones pendientes web. Aprueban 2.000 unitarias Android, lint sin errores con K1 (311 advertencias) y paquetes 173 firmados, con comprobación de actualización/entrada en AOSP 35. Web publicada, 875 pruebas y 90 controles públicos aprobados. El trabajo de correciones se conserva aparte para el otro chat; los siguientes apartados mantienen sus fechas y alcances históricos.

## Cierre de los cinco puntos y segunda revisión

La [revisión 96](96-segundo-plano-subidas-y-perfiles-2026-10-30.md) y la [segunda revisión 97](97-segunda-revision-cancelacion-y-cola-push-2026-10-30.md) cierran los cinco puntos solicitados: segundo plano, interrupción/reanudación de archivos, preparación del feed, consulta de perfiles del servidor y advertencias Android. Aprueban 1.979 unitarias en 417 suites y 70 casos nativos únicos seleccionados en 25 clases, en tandas con alcances diferenciados. Lint: cero errores y 312 advertencias, sin desactivar reglas. **APK/AAB 9.1.32 / 172 firmados**, con firma/R8/16 KiB y actualización desde 171 comprobados en AOSP 35. La consulta SQL está en producción: 26,007→3,154 ms de media en muestras de ejecución SQL, sin equipararlos a latencia completa. El informe conserva un timeout nativo inicial y la variabilidad del video; no certifica todos los escenarios. No publicado en Play ni verificado FCM/LIVE/llamadas entre teléfonos físicos.

## Auditoría móvil del 30/10/2026

La [revisión 95](95-auditoria-apk-notificaciones-y-reconexion-2026-10-30.md) corrige el contador de notificaciones para descartar respuestas anteriores o de sesiones invalidadas. Aprueba 1.963 unitarias en 415 suites, 65 nativas en AOSP 35 y lint con cero errores/320 advertencias existentes. **APK y AAB 9.1.31 / 171 firmados**, con R8/alineación de 16 KiB contrastados. La actualización conservó la sesión y un mensaje enviado sin red sobrevivió al cierre, llegó una sola vez al reconectar y se limpió. En API 36 se completó el recorrido de entrada después de recuperar un aviso inicial de System UI del emulador; no se oculta esa incidencia ni se certifica el primer intento. No se distribuyó en Play ni se cambió la API, los pagos o el esquema de datos.

## Reacciones y recorrido web del 30/10/2026

La [revisión 94](94-reacciones-y-recorrido-web-2026-10-30.md) corrige un filtro que ocultaba 😮, 😢 y 🙏 ya guardados, en chats, grupos y comunidades. La corrección está publicada; el navegador comprobó los tres emojis, la persistencia de la última reacción al recargar y su retirada. Se documenta la regla existente de una reacción por persona y mensaje, el recorrido de las pantallas principales y secundarias y las mediciones, sin atribuir toda espera a internet. Aprueba 865 pruebas web, 96 Node y 90 controles públicos. No cambia API ni APK 170.

## Limpieza local del 30/10/2026

La [revisión 93](93-limpieza-local-y-git-2026-10-30.md) registra la retirada de resultados de auditoría y compilación obsoletos de ambas carpetas, con unos 295 GiB físicos recuperados. Conserva fuentes, configuración protegida, respaldos de fuentes y las entregas Android 169/170; Git mantiene sus referencias y pasa `fsck`. Las exclusiones de archivos generados quedan en ambas ramas. No cambia el servidor ni la aplicación publicada.

## Portadas, stickers y contador web

La [revisión 92](92-miniaturas-portada-y-contadores-inmediatos-2026-10-29.md) distingue el límite de red de la espera introducida por el código: el evento realtime actualizaba el mensaje, pero el contador esperaba otra consulta HTTP. Corrige esa proyección, solicita portadas estáticas de 640 px con caché por cuenta y miniaturas de stickers estáticos de 160 px, conservando originales/animaciones. Está publicada y aprueba 862 pruebas web, 96 Node, 90 controles públicos y lint sin errores. El navegador comprobó contador con la consulta retrasada y miniaturas de portada/sticker mediante un fixture sintético explícito. No cambia el APK/AAB 170, la API ni los pagos.

## Preferencias posteriores al cierre

El [plan 91](91-presupuesto-30m-correo-y-opcion-dlocal-2026-10-29.md) actualiza el presupuesto con cable de 30 m y dominio más correo de pago. El propietario pospone UPS y respaldo fuera del local, conservando las copias existentes. Prefiere investigar otro proveedor en lugar de PayPal: dLocal encaja con su descripción, pero falta confirmarlo y no está integrado ni habilitado. No hay cambios funcionales ni una nueva distribución de la versión 170 en esta actualización documental.

## Cierre de auditoría del 29/10/2026

La [revisión 90](90-cierre-produccion-red-y-presupuesto-2026-10-29.md) registra servidor operativo, 256 tablas restauradas desde el respaldo externo, dependencias runtime sin vulnerabilidades conocidas y correo autenticado tras un timeout transitorio. Confirma que la conexión del servidor negocia a 100 Mbps, con tarjeta que admite gigabit; prioriza switch/cableado, dominio y continuidad con S/300. APK y AAB **9.1.30 / 170** están firmados y verificados, con recorrido de entrada del APK release aprobado en AOSP 35, sin publicación en Play. La web aprueba 90 controles públicos nuevos. No se cambió la API, no se activaron retiros y no se certifica capacidad masiva ni aceptación física entre redes.

## Continuación web y APK del 29/10/2026

La [revisión 89](89-colas-por-cuenta-y-recuperacion-visor-2026-10-29.md) corrige la selección de pendientes de grupos/comunidades, que podía quedar ocupada por otra cuenta o mensajes borrados, y la recuperación del visor web cuando no se resolvía el origen protegido. La web está publicada, con 857 pruebas web, 96 Node y 90 controles públicos aprobados; se comprobó la recuperación real de una imagen en el navegador. Android aprueba 1.956 unitarias, ocho nativas en AOSP 35 y lint sin errores, con 320 advertencias existentes. Las correcciones están comiteadas y subidas a ambas ramas. El candidato 170 no se distribuye a Play y no se cambia la API de producción.

## Revisión adicional del 29/10/2026

La [revisión 88](88-reintento-video-e-identidades-completas-2026-10-29.md) reproduce y corrige un botón Reintentar que no recuperaba el origen protegido del video y una consulta que descartaba identidades después de cien perfiles. Aprueba 855 pruebas web y 96 Node. El recorrido real antes/después confirma recuperación y reproducción con la web corregida. Se publica y se sube a ambas ramas, conservando su igualdad. No cambia API ni Android; sus límites de distribución y aceptación física continúan vigentes.

## Continuación web del 29/10/2026

La [revisión 87](87-web-insignias-chat-adaptable-y-estados-2026-10-29.md) registra la web publicada con verificados/ADMIN/insignias, conversaciones que recuperan la vista de escritorio al ampliar la ventana, menús por sección, color personalizado y recuperación/estados de video, voz y envíos. Aprueba 852 pruebas web, 96 Node y 90 controles públicos. Se comprobaron borrador y redimensionado en la web real, perfiles, selector de color, reproducción, menú de voz y recuperación de red. Los cambios están subidos a GitHub en ambas ramas. No hay nueva distribución Android ni despliegue de la optimización API aislada.

## Salas y publicación web del 29/10/2026

La [revisión 86](86-salas-compartidas-voz-y-avatares-2026-10-29.md) continúa la auditoría con prioridad web autorizada por el propietario. Corrige la recuperación/carga de voz, añade enlaces compartidos con retorno tras autenticación y fallback Android/web, y resuelve el estado de avatares cuyo origen protegido falla. Registra la publicación web y los controles reales. Android 9.1.30 / 170 permanece como candidato local, con 1.956 unitarias aprobadas; faltan la firma/distribución del nuevo paquete y la huella de firma de Play para verificar HTTPS. No se despliega la optimización API aislada de 85.

## Auditoría local del 29/10/2026

La [continuación 85](85-conectividad-cancelacion-y-carga-movil-2026-10-29.md) corrige cancelación en reintentos de grupos/comunidades, repetición de esperas DNS y coste de perfiles importados. Aprueba 1.953 unitarias y registra la comparación aislada de capacidad y sondas públicas reales. No se despliega una nueva API ni se publica Android; los límites y resultados de esta ejecución están en esa revisión.

La [revisión 84](84-auditoria-integral-movil-2026-10-29.md) inició la estabilización móvil por módulos. El candidato 9.1.30 / 170 permanece local y en validación; incluye la corrección de la espera circular al abrir videos y protecciones de operaciones, borradores y cuentas. Esa tanda aprobó 1.931 unitarias y 51 pruebas nativas aisladas; la continuación 85 registra las ejecuciones nuevas. No cambia por sí mismo la publicación de producción descrita en 83.

## Continuidad del 28/10/2026

La [revisión 83](83-capturas-chat-y-recuperacion-voz-2026-10-28.md) registra web y aviso de likes en momentos publicados con autorización; 90/90 verificaciones públicas y prueba real de foco/avatares aprobadas. Validación de código: 816 pruebas web, 1.914 unitarias Android y 16 nativas. El paquete 9.1.29 / 169 está firmado, verificado y con recorrido de entrada aprobado en emulador, sin subida automática a Play. Continúa la [tanda 82](82-carga-multimedia-emojis-y-candidato-169-2026-10-28.md) y detalla las comprobaciones físicas/audio/push pendientes. Los apartados siguientes conservan su alcance histórico.

## Actualización del 27/10/2026

La [revisión 80](80-auditoria-seo-y-candidato-local-2026-10-27.md) registra el nuevo candidato SEO **local, pendiente de autorización**: puerta de build, huellas/sitemap, redirects públicos, control de IndexNow y privacidad de vistas compartidas. No se ha desplegado ni subido a GitHub desde la restricción del propietario. La producción descrita en 78/79 continúa como referencia del último despliegue autorizado.

El producto se presenta como **Novvin**, con identificador Android `conectate.com`. El candidato firmado revisado es **9.1.27 / código 167**; su código móvil está confirmado en ambas ramas. Se detectó además un incremento local a **9.1.28 / 168** en `feature/live-gifts`, fuera de los cambios de servidor de esta entrega y todavía sin validar como artefacto aquí. El estado de acceso, regresiones, pruebas físicas y artefactos se detalla en la [auditoría 75](75-cierre-movil-167-2026-10-25.md). Las versiones, mediciones y pendientes escritos debajo corresponden a sus fechas históricas.

La [auditoría de servidor 76](76-auditoria-servidor-2026-10-26.md) y su [continuación 78](78-capacidad-host-y-acceso-web-2026-10-27.md) registran Ubuntu actualizado, reinicio verificado, 14 contenedores operativos y transporte web público sin permiso de red local. La simulación detectó degradación desde 24 solicitudes simultáneas y errores en 64; no se aprueba capacidad masiva. La [revisión 79](79-indexacion-publica-2026-10-27.md) registra 19 páginas públicas, seis imágenes y estado real de indexación. El [prompt de continuidad 77](77-prompt-continuidad-produccion-y-escalado.md) conserva los objetivos pendientes.

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

