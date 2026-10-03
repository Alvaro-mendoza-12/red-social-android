# Emulador: envío fuera del hilo de la pantalla y documentos

Fecha: 2026-10-02. Se mantienen versionCode 117 y versión base 9.0.20.

## Cambio implementado

El envío de chats privados ejecuta sus comprobaciones, acceso local, preparación y transporte dentro de `Dispatchers.IO`. El aviso de que el mensaje quedó guardado vuelve al contexto del llamador. Se conservan la cancelación estructurada, el bloqueo por ID, la identidad de la cuenta y la cola durable. Una función `suspend` no cambia automáticamente de hilo; esta decisión sigue la [guía oficial de corrutinas de Android](https://developer.android.com/kotlin/coroutines/coroutines-adv?hl=en).

Se añaden dos etapas a la medición: actualización optimista completada y petición del token. Cada etapa indica si se ejecutó en el hilo principal, sin registrar contenidos, nombres, credenciales ni URLs. Este cambio se aplica al repositorio de chat privado; no demuestra el rendimiento de grupos, comunidades, llamadas o LIVE.

## Resultados comprobados

La compilación de APK y APK de pruebas terminó correctamente. Pasaron **1.113 pruebas unitarias en 246 suites**, siete pruebas instrumentadas de interfaz y tres de transporte HTTP. Estas últimas usan un token inválido y comprueban la conservación de HTTP 401; no prueban una conversación autenticada entre dos dispositivos. Las cinco pruebas nuevas comprueban separación de hilos, retorno del callback y propagación de cancelaciones y errores.

La suite completa del API dio **360 aprobadas, cero fallos y dos omitidas** de 362. Las omisiones corresponden a fixtures de shell para Linux que no se ejecutan en Windows; restauración y monitorización en el servidor siguen necesitando esa comprobación.

Se instaló la APK sobre los datos existentes únicamente en el emulador Android API 35. Se trabajó en una conversación de cuentas ficticias de esta auditoría, sin operar el Motorola ni la tablet.

| Muestra nativa | Guardado local | Token disponible | Solicitud HTTP | Confirmación total |
|---|---:|---:|---:|---:|
| Sticker 1 | 8 ms | 107 ms | 279 ms | 416 ms |
| Sticker 2 | 5 ms | 282 ms | 283 ms | 584 ms |
| Sticker 3 | 7 ms | 171 ms | 280 ms | 489 ms |
| PDF de 30.372 bytes | 61 ms | 181 ms | 265 ms | 1.595 ms |

Las etapas observadas corrieron fuera del hilo principal. El panel de stickers permaneció abierto y el último envío quedó por encima del compositor. Las tres muestras nuevas mejoran las confirmaciones del [bloque anterior](49-emulador-mensajes-respuestas-y-sync-2026-10-02.md), pero las condiciones de arranque, carga del host y compilación ART también cambiaron: no se atribuye toda la diferencia únicamente al cambio de contexto ni se promete un plazo universal.

Se envió otro sticker con Wi-Fi y datos del emulador desactivados. Quedó guardado con reloj compacto, sin el texto vertical de la captura reportada. La consulta pública del historial mostró cero entradas para ese ID. Al restaurar la red, la misma fila pasó a `sent`, sin pendiente local, y el historial público mostró **una sola entrada**. La respuesta a un sticker del bloque anterior también conservó la cita tras instalar esta APK.

Para documentos se autorizó una carpeta de fixtures una vez mediante el permiso obligatorio de Android y después se seleccionó el PDF desde el catálogo interno de Novvin. La vista previa conservó el diseño de dos páginas; un deslizamiento mostró la segunda. El envío quedó confirmado en una sola fila, con URL remota y tamaño correcto. El trayecto de subida y preparación antes de la solicitud final consumió aproximadamente 1.145 ms: este documento todavía supera un segundo de confirmación total.

Los datos sanitizados y el hash de la APK están en [resultados](../evidencias-emulador-envio-background-2026-10-02/resultados.json). No se publican tokens, IDs de cuentas/conversaciones ni capturas de conversaciones completas.

## Pendientes y límites

- Falta el campo de comentario en esta vista previa de documentos; se continúa con esa implementación y su validación visual.
- La [protección de creación de grupos y comunidades](51-creacion-grupos-propiedad-2026-10-02.md) está probada localmente, pero no se ha desplegado: Tailscale SSH exige comprobar el acceso.
- Continúan las opciones restantes de mensajes, grupos/encuestas, comunidades, voz/llamadas, videos/LIVE, Social, juegos y perfil; después corresponde la paridad web y Vercel.
- No se certifica producción, 60 FPS de LIVE, arranque del invitado ni conectividad física entre redes con estas pruebas del emulador.

## Reversión

Revertir el commit de este bloque y recompilar. No hay migración de datos ni cambio de configuración del servidor.
