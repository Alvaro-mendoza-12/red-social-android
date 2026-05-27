# Conectate Web Enterprise Parity 2026

Fecha: 2026-05-07

## Estado aplicado

- Build productiva web: `npm run build` pasa correctamente.
- Arquitectura: React/Vite con rutas lazy, layout responsive, preferencias persistentes y cola offline.
- Supabase: cliente central con sanitización de errores, detección de restricciones de cuota/servicio y pausa de Realtime por 5 minutos.
- Mensajes: bandeja con chats, grupos, comunidades, voz, split view, búsqueda, no leídos, favoritos, archivados, mute/pin, reply, forward, editar, borrar, view-once, reacciones, stickers, audio y estados de lectura.
- Videos: feed, upload, portada por archivo/fotograma, preview, trim visual, límite duro de 30 segundos antes del upload y guard contra señales de watermark.
- Social: publicaciones, momentos, retos, ranking, solicitudes, seguidores, notificaciones y moderación admin siguen conectados al mismo backend.
- Perfil: banner/avatar/bio/intereses/juegos/música/libro/deporte/carrera/horario/privacidad de estado online.
- Ajustes: cuenta, privacidad, seguridad, QR, preferencias web, apariencia, escala de texto, idioma y ahorro de datos.
- Monetización: centro web de creator program, payout, earnings, wallet, gifts, premium y ads.

## Dependencias de backend

- Para archivado/favoritos de chats se requiere `sql/migrations/20260506_chat_favorites_preferences_2026.sql`.
- Para perfil Discord-style, highlights y creator program se requiere `sql/migrations/supabase_sprint_1_to_5_final_contract_2026.sql`.
- Para wallet/gifts/premium/ads se requieren las migraciones de hardening 20260506.
- Si Supabase devuelve `exceed_storage_size_quota`, la web seguirá abriendo pero pausará Realtime/storage sensible hasta resolver cuota/billing.

## Notas honestas

- La web no puede usar ML Kit nativo; implementa detección web por señales de archivo/metadatos/texto. La detección visual profunda sigue siendo responsabilidad del móvil o de un worker backend.
- La web bloquea archivos mayores a 30 segundos antes de subir para que no se publique el original completo sin transcodificar.
- Las llamadas WebRTC web existen como módulo experimental, pero el flujo principal estable sigue estando en móvil y mensajería.

## Verificación

```bash
npm run build
```

Resultado: build exitosa, artefactos generados en `dist/`.
