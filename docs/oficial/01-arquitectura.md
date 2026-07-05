# 01 — Arquitectura general

Conéctate está compuesto por varios clientes y un backend propio. La arquitectura actual favorece independencia del proveedor, operación casera con mini PC y mecanismos de discovery para sobrevivir a CGNAT.

## Vista general

```text
Usuarios Android
    |
    | HTTPS / WebSocket / WebRTC signaling
    v
DuckDNS TXT discovery
    |
    v
Cloudflare Quick Tunnel URL actual
    |
    v
Nginx edge en mini PC
    |
    +--> API Node/Express
    |       +--> PostgreSQL
    |       +--> Storage local Docker/external disk
    |       +--> Firebase Cloud Messaging
    |
    +--> Realtime Gateway
    |
    +--> Signaling Server Socket.IO/WebRTC
            +--> STUN/TURN externo o coturn público si existe
```

## Clientes

### Android

Cliente principal de producción.

Ruta:

```text
app/
```

Responsabilidades:

- login/registro/migración de contraseña;
- navegación principal;
- mensajes 1 a 1;
- grupos;
- comunidades;
- videos;
- social/feed;
- perfil;
- notificaciones;
- llamadas;
- salas de voz;
- cámara/micrófono/pantalla;
- cache local y renderizado móvil.

### Web

Cliente secundario.

Ruta:

```text
Conectate-web/
```

Responsabilidades esperadas:

- login con backend Ubuntu;
- acceso a módulos web;
- futura paridad progresiva con Android;
- posible “web de reuniones” o cliente complementario.

No asumir paridad completa sin pruebas.

## Backend Ubuntu

Ruta:

```text
infrastructure/local-fallback/
```

Servicios principales:

- `db`: PostgreSQL 16.
- `api`: API Node/Express.
- `edge`: nginx reverse proxy.
- `realtime-gateway`: gateway realtime.
- `signaling`: Socket.IO/WebRTC signaling.
- `coturn`: TURN local opcional, no suficiente si hay CGNAT sin IP pública.

## Servidor de señalización

Ruta:

```text
signaling-server/
```

Responsabilidades:

- salas Socket.IO;
- llamadas privadas;
- salas de voz;
- intercambio de offer/answer/candidates WebRTC;
- entrega de credenciales ICE;
- integración con API Ubuntu;
- control de rate limit y orígenes.

## Base de datos

PostgreSQL vive en Docker y debe quedarse en SSD.

Razón:

- menor latencia;
- más IOPS;
- menos riesgo de cortes por USB;
- recuperación más rápida.

Los discos externos deben usarse para:

- backups;
- media/user uploads si están montados de forma estable;
- exports;
- cachés regenerables.

## Storage

El compose actual monta `conectate-fallback-storage` en:

```text
/data/storage
```

dentro del contenedor API.

La recomendación para escalar storage es usar rutas configurables:

```text
STORAGE_ROOT=/data/storage
MEDIA_ROOT=/mnt/almacenamiento/uploads
BACKUP_ROOT=/mnt/conectate-backup/conectate-backups
```

No usar rutas absolutas hardcodeadas en el código. Toda ruta debe salir de variables de entorno o configuración.

## Discovery público

La app no debe depender de una URL fija `trycloudflare.com`.

Modelo correcto:

1. El servidor levanta Cloudflare Quick Tunnel.
2. El túnel genera una URL temporal.
3. Un script publica esa URL en DuckDNS TXT.
4. La app resuelve `BACKEND_DISCOVERY_HOST`.
5. La app usa la URL activa.

Esto es necesario por CGNAT.

## Separación de módulos

El sistema debe mantenerse modular:

- autenticación;
- usuarios/perfiles;
- mensajes 1 a 1;
- grupos;
- comunidades;
- videos;
- social/feed;
- notificaciones;
- llamadas;
- salas de voz;
- WebRTC;
- storage;
- administración;
- backups/operación.

Regla: una falla en llamadas no debe tumbar mensajes; una falla en storage no debe tumbar login; una falla del discovery no debe borrar configuración local.

## Zonas de riesgo técnico

1. **URL pública dinámica**
   - Si la app conserva URL antigua, falla todo.

2. **CGNAT**
   - Afecta WebRTC y acceso remoto.

3. **Quick Tunnel**
   - Útil y gratis, pero no equivalente a dominio/túnel permanente con SLA.

4. **TURN gratis**
   - Puede tener límites o variabilidad.

5. **Mini PC como único servidor**
   - Es un single point of failure.
   - Requiere backups, watchdog y anti-suspensión.

6. **Módulos heredados**
   - Algunas rutas, nombres o flujos pueden conservar suposiciones de Supabase/VPS.

