# Auditoría de Paridad APK vs Web (2026-03-02)

## Resultado ejecutivo

No está al 100% todavía, pero ya cubre mensajería avanzada + momentos + retos + moderación admin base.

## Cobertura implementada en Web

- Auth:
  - Login / registro / sesión persistente.
  - Gate web de cuenta suspendida (`secure_get_my_suspension_state` con fallback a `profiles`).
- Feed:
  - Lista de `posts`.
  - Crear post con texto/archivo.
  - Likes.
  - Detalle de post con comentarios + compartir.
- Reels/Videos:
  - Lista de reels/videos.
  - Publicación de reel desde web (upload + insert en `videos` con fallback de esquema).
  - Publicación con portada opcional, validación de tamaño y detección de duración.
  - Generación de portada desde fotograma del video (selector de tiempo en web).
  - Recorte básico de inicio/fin en publicación (persistido cuando el esquema de `videos` soporta columnas de recorte).
  - Preview dinámico del recorte en web (timeline visual + loop de reproducción entre inicio/fin).
  - Thumbnail strip de frames para navegación rápida durante edición de recorte.
  - Exploración en reels con búsqueda, filtro "solo míos" y orden por recientes/likes/vistas.
  - Detalle de video con like + comentarios.
  - Reporte de video desde detalle (RPC con fallback).
  - Edición/eliminación de video propio desde web.
  - Compartir enlace del video y navegación al perfil del autor.
  - Eliminación de comentarios propios o como dueño del video.
- Mensajes DM (avanzado):
  - Envío texto + adjuntos.
  - Reacciones.
  - Reply / forward.
  - Editar mensaje.
  - Borrar para todos / borrar para mí.
  - View-once (consumo por RPC).
  - Stickers (búsqueda + favoritos + envío).
  - Encuestas (crear + votar).
  - Programación de mensajes.
  - Mute/pin por chat.
  - Ajustes de traducción por chat.
  - Marcado entregado/leído.
  - Compartir contacto y ubicación (link a mapa).
  - Indicador "leido por contacto" en mensajes propios (basado en `chat_participants.last_read_at`).
  - Badges visuales de checks para estado de envío/entrega/lectura.
- Mensajes Grupo/Comunidad (avanzado):
  - Envío texto + adjuntos.
  - Reacciones.
  - Reply / forward.
  - View-once.
  - Encuestas (crear + votar).
  - Marcado leído por RPC.
  - Consulta de recibos de lectura por mensaje.
- Bandeja de mensajes:
  - Tabs de chats/grupos/comunidad/voz.
  - Contador no leído mejorado con `last_read_at`.
  - Contador no leído robusto: prioriza `read_at` para evitar badges pegados cuando `last_read_at` queda desfasado.
  - Marcado de lectura best-effort al abrir chat para evitar badges pegados.
  - Apertura de DM desde bandeja con sync de lectura previo (evita entrar/salir y mantener contador).
  - Acción global "Marcar todo como leído" en bandeja de chats.
  - Realtime de lectura también por cambios en `chat_participants`.
  - Chat mute/pin.
  - Carpetas de chats (crear + asignar).
- Momentos/Stories:
  - Publicar imagen/video.
  - Expiración por tiempo.
  - Registro de vistas.
  - Eliminar momentos propios.
  - Visor full-screen con navegación y autoavance.
  - Tap/swipe para historia anterior/siguiente + barra de progreso clickeable + pausa contextual (presión/input/pestaña oculta).
  - Toggle de audio en historias de video.
  - Responder historias por DM (creación/uso de chat directo).
  - Preview de viewers recientes en historias propias.
- Retos/Challenges:
  - Obtener/generar reto activo.
  - Ver estado/fase del reto.
  - Enviar participación (texto/archivo).
  - Votar participaciones (RPC con fallback).
  - Realtime en estado/submissions/votos.
  - Ranking/podio web por votos acumulados.
  - Pantalla dedicada de ranking global con filtro temporal, búsqueda y podios recientes.
  - Acciones rápidas en ranking/podios para abrir perfil o chat directo con usuarios.
  - Filtros de participaciones (todas/mías/sin votar), orden top/nuevas y señal de tiempo restante.
  - Búsqueda dentro de participaciones y acciones rápidas perfil/mensaje en cada participación.
  - Tarjeta de resumen con métricas del reto y del usuario.
- Admin (expandido):
  - Panel web para `video_reports` con filtros por estado + búsqueda.
  - Cambio de estado de reporte de video.
  - Advertencia al creador del video.
  - Eliminación de video reportado.
  - Suspensión directa del creador desde reporte de video (RPC dedicada con fallback).
  - Panel web para `reports` (reportes de usuario) con filtros, cambio de estado y eliminación.
  - Atajos operativos en reportes a perfil de creador/reportero/reportado y al video reportado.
  - Acciones masivas (bulk): estado/advertencia/eliminación masiva en `video_reports`, y estado/eliminación masiva en `reports`.
  - Suspensión masiva por selección de creadores/reportados desde reportes visibles.
  - Procesamiento bulk en lotes paralelos para reducir tiempo de operaciones administrativas grandes.
  - Suspensión directa de usuario reportado desde su reporte.
  - Suspender/reactivar cuentas por RPC.
  - Duración de suspensión configurable por unidad y modo permanente cuando el backend lo permite.
  - Listado de cuentas suspendidas y levantamiento rápido de suspensión.
  - Visor de `moderation_events` con filtros básicos.
- Social/Profile:
  - Perfil con publicaciones propias.
  - Perfil público de otros usuarios con follow/unfollow.
  - Apertura de chat directo desde perfil público.
  - Reporte de usuario desde perfil público.
  - Followers/following.
  - Solicitudes de amistad (si existe tabla `friends`).
- Ajustes de cuenta:
  - edición de perfil (nombre/usuario/bio).
  - privacidad básica desde web (`is_private` / `profile_visibility` si existen columnas).
  - cambio de contraseña con `supabase.auth.updateUser`.
- Notificaciones:
  - Lista + real-time + marcar leído.
- Search:
  - Búsqueda de perfiles.
  - Reporte de usuario desde resultados (RPC con fallback).
- Voice:
  - Listado de salas de voz.

## Funcionalidad APK aún pendiente

- Mensajería no cerrada al 100%:
  - UI completa de estado de entrega tipo WhatsApp por participante en DM: hay checks base, faltan detalles avanzados de multi-dispositivo/participante.
  - Moderación/acciones avanzadas equivalentes exactas a móvil en todos los casos edge.
- Social:
  - UX de stories: ya hay visor full-screen + replies DM + tap-zones + pausa contextual, faltan pulidos finos para equivalencia visual/gestual exacta.
  - UX de retos al nivel exacto de la APK: ya hay búsqueda + acciones rápidas perfil/chat, faltan pulidos visuales/gestuales finales.
- Video:
  - Ya existe recorte básico inicio/fin, pero el editor multimedia avanzado de APK (timeline/edición visual completa) aún pendiente.
- Administración:
  - Aún faltan reglas/acciones avanzadas exactas de la APK (workflow completo de sanciones y automatizaciones).
- Ajustes de cuenta:
  - Ya existe pantalla dedicada en web; faltan equivalencias avanzadas exactas de UX/reglas que tenga la APK.

## Verificación de “cuenta existente”

La web consulta y escribe sobre el mismo backend Supabase (mismo `auth.uid`, mismas tablas/RPC), por lo que una cuenta existente ve sus conversaciones, publicaciones y datos compartidos cuando inicia sesión.

En este entorno no se pudo validar con credenciales reales por falta de `.env` conectado a Supabase productivo.

## Conclusión

- Estado actual: paridad funcional alta en mensajería y base social.
- Aún no se puede afirmar “100% APK parity” por módulos pendientes de UX/flujo completo en stories-retos-admin y video avanzado.
