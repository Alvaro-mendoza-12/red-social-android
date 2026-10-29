# Novvin — Documentación oficial del proyecto

Fecha base: 2026-08-24
Última actualización de la fuente canónica: 2026-10-28 (Bogotá; cada evidencia indica su fecha y alcance)

Estado: documentación canónica para desarrollo, operación y traspaso técnico.

Revisión actual: [83 — Capturas de chat y recuperación de voz](83-capturas-chat-y-recuperacion-voz-2026-10-28.md). Continúa la [tanda 82](82-carga-multimedia-emojis-y-candidato-169-2026-10-28.md): 816 pruebas web, 1.914 unitarias Android y 16 nativas aprobadas; **9.1.29 / 169** firmado y validado estructuralmente. El propietario autorizó publicar web y aviso de likes en momentos; este último ya se activó recreando únicamente API, con el resto de contenedores preservado. La comprobación final de la nueva web y del recorrido del paquete firmado se registra en la revisión 83. La [revisión 81](81-continuidad-seo-chats-y-release-168-2026-10-28.md) conserva las evidencias del paquete 168 y las publicaciones previas.

Servidor: [76 — Limpieza, actualización y verificación](76-auditoria-servidor-2026-10-26.md). Continuidad del proyecto en producción: [77 — Prompt de auditoría y escalado](77-prompt-continuidad-produccion-y-escalado.md).

Este directorio reúne la documentación de Novvin (identificadores históricos: Conéctate/NoWi). Está escrito para que un equipo de desarrollo, una IA de programación o una persona nueva entienda el sistema sin adivinar ni asumir resultados no verificados. Cada documento indica su fecha y alcance: las evidencias de conectividad, Videos, Social, perfiles, borrado, programados, bandeja e integridad de reintentos están en los documentos 16–21 y en sus continuaciones posteriores, junto al handoff 08. Una prueba nueva de un módulo no certifica toda la aplicación.

Importante: en el repositorio existen documentos históricos, diagnósticos y notas de migración. Algunos reflejan etapas anteriores con Supabase, VPS, túneles temporales, pruebas o ideas aspiracionales. Si hay conflicto entre esos documentos y esta carpeta, usa esta carpeta como referencia principal y valida el estado real con los comandos del runbook.

## Índice recomendado de lectura

1. [Contexto y estado actual](00-contexto-y-estado.md)
2. [Arquitectura general](01-arquitectura.md)
3. [Aplicación Android](02-android.md)
4. [Backend Ubuntu / servidor mini PC](03-backend-mini-pc.md)
5. [WebRTC: llamadas, salas de voz, cámara y pantalla](04-webrtc-llamadas-salas.md)
6. [Datos, storage y backups](05-datos-storage-backups.md)
7. [Operación en producción / runbook](06-operaciones-runbook.md)
8. [Seguridad y legal](07-seguridad-legal.md)
9. [Pruebas, release y handoff](08-pruebas-release-handoff.md)
10. [IA propia de Conectate (Novvin)](09-ia-conectate.md)
11. [Experiencia Novvin LIVE](10-live-experience-reference.md)
12. [Auditoría y validación de Novvin LIVE](11-live-validation-audit.md)
13. [Auditoría de preparación para producción](12-production-readiness-audit.md)
14. [Música comunitaria, audio de Momentos y batallas Cross-LIVE](13-musica-comunitaria-y-batallas-cross-live.md)
15. [Sistema musical legal para Momentos](14-sistema-musical-legal.md)
16. [Auditoría extrema de producción 2026-08-24](15-auditoria-extrema-2026-08-24.md)
17. [Auditoría técnica y plan priorizado 2026-08-25](../AUDITORIA_TECNICA_2026-08-25.md)
18. [Runbook: Cloudflare Tunnel nombrado](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md)
19. [Modelo operativo de NoWi por áreas](../empresa/README.md)
20. [Roles y capacidades administrativas](../empresa/06-roles-y-capacidades-administrativas.md)
21. [Recuperación de túnel, feed y mezcla de momentos — 2026-09-09](16-recuperacion-tunel-y-feed-2026-09-09.md)
22. [Borrado durable por cuenta — 2026-09-09](17-borrado-durable-por-cuenta-2026-09-09.md)
23. [Timeout de mensajes programados y aislamiento del chat — 2026-09-09](18-mensajes-programados-timeout-2026-09-09.md)
24. [Visibilidad de la bandeja por cuenta — 2026-09-10](19-visibilidad-bandeja-2026-09-10.md)
25. [Integridad de los reintentos de mensajes — 2026-09-10](20-integridad-reintentos-mensajes-2026-09-10.md)
26. [Videos, Social y conectividad pública — 2026-09-10](21-videos-social-conectividad-2026-09-10.md)
27. [Social, LIVE, regalos y validación de servicios — 2026-09-12](22-live-regalos-y-validacion-2026-09-12.md)
28. [Multimedia sin conexión y restauración aislada — 2026-09-13](23-multimedia-offline-y-restauracion-2026-09-13.md)
29. [Música, permisos de multimedia y plan Gamer Hub — 2026-09-13](24-musica-privacidad-y-plan-gamer-hub-2026-09-13.md)
30. [Prueba física, cabecera de Momentos y filtros PlayMatch — 2026-09-14](25-prueba-fisica-momentos-y-playmatch-2026-09-14.md)
31. [Firma AAB desde Android Studio — 2026-09-14](26-firma-aab-android-studio-2026-09-14.md)
32. [Música completa, portadas y aviso de Windows — 2026-09-14](27-musica-completa-y-watchdog-2026-09-14.md)
33. [Fragmentos musicales en video — 2026-09-16](28-fragmentos-musicales-en-video-2026-09-16.md)
34. [Catálogo musical: concurrencia y recuperación — 2026-09-16](29-catalogo-musical-concurrencia-2026-09-16.md)
35. [Identidades sin conexión y retención por cuenta — 2026-09-17](30-identidades-offline-por-cuenta-2026-09-17.md)
36. [Destacados del perfil y plan de diferenciales — 2026-09-17](31-perfil-destacados-y-diferenciales-2026-09-17.md)
37. [Permisos de stickers y cierre de destacados — 2026-09-18](32-permisos-stickers-y-cierre-destacados-2026-09-18.md)
38. [Fluidez y auditoría por módulos — 2026-09-30](33-fluidez-y-auditoria-modulos-2026-09-30.md)
39. [Cadencia de LIVE, salas, llamadas y PK — 2026-10-01](34-cadencia-live-salas-2026-10-01.md)
40. [Arranque, invitaciones LIVE y recuperación por correo — 2026-10-01](35-arranque-invitaciones-y-recuperacion-correo-2026-10-01.md)
41. [Mensajería, recomendaciones y avisos — 2026-10-01](36-mensajeria-recomendaciones-avisos-2026-10-01.md)
42. [SSD autorizado, llamadas y multimedia — 2026-10-01](37-ssd-llamadas-multimedia-2026-10-01.md)
43. [Web publicada: autenticación y avisos — 2026-10-01](38-web-autenticacion-avisos-2026-10-01.md)
44. [Móvil: cancelación de llamadas y adjuntos simultáneos — 2026-10-01](39-movil-llamadas-y-adjuntos-2026-10-01.md)
45. [Móvil: mensajes físicos, notas de voz y galería — 2026-10-01](40-movil-pruebas-fisicas-mensajes-2026-10-01.md)
46. [Móvil: lectura inmediata y archivos entre redes — 2026-10-02](41-movil-confirmaciones-y-archivos-2026-10-02.md)
47. [Móvil: citas y reintentos de documentos — 2026-10-02](42-movil-citas-y-reintentos-2026-10-02.md)
48. [Móvil: negociación de llamadas y documentos internos — 2026-10-02](43-movil-arranque-llamadas-y-documentos-2026-10-02.md)
49. [Móvil: preparación de red y llamada con la tablet bloqueada — 2026-10-02](44-movil-preparacion-red-y-llamada-bloqueada-2026-10-02.md)
50. [Móvil: señalización durante el timbre y cancelación física — 2026-10-02](45-movil-senalizacion-durante-timbre-2026-10-02.md)
51. [Móvil: capacidad compartida y fallback de llamadas — 2026-10-02](46-movil-capacidad-fallback-llamadas-2026-10-02.md)
52. [Móvil: TURN por sesión y tiempos nativos de llamadas — 2026-10-02](47-movil-turn-sesion-y-tiempos-2026-10-02.md)
53. [Emulador: stickers y recuperación de la cola — 2026-10-02](48-emulador-stickers-y-cola-2026-10-02.md)
54. [Emulador: respuestas a multimedia y sincronización — 2026-10-02](49-emulador-mensajes-respuestas-y-sync-2026-10-02.md)
55. [Emulador: envío fuera del hilo de la pantalla y documentos — 2026-10-02](50-emulador-envio-background-y-documentos-2026-10-02.md)
56. [Creación de grupos y comunidades: propiedad y reintentos — 2026-10-02](51-creacion-grupos-propiedad-2026-10-02.md)
57. [Enlaces de chat — 2026-10-02](52-enlaces-chat-2026-10-02.md)
58. [Transporte de llamadas, salas y LIVE — 2026-10-02](53-transporte-llamadas-salas-live-2026-10-02.md)
59. [Permisos al contestar llamadas — 2026-10-02](54-permisos-al-contestar-llamadas-2026-10-02.md)
60. [Salas privadas y media entre emuladores — 2026-10-03](55-salas-privadas-y-media-emuladores-2026-10-03.md)
61. [Entrada a salas sin peticiones repetidas — 2026-10-03](56-admision-salas-sin-peticiones-repetidas-2026-10-03.md)
62. [LIVE: controles e invitados — 2026-10-03](57-live-controles-e-invitados-2026-10-03.md)
63. [Encuestas: transacción y actualizaciones — 2026-10-03](58-encuestas-transaccion-y-actualizaciones-2026-10-03.md)
64. [Registro de visitas autenticadas — 2026-10-03](59-registro-de-visitas-autenticadas-2026-10-03.md)
65. [Encuestas, audio, Momentos, avisos y perfil — 2026-10-03](60-encuestas-audio-momentos-avisos-y-perfil-2026-10-03.md)
66. [Perfil, arranque y consultas visibles — 2026-10-03](61-perfil-arranque-y-consultas-visibles-2026-10-03.md)
67. [Social, Momentos y avisos — 2026-10-03](63-social-momentos-y-avisos-2026-10-03.md)
68. [Validación Moto G04 — 2026-10-03](64-validacion-moto-g04-2026-10-03.md)
69. [Rechazo de llamadas y arranque — 2026-10-04](65-rechazo-de-llamadas-y-arranque-2026-10-04.md)
70. [Validación móvil e729365 — 2026-10-04](66-validacion-movil-e729365-2026-10-04.md)
71. [Inicialización de novedades Home — 2026-10-04](67-inicializacion-de-novedades-home-2026-10-04.md)
72. [Editor móvil: música, letras y duración — 2026-10-04](68-editor-movil-musica-letras-y-duracion-2026-10-04.md)
73. [Encuestas: editor móvil y publicación — 2026-10-04](69-encuestas-editor-movil-y-publicacion-2026-10-04.md)
74. [Medios, arranque y avisos — 2026-10-04](70-medios-arranque-y-avisos-validacion-2026-10-04.md)
75. [Captura, edición y entrega — 2026-10-04](71-auditoria-captura-edicion-y-entrega-2026-10-04.md)
76. [Medios, Social y LIVE entre redes — 2026-10-05](72-medios-social-y-live-redes-distintas-2026-10-05.md)
77. [Botella al mar: recepción sin filtros — 2026-10-07](73-botella-al-mar-recepcion-sin-filtros-2026-10-07.md)
78. [Auditoría del release: acceso, sesión, invitado, vídeo y perfiles — 2026-10-24](74-auditoria-release-acceso-y-medios-2026-10-24.md)
79. [Continuidad móvil: acceso, emojis, avatares, vídeo y música de perfil — 2026-10-25](75-cierre-movil-167-2026-10-25.md)
80. [Servidor: limpieza, runtimes, dependencias y restauración — 2026-10-26](76-auditoria-servidor-2026-10-26.md)
81. [Prompt de continuidad: producción, auditoría y crecimiento](77-prompt-continuidad-produccion-y-escalado.md)
82. [Host actualizado, capacidad y acceso público web — 2026-10-27](78-capacidad-host-y-acceso-web-2026-10-27.md)
83. [Indexación pública e imágenes — 2026-10-27](79-indexacion-publica-2026-10-27.md)
84. [Auditoría SEO y candidato local, pendiente de autorización — 2026-10-27](80-auditoria-seo-y-candidato-local-2026-10-27.md)

## Reglas para quien trabaje en este proyecto

- No asumir que “compila” significa “funciona en producción”.
- No declarar listas las llamadas, salas de voz o pantalla compartida sin prueba en Android real, con al menos dos dispositivos y, cuando aplique, en redes diferentes.
- No volver a depender de Supabase como backend principal. Supabase queda como legado histórico/migración, salvo que el dueño del proyecto lo autorice explícitamente.
- No publicar secretos, tokens, llaves Firebase, keystores, `.env`, `secrets.properties` ni credenciales de túneles/TURN.
- Antes de tocar backend de producción, crear backup verificable.
- Antes de subir a Play Store, subir `versionCode`, generar AAB release y probar instalación limpia.
- Para cualquier cambio grande, documentar:
  - qué se cambió;
  - qué se probó;
  - qué queda pendiente;
  - cómo revertir.

## Definiciones rápidas

- **Conéctate**: red social móvil con mensajes, grupos, comunidades, videos, social/feed, perfiles, llamadas, salas de voz y notificaciones.
- **APK/App Android**: cliente principal de producción.
- **Backend Ubuntu**: backend propio desplegado en la mini PC con Ubuntu Server.
- **Mini PC / servidor**: máquina física que aloja Docker, PostgreSQL, API, realtime, signaling, nginx edge, backups y servicios 24/7.
- **Ingreso público actual**: HTTPS mediante Tailscale Funnel en `https://conectate.tail9b267c.ts.net`, comprobado desde IPv4 público sin usar la LAN ni la IP privada de Tailscale. Los documentos de Cloudflare describen el despliegue anterior y una alternativa operativa; no prueban la configuración vigente.
- **DuckDNS TXT discovery**: mecanismo firmado y temporal de transición/recuperación; no sustituye el hostname API estable.
- **TURN/STUN**: infraestructura para que WebRTC pueda conectar audio/video/pantalla entre redes distintas.
- **CGNAT**: restricción del proveedor de internet que impide exponer puertos públicos directamente desde el router.
