# 163 · Álbumes y cambio local de día

Estado: **51 casos JVM aprobados; APK y dispositivo pendientes**.

El agrupador de imágenes compartía una fila cuando emisor, tipo y distancia temporal coincidían. Dos fotos separadas por veinte segundos podían quedar dentro del mismo álbum aunque cruzaran medianoche local. En el chat directo, la segunda fila se omitía como continuación y su encabezado de día no llegaba a mostrarse. Esta corrección continúa el pendiente identificado por lectura en 162; no se atribuye a la captura original un formato de fecha que no se observó.

El agrupador exige ahora un día local conocido común para todos los elementos del álbum. La primera imagen del día siguiente inicia otro grupo y conserva su fila. Una fecha ausente o inválida se muestra individualmente, sin inventar un día compartido. El separador y el agrupador usan `messageLocalDate`, que conserva el lector de instantes de 162 y la zona del dispositivo. Las imágenes del mismo día siguen agrupándose con el límite temporal previo; los stickers siguen fuera del álbum. No se reescriben fechas, mensajes ni archivos.

| Validación | Casos | Fallos | Omisiones |
| --- | ---: | ---: | ---: |
| Referencia directa inicial | 8 | 3 | 0 |
| Referencia directa ampliada | 8, los mismos casos | 4 | 0 |
| Reparación Gradle y regresiones | 51 en seis clases | 0 | 0 |

La ampliación añade combinaciones de fechas inválidas al mismo caso; no son dieciséis casos distintos. Las referencias ejecutan las funciones reales ya compiladas del bloque 162, con la prueba Java nueva compilada por Javac/JDK21. Se contrastaron las entradas fuente del build 162 y se congelaron 12.159 archivos de clases y classpath antes y después de cada referencia. No son una compilación Gradle nueva de producción ni un ensayo de interfaz. Sus salidas registran únicamente fallos de aserción, sin pruebas ignoradas ni supuestos omitidos.

La primera reparación quedó interrumpida en compilación, sin resultado de pruebas ni proceso vivo al comprobarla; su causa no se determinó. Se preservó su log y se reanudó la selección completa en otra ruta después de comprobar que no había compilaciones activas. La reparación terminal finalizó `2026-11-16T03:53:18.967648+00:00` con 51 casos, cero fallos, errores u omisiones, entradas iguales y marcador real `223098d302c4` sin override. Incluye ocho casos de álbumes, los 27 de 162 y dieciséis regresiones del visor. Los resultados, snapshots, classpath y hashes están en [la evidencia de 163](../quality/evidence/163-media-album-local-day.json).

La lectura completa nueva comprende el helper común, el separador y la prueba Java. `MediaGridMessageBubble` conserva revisión parcial del agrupador; su renderizador completo no queda certificado por estas pruebas. Continúan pendientes la interfaz real en chats/grupos/comunidades, los recorridos físicos y una APK identificada que incluya esta corrección. Los pendientes de carga de stickers de 161, recuperación de 160 y revisión integral permanecen separados. No se entrega ni instala una APK ni se certifica preparación de producción o marketing con este commit.
