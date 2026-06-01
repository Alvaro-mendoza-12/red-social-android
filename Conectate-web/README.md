# Conectate-web

Frontend web independiente de tu app móvil Conectate.

## Objetivo

- Esta carpeta es autónoma.
- La puedes mover a otro disco o ruta y seguirá funcionando igual.
- No depende de archivos de `app/` Android.
- La única conexión compartida entre móvil y web es Supabase.

## Actualización enterprise 2026-05-07

- Build web productiva verificada con `npm run build`.
- Lint web verificado con `npm run lint`.
- TypeScript de producción excluye tests/prototipos legacy para compilar el bundle real de la app.
- Realtime usa guard centralizado: si Supabase devuelve restricción/cuota, la web pausa canales y muestra banner en vez de entrar en loops.
- Mensajes web ahora soporta mute, pin, archivado, favoritos, lectura, reply, forward, editar, borrar, view-once, reacciones, stickers y búsqueda.
- La bandeja de mensajes web ya no queda plana: ahora muestra resumen de estado, contexto de filtros y atajos a llamadas/voz/notificaciones para acercarse al flujo real de la APK.
- Videos web fuerza máximo 30 segundos antes de subir y bloquea señales de marca de agua conocidas por heurística web: TikTok, Facebook, YouTube, Kwai e Instagram.
- Perfil web queda alineado con el perfil estilo Discord de móvil: banner, avatar, bio, intereses, juegos, música, libro, deporte, carrera, horario y privacidad online.
- El perfil web fue reordenado como dashboard: identidad, actividad social, QR, privacidad y monetización ya no compiten en la misma columna sin jerarquía.
- Nuevo centro `/app/monetization` para creator program, wallet/gifts/premium/ads y earnings sobre el SQL final de Supabase.
- Ajustes web ahora tienen resumen, anclas internas y mejor separación entre perfil, privacidad, seguridad, QR y preferencias para que no se sientan aglomerados.

## Deploy en Vercel desde este repo

- El repo raíz ya incluye `package.json` puente y `vercel.json`.
- El proyecto Vercel `red-social-android` usa `Conectate-web` como Root Directory:
  - `installCommand`: `npm ci --legacy-peer-deps`
  - `buildCommand`: `npm run build`
  - `outputDirectory`: `dist`
- `Conectate-web/vercel.json` es la configuración que Vercel consume cuando el Root Directory apunta a esta carpeta; el `vercel.json` raíz queda como respaldo si el proyecto se configura desde la raíz del repo.
- También queda configurado el fallback SPA para rutas como `/app/messages` y `/login?mode=qr`, más headers de seguridad básicos.
- Variables que debes cargar en Vercel:
  - `VITE_SUPABASE_URL`
  - `VITE_SUPABASE_ANON_KEY`
- Variables opcionales para WebRTC:
  - `VITE_SIGNALING_URL`
  - `VITE_CALL_SIGNALING_URL`
- Si no se definen, web usa el mismo signaling activo que Android: `https://voice.srv1495648.hstgr.cloud`.
- El build web ahora falla temprano si esas variables faltan o siguen con placeholders.

## Stack

- React 19 + TypeScript + Vite
- React Router DOM
- Supabase JS

## Configuración

1. Copia `.env.example` a `.env`.
2. Completa:
   - `VITE_SUPABASE_URL`
   - `VITE_SUPABASE_ANON_KEY`

## Ejecutar

```bash
npm install
npm run dev
```

## Build producción

```bash
npm run build
npm run preview
```

## Módulos incluidos

- Arquitectura y resiliencia:
  - `AppErrorBoundary` global para evitar pantallas en blanco por fallos de render
  - cola persistente de mutaciones críticas (`localStorage`) con flush automático al reconectar
  - estrategia de reintentos exponenciales en operaciones transientes (network/timeout/rate-limit)
  - banner de estado de conectividad y sincronización de acciones pendientes
- Auth (login / register)
- Auth (login / register):
  - sesión persistente
  - validación de suspensión de cuenta al entrar (bloqueo web si backend marca suspensión)
- Feed
- Crear publicaciones + likes básicos
- Detalle de post con comentarios y compartir
- Reels / videos:
  - feed de videos
  - publicación de reel desde web (upload a bucket `videos` + inserción en `videos` con compatibilidad de esquema)
  - límite duro de 30 segundos antes de subir para mantener paridad con móvil
  - detección web de señales de marca de agua por nombre/metadatos/texto antes de publicar
  - portada opcional + detección de duración + validación de tamaño antes de publicar
  - generación de portada desde fotograma del video (selector de tiempo)
  - recorte básico de inicio/fin antes de publicar (persistido cuando el esquema lo soporta)
  - preview dinámico del recorte (loop entre inicio/fin + timeline visual)
  - tira de miniaturas (thumbnail strip) para saltar a frames del video durante el recorte
  - preview en tiempo real del frame seleccionado para portada al mover el slider
  - preview antes de publicar (video/portada)
  - exploración con búsqueda, filtro "solo míos" y orden por recientes/likes/vistas
- Detalle de video con likes/comentarios
- Detalle de video con likes/comentarios y acciones avanzadas:
  - reporte de video desde web
  - edición y eliminación de video propio
  - enlace compartible del video (copiar al portapapeles)
  - enlace al perfil del autor desde el detalle
  - eliminación de comentarios propios o como dueño del video
- Mensajes DM avanzados:
  - reply, forward, editar/borrar, view-once, stickers, encuestas, programación
  - ajustes mute/pin y traducción por chat
  - compartir contacto y ubicación (enlace a mapa)
  - estado de lectura en DM con señal "leido por contacto"
  - badges visuales tipo checks (`✓`, `✓✓`) para enviado/entregado/leído
- Mensajes de grupo/comunidad avanzados:
  - reply, forward, encuestas, view-once, recibos de lectura básicos
- Bandeja mejorada:
  - mute/pin por chat
  - archivado/favoritos por usuario con RPC `secure_set_chat_preferences`
  - carpetas y asignación de chats
  - búsqueda de conversaciones por título/último mensaje/carpeta
  - contador no leído basado en `last_read_at`
  - fallback local de lectura para evitar contadores pegados cuando el backend tarde en sincronizar
  - considera `status=read/seen` además de `read_at` para mayor compatibilidad móvil/web
  - sincronización de lectura al abrir chat (espera de marcado antes de navegar en DM)
  - prioriza `read_at` para evitar contadores pegados cuando `last_read_at` esté desfasado
  - acción "Marcar todo como leído" para limpiar contadores pegados
  - actualización en tiempo real por cambios en `messages` y `chat_participants`
- Momentos / stories:
  - publicar (imagen/video), expiración, vistas y borrado
  - visor full-screen con avance automático y navegación
  - tap/swipe izquierda-derecha, barra de progreso clickeable y pausa contextual (presión, input, pestaña oculta)
  - control rápido de audio en historias de video
  - respuestas a historias por DM con apertura directa de chat
  - preview de quién vio tus historias (top reciente)
- Retos / challenges:
  - reto activo, envío de participación, votos y realtime
  - ranking/podio dinámico por votos
  - pantalla dedicada de ranking global con filtros por periodo, búsqueda y podios recientes
  - acciones directas en ranking/podios: abrir perfil y chat con usuario
  - filtros de participaciones (todas/mías/sin votar), orden top/nuevas y tiempo restante
  - búsqueda interna de participaciones y acciones rápidas perfil/mensaje por participación
  - resumen del reto (participaciones, votos totales, tus votos/participaciones)
- Moderación admin:
  - video reportes: filtros, cambio de estado, advertencia y eliminación de video reportado
  - suspensión directa de creador desde reporte de video
  - reportes de usuario (`reports`): listado, filtros, cambio de estado y eliminación
  - atajos de revisión: abrir video reportado y perfiles de creador/reportero/reportado
  - acciones masivas (bulk) en reportes:
    - video: seleccionar visibles, aplicar estado masivo con nota, advertir masivamente o eliminar videos reportados
    - usuario: seleccionar visibles, aplicar estado masivo o eliminar seleccionados
    - suspensión masiva de creadores/reportados seleccionados con duración en días
    - ejecución por lotes paralelos para acelerar acciones masivas grandes
    - reintento automático en operaciones bulk y resumen de fallos por ID
  - suspensión directa de usuario reportado desde el reporte
  - suspensión / reactivación de cuentas por RPC admin
  - duración configurable (min/horas/días/años) o suspensión permanente (si el backend lo soporta)
  - listado de cuentas suspendidas con levantamiento rápido de suspensión
  - visor de eventos de moderación (`moderation_events`) con filtros
- Notificaciones:
  - contador no leído en tiempo real
  - badge global en navegación web/móvil
  - búsqueda y filtros (todas/sin leer/mensajes/social/moderación)
  - marcado masivo de notificaciones visibles
  - acción directa para abrir destino (post/video/chat/grupo/comunidad/perfil)
  - marcado seguro vía RPC backend (`secure_mark_notifications_read`) con fallback compatible
- Followers/following y solicitudes de amistad
- Búsqueda de usuarios:
  - búsqueda por nombre/usuario/bio
  - reporte de usuario desde resultados
- Perfil:
  - perfil propio con publicaciones
  - perfil público de otros usuarios (`/app/profile/:profileId`)
  - acciones sociales en perfil público: seguir/dejar de seguir, mensaje directo, reporte
  - perfil Discord-style con banner, avatar, intereses, juegos, música, libro, deporte, carrera y horario
- Ajustes de cuenta:
  - editar perfil (nombre/usuario/bio)
  - editar banner/avatar/intereses/juegos/música/libro/deporte/carrera/horario cuando el SQL final está aplicado
  - privacidad (cuenta privada/visibilidad cuando el backend lo soporta)
  - privacidad granular de último acceso y estado online
  - apariencia, escala de texto, idioma, inbox compacta y ahorro de datos
  - cambio de contraseña con Supabase Auth

## Monetización y creadores

- Ruta: `/app/monetization`.
- Lee y escribe `creator_program_applications`.
- Lee `creator_payout_accounts`, `creator_earnings`, `wallets`, `gift_transactions`, `premium_subscriptions` y `ad_impressions` si existen.
- Si una tabla aún no existe o el SQL no está aplicado, la pantalla queda degradada de forma segura en lugar de romper la app.

## Verificación reciente

```bash
npm run lint
npm run build
```

Resultado verificado: lint y build exitosos con ESLint, Vite y TypeScript; salida generada en `dist/`.

## Verificación extra del bridge para Vercel

```bash
cd ..
npm run vercel:build
```
