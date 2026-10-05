# Continuación de los trabajos móviles y de diagnóstico

**Fecha:** 4 de octubre de 2026. **Rama:** `feature/live-gifts`.

Se retomaron los pendientes de los chats «Evalúa producción y ecosistema Nowin» y «Analiza debilidades de live-gifts», además de implementar Sorteos. Sus cambios previos y evidencias se conservaron. Este documento registra las comprobaciones nuevas y distingue los productos medidos.

## 1. Punto de partida

Los documentos [67](docs/oficial/67-inicializacion-de-novedades-home-2026-10-04.md) y [68](docs/oficial/68-editor-movil-musica-letras-y-duracion-2026-10-04.md) contienen la implementación previa del editor/importación LRC, inicio de Home y diagnóstico musical. El APK `fed1546` tenía 1.294 unitarias y 78 casos físicos aprobados según esas evidencias, con dos fallos previos de cierre de invitación que permanecían sin causa demostrada. Esas cifras no se atribuyen al APK posterior con Sorteos.

## 2. Arranque controlado del APK anterior

Se ejecutó el protocolo preparado por la otra IA antes de instalar otro APK, con Gradle inactivo. Se verificó el mismo SHA-256 instalado antes/después y la identidad del directorio de datos. No hubo desinstalación, limpieza de datos, trazado ni recompilación.

| Muestra | Procedimiento | Estado emitido por Android | TotalTime |
| --- | --- | --- | ---: |
| 1 | Force-stop, confirmar proceso ausente y abrir | COLD | 5.881 ms |
| 2 | Recrear Activity conservando proceso | WARM | 3.071 ms |
| 3 | Force-stop, confirmar proceso ausente y abrir | WARM | 4.492 ms |

La tercera clasificación se conserva tal como la emitió Android aunque el proceso estuvo ausente antes de abrir. Android no emitió `ThisTime`; no se inventa. Estos tiempos corresponden al primer fotograma informado por el framework, no a contenido Social listo ni al sonido. Tres muestras debug no acreditan un SLA ni aíslan causalidad del cambio Lazy de Home. No se vaciaron cachés del sistema/disco. El protocolo anterior e729365 utilizaba `am start -S` para todas las muestras y no ofrece una comparación causal equivalente.

Evidencia nueva: [VALIDACION_CONTINUIDAD_ADICIONAL.json](VALIDACION_CONTINUIDAD_ADICIONAL.json).

## 3. Comparación de la ruta de audio en Linux

Se recuperó SSH mediante la dirección Tailscale verificada del servidor y se ejecutó `scripts/diagnose-storage-response-latency.py` por stdin, sin instalar scripts ni modificar contenedores. La conexión LAN antigua agotó su tiempo. Se consultó solo el audio sintético propio del diagnóstico previo.

Tres lecturas por ruta, conexión HTTP/1.1 nueva, lectura máxima de 4.097 bytes. La API delegó el archivo mediante X-Accel-Redirect con cuerpo vacío: mide autorización/metadatos, no entrega de audio. Nginx y ruta pública respondieron 206 con 4.096 bytes válidos. El diagnóstico verificó el contenedor API en la revisión `f42a349`.

| Ruta desde el servidor | Espera hasta encabezados, muestras 1/2/3 | Alcance |
| --- | --- | --- |
| API loopback | 5,217 / 19,582 / 2,796 ms | Autorización y metadatos; `app;dur` 3,9 / 18,1 / 1,6 ms. |
| Nginx loopback | 3,139 / 8,757 / 2,878 ms | Lectura parcial del objeto aprobado. |
| URL pública desde Linux | 33,990 / 7,065 / 3,476 ms | Ruta vista desde el propio servidor, no red del Motorola. |

Las nueve muestras fueron válidas. No se reprodujo la espera de 3–5 segundos observada antes en Android. Esto no demuestra que la demora móvil esté corregida: cliente, red, conexiones HTTP/2, caché y momento de medición son diferentes. No se cambió un búfer o proxy basándose en una causa no demostrada. Falta una captura móvil simultánea que distinga envío, encabezados, preparación y avance de audio.

Las nueve pruebas offline del script también pasaron. Los registros publicables no incluyen URL del archivo, credenciales, cabeceras privadas ni inventario completo de Docker.

## 4. Contraste del selector musical

Se corrigió `MomentMusicPickerDialog.kt`: cuando solo existe una duración válida, su rótulo fijo utiliza `MaterialTheme.colorScheme.onSurface`, evitando heredar el color atenuado del botón deshabilitado. Se conserva el comportamiento: no abre un selector si la duración es fija. El APK actualizado compiló correctamente junto con Sorteos y pasó 1.300 unitarias. En la tanda física final pasaron los doce casos del selector y los dos de letras, incluidos duración fija, texto grande, reproducción y confirmación. No se presenta una medición cuantitativa del contraste.

## 5. Llamadas y pendientes

Se conserva la incidencia `theRingTimeoutAlsoClosesTheAnswerScreen`: dos tandas anteriores cerraron la Activity sin comprobar la marca esperada. Otra tanda, el caso aislado y el diagnóstico con JDWP pasaron. No se cambió el límite de la prueba, el temporizador de 45 segundos ni el producto de llamadas para ocultar esos fallos.

El caso se repitió sobre el APK actualizado, en el teléfono y sin depurador: pasó junto con los otros cinco casos de llamadas locales en la tanda final de 26/26 pruebas, sin omisiones. La causa de los fallos anteriores sigue sin demostrarse. Una llamada entre dos personas, FCM real y redes diferentes sigue requiriendo ese escenario; no puede acreditarse con las invitaciones locales de instrumentación.

El acceso SSH recuperado permite continuar el diagnóstico. La compilación y las pruebas del APK actualizado se completaron; no se desplegaron el candidato musical ni los archivos de Sorteos a producción. El servidor consultado conserva la versión previa descrita arriba. AAB firmado, paridad web, FPS sostenidos en LIVE y auditoría restante del producto siguen pendientes.

Evidencia final del APK actualizado y del dispositivo: [VALIDACION_SORTEOS.json](VALIDACION_SORTEOS.json). Diagnóstico de arranque/audio del APK anterior: [VALIDACION_CONTINUIDAD_ADICIONAL.json](VALIDACION_CONTINUIDAD_ADICIONAL.json). Son productos y escenarios diferentes; sus cifras se mantienen separadas.

## 6. Estado posterior de los once puntos

Los apartados anteriores conservan el checkpoint previo a los cambios/despliegues posteriores. El estado vigente se encuentra en [AUDITORIA_CIERRE_11_PUNTOS.md](AUDITORIA_CIERRE_11_PUNTOS.md), [informe 70](docs/oficial/70-medios-arranque-y-avisos-validacion-2026-10-04.md) y [evidencia consolidada](docs/oficial/evidencias/cierre-11-2026-10-04.json). El backend actual es `live-gifts-cierre11-a1299ad`; no la imagen previa citada en el apartado 3.

Con Motorola y tablet pasaron llamadas con TURN, voz y LIVE con pistas reales, cámara/editor/enlaces 31/31 por equipo. Después de retirarse el Motorola, en tablet pasaron seis entregas integradas de cámara, perfiles/foto/música reales y tres muestras LIVE con emisor separado. El arranque optimizado QA pasó 10/10 muestras verificadas: bandeja local fría 513–606 ms, caliente 278–296 ms. Falta medir el APK principal optimizado en el Motorola con sus datos existentes.

La repetición del aviso administrativo físico se detuvo antes de enviar por renovación SSH de Tailscale; se conserva la prueba FCM real previa en emulador. La regresión Android completa tiene 1.316 casos correctos y lint cero errores/348 advertencias. **No se certifica aún producción**: los límites y restantes comprobaciones se detallan en la matriz vigente. Se conservaron aplicaciones/sesiones humanas y trabajo ajeno.
