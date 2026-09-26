# Recuperación del acceso público, feed y audio de momentos

Estado: cambios del 8–9 de septiembre de 2026. No constituye una certificación
de todos los controles de la aplicación ni una prueba de reproducción física.

## Incidente del túnel

El servidor conservaba ocho contenedores saludables, pero el nombre público
`closest-dept-pay-sport.trycloudflare.com` había desaparecido de DNS. El monitor
acumuló 463 fallos consecutivos. El supervisor confiaba en un proceso
cloudflared vivo y seguía renovando el descubrimiento firmado con ese nombre.
Un proceso vivo no demostraba conectividad pública.

`infrastructure/local-fallback/scripts/public-tunnel-dns.mjs` comprueba el
nombre en los resolutores públicos de Google y Cloudflare. Sólo dos respuestas
NXDOMAIN válidas permiten invalidar el origen. Timeouts, errores HTTP,
respuestas malformadas, truncadas o contradictorias conservan el estado para
no provocar rotaciones por un fallo del diagnóstico. Incluye siete pruebas
aprobadas en Windows y Ubuntu.

El supervisor actualizado se instaló en el repositorio del servidor y en
`/home/alvaro/bin/conectate-cloudflare-tunnel.sh`. Se reinició únicamente el
supervisor de usuario; su reconciliación existente comprobó el nuevo túnel y
publicó el descubrimiento firmado. Respaldo de scripts/configuración dentro
del mismo servidor, con acceso restringido:
`/home/alvaro/conectate-tunnel-dns-20260909T0356/`.

Origen resultante observado (dinámico, no debe fijarse como identidad de medios):
`https://cottage-mandatory-harper-excluded.trycloudflare.com`.
La firma TXT de `conectate-alvaro.duckdns.org` se verificó con la clave pública
del proyecto. Desde Windows `/ready` respondió HTTP 200 en 517 ms a las
07:51 -05:00 del 9 de septiembre.

La dirección Tailscale no era accesible desde esta PC; SSH por la dirección
LAN documentada `192.168.18.22` sí funcionó. No extrapolar el estado de
Tailscale a todos los servicios de la aplicación.

Revalidación leída durante el siguiente bloque: monitor `ok`, cero fallos
consecutivos, marca UTC `2026-09-09T13:01:55Z`, temporizador de usuario activo
y ocho contenedores saludables. No se reinició nuevamente el servidor.

Comprobación posterior por LAN: marca del monitor `2026-09-09T22:33:54Z`,
estado `ok`, cero fallos consecutivos y timer activo. API/edge/realtime/signaling
saludables tras 19 horas; otros cuatro contenedores saludables tras cuatro días.

## Mediciones del monitor a las 12:51 UTC

- Social: 37 publicaciones, 551 ms; Videos: 12 elementos, 653 ms.
- Búsqueda de perfiles: 20 resultados, 513 ms; imágenes HTTP 200,
  418–766 ms con el cuerpo completo.
- Dos rangos de video de 1 MiB: HTTP 206, 954/762 ms completos; TTFB 387/209 ms.
- Mensajes: inbox de 8 chats, 651 ms; grupos 619 ms; comunidades 597 ms;
  historial de 184 mensajes, 510 ms; avatares 437/730 ms.

Son muestras de transporte público, no garantías de latencia desde cualquier
operador ni evidencia de primer fotograma, códec/GPU o reproducción continua.
El Quick Tunnel sigue sin proporcionar un dominio estable/SLA.

## Android: sesión de feed y mezcla de audio

`VideoFeedPageStore` publica el listado y la identidad de la nueva sesión
atómicamente. `VideoFeedScreen` vuelve al primer elemento al recibir la nueva
sesión, no al iniciar una petición mientras aún muestra los videos anteriores.
Conserva navegación por perfil y enlaces a un video concreto. El gesto de
actualización se habilita en el límite superior; avanzar y regresar a videos
ya vistos continúa permitido. Una respuesta vacía sin red conserva la página.

`isRefreshing` se separa de la carga de páginas adicionales. Cinco pruebas
verifican publicación atómica, lista idéntica con sesión nueva, conservación
offline, paginación sin reinicio y limpieza de cuenta.

`MomentAudioMixDialog` permite controlar por separado audio original y música
añadida, reutilizando la selección y exportación ya existentes. El selector
local toma la duración del video; cambiar canción conserva los volúmenes.
La vista previa respeta la atenuación y se pausa mientras se selecciona audio.
La música ya disponible para imágenes sigue compartida con los videos.

## Artefacto y límites de esta validación

Puerta Android del 8 de septiembre:

- `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, 498 pruebas
  en 131 suites, cero fallos/errores.
- `:app:lintDebug`: informe de las 23:07:55 -05:00, cero errores y 191 advertencias.
- APK de las 22:54:15 -05:00: 270,840,497 bytes; SHA-256
  `807D4964FF083D8AA04DDA55310A7BE46CF09073E210B83AE3B6DFFD9646FAA3`.
- Ubicación: `app/build/outputs/apk/debug/app-debug.apk`. Los cambios de borrado
  durable posteriores no pertenecen a ese artefacto hasta una nueva compilación.

No hay dispositivo ADB conectado y los cuatro emuladores fueron eliminados
por petición del usuario. No recrearlos para presentar pruebas como físicas.
La prueba instrumentada de cambio de cuenta continúa pendiente: su última
ejecución falló por un selector ambiguo. No declarar equivalencia con WhatsApp,
TikTok u otras plataformas a partir de pruebas unitarias.

Pendientes: reproducción continua y gestos en el teléfono reportado, mezcla
exportada con códecs reales, recorrido offline entre dos cuentas/dispositivos,
edición y borrado durables, auditoría de todos los controles y despliegue
operativo con dominio estable. El objetivo global permanece abierto.
## Muestra adicional de archivos MP4 — 10 de septiembre, 02:14 -05:00

Comprobación autenticada de lectura del feed público, desde el servidor.
Se inspeccionaron tres URLs MP4 distintas de los primeros doce resultados,
sin guardar archivos, imprimir tokens ni modificar publicaciones. Las URLs
pertenecían al mismo origen público verificado. Referencias de archivo:
SHA-256 parcial del pathname, no identificadores personales.

| Referencia | Tamaño (bytes) | Posición moov | Posición mdat | Centro 32 KiB | Final 32 KiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 419d92854ea448f6 | 2.650.851 | 48 | 8.064 | 181 ms | 168 ms |
| 4f9b91b2f97babc4 | 2.709.690 | 32 | 9.387 | 180 ms | 174 ms |
| e191541e525be5e0 | 1.143.506 | 32 | 12.150 | 183 ms | 171 ms |

Las 18 lecturas por rango devolvieron HTTP 206 y Content-Range/longitud
coherentes. Lecturas iniciales de cabecera: 146–506 ms. El índice `moov`
apareció antes de `mdat` en los tres archivos; no se observó un archivo que
exigiera descargar todo el contenido antes de encontrar ese índice.
Marca de ejecución: 2026-09-10T07:14:57.688Z.

Alcance: muestra pequeña del feed actual, no necesariamente el video de la
captura histórica. Se comprobaron disponibilidad, rangos y posición de cajas,
no decodificación audiovisual ni reproducción continua en Android. El host
no dispone de `ffprobe`/`ffmpeg`; no se instalaron herramientas ni se
reiniciaron servicios para esta lectura. Estos resultados no certifican
todos los videos, redes, cachés, códecs ni el ciclo de vida del reproductor.
