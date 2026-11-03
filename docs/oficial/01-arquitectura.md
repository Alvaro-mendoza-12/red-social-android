# 01 — Arquitectura general

Conéctate/NOVVIN está compuesto por varios clientes y un backend propio. La
arquitectura objetivo favorece independencia del proveedor, operación en mini PC
y un origen HTTPS estable aun detrás de CGNAT.

Estado de referencia: 3 de noviembre de 2026. La [revisión 101](101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md) mantiene Android abierto y la [operación 102](102-r2-replica-y-entrega-multimedia.md) identifica imágenes desplegadas, réplica R2 y entregas. El runbook de Cloudflare Tunnel nombrado conserva una alternativa operativa; no representa el ingreso activo.

## Vista general

```text
Usuarios Android
    |
    | HTTPS / WebSocket / WebRTC signaling
    v
Hostname HTTPS estable (conectate.tail9b267c.ts.net)
    |
    v
Tailscale Funnel
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
            +--> Redis Streams para señalización
            +--> STUN/TURN

Storage Ubuntu --> réplica R2 privada <-- Worker HTTPS <-- lectura multimedia autorizada
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
NoWing-web/
```

Responsabilidades esperadas:

- login con backend Ubuntu;
- acceso a módulos web;
- mensajería, Social, videos, perfiles y comunicaciones según sus contratos;
- revisión integral posterior al móvil.

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

Ubuntu conserva la fuente de los archivos y los replica en R2 privado. El
Worker sirve lecturas autorizadas y rangos; Android configura esa dirección en
`MEDIA_EDGE_ORIGIN` y conserva el retorno a Ubuntu. Las imágenes activas, el
alcance de las pruebas y la diferencia entre el marcador base de `/ready` y la
imagen parcheada están en la operación 102. No se infiere el cierre de todos
los recorridos de reproducción a partir de una lectura de rango aprobada.

La recomendación para escalar storage es usar rutas configurables:

```text
STORAGE_ROOT=/data/storage
MEDIA_ROOT=/mnt/almacenamiento/uploads
BACKUP_ROOT=/mnt/conectate-backup/conectate-backups
```

No usar rutas absolutas hardcodeadas en el código. Toda ruta debe salir de variables de entorno o configuración.

## Origen público y descubrimiento

La ruta primaria documentada y comprobada es
`https://conectate.tail9b267c.ts.net` mediante **Tailscale Funnel**. Los cambios
de dominio o proveedor requieren contrastar API, WebSocket, autorización y
enlaces instalados antes de sustituir esa configuración.

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

1. **Disponibilidad del ingreso público**
   - Tailscale Funnel y el servidor propio forman parte de la ruta activa.
     Un cambio de ingreso requiere comprobar las sesiones y los clientes ya
     instalados; una URL temporal no sustituye su configuración estable.

2. **CGNAT**
   - Afecta WebRTC y acceso remoto.

3. **Tunnel/edge**
   - El ingreso HTTPS y WebSocket no sustituye TURN público
     para media WebRTC entre redes distintas.

4. **TURN gratis**
   - Puede tener límites o variabilidad.

5. **Mini PC como único servidor**
   - Es un single point of failure.
   - Requiere backups, watchdog y anti-suspensión.

6. **Módulos heredados**
   - Los contratos de importación y sus consumidores deben sustituirse juntos.
     Un nombre antiguo no demuestra que se use otro backend.

Redis Streams ya está integrado en señalización. La configuración parte de
una réplica; antes de ampliarla deben comprobarse membresías, recuperación,
servicios auxiliares y cualquier estado que permanezca local al proceso.
Su presencia no acredita que toda la plataforma soporte varias réplicas.

