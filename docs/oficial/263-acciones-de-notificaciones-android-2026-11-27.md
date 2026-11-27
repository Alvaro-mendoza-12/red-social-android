# Acciones de notificaciones Android y latencia en el laboratorio Ubuntu

27 de noviembre de 2026 · La revisión tomó como base la entrega Android 191 y los contenedores activos de Ubuntu.

## Acciones desde una notificación

Marcar un chat directo como leído exige que el servidor confirme el cambio. Si falla la consulta, el aviso queda disponible para reintentar. Después de la confirmación se actualizan en una transacción los mensajes anteriores al inicio de la operación y se recalcula el contador local. El aviso más reciente se gestiona por separado si llegó otro mensaje durante la operación.

Una respuesta ya guardada en la cola local conserva su identificador si vence el plazo del trabajo de la notificación. Se solicita otro turno para enviar esa misma respuesta. Si vence antes de guardarla, se informa el fallo. Las respuestas y las confirmaciones de chats directos usan la sesión capturada al iniciar la acción. El silencio temporal se guarda para esa cuenta.

Pasaron 33 pruebas dirigidas en seis clases con `testReleaseUnitTest`. Cubrieron confirmación de lectura, almacenamiento en Room, cambio de cuenta, preferencias de silencio y plazos de las respuestas. Cinco casos prueban directamente el comportamiento de la respuesta rápida. Este cambio corresponde al código posterior a la [APK 191](262-android-191-subidas-y-verificacion-2026-11-27.md).

## Carga acotada y observabilidad

Se usaron diez cuentas ficticias, cinco conexiones WebSocket de recepción y un máximo de 16 operaciones en curso. La carga combinó feed, perfiles, mensajería y notificaciones a 12 llegadas por segundo. PostgreSQL 16.15, Node.js 22.23.4, la API candidata y el gateway corrieron en contenedores con límites de CPU y memoria. El laboratorio compartió un espacio de red sin acceso externo. Las pruebas usaron datos creados en el propio laboratorio.

| Escenario | Segundos | Llegadas previstas y despachadas | Solicitudes/s | Mensajes confirmados a tiempo | P50 / P95 / P99 de la ventana con mayor P95 |
|---|---:|---:|---:|---:|---:|
| PostgreSQL con 0,75 CPU | 46 | 540 / 526 | 11,4 | 129 / 129 | 1.171 / 2.879 / 3.048 ms |
| Repetición con 0,75 CPU | 75 | 900 / 896 | 11,9 | 225 / 225 | 618 / 1.673 / 2.145 ms |
| PostgreSQL con 1,5 CPU | 75 | 900 / 865 | 11,5 | 213 / 215 | 1.379 / 3.382 / 3.559 ms |
| API con telemetría y PostgreSQL con 0,75 CPU | 8 | 900 / 77 | 9,6 | 14 / 18 | 1.336 / 3.755 / 3.876 ms |

Todas las solicitudes despachadas devolvieron HTTP 2xx. El generador dejó llegadas sin despachar en los cuatro ensayos. En dos también venció el plazo de recepción de mensajes WebSocket. El último ensayo activó el corte de protección. Un muestreo capturó las 12 conexiones de PostgreSQL ocupadas en la API y cuatro peticiones esperando. La variante con más CPU de PostgreSQL mantuvo la degradación. Las cifras describen este entorno acotado y dejan abierta la capacidad real de producción y la causa de los picos.

La API y el gateway activos carecen de la ruta de métricas que está versionada en Git. Se preparó la imagen de API `novvin-audit-api:telemetria263-20261127` sobre la candidata de subidas TUS para poder medir el consumo, la espera de PostgreSQL y el bucle de eventos sin sustituir el código activo. En un contenedor aislado se verificaron el rechazo sin credenciales, la respuesta autenticada y el formato Prometheus. El gateway sigue pendiente de reconciliación. La imagen de API queda pendiente de promoción mientras `/ready` siga en 503 por las firmas de ClamAV.
