# Validación en teléfono físico: Moto g04

**Fecha:** 3 de octubre de 2026, America/Bogota. **Rama:** `feature/live-gifts`.

**Dispositivo:** Moto g04, Android 14/API 34, pantalla 720 × 1612, densidad 280 dpi. El usuario conectó y autorizó expresamente las pruebas por USB. Medios remotos por WiFi; Encuestas por HTTPS al fixture local exclusivo mediante USB reverse.

**Aplicación:** `conectate.com`, `9.0.20-debug`, código 117; código del producto `07e4989`. APK SHA-256 `86b89eb3cc98b07e950b077e463e82d53c394ad1eb2f03b533cb193731db3eac`. Se actualizó la instalación debug compatible mediante `install -r -t`; no se desinstaló la aplicación ni se borraron sus datos. El identificador de su directorio privado permaneció igual antes y después.

## Resultados

| Comprobación | Resultado | Alcance |
|---|---|---|
| Interfaz nativa | 39 correctas, 88,235 s, sin omisiones | Encuestas por HTTPS real, contratos de documentos, Social/paginación, avisos, música y regresión anterior. Paquete de pruebas `2a2b9bf`. |
| Medios remotos | Cinco correctas, 16,201 s, sin omisiones | Audio de perfil, ciclo de vida, fragmento de comunidad, foto y error con reintento. Archivos propios de QA; timeout de diez segundos intacto. |
| Llamadas/RTC y composición LIVE | Última ronda: 18 correctas, 80,439 s | Invitaciones locales ficticias, cancelación, cierre de recursos nativos y distribución de controles LIVE; no es una llamada entre dos usuarios reales. |
| Captura tras entrega del fotograma | Siete correctas, 15,920 s | Helper exclusivo de pruebas `bfacfd5`; no cambia el APK del producto. |

Las pruebas focalizadas y repetidas se superponen con las suites; no se suman como verificaciones independientes adicionales. El resultado de la primera ronda de llamadas se conserva y se explica abajo.

**Medición de medios de esta ejecución:** foto 1.139 ms, audio de perfil 563 ms, preescucha comunitaria 1.191 ms. No demuestra esa latencia con toda canción, red o teléfono ni promete que todo se reproduzca en menos de un segundo.

**Arranque:** tres solicitudes con `am start -S -W`, conservando datos. Android clasificó dos como COLD (4.870 y 4.395 ms) y una como WARM (4.310 ms). Es tiempo hasta el primer fotograma que registra el sistema; no mide cuándo el feed resulta utilizable. PSS observado: 274.822 KiB. No se declara un objetivo de arranque cumplido ni se atribuye causalidad a una clase sin traza de rendimiento.

## Incidencia inicial de la prueba de vencimiento de llamada

La primera suite adicional terminó con 17 casos correctos y uno fallido en 40,548 s. `theRingTimeoutAlsoClosesTheAnswerScreen` observó la destrucción de la pantalla sin encontrar la marca de cancelación. Toda la suite terminó antes de los 45 segundos del temporizador del servicio; no acredita que el temporizador haya vencido.

El servicio ya contenía la escritura de esa marca antes de cerrar. La prueba aislada pasó en 47,146 s y la suite completa se repitió: 18/18 correctas en 80,439 s. En esa repetición se registraron eventos de Android de creación/cierre y el cierre de la invitación tras el plazo esperado. No se modificó el código del producto para obtener esos resultados ni se ampliaron los plazos.

La primera ronda carecía de la traza de actividades necesaria para explicar su destrucción prematura. Su causa sigue sin establecerse; no se presenta como un fallo productivo corregido ni se atribuye a una acción del usuario. La evidencia conserva el fallo inicial, la prueba aislada y la repetición completa.

## Revisión visual coordinada

Ambas IAs revisaron los PNG originales de 720 × 1612, uno por uno. La tarjeta con texto ampliado, etiqueta y solo música conservan la barra superior, tres opciones y los botones inferiores sin solaparse. Las imágenes contienen únicamente la canción ficticia de QA.

- [Catálogo](capturas/moto-g04-07e4989/qa-music-catalog.png).
- [Tarjeta con texto ampliado](capturas/moto-g04-07e4989/qa-music-selection-large-text.png).
- [Etiqueta](capturas/moto-g04-07e4989/qa-music-label.png).
- [Solo música](capturas/moto-g04-07e4989/qa-music-audio-only.png).

## Evidencia y límites de entrega

[Resultados, hashes y rondas](VALIDACION_MOTO_G04_07E4989.json). Los logs privados no se publican íntegramente y la evidencia no incluye credenciales ni el número de serie del teléfono.

Esta ejecución acredita el comportamiento de los casos probados en este teléfono y APK debug. Quedan separados la incidencia intermitente descrita, una llamada real de dos participantes, la entrega física FCM completa, el rendimiento sostenido y la verificación del AAB firmado para Google Play. No constituye una certificación externa de toda la aplicación.
