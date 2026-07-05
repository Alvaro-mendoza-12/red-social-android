# Conéctate — Documentación oficial del proyecto

Fecha base: 2026-07-04  
Estado: documentación canónica para desarrollo, operación y traspaso técnico.

Este directorio es la fuente de verdad del proyecto Conéctate. Está escrito para que un equipo de desarrollo, una IA de programación o una persona nueva en el proyecto entienda el sistema sin adivinar, sin mezclar información antigua y sin asumir resultados no verificados.

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
- **Cloudflare Quick Tunnel**: túnel HTTP público usado por CGNAT. Su URL puede cambiar, por eso la app usa descubrimiento dinámico.
- **DuckDNS TXT discovery**: mecanismo de descubrimiento para que la app encuentre la URL pública actual.
- **TURN/STUN**: infraestructura para que WebRTC pueda conectar audio/video/pantalla entre redes distintas.
- **CGNAT**: restricción del proveedor de internet que impide exponer puertos públicos directamente desde el router.

