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

Antes de crear las dos cuentas temporales se verificó el respaldo
`/home/alvaro/conectate-backups/20261001T045412Z` (PostgreSQL, storage y estado de AI; hashes y
lectura de artefactos correctos). Los nueve servicios estaban saludables y los 93 archivos
JavaScript de la API desplegada coincidían con la fuente local. No se cambió el backend.

## Producción y siguiente etapa

Siguen abiertas las pruebas de larga duración y carga, correo recibido y recuperación física,
llamada real con teléfono bloqueado, firma de release original y paridad web/Vercel.
Una prueba de captura local no certifica esos flujos ni garantiza 60 FPS en todos los equipos.
