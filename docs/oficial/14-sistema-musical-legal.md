# Sistema musical legal para Momentos

Fecha: 2026-08-15  
Rama: `feature/live-gifts`  
Estado: implementado para prueba cerrada; no habilitar públicamente hasta completar las puertas de producción de la sección 8.

## 1. Alcance implementado

El creador de Momentos dispone de un selector simple con dos fuentes visibles: **Mi música** y **Comunidad**. Mi música lee MediaStore y detecta título, artista, álbum, duración y portada cuando el archivo los contiene. Comunidad muestra únicamente canciones aportadas por usuarios, aprobadas y autorizadas para reutilización.

Fuentes visibles en el producto:

- biblioteca musical del propio dispositivo, que conduce a la declaración de derechos y publicación moderada;
- catálogo comunitario propio, únicamente después de moderación y con permiso explícito del autor.

Los adaptadores externos anteriores se conservan solo para validar y reproducir Momentos históricos ya publicados. No se consultan ni aparecen en el nuevo selector. Esto evita romper contenido existente sin volver a ofrecer música externa para nuevas publicaciones.

No se usa scraping ni se extrae audio de YouTube, Spotify, TikTok, Apple Music o SnapTube. La aplicación no acepta una URL musical escrita por el usuario.

## 2. Publicación y reproducción

Al elegir una pista se puede:

- escuchar o pausar la previsualización;
- seleccionar un fragmento de 5 a 60 segundos, sin superar la duración real;
- ajustar volumen musical y volumen original;
- activar reducción automática del audio original;
- configurar entrada y salida gradual;
- cambiar o retirar la canción;
- guardar en favoritos, compartir atribución y reportar. La música comunitaria se reproduce únicamente por streaming; no se ofrece descarga ni guardado sin conexión.

La publicación almacena identificador, título, artista, fuente, URL de origen, licencia, URL de licencia, URL de audio verificada, inicio, fin, ambos volúmenes, fundidos y ducking. El backend vuelve a consultar el proveedor externo o la pista comunitaria aprobada y reemplaza los datos del cliente con datos autoritativos.

En videos, Android usa Media3 Transformer/Composition para exportar una sola pieza H.264/AAC que mezcla el audio original y el fragmento musical con sus volúmenes, fundidos y ducking. Se persiste `music_embedded=true`, por lo que el visor nunca inicia una segunda pista y evita audio duplicado. En imágenes, el visor conserva un único ExoPlayer musical, sincronizado y liberado al cambiar de Momento o abandonar la pantalla.

En imágenes, la duración visible y musical es exactamente de seis segundos. La política es una función determinista compartida por visor y destacados, y el backend normaliza contenido antiguo. El tiempo funcional usa `SystemClock.elapsedRealtime()` y no depende de la escala de animaciones del dispositivo. El avance espera `isPlaying=true`, no solo la preparación de Media3; al alcanzar `6000 ms`, imagen y audio se detienen juntos.

Para eliminar diferencias de decodificación entre fabricantes, el backend no entrega directamente el formato arbitrario del teléfono publicador. Renderiza un derivado M4A AAC-LC de seis segundos, 44,1 kHz, estéreo y `faststart`, conserva la atribución y el fragmento seleccionados y elimina ese derivado cuando se reemplaza o borra el Momento. La publicación falla de forma visible si el archivo fuente no puede decodificarse o el derivado no puede validarse.

## 3. Catálogo, streaming y seguridad

Rutas principales:

- `GET /v1/data/music/catalog/categories`
- `GET /v1/data/music/catalog/search`
- `GET /v1/data/music/catalog/tracks/:trackKey`
- `POST|DELETE /v1/data/music/catalog/:trackKey/favorite`
- `POST /v1/data/music/catalog/:trackKey/report`

El catálogo comunitario solo entrega una URL de reproducción autenticada/validada según corresponda. No se emiten tickets de descarga, no se usa `DownloadService` para música y el reproductor no mantiene una caché offline de estas pistas. Las descargas de archivos enviados dentro del chat pertenecen a otro flujo y no convierten la música comunitaria en contenido descargable.

Variables exclusivamente de servidor para operación del catálogo:

- `OPENVERSE_API_TOKEN`, opcional pero recomendado para cuota estable;
- `OPENVERSE_API_BASE`, solo para operación controlada;
- `JAMENDO_CLIENT_ID`, opcional; no relaja la política de licencias.

## 4. Música comunitaria

El usuario puede publicar una obra propia desde el selector. Android valida MIME, tamaño y duración; el archivo se guarda en `community-music`; el backend comprueba propiedad del objeto, vuelve a decodificarlo con `ffprobe`, calcula SHA-256, bloquea hashes previamente retirados y genera con `ffmpeg` una previsualización MP3 segura de hasta 30 segundos. Después registra la declaración de derechos. La pista queda `PENDING_REVIEW` y no aparece en búsqueda ni recomendaciones hasta ser `APPROVED`.

El autor elige de forma explícita si permite escucha, uso en Momentos y remix; la descarga no es una opción del producto. Se conservan versión de términos, identidad, fecha, evidencia, licencia, permisos e historial de moderación. Los términos actuales son un borrador técnico y requieren revisión jurídica antes de habilitar esta función fuera de prueba cerrada.

## 5. Datos

Aplicar en orden:

1. `20260814_moment_licensed_music.sql`
2. `20260814_community_music_catalog.sql`
3. `20260814_legal_music_system.sql`
4. `20260815_music_composition_and_retention.sql`

La migración del 15 de agosto añade `music_embedded`, retención offline, eventos reales de reproducción y recomendaciones auditables. Las tablas sensibles permanecen cerradas por RLS para que solo el backend pueda verificar licencias o emitir tickets.

## 6. Investigación oficial utilizada

- Android Media3: reproducción por streaming y liberación del reproductor.
- Android Media3 Transformer: arquitectura de edición no destructiva y límites de composición.
- Openverse API: endpoint de audio, metadatos y filtros de licencia.
- Jamendo API: autenticación, metadatos de pistas y permisos de reutilización.

Las referencias se revisaron el 15 de agosto de 2026. La verificación de licencia no depende del texto visible: se valida código y URL canónica devueltos por el proveedor.

## 7. Validación técnica

- comprobación sintáctica de rutas y adaptadores Node;
- pruebas de política CC0/dominio público y rechazo de licencias no admitidas;
- consulta real a Openverse con descarte de resultados no verificables;
- pruebas del backend de Momentos y música;
- compilación Kotlin y pruebas unitarias Android;
- reproducción Media3 con foco de audio, caché y liberación por ciclo de vida.

Evidencia de cierre del 15 de agosto de 2026:

- `npm run check:music`, `npm run test:music` y `npm run test:moments`: correctos; 3 pruebas de rutas musicales, pruebas de adaptadores Openverse/Jamendo y 7 pruebas de Momentos;
- `:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`;
- `:app:assembleDebug`: `BUILD SUCCESSFUL`;
- APK de prueba: `app/build/outputs/apk/debug/app-debug.apk`, 306 967 185 bytes, SHA-256 `C57DED37DBEE944AFA2612F6CF8517FF37AC5D37B819BB2D98E582935C1E97E9`;
- instalación limpia sobre la sesión existente del emulador `emulator-5554`: correcta;
- flujo manual verificado: abrir Crear Momento, escoger imagen, recortar, abrir Añadir música, cargar catálogo real, reproducir una pista de dominio público, seleccionar un fragmento de 15 segundos y volver al editor con la atribución visible;
- Android confirmó `USAGE_MEDIA / CONTENT_TYPE_MUSIC`, una pista `AudioTrack` iniciada y salida por altavoz; al cerrar el editor el foco de audio quedó vacío;
- se detectó y corrigió durante la prueba un `VerifyError` de DEX provocado por la firma excesiva del composable del editor. La firma quedó reducida a estado y acciones tipadas; el mismo recorrido dejó de cerrar la aplicación y no produjo otra `FATAL EXCEPTION`.

## 8. Puertas antes de producción pública

- aplicar y ensayar las migraciones en staging con copia de seguridad y rollback;
- reconstruir la imagen Docker de la API para incluir `ffmpeg`/`ffprobe`;
- obtener revisión jurídica de términos, licencias, atribución, reclamos y retirada;
- disponer de moderación real y procedimiento de apelación;
- verificar proveedor, reproducción y fundidos en varios teléfonos físicos y redes;
- ejecutar prueba de dos cuentas para carga, aprobación, búsqueda, reutilización, reporte y retirada;
- añadir monitorización de errores Media3, latencia del catálogo, fallos de proveedor, tickets y almacenamiento;
- validar límites y rate limiting bajo carga.

No debe declararse lista para producción pública solo porque compile. La implementación queda apta para continuar la prueba cerrada una vez aplicadas las migraciones y variables indicadas.

## 9. Verificación reforzada del 22 de agosto de 2026

- E2E real de publicación y lectura: imagen `6000 ms`, música `6000 ms`, AAC-LC, 44.100 Hz, estéreo, volumen máximo `-11,5 dB` y decodificación no silenciosa.
- La cuenta espectadora recibió el audio canónico y, al borrar el Momento, el objeto pasó a `404`; no quedaron derivados temporales en la base.
- Android publicó desde galería una imagen con WAV local de siete segundos, mostró el corte `0:00–0:06`, abrió el propio Momento desde `Tú` y mantuvo una pista activa de `conectate.com` en `AudioFlinger` a 44,1 kHz y estéreo.
- La prueba Android se repitió con las tres escalas de animación en `0`, condición que reproducía el fallo de un segundo antes de sustituir `Animatable/tween` por reloj monotónico.
- Producción: release `conectate-mini-pc-20260822T062433Z`; `/ready` exige `moment_music_aac_lc_v1` para que un backend antiguo no pueda aceptarse silenciosamente.

Esta evidencia valida el recorrido observado y el contrato técnico. La matriz de teléfonos físicos y salidas de audio sigue siendo una puerta obligatoria para afirmar compatibilidad pública amplia.
