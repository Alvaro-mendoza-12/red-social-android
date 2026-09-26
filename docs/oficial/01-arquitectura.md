# 01 — Arquitectura general

Conéctate/NOVVIN está compuesto por varios clientes y un backend propio. La
arquitectura objetivo favorece independencia del proveedor, operación en mini PC
y un origen HTTPS estable aun detrás de CGNAT.

## Vista general

```text
Usuarios Android
    |
    | HTTPS / WebSocket / WebRTC signaling
    v
Hostname HTTPS estable (api.novvin.example)
    |
    v
Cloudflare Tunnel nombrado
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

### Política WebRTC del cliente web

`WebRtcPeerSession.ts` concentra la máquina de estado por peer para llamada
privada, sala de voz y LIVE. El transporte Socket.IO permanece fuera del módulo:
la sesión sólo coordina SDP/ICE y entrega ofertas al consumidor correspondiente.

Invariantes:

- negociación perfecta con rol `polite/impolite`, rollback e `ignoreOffer`;
- una sola operación SDP activa por peer;
- `onnegotiationneeded` obligatorio cuando se añade cámara o pantalla;
- ICE restart después de `failed` o de un `disconnected` sostenido;
- máximo tres intentos con backoff, cancelados al volver a `connected`;
- cierre y salida de participante cancelan timers y descartan estado pendiente.

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

## Origen público y descubrimiento

La ruta primaria de producción es un hostname HTTPS fijo —por ejemplo
`https://api.novvin.example`— conectado a un **Cloudflare Tunnel nombrado**. El
token del túnel sólo reside en Ubuntu, en un archivo de permisos `0600`, y
systemd lo inicia y supervisa.

El descubrimiento por DuckDNS TXT firmado (`novvin1`) sigue soportado sólo para
una transición controlada o recuperación. En ese modo Android/web verifican
firma ECDSA P-256, expiración, URL raíz HTTPS y capacidades de `/ready` antes de
usar una ruta dinámica. Una URL plana o sin firma válida se rechaza siempre.

El `FALLBACK_API_URL` empaquetado es confianza explícita. No se debe cambiar a
una URL temporal `trycloudflare.com`, ni hacer que el discovery dinámico sea el
camino normal de login, multimedia o WebSocket.

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

### Plano administrativo

La autorización administrativa es un módulo independiente de UI y de insignias de
perfil. `admin-capabilities.js` traduce `none/view/write/full` más la marca de
propietario a capacidades nominales. Las rutas de moderación, contenido compatible,
música, catálogo PlayMatch, pagos y salud del sistema consultan esas capacidades en
la API. Android y web consumen el mismo contrato mediante
`GET /v1/data/admin/access`, pero nunca son la barrera de seguridad.

El token operativo `x-admin-token` pertenece al plano de sincronización del servidor;
no equivale a un rol humano. La matriz y el proceso de alta/baja están en
`docs/empresa/06-roles-y-capacidades-administrativas.md`.

## Zonas de riesgo técnico

1. **Origen público sin terminar de migrar**
   - Si el servidor sigue en un Quick Tunnel, una rotación de URL puede
     interrumpir API, sesiones y multimedia. La mitigación obligatoria es el
     túnel nombrado con hostname estable.

2. **CGNAT**
   - Afecta WebRTC y acceso remoto.

3. **Tunnel/edge**
   - El túnel nombrado cubre HTTPS y WebSocket, pero no sustituye TURN público
     para media WebRTC entre redes distintas.

4. **TURN gratis**
   - Puede tener límites o variabilidad.

5. **Mini PC como único servidor**
   - Es un single point of failure.
   - Requiere backups, watchdog y anti-suspensión.

6. **Módulos heredados**
   - Algunas rutas, nombres o flujos pueden conservar suposiciones de Supabase/VPS.

