# NoWi — Documentación oficial del proyecto

Fecha base: 2026-08-24
Última actualización de la fuente canónica: 2026-10-01 (Bogotá; cada evidencia indica su fecha y alcance)

Estado: documentación canónica para desarrollo, operación y traspaso técnico.

Este directorio reúne la documentación de NoWi (identificador técnico e histórico: Conéctate/Novvin). Está escrito para que un equipo de desarrollo, una IA de programación o una persona nueva entienda el sistema sin adivinar ni asumir resultados no verificados. Cada documento indica su fecha y alcance: las evidencias recientes de conectividad, Videos, Social, perfiles, borrado, programados, bandeja e integridad de reintentos están en los documentos 16–21, junto al handoff 08. Una prueba nueva de un módulo no certifica toda la aplicación.

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
