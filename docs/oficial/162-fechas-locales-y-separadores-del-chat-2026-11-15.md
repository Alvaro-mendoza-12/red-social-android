# 162 · Fechas locales y separadores del chat

Estado: **corrección JVM aprobada; ensayo físico y APK pendientes**.

El propietario informa de «Hoy» entre mensajes de 18:07 y 18:10 de una conversación continua. La captura no incluye los timestamps originales: no se afirma haber demostrado su formato exacto ni se leyeron mensajes privados del servidor. Se reprodujo una causa compatible en el código Android vigente: el reloj y la ordenación usan `parseMessageInstant`, pero el separador mantenía otro lector que añadía `Z` a offsets negativos y no aceptaba formatos cortos/compactos de PostgreSQL ni fechas epoch. Un mensaje histórico válido se convertía en fecha desconocida y el siguiente UTC podía iniciar otro bloque «Hoy».

La corrección usa el mismo lector de instantes para hora y separador y compara el día en la zona local del dispositivo. Los mensajes sin fecha válida no inventan separadores; el siguiente compara con el último día conocido. El primer mensaje válido conserva su encabezado y el comparador sigue detectando el cambio real de día. Grupos y comunidades usan ahora la misma decisión sobre su lista. No se modifican fechas almacenadas, mensajes, recibos, orden de la conversación ni el servidor. La conversión usa las API documentadas de [Instant](https://developer.android.com/reference/java/time/Instant) y [ZoneId.systemDefault](https://developer.android.com/reference/java/time/ZoneId#systemDefault()); la zona es la del dispositivo y puede cambiar con sus ajustes.

| Ejecución | Casos | Fallos | Errores / omisiones |
| --- | ---: | ---: | ---: |
| Referencia anterior al parche | 12 | 8 | 0 / 0 |
| Reparación y regresiones | 27 en cuatro clases | 0 | 0 / 0 |

Los doce casos de referencia incluyen cuatro controles aprobados. La clase propia final contiene 16 casos; sus 4 controles adicionales se añadieron después del parche y no tienen referencia previa. Se conservan UTC, offsets negativos y positivos, PostgreSQL, epoch, medianoche UTC frente a local, fechas ausentes/malformadas, límites de lista y etiquetas Hoy/Ayer. Las otras clases validan la hora visible, la caché de timestamps y el orden de mensajes. Son pruebas JVM sobre las funciones reales; no son recorridos de interfaz ni una prueba de conectividad.

La referencia terminó `2026-11-15T23:48:12.353321+00:00` y la reparación `2026-11-16T00:05:28.112826+00:00`. Ambas conservaron sus entradas y las huellas privadas; el marcador de compilación no se sobreescribió. Logs, XML, snapshots y sus hashes figuran en [la evidencia de 162](../quality/evidence/162-chat-local-date-separators.json). La lectura completa se limita al separador y la prueba nueva; las pantallas de grupos/comunidades tienen revisión parcial de los puntos cambiados.

Una sonda de solo lectura de las 23:58:14 UTC comprueba el reloj actual de Ubuntu: informa NTP sincronizado y su diferencia respecto al reloj local queda dentro del intervalo de −9,26 a 7.342,87 ms, debido a los 7.352,13 ms de la operación SSH completa. Ese intervalo incluye cero y descarta un salto actual de un día; no determina un desfase exacto ni certifica la hora del servidor cuando se tomó la captura. No se ajustó ningún reloj ni dato.

Pendientes: ensayo con una APK que incluya esta corrección, dispositivo físico, captura de la incidencia concreta si reaparece, revisión completa de Mensajes y conectividad/stickers de 161. Por lectura se detecta además que el agrupador de álbumes puede atravesar medianoche; su reproducción y corrección quedan aparte, sin atribuirles esta prueba. El bloque 160 de recuperación permanece separado, sin incluir su prueba nueva en esta entrega. La revisión integral, seguridad, rendimiento y preparación de marketing siguen abiertas. No se entrega ni instala una APK con este commit.
