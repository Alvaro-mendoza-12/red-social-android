# Cadencia de cámara en LIVE, salas, llamadas y PK

Actualizado: 01/10/2026. Rama: `feature/live-gifts`. Versión conservada: 117 / 9.0.20.

## Diagnóstico

La captura directa del Motorola moto g04, sin filtros, codificación ni red, produjo aproximadamente
10 FPS con la cámara trasera y 20 FPS con la frontal. La misma caída aparece con Camera1 y Camera2.
La prueba inicial de Camera1 falló al reconfigurar la frontal a una petición de 60 FPS y produjo
cero cuadros; se conserva esa ejecución como fallo, no como una prueba aprobada.

Ambas cámaras anuncian un máximo de 30 FPS en sus formatos y rangos de exposición automática.
Incluyen `[30,30]` y rangos variables como `[5,30]`. No anuncian formatos de 60 FPS para esta
captura. Pedir 60 no convierte el sensor ni la imagen recibida en 60 FPS.

La selección de rangos de la biblioteca da preferencia a límites inferiores bajos. Esto permite
reducir la frecuencia para ajustar la exposición. Referencias primarias:
[selección de rangos en WebRTC](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/sdk/android/api/org/webrtc/CameraEnumerationAndroid.java)
y [sesión Camera2](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/sdk/android/src/java/org/webrtc/Camera2Session.java).

El Motorola ofrece codificadores H264 por hardware y VP8 por software. La captura anterior ya
era lenta antes de llegar al codificador; el estado del servidor no explica esa caída local.

## Implementación

- Selector compartido de rangos AE: usa exclusivamente rangos anunciados, elige el techo más
  cercano y prioriza el mayor piso compatible. Por ejemplo, `[30,30]` sobre `[5,30]` al pedir 30.
- Cámara nativa: sesión Camera2 con esta política para LIVE, invitado, llamadas y salas.
- Invitado SFU/PK: sesión equivalente en el espacio de nombres del SDK LiveKit; publica la
  cámara personalizada antes de activar sus controles para evitar abrir una segunda cámara.
- Salas y llamadas solicitan hasta 60 FPS; la selección de formato mantiene el límite real
  anunciado por el dispositivo y el presupuesto de resolución existente.
- Se conserva el ciclo de cámara del SDK: cambios de cámara/formato, cierre, orientación,
  enfoque y estabilización. Las dos sesiones están separadas por los espacios de nombres de
  WebRTC 137.7151.05 y WebRTC prefixed 144.7559.09.
- Los archivos derivados conservan su licencia BSD y concesión de patentes en
  `app/src/main/assets/licenses/webrtc`. Al actualizar esos SDK hay que comparar y actualizar
  ambas sesiones y repetir las pruebas físicas.

La frecuencia estable puede reducir el tiempo disponible para exposición con poca luz.
Queda pendiente evaluar iluminación muy baja, batería/temperatura y otros fabricantes.

## Validación y evidencia

Las pruebas guardan contadores y capacidades; no guardan ni transmiten imágenes de la cámara
durante el diagnóstico local. Las pruebas con servidor usan cuentas temporales aisladas.
Resultados locales de la APK corregida:

| Medición | Trasera | Frontal |
|---|---:|---:|
| Camera2 original, misma ejecución | 10,00 FPS | 19,80–20,00 FPS |
| Camera2 con rango estable, 640×480 | 30,00 FPS | 30,00 FPS |
| Track de la aplicación, formato seleccionado por su presupuesto | 30,00 FPS | 30,00 FPS |

Se probaron peticiones de 30 y 60; el formato efectivo del Motorola está limitado a 30.
Ambas pruebas nativas pasaron (103,237 s), incluyendo umbral de 27 FPS para el track de la app
y al menos 90% del rango fijo anunciado en la captura directa. Pasaron 20 pruebas unitarias
de selección de rango y formato. La APK y su APK de pruebas compilaron y se instalaron con
`install -r`, conservando la cuenta y los datos.

Motorola con datos móviles y emulador con Wi-Fi, con comprobación del transporte en los tests:

| Flujo | FPS recibidos en el emulador desde Motorola | FPS recibidos en Motorola | Primer cuadro |
|---|---:|---:|---|
| LIVE | 28,65 | No aplica: host sólo publica imagen | 3.541 ms en receptor |
| Sala | 30,83 | 25,19 | 3.457 / 5.230 ms |
| Llamada, TURN obligatorio | 29,50 | 25,19 | 3.568 / 3.513 ms |

Los seis tests pasaron; la dirección desde el Motorola exige al menos 25 FPS. Hubo paquetes
de audio entrantes en ambos equipos. No prueba escucha humana ni garantiza ausencia de cortes:
el receptor LIVE registró un congelamiento de 450 ms durante su muestra. Son medias breves,
no una certificación de estabilidad. Wi-Fi fue restaurado al terminar.

La primera repetición de PK recibió 24,90 FPS en el host y 23,79 en el invitado. El invitado
falló el umbral de 25, aunque recibió audio e imagen. Se conserva como fallo y motiva revisar
la conversión síncrona del puente de cuadros compartidos. El arranque sigue por encima de
un segundo en estas redes; no queda cerrado por corregir la cadencia.

### Puente de cuadros compartidos y repeticiones de PK

La conversión entre los dos SDK pasa a un trabajador dedicado. El callback de la cámara
retiene y entrega el cuadro; la cola mantiene un cuadro en proceso y como máximo uno pendiente.
Los cuadros pendientes antiguos se liberan, y el cambio de fuente o la salida invalida los
cuadros de la generación anterior. Ocho tests comprueban propiedad/liberación, rechazo del
trabajador, errores de conversión y cierre concurrente con 401 cuadros.

La suite completa terminó con **1.015 tests, cero fallos, errores u omisiones**, en 227 clases.
Lint terminó con cero errores y 268 advertencias existentes. La nueva APK mantuvo 30 FPS en
ambas cámaras al repetir el diagnóstico local. Otros seis tests físicos pasaron: cuatro de
layout de salas, 16 ciclos de salida/reentrada dentro de un test y tres ciclos de cancelación
y reintento de SFU dentro de otro. Se revisaron las cuatro capturas sintéticas de layout.

Las repeticiones usan Motorola en `CELLULAR` y emulador en `WIFI`, comprobados antes y después:

| PK con puente asíncrono | Host recibe | Invitado recibe | Resultado |
|---|---:|---:|---|
| Primera ejecución | 28,30 FPS | 28,80 FPS | Pasó |
| Repetición | 28,40 FPS | 8,00 FPS | Falló el umbral de 25 FPS del invitado |
| Con métricas ampliadas | 28,60 FPS | 29,70 FPS | Pasó |
| Repetición con métricas | 28,40 FPS | 28,00 FPS | Pasó |
| Muestra continua de 30 segundos | 28,57 FPS | 29,60 FPS | Pasó 25 FPS y máximo 500 ms sin cuadros en ambos equipos |

En la ejecución fallida la cámara original siguió a 30 FPS y el emisor informó 29 FPS
codificados, con `qualityLimitationReason=none`. El invitado registró 261 cuadros descartados y
3,646 s acumulados de congelamiento. Esa ejecución todavía no incluía contadores ampliados
de jitter/pérdidas: no demuestra la causa de la caída. Los nuevos contadores se conservan junto
con las pruebas positivas y negativas. No se atribuye el fallo al servidor o al decodificador
sin reproducirlo con esas métricas.

Los primeros cuadros del invitado tardaron 3,940–4,819 s en las ejecuciones completas, más la
petición de sesión. Los 12–15 s del host incluyen esperar que el coordinador arranque el otro
test y publique al invitado; no son una medición de arranque equivalente. Hubo paquetes de
audio en ambas direcciones. Los tests del host comprobaron mute/reanudación y reemplazo de
cámara manteniendo la captura original; no había un tercer espectador P2P recibiendo el LIVE
original durante la batalla. Sigue pendiente esa prueba de carga simultánea y la escucha humana.

La estabilidad de PK sigue abierta, aunque la captura local está corregida. Se añadió medición
por segundo y del mayor intervalo sin cuadros, para que una media alta no oculte un corte.
En la muestra continua, el host recibió 25–30 cuadros por segundo y el invitado 28–31
(la llegada puede agruparse entre segundos). Los máximos intervalos fueron 183,236 y 171,245 ms.
El SDK informó cero pérdidas pendientes al tomar la muestra y el invitado emitió 92 NACK;
ese contador muestra que hubo solicitudes de recuperación. El SDK contó un congelamiento de
243 ms en el invitado durante la sesión completa. No equivale a latencia visual medida ni
elimina la ejecución anterior que cayó a 8 FPS. También faltan pruebas prolongadas, más
dispositivos y carga con espectadores simultáneos.

Antes de crear las dos cuentas temporales se verificó el respaldo
`/home/alvaro/conectate-backups/20261001T045412Z` (PostgreSQL, storage y estado de AI; hashes y
lectura de artefactos correctos). Los nueve servicios estaban saludables y los 93 archivos
JavaScript de la API desplegada coincidían con la fuente local. No se cambió el backend.
Al cerrar esta tanda se borraron únicamente las dos identidades temporales, verificando antes
sus IDs y correos de creación. Sus tokens pasaron a responder HTTP 401 y se retiraron los
archivos de fixture de ambos dispositivos y del PC. Wi-Fi del Motorola quedó restaurado.

## Producción y siguiente etapa

Actualización posterior: la recepción real del código por Gmail, el ciclo de cambio/login del
servidor y las mejoras de invitaciones se documentan en
[35](35-arranque-invitaciones-y-recuperacion-correo-2026-10-01.md). Conservamos debajo los
pendientes identificados al cerrar esta tanda de medios; no todos han sido resueltos.

Siguen abiertas las pruebas de larga duración y carga, correo recibido y recuperación física,
llamada real con teléfono bloqueado, firma de release original y paridad web/Vercel.
Una prueba de captura local no certifica esos flujos ni garantiza 60 FPS en todos los equipos.

La firma seguirá el proceso manual del propietario en Android Studio. Se encontró el archivo
`D:\Novvin llave\llaves de mi apk conecta` (sin extensión; cabecera DER compatible con PKCS12).
La ruta indicada inicialmente, `D:\llaves de mi apk conecta`, no existe. No se han leído claves
privadas ni contraseñas, no se ha creado una llave sustituta y no se ha firmado un AAB con ese
archivo. La identidad de subida debe comprobarse con la firma original al generar el bundle.
