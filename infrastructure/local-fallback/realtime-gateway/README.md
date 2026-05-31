# Realtime Gateway (Ubuntu local-fallback)

Servicio WebSocket para eventos realtime sin Supabase.

## Health
- `GET /health`

## Autenticación
El cliente debe enviar `Authorization: Bearer <JWT>` durante el handshake WebSocket.

JWT se valida con:
- `JWT_SECRET` (obligatorio)
- `JWT_ISSUER` (opcional, default `conectate-local-fallback`)
- `audience`: `conectate-clients`
- `issuer`: `JWT_ISSUER`
- `sub`: `userId`

## Suscripción
Al conectar, el cliente puede enviar mensajes JSON:

```json
{ "type": "subscribe", "channel": "user:<userId>" }
```

El server responde:
```json
{ "type": "subscribed", "channel": "user:<userId>" }
```

Actualmente el gateway implementa una allowlist básica:
- `user:<selfUserId>` está permitido
- otro tipo de canales se pueden extender luego

## Publicación (MVP)
Endpoint HTTP interno para publicar eventos desde el backend HTTP:

- `POST /internal/publish`
- body JSON: `{ "channel": "<channel>", "payload": { ... } }`

El gateway emitirá:
```json
{
  "type": "event",
  "channel": "<channel>",
  "payload": { ... },
  "ts": "..."
}
```

## Puerto
Default: `3002`
- `PORT` env var
- mapeado en `docker-compose.yml` con `FALLBACK_REALTIME_PORT` (default 3002)
