# 60 — Encuestas, audio, momentos, avisos y perfil

Fecha: 03/10/2026. Android: versión 117 / 9.0.20, conservada por indicación del
propietario. APK debug recompilada e instalada mediante actualización; sin borrar
datos ni cerrar sesiones. Pruebas en emuladores API 35 y backend Ubuntu real.

## Correcciones móviles

### Encuestas de grupos y comunidades

- Actualización mientras la encuesta es visible: invalidaciones del servidor y
  reconciliación cada cinco segundos. Se cancelan consultas fuera de pantalla.
- Una respuesta anterior al voto propio, cambio de sala o cuenta no sustituye
  la selección actual. Los fallos de red tienen espera progresiva acotada.
- Botones legibles en mensajes recibidos y enviados; «1 voto» en singular y
  diálogo de votantes sin altura vacía obligatoria.
- Recorrido real entre dos cuentas: votar A, votar B desde la segunda cuenta,
  cambiar A por B y quitar el voto propio. El voto ajeno se conservó. El diálogo
  mostró nombre y @usuario. Tras actualizar la APK y reabrir el grupo permanecía
  un voto en B. Captura revisada visualmente.

La transacción del servidor se documenta en la revisión 58. El modo heredado de
Supabase no tiene la misma transacción. Sigue pendiente cerrar encuestas mientras
hay escrituras concurrentes y medir historiales grandes.

### Música de perfil y selección para momentos

El audio de perfil usa Media3 con reproducción en flujo, recuperación del error,
pausa al pasar a segundo plano y liberación al salir. Un cambio del propietario
del ciclo de vida no libera un reproductor todavía visible. Se rechazan páginas
web que no sean fuentes directas compatibles.

La preescucha de música de momentos muestra carga, permite cancelarla y prepara
directamente el tramo seleccionado. Se reduce el umbral de arranque a 300 ms de
audio almacenado; esto no garantiza 300 ms de latencia de red. Se mantienen audio
focus, pausa con auriculares desconectados y autorización del catálogo.

En pruebas instrumentadas con un WAV propio del servidor: audio de perfil listo
en **450 ms** y preescucha de selección de comunidad en **1049 ms**. Se probaron
pausa, reanudación y cambio de ciclo de vida. También se abrió el perfil real y
se comprobó el control «Pausar». No se verificó audición humana con el emulador
sin salida de audio ni la carga completa del catálogo comunitario.

### Fotos de momentos

La imagen presenta miniatura mientras carga, error visible y reintento. Para
storage propio se solicita una variante limitada al ancho visible; la decodificación
se limita también. Las URLs externas y firmadas conservan su ruta.

Un JPEG propio de **6 806 215 bytes** tardó **11 469 ms** en descargarse desde el
host. Su variante de 1080 px pesó **553 876 bytes** y tardó **1672 ms**. En Android,
la foto del mismo fixture quedó visible en **1310 ms**, sin pantalla negra.
Son mediciones de rutas distintas: descarga HTTP del host y carga del componente
Android. Se probó también una imagen inexistente y su botón de reintento.

La variante limita el detalle al ampliar; todavía no se carga el original bajo
demanda. No se afirma ese tiempo para todo tamaño, red o dispositivo.

### Perfil, visitas y avisos administrativos

El botón Mensaje evita navegación duplicada o con destino vacío. Los fallos de
datos opcionales y presencia dejan de cancelar el chat. Se recorrió perfil →
Mensaje → chat sin cierre. El cierre intermitente original no dispone de una
traza que permita afirmar que toda causa posible quedó eliminada.

Las visitas se registran también al mostrar un perfil desde caché, con reintento
tras fallo y aislamiento por cuenta. La corrección adicional del backend y la
visita confirmada desde Android están en la revisión 59. Una preferencia de
privacidad explícitamente desactivada se conserva.

El formulario de avisos tiene selección horizontal de tipo, revisión antes de
publicar y limpia sus campos solo después de confirmar el servidor. Un error al
recargar la lista posterior no anuncia falsamente que la publicación falló.
Android descarta notificaciones repetidas, malformadas o de otra cuenta y obtiene
el contador autorizado del servidor.

El ensayo PostgreSQL aislado comprobó tres destinatarios temporales, tres
notificaciones persistidas, idempotencia, filtro de versión y revocación, sin
publicar un aviso global a personas reales. Los adaptadores push/realtime fueron
simulados: queda pendiente comprobar la entrega FCM en teléfonos físicos.

## Resultado de esta ronda

- Compilación debug, test APK y pruebas unitarias: correcta.
- **33 pruebas unitarias Android**, cero fallos/omisiones.
- **12 pruebas instrumentadas**, cero fallos/omisiones: cuatro de encuestas,
  tres del formulario de avisos y cinco de audio/fotos con recursos propios.
- **8 pruebas Node** de visitas y valores por defecto, correctas; la comprobación
  previa de avisos y valores por defecto aprobó 11 pruebas.
- Nueve servicios saludables; fuentes de API verificadas y release `e5e3ce1`.

## Estado de los once requisitos

| Punto | Estado comprobado y trabajo restante |
|---|---|
| 1. Enlaces azules | Implementados y probados en revisión 52; revisar contraste sobre fondos rojos. |
| 2. Llamadas/salas | Mejoras y recorridos en revisiones 53–56; falta cerrar tiempos de media y pruebas físicas entre redes. |
| 3. LIVE | Revisión 57: cámara de ambos, chat y unión comprobados; primer vídeo remoto todavía alrededor de 6,5 s en esa muestra y 60 FPS pendientes. |
| 4. Encuestas | Cambio/eliminación/votantes/actualización ajena probados; falta concurrencia de cierre e historial grande. |
| 5. Mensaje del perfil | Ruta protegida y probada; continuar buscando causas del cierre intermitente. |
| 6. Música de perfil | Reproducción/pause/resume probados con audio del servidor. |
| 7. Música de comunidad | Preescucha de selección probada; falta recorrido completo del catálogo y publicación. |
| 8. Foto negra | Variante, miniatura y reintento probados; ampliar cobertura de formatos/redes. |
| 9. Avisos | Formulario y persistencia/idempotencia probados; entrega física FCM pendiente. |
| 10. Visitas | Activadas por defecto y escritura desde Android confirmada; opt-out conservado. |
| 11. Arranque | Sigue abierto: muestras debug de aproximadamente 3,5–4,4 s. Objetivo ≤3 s no cumplido. |

La traza Perfetto encontró coste de carga de APK, inicialización y primeras
composiciones. Se debe medir también el tiempo hasta contenido utilizable: dibujar
un splash rápido no equivale a terminar el arranque. Referencias: [medición de
arranque Android](https://developer.android.com/topic/performance/issues/launch-time),
[eventos de Media3](https://developer.android.com/media/media3/exoplayer/listening-to-player-events)
y [permiso de notificaciones](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

No es una certificación integral de producción. Quedan la auditoría continua de
opciones móviles, mejoras WebRTC/arranque y paridad completa web/Vercel. Los fixtures
propios se conservan mientras continúan las pruebas; no se guardan credenciales
ni logs privados en esta documentación.
