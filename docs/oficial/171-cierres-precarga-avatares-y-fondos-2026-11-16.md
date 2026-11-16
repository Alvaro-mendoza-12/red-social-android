# 171 — Cierres, precarga, avatares y fondos

Revisión en curso, 16 de noviembre de 2026. Este avance no cierra la auditoría integral ni acredita una entrega de producción.

## Evidencia conservada

La evidencia de esta continuación está fuera de `app/build`, en `D:/Novvin-Entregas/evidencias/2026-11-16-mobile171/`. Los recibos distinguen fuentes, resultados anteriores al parche, pruebas de reparación y ensayos separados en Ubuntu. No se guardaron credenciales de invitado ni secretos del servidor.

El Moto g04 conectado conserva `9.1.40-debug/180`, actualizado a las 10:20:04 locales. Su búfer histórico registra:

| Hora local | Registro | Diagnóstico y alcance |
| --- | --- | --- |
| 10:23:21 | `IllegalArgumentException`, búsqueda relacionada, grafo con inicio `messages` | La búsqueda se solicita al controlador de pestañas; el destino pertenece al grafo raíz. |
| 10:24:57 y 10:27:59 | `SimpleCache.startFile`, desde `VideoCache.preloadStart` | La precarga puede cerrar su `DataSource` desde otro hilo durante `open/read`. |
| 10:45:57 | `ForegroundServiceDidNotStartInTimeException`, `VoiceForegroundService` | La operación de detener llama a `startForegroundService` con `ACTION_STOP`. |
| Varias | `LOW_MEMORY`, `MemPressure`, también en segundo plano | Son terminaciones distintas. No se ha demostrado que las correcciones siguientes eliminen la presión de memoria. |

`device-crash-baseline/result.json` conserva huellas del registro de este paquete/UID, motivos de salida y versión. Es una captura de registros existentes: no representa una reproducción nueva ni acredita otros modelos.

## Correcciones y comprobaciones

1. **Navegación de vídeos.** Tres argumentos de `HomeScreen` pasan el controlador raíz a `VideoFeedScreen`. No se modifica la reconciliación de playlist de 165. `HomeVideoNavigationTest` reproduce la excepción del grafo anterior y comprueba búsqueda codificada y regreso conservando la entrada de la pestaña.
2. **Avatares de vídeo.** Los consumidores de avatar y edición usan repetición nativa de Media3. Se mantiene el helper genérico de reproducción única para los recorridos que la piden explícitamente, además de visibilidad, pausa y liberación. La instrumentación espera dos transiciones de repetición antes de comprobar segundo plano y pausa manual; todavía no se ejecutó en esta continuación.
3. **PlayMatch.** No se ofrecen decisiones sobre vencimientos inválidos o ya pasados; los payloads antiguos sin fecha conservan su comportamiento. Tras `MATCHED` se actualizan conversaciones. Ante `MATCH_NOT_PENDING` se muestra un mensaje comprensible y se recarga la actividad. Una lectura posterior fallida no revoca una confirmación del servidor. Las referencias conservadas reproducen dos fallos en cinco casos de actividad y dos fallos en tres casos del ViewModel.
4. **Selector genérico.** Los dos consumidores de `InAppMediaPicker` ocultan pie de mensaje y opción de una sola visualización, usando título y descripción de selección. No se eliminan esas opciones del compositor de mensajes. El icono de confirmación y la comprobación visual del flujo siguen pendientes.
5. **Carrera de precarga.** La referencia `crash-races-baseline-v2` reproduce cierre concurrente durante apertura y lectura con `VideoCache` y `CacheDataSource` reales, reemplazando únicamente el transporte. La reparación cancela las solicitudes HTTP de esa precarga y deja `close` en el hilo del lector. Conserva cuotas, permisos, caché y presupuesto; no cancela el dispatcher compartido. Las excepciones especulativas utilizan el recorrido recuperable de E/S. La repetición fue interrumpida y sigue pendiente.
6. **Salida de voz.** `stopVoiceForegroundService` usa `stopService`, seguro también cuando el servicio ya no está iniciado. Se conservan `onDestroy`, la limpieza y la retirada de la notificación. La referencia reproduce la orden incorrecta en API 24, 26 y 34. La repetición fue interrumpida y sigue pendiente; no acredita todavía una sala física completa.
7. **Formato de fondo.** `ChatBackgroundLayer` usa `Fit` para conservar todo el resultado del recorte elegido, evitando un segundo recorte al aplicarlo. La nueva instrumentación compara bordes y proporciones de cuatro formatos con imágenes sintéticas; aún no está compilada ni ejecutada.

La referencia inicial `crash-races-baseline` falló al compilar un doble de pruebas que devolvía `Flow` en vez de `StateFlow`; ejecutó cero casos. Se conservó y corrigió ese doble antes de ejecutar v2. V2 registra cinco fallos de aserción en cinco casos, sin errores ni omisiones. No se atribuyen cinco defectos distintos: son dos intercalaciones de caché y un contrato de parada repetido en tres API.

Antes de los cambios de caché, voz y formato, `full-repair` aprobó **3.083 casos en 553 clases**, sin fallos, errores ni omisiones, con 2.063 entradas públicas, cuatro huellas privadas y HEAD estables. Es JVM/audit sobre versión 181, no una APK ni una comprobación del código posterior.

El proceso de `crash-races-repair` dejó de existir antes de generar `result.json`; también desaparecieron su handle, cliente y daemon original. Se conserva el log parcial y una observación independiente en `publication/interruption-observed.json`, que comprueba las fuentes sin cambios antes de los commits. Hubo un `OutOfMemoryException` en una consola de Windows, pero la causa concreta de la interrupción Gradle no está determinada. No se inventa un código de salida ni un resultado de pruebas. Los daemons nuevos del IDE/VSCode no se cerraron.

## Avances anteriores guardados junto a esta continuación

- **160/169, recuperación.** Se conserva la confirmación remota aunque falle la continuación de sesión o la limpieza local, con guardas de cuenta/visita y lectura estricta del recibo. `2026-11-16-mobile169-full-durable-v3` conserva 3.072 casos/550 clases y `full-repair` vuelve a incluir esos consumidores: nueve controles de API y dieciséis de servicio. La activación del endpoint169 requiere terminar sus consumidores web; no se despliega por este guardado en Git.
- **166, fotos.** Se guardan la galería móvil, el ajuste completo de imágenes y el ensayo instrumental. La QA166 y sus resultados físicos son históricos; no contienen171.
- **167/168, web.** Se guardan la precisión del último mensaje, la proyección de la respuesta enviada, la galería acotada y las reacciones. Sus comprobaciones históricas no se presentan como una ejecución nueva ni como prueba de despliegue actual.

El propietario reiteró que todo el avance debe quedar comprometido. Se guardaron59rutas de código y pruebas en seis commits de main: `cfc8035a`, `09253a4d`, `9ac37dd9`, `652b3979`, `4d927768` y `6d368511`. El bloque de caché/voz/fondo se identifica expresamente como `wip`, con validación de reparación pendiente. La evidencia y las huellas se guardan en un commit documental posterior. La sincronización y publicación remota se comprobarán separadamente.

## Compartidos: diagnóstico separado

En el perfil público solicitado por el propietario, una imagen anónima responde 401 tanto en el host heredado como en el dominio público. Con acceso de invitado autorizado, el objeto responde 200. La muestra original pesa 9.506.915 bytes; su variante de 1.080 px/q78 pesa 168.034 bytes. Esto compara tamaño transferido de esa imagen pública; no mide el tiempo total de carga del feed Android, que ya tiene una fábrica de variantes.

El avatar actual es un MP4. El lector anterior intentaba tratarlo como imagen sin autorización y el proceso del servidor tampoco podía alcanzar su propia salida HTTPS. La ruta interna directa de API devolvía una concesión `X-Accel-Redirect` sin cuerpo; es el edge interno quien sirve los bytes.

El lector candidato valida primero la referencia propia, utiliza únicamente identidad efímera de invitado y pide un póster acotado del avatar. Mantiene rechazo de objetos privados, orígenes/rutas no permitidos, redirecciones, tamaños y tipos no admitidos. Conserva los contratos de almacenamiento y la autorización existente.

`share-reader-edge-repair` aprueba **19/19** controles Node. `share-reader-live-shadow/20261116T172554653778Z` ejecuta los dos módulos candidatos solamente en memoria, en un proceso Node separado dentro del contenedor activo, con sesión SQL de solo lectura. Obtiene el póster y produce una tarjeta diferente con la imagen real. Las fuentes del proceso API, su imagen y configuración permanecen sin despliegue. La galería HTML todavía enlaza objetos que requieren autorización y el encabezado aún muestra una tarjeta sobredimensionada: esos puntos siguen abiertos.

## Referencias técnicas

- [Android: detener un servicio en primer plano](https://developer.android.com/develop/background-work/services/fgs/stop-fgs): otro componente puede detenerlo mediante `stopService`; el sistema retira su notificación.
- [Media3 1.11.0: CacheDataSource](https://github.com/androidx/media/blob/1.11.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/CacheDataSource.java) y [SimpleCache](https://github.com/androidx/media/blob/1.11.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/SimpleCache.java): contratos de lectura, escritura y bloqueo usados en la investigación. La carrera se acredita con los registros y la prueba local, no únicamente con estas referencias.

## Pendientes de entrega

- Repetición de las carreras corregidas y controles reales de admisión/lectura; compilación e instrumentación actualizadas.
- Ensayo físico de navegación, gestos con desplazamiento vertical bloqueado, avatares, fondos y carga multimedia. Un verde JVM no cierra esos recorridos.
- Investigación adicional de memoria, sesiones prolongadas y diferencias entre dispositivos.
- Confirmación propia y ajena de decisiones PlayMatch; vencimiento durante una pantalla ya abierta.
- Imágenes y diseño del perfil público, apertura del enlace móvil, integración web y despliegue de API.
- APK/AAB nuevos, firma, tamaño y comprobaciones de release. La QA166 conservada es histórica y no incluye esta continuación. No hay comparación nueva de tamaño ni mejora temporal acreditada.
- Publicación de correcciones en ambas ramas estables, conservando la reserva de índices/publicación del otro asistente y todos los cambios ajenos. La igualdad de los árboles comprometidos anteriores no implica igualdad de estos cambios locales.

No se eliminaron dependencias ni recursos por su nombre o aparente antigüedad. La limpieza y las actualizaciones amplias continúan sujetas a comprobar consumidores y regresiones. Las revisiones 160 y 166–169 conservan sus alcances y evidencias independientes.
