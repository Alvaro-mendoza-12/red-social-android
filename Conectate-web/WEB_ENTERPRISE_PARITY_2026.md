# Conectate Web Enterprise Parity 2026

Fecha: 2026-05-07

## Estado aplicado

- Build productiva web: `npm run build` pasa correctamente.
- Lint web: `npm run lint` pasa correctamente.
- Arquitectura: React/Vite con rutas lazy, layout responsive, preferencias persistentes y cola offline.
- Supabase: cliente central con sanitización de errores, detección de restricciones de cuota/servicio y pausa de Realtime por 5 minutos.
- Mensajes: bandeja con chats, grupos, comunidades, voz, split view, búsqueda, no leídos, favoritos, archivados, mute/pin, reply, forward, editar, borrar, view-once, reacciones, stickers, audio y estados de lectura.
- Mensajes web: nueva capa de overview para la bandeja, contexto de filtros y enlaces rápidos a llamadas, voz y notificaciones para reducir el desorden visual del inbox.
- Videos: feed, upload, portada por archivo/fotograma, preview, trim visual, límite duro de 30 segundos antes del upload y guard contra señales de watermark.
- Social: publicaciones, momentos, retos, ranking, solicitudes, seguidores, notificaciones y moderación admin siguen conectados al mismo backend.
- Perfil: banner/avatar/bio/intereses/juegos/música/libro/deporte/carrera/horario/privacidad de estado online.
- Perfil web: dashboard reorganizado con resumen social, acceso QR, bloque de privacidad, ruta de creador y atajos entre módulos en una sola estructura consistente.
- Ajustes: cuenta, privacidad, seguridad, QR, preferencias web, apariencia, escala de texto, idioma y ahorro de datos.
- Ajustes web: resumen superior, navegación por anclas y separación más clara entre bloques largos para reducir saturación visual.
- Monetización: centro web de creator program, payout, earnings, wallet, gifts, premium y ads.
- Login web: foco exclusivo en el panel de acceso y humanización de errores de cuota/restricción para no exponer mensajes técnicos al usuario final.

## Dependencias de backend

- Para archivado/favoritos de chats se requiere `sql/migrations/20260506_chat_favorites_preferences_2026.sql`.
- Para perfil Discord-style, highlights y creator program se requiere `sql/migrations/supabase_sprint_1_to_5_final_contract_2026.sql`.
- Para wallet/gifts/premium/ads se requieren las migraciones de hardening 20260506.
- Si Supabase devuelve `exceed_storage_size_quota`, la web seguirá abriendo pero pausará Realtime/storage sensible hasta resolver cuota/billing.

## Notas honestas

- La web no puede usar ML Kit nativo; implementa detección web por señales de archivo/metadatos/texto. La detección visual profunda sigue siendo responsabilidad del móvil o de un worker backend.
- La web bloquea archivos mayores a 30 segundos antes de subir para que no se publique el original completo sin transcodificar.
- Las llamadas WebRTC web existen como módulo experimental, pero el flujo principal estable sigue estando en móvil y mensajería.
- La pasada visual del `2026-05-07 22:34` sí ordena mejor `messages` y `profile`, pero la verificación visual browser de esas rutas sigue incompleta mientras no haya una sesión autenticada disponible en este entorno.
- El repo raíz ya quedó preparado para desplegar `Conectate-web` en Vercel, pero si el despliegue remoto falla todavía, el siguiente sospechoso real serán variables de entorno faltantes o una mala configuración del proyecto en el panel de Vercel.
- El build web ahora incluye un preflight de entorno que corta la compilación si `VITE_SUPABASE_URL` o `VITE_SUPABASE_ANON_KEY` no están definidos con valores reales.

## Verificación

```bash
npm run lint
npm run build
npm run vercel:build
```

Resultado: lint y build web exitosos, y el bridge de build desde la raíz también queda ejecutable para Vercel.
