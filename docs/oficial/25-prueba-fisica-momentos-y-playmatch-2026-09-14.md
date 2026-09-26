# NoWi: prueba física, cabecera de Momentos y filtros PlayMatch

Fecha: 2026-09-14, America/Bogota. Repositorio: `D:\red social - live-gifts`, rama `feature/live-gifts`.

Continuación del informe 24. **No constituye certificación de producción.** Se conservaron los cambios previos del repositorio, los datos del teléfono y los datos del servidor. No se enviaron mensajes, regalos ni se publicaron contenidos durante estas pruebas.

## 1. Prueba controlada en moto g04

Con autorización del propietario, sin interacciones simultáneas durante la secuencia:

- Arranque frío: `TotalTime=5702 ms`, `WaitTime=5711 ms`. Una muestra debug; no es mediana, primer cuadro de video ni tiempo de carga completa. El arranque sigue siendo mejorable.
- Videos: pausa por toque, imagen estable con indicador de pausa; reanudación y cambio de cuadro/progreso; desplazamiento al siguiente video y avance de sus cuadros. No apareció el cartel de reintento en esa secuencia. Esto no certifica todos los archivos ni cambios entre Wi-Fi y datos móviles.
- Social: al regresar aparecieron publicaciones, avatar, foto y Momentos. No se observó `INTERNAL_ERROR` ni cabecera deformada en esa secuencia.
- Momento existente: cargó su imagen y avanzó el indicador. **Se detectó superposición de canción y nombre del autor.** Las capturas no prueban por sí solas que el audio se oyera correctamente.
- El muestreo de logcat del PID observado no mostró `FATAL EXCEPTION`, `PlaybackException` ni errores HTTP del reproductor en la ventana filtrada. No equivale a ausencia global de errores.

Evidencia privada en `tmp/phone-validation-20260913/`: archivos `video-paused-1-20260914.png`, `video-paused-2-20260914.png`, `video-resumed-20260914.png`, `video-next-1-20260914.png`, `video-next-2-20260914.png`, `social-resume-20260914.png`, `moment-1-20260914.png`, `moment-2-20260914.png` y grabación `video-a-20260914.mp4`. No publicar capturas que contienen datos de la cuenta.

Excluir de la evidencia de reproducción el primer `video-a-20260914.png`, que aún capturó la transición desde Mensajes. El `video-controlled.xml` del día anterior estaba obsoleto: `uiautomator` no consiguió estado idle y no generó un árbol nuevo. No inferir navegación a partir de ese XML.

Muestra acumulada de `gfxinfo`: 4479 frames, 524 fuera de deadline (11,70%), p95 36 ms, p99 113 ms. Incluye arranque debug y captura de video; no es comparable directamente con una ventana diferente del informe 24 ni prueba de rendimiento sostenido.

## 2. Cabecera de Momentos

Causa: el chip musical se dibujaba dentro de cada página multimedia con `padding(top = 72.dp)`, mientras la cabecera del autor usaba insets de barra de estado y su propio tamaño. Ambos eran capas independientes. El título largo se superponía al autor en el equipo real.

Corrección:

- Nuevo `MomentAuthorHeader.kt`: autor, fecha y canción comparten una columna medida. Título y nombre tienen elipsis; el icono de licencia conserva su espacio. No se fija una coordenada vertical para la música.
- `MomentViewerScreen.kt` sólo integra el componente. Se elimina el chip de cada página multimedia y la cabecera interactiva queda encima de la capa de gestos; abrir una licencia no debe interpretarse como avanzar el Momento.
- La música y el reproductor mantienen su lógica; este cambio no altera la mezcla, derechos ni publicación.
- `MomentAuthorHeaderTest.kt`: regresiones de texto largo con fuente 1,8x en 240 dp, posición bajo autor/fecha, clic de licencia y ausencia de fila sin música.

Validación Android terminó a las 06:09:02: **BUILD SUCCESSFUL in 21m 6s**, 642 unitarias ejecutadas sin fallos/errores/omitidas; Lint cero errores y 195 advertencias. Se compiló la APK de instrumentación, pero **las dos pruebas instrumentadas nuevas no se ejecutaron** antes de cambiar a la incidencia de firma. Log `tmp/android-moment-header-20260914.log`.

Con nueva autorización del propietario, se compararon certificados y se instaló la APK con `adb install -r -t`: Success. Certificado compatible con la versión debug anterior; sin desinstalar ni borrar datos. APK: 270 902 626 bytes, SHA-256 `E8FA131CF8B216E9585A08DE905789D4CB9C86497F09BEB32ACB1D276C5DC012`; `lastUpdateTime=2026-09-14 05:58:52`, `firstInstallTime=2026-08-27 16:04:58` conservado.

La comprobación manual posterior mostró autor/fecha separados de canción y retorno automático a Social al terminar el Momento (`moment-header-fixed-1-20260914.png`, `header-after-moment-20260914.png`). Otro video cargó y avanzó (`header-build-video-2-20260914.png`, `header-build-video-3-20260914.png`), aunque la primera captura aproximadamente 2,5 segundos después de entrar aún mostró carga y pantalla negra (`header-build-video-1-20260914.png`); no afirmar carga instantánea. Arranque frío inmediatamente posterior a instalar: 8686 ms; muestra debug, no benchmark comparable. No se observaron excepciones del reproductor en el muestreo filtrado del PID 15720. No se modificó la fuente Android de PlayMatch durante esta compilación.

## 3. PlayMatch: compatibilidad obligatoria

Causa reproducida: los filtros buscaban cada campo con un `some`/`EXISTS` independiente. Un perfil con Valorant/PC y Fortnite/PlayStation podía satisfacer Valorant/PlayStation. Además, las listas de criterios de matchmaking no se aplicaban en SQL antes de `LIMIT 250`, y el estilo requerido se ignoraba.

Corrección modular:

- `playmatch-candidate-constraints.js` normaliza criterios de búsqueda y sesión; juego, plataforma y rol deben coincidir en **una misma entrada**. Las alternativas dentro de cada lista son OR; los campos obligatorios son AND. Región, idioma, estilo y micrófono también se respetan.
- `candidateBundles` aplica los criterios parametrizados antes del límite y exige catálogo activo para satisfacer filtros de juego. Conserva bloqueos en ambas direcciones, descubribilidad, confirmación adulta, suspensiones y exclusión de matches recientes. Revalida los bundles cargados.
- No se interpreta la cadena `"false"` como verdadero para micrófono: el contrato JSON exige booleano `true` para activarlo.
- No cambia la puntuación blanda, disponibilidad horaria, LFG ni Squads. Los filtros explícitos de plataforma no se relajan por cross-play de otro juego.

Pruebas:

- Antes: seis de siete regresiones fallaban, `tmp/playmatch-constraints-before-20260914.log`.
- Después: nueve pruebas del módulo pasan; suite API completa: **154 pruebas, 153 pasan, una omitida, cero fallos**. `tmp/api-playmatch-final-20260914.log`. La puerta de privacidad se ejecuta por separado y sigue fallando; no queda oculta en este total.
- PostgreSQL 16.15 aislado, sin red y con datos en tmpfs: **18 comprobaciones de la consulta real pasan**. Incluyen bloqueos, perfil oculto, suspensión vigente/vencida, confirmación adulta, catálogo inactivo, coincidencia conjunta y un candidato válido detrás de 260 incompatibles. `scripts/build-playmatch-sql-regression.js` genera la consulta desde el código usado por la API. Log `tmp/playmatch-postgres-20260914.log`. Se eliminó únicamente el contenedor desechable con sus fixtures; no se copiaron ni borraron datos reales.
- Imagen candidata Linux: **26 pruebas pasan**, imports correctos. `tmp/api-playmatch-candidate-20260914.log`.
- Esquema real: **cuatro consultas bajo `BEGIN READ ONLY` pasan**, dos candidatos elegibles para la cuenta de diagnóstico; rollback final, sin crear sesiones ni perfiles. `verify-playmatch-candidates-readonly.js`. El driver advierte que en una futura versión no admitirá consultas concurrentes sobre un mismo Client; este diagnóstico reutiliza un Client transaccional y no indica un error HTTP actual.
- HTTP público: directorio 200/480 ms; juego+plataforma 200/299 ms; juego inexistente 200 con cero resultados; cursor inválido 400; sin sesión 401. Caché privada en respuestas de jugadores. `tmp/playmatch-http-20260914.log`. No se creó una conexión o un match real para esta prueba.

## 4. Despliegue y conectividad

Se recreó **sólo API** con reversión preparada; los otros siete servicios permanecieron activos. No hubo migraciones de tablas ni reinicio completo del servidor.

- Imagen activa: `conectate-api-playmatch:20260914`, ID `sha256:f5c8bef3092179972716621f4ccbe881af9d95696dabb8d921b12eddfa2340ed`.
- `playmatch-routes.js`: SHA-256 `e3ff525e4968230ba1334a1d72bff528ebf7558acdaf76d976b2d052d5c47769`.
- `playmatch-candidate-constraints.js`: SHA-256 `e059a075090885d9583b231c8432e4a1ffab8da9acc231fa497fb68c8b188261`.
- Se conservaron sin cambios los cuatro archivos de música/permisos del informe 24; hashes verificados dentro del contenedor.
- Fuente del host reconciliada. Release: `/home/alvaro/conectate-releases/api-playmatch-20260914`. Copia previa del archivo sustituido: `host-before.OW2tK9/playmatch-routes.js`.
- Rollback: `conectate-api-before-playmatch:20260914`, imagen previa `sha256:8711ac094a7b25fe5e426b053d56b79446380458ace27b7629bbadf560d39a90`. El script `rollout-api-playmatch-20260914.sh` revierte ante fallos de salud o diagnóstico; para reversión manual, reetiquetar la previa como `conectate-local-fallback-api:latest`, recrear sólo `api`, restituir el archivo previo y verificar `/ready`. El nuevo módulo no usado puede permanecer.

Lecturas públicas posteriores, desde el diagnóstico del servidor a su URL HTTPS (no latencia LTE ni prueba de carga): Social 383 ms/37 publicaciones; Videos 449 ms/12 elementos; perfiles 455 ms/20; imágenes 286–337 ms; dos rangos MP4 de 1 MiB con 206 en 420 y 301 ms. Mensajes: bandeja 343 ms, grupos 579 ms, comunidades 323 ms, historial 465 ms, programados 271 ms. Momentos 447 ms; salas de voz 157 ms; catálogo de juegos 417 ms; capacidades LIVE 322 ms; catálogo de 24 regalos 313 ms. No había LIVE activo para probar recepción audiovisual de regalos.

Logs: `tmp/verify-social-read-paths-after-playmatch-20260914.log`, `tmp/verify-messaging-read-paths-after-playmatch-20260914.log`, `tmp/verify-service-read-paths-after-playmatch-20260914.log`, `tmp/api-playmatch-rollout-20260914.log`.

## 5. Bloqueos y siguiente trabajo

1. **Privacidad de multimedia:** el diagnóstico local vuelve a exponer ocho rutas públicas/legadas de cuatro buckets privados. `tmp/messaging-media-privacy-20260914.log`. La puerta de release sigue bloqueada. Requiere ACL por objeto y referencias firmadas coordinadas en Android/Web; no basta con cerrar rutas y romper clientes antiguos.
2. **PlayMatch Android:** `setQuery` cancela el debounce, pero `search` lanza otro trabajo independiente; respuestas viejas y paginación pueden sobrescribir filtros nuevos. `refreshHome` espera todos los resultados y conserva listas antiguas incluso ante una respuesta vacía válida. Aún no corregido en esta entrega; necesita generación de solicitudes, cancelación real y pruebas de carreras, sin mezclarlo con esta compilación de Momentos.
3. Completar la comprobación física del cambio de cabecera y medir arranque/scroll en ventanas comparables. No equiparar un test debug con benchmark release.
4. Llamadas, salas y LIVE/regalos requieren dos extremos reales y redes representativas; catálogo accesible no prueba audio/video, cobro, recepción o animación remota. No gastar moneda de cuentas reales como prueba.
5. El túnel público temporal, la carga concurrente y recuperación integral siguen pendientes de la puesta en producción. Aplican también los pendientes del informe 24 y su plan Gamer Hub progresivo.
