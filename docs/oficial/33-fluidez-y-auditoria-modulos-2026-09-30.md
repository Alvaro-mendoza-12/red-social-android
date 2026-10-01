# 33 — Fluidez y auditoría de módulos de Novvin

Fecha: 30/09/2026, America/Bogota. Algunos respaldos y registros tienen fecha 01/10/2026 UTC.
Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Versión mantenida por indicación del propietario: `versionCode 117`, `versionName 9.0.20`.

## Alcance y conclusión

Se corrigieron rutas críticas de audio, recuperación de contraseña, notificaciones de llamadas,
editor, búsqueda, ranking y permisos de Juegos/Momentos. El servidor recibió estos cambios.
La compilación, pruebas automatizadas, inventario y lecturas HTTPS públicas cubren los módulos
indicados abajo. No certifican cada opción visual ni cada línea del proyecto.

**La entrega completa para producción y la paridad web siguen abiertas.** La APK se actualizó en
el Motorola moto g04 conservando sus datos; once pruebas nativas pasaron en la repetición actual. No se verificaron escucha humana, recepción real del correo,
llamada con teléfono bloqueado, 60 FPS reales, larga duración
ni AAB firmado con la clave original. Estos límites también aparecen en el documento 08.

El propietario amplió el trabajo: completar móvil primero; después cerrar la paridad web,
subir a GitHub y desplegar en Vercel. Los cambios terminados se subieron a `origin/feature/live-gifts`;
no se completó la paridad ni se ejecutó un despliegue Vercel en esta etapa. Una ruta o pantalla existente no prueba paridad de opciones.

## Los seis puntos solicitados

| Punto | Implementación y comprobación | Cierre pendiente |
|---|---|---|
| 1. Reproducción fluida y recuperación multimedia | Audio preparado al abrir su pantalla, caché compartida, carga inicial de 250 ms, pausa/cancelación/reintento y liberación del reproductor. Invitado usa caché y tamaño de ventana actual. Dos sonidos publicados avanzaron y pudieron pausarse en emulador. | Arranque en frío, códecs/HDR, Bluetooth, estabilidad entre cambios de red y equipos de distintas gamas. No se garantizan 60 FPS por configurar 60 en el capturador. |
| 2. Lecturas rápidas, caché y carga acotada | Lecturas públicas por HTTPS con límites, cuenta aislada y revocación comprobada. La búsqueda tiene debounce de 250 ms y descarta respuestas de la cuenta/consulta anterior. Se mantienen ventanas acotadas en el backend. | Peor caso, cargas grandes, colas de DB y perfiles/rankings grandes. Aún existen operaciones por encima de un segundo. |
| 3. Llamadas y avisos con acciones claras | Responder/descartar conserva la cuenta destinataria. Rechazar desde otra cuenta usa su token guardado y no cambia la sesión activa. Una descarga tardía del avatar no reinicia el timbre ni revive la llamada. Pruebas de PendingIntents y política de refresco pasaron. | Entrega FCM física con app cerrada, pantalla bloqueada, fabricantes, ahorro de batería y permisos. No se afirma equivalencia completa con llamadas del sistema. |
| 4. Recuperación por correo durable | Código y trabajo de correo se guardan en la misma transacción. Destinatario/código cifrados AES-GCM, reclamación de jobs, reintentos acotados, cancelación del código anterior y borrado del payload al cerrar el trabajo. API responde sin esperar a Gmail. SMTP autenticado y TLS comprobados sin enviar correo real. | Bandeja real, spam, límites de Gmail, carga y procedimiento alternativo. SMTP es al menos una vez: una caída después del envío y antes del acuse podría repetir el mismo correo. |
| 5. Release/R8, tamaño y firma | Heap Gradle de 4 GiB, compilación debug y análisis estático. Se conserva la barrera que exige la clave original de carga. | AAB publicable/R8 final, tamaño comprimido, instalación limpia y actualización con firma original. Esta sesión no tiene la configuración original de firma. No se sustituyó la clave por una efímera. |
| 6. Interfaz adaptable, editor y estabilidad | Tamaño de ventana real en invitado/editor/stickers. Metadatos del editor fuera del hilo principal. Checkpoint acotado. Ranking filtrable. Filtro importado previsualizado y exportado en emulador y Motorola. Micrófono, reacciones y código corto capturados en Motorola. | Guardado durable entre cierres forzados, acceso de proveedores que revocan permisos, TalkBack completo, todas las pantallas y uso prolongado. SavedState cubre recreación gestionada por Android, no force-stop. |

## Resultados reproducibles

### Audio de Videos

Medición con `AnalyticsListener.onAudioPositionAdvancing`, salida silenciada y contenido publicado.
Los modos se ejecutaron consecutivamente; la preparación utiliza bytes descargados/caché del ensayo.
No equivale a medir el primer acceso desde una instalación vacía.

| Muestra | Reproductor anterior | Nuevo sin preparar | Nuevo preparado |
|---|---:|---:|---:|
| 1 | 2.970 ms | 1.772 ms | 125 ms |
| 2 | 1.491 ms | 1.683 ms | 174 ms |

La preparación tardó 163/164 ms en esas muestras. No hubo autoplay antes de pulsar.
La segunda muestra sin preparar fue más lenta que la anterior: se conserva ese resultado.
La cuenta de prueba se eliminó. Evidencia: `../evidencias-fluidez-2026-09-30/audio-preview.json`.

### Servidor y latencia

- Ingreso: `https://conectate.tail9b267c.ts.net`, forzando resolución IPv4 pública y TLS/SNI normal.
- Cliente de auditoría HTTP/1.1, keep-alive y cuenta aislada. No usa IP LAN ni CGNAT de Tailscale.
- Última muestra: 48 comprobaciones satisfactorias, incluido ranking propio, búsquedas y permisos.
- p95 del tiempo completo: **574 ms**. p95 del tiempo de aplicación indicado por el servidor: **271,6 ms**.
- Casos superiores a un segundo: registro 1.457 ms (aplicación 306,2), ranking seguidores 1.031 ms
  (aplicación 526,6), borrado de la cuenta temporal 1.224 ms (aplicación 994,6).
- Estos son percentiles de una muestra pequeña y secuencial, no un SLA ni una prueba de capacidad.
- Tras recrear el API se observaron dos 502 en conexiones y partidas de Juegos. La repetición pasó
  48/48. Se conserva la ejecución fallida como evidencia de interrupción durante despliegue;
  todavía no se certifica despliegue sin interrupciones.
- Nueve contenedores se observaron saludables: API, DB, edge, realtime, signaling, Redis, TURN,
  IA y vista previa Office. SMTP autenticado con TLS en 2.731 ms; correos reales enviados: cero.
- Respaldo previo al correo: `/home/alvaro/conectate-backups/20261001T010003Z`.
- Respaldo previo al descubrimiento: `/home/alvaro/conectate-backups/20261001T021530Z`.
  El script verificó PostgreSQL, storage y datos de IA antes del despliegue.
- Respaldos de Juegos/Momentos: `/home/alvaro/conectate-backups/20261001T025542Z` y
  `/home/alvaro/conectate-backups/20261001T030905Z`; copias de fuente conservadas antes de reemplazar.
- Respaldo previo a optimizar el borrado: `/home/alvaro/conectate-backups/20261001T033102Z`,
  con PostgreSQL, storage y datos de IA verificados.
- Comparación reproducible: `infrastructure/local-fallback/scripts/verify-api-source-provenance.py`;
  cuatro pruebas locales verifican diferencias, archivos adicionales y normalización CRLF.
- 93/93 archivos JavaScript del API coincidieron por SHA-256 normalizado con la fuente revisada;
  `/ready` respondió correctamente. El marcador de release del stack aún indica el commit
  `52f948e5c2e5` del 28/09: **no identifica los parches actuales**. Actualizar su procedencia sigue pendiente.

### Interacciones entre cuentas y límites del ensayo

Ocho secciones se ejecutaron por HTTPS público: acceso/perfil, chat directo, grupos, comunidades,
stickers entre cuentas, video privado/publicación/comentarios/búsqueda, Social/galería/Momentos y Juegos.
La ejecución anterior de siete secciones pasó 74/74. Una ampliación terminó **91/93 y `ok:false`**:
dos borrados vencieron a los doce segundos y otro tardó 10.917 ms. Se conserva ese fallo.
La consulta de limpieza legacy se corrigió para expandir el JSON una sola vez y convertir a texto
únicamente los campos de identidad. Su prueba de PostgreSQL aislada conservó las filas ajenas.
Después del despliegue, la repetición pasó **95/95**, incluidas limpieza y revocación (401).
El p95 de esa repetición fue **810 ms**; el peor borrado todavía tardó **3.580 ms** (servidor 3.292,8),
y una subida 1.577 ms. Una cuenta nueva sin contenido se borró en **428 ms** dentro del servidor;
la consulta legacy fue su tramo más lento, 192 ms. Son cargas diferentes: no equivalen a un SLA ni
prueban que todos los borrados estén por debajo de un segundo.

Los ensayos detectaron y corrigieron dos defectos de autorización: un ajeno podía repetir una aceptación
de conexión en Juegos y recibir su fila; un ajeno podía registrar vistas/reacciones en un Momento privado.
Las rutas ahora comprueban participante y audiencia antes de devolver o modificar datos. Las regresiones
negativas están en suites y se repitieron contra el servidor. No certifica cambios concurrentes de audiencia.

Evidencias: `interacciones-74.json`, `interacciones-completas.json`,
`interacciones-95.json`, `perfil-borrado-sintetico.json`, `limpieza-cuentas-temporales.json` y `operaciones-lentas.json` en la carpeta de evidencias.
Los límites públicos de registro se respetaron; para la ampliación se crearon fixtures aislados por
bootstrap local y se verificaron por HTTPS. No se enviaron correos reales ni se usaron cuentas personales.

### Android y capturas

- 997 pruebas unitarias, 225 clases, cero fallos/errores/omitidas.
- Backend: 330 pruebas, 328 aprobadas, cero fallos y dos omitidas. Pruebas específicas de PostgreSQL
  para correo, búsqueda y limpieza se ejecutaron por separado.
- Lint: cero errores y 268 advertencias; no se ocultan las advertencias.
- Compilación debug de APK principal y APK de pruebas completada; versión 117 / 9.0.20 conservada.
- Emulador: diez pruebas de comunicaciones/recuperación/salas y una de exportación nativa pasaron.
- Motorola: seis pruebas iniciales de editor/salas/acciones con texto ampliado pasaron.
  La ejecución combinada actual fue primero 10/11: la lectura de accesibilidad no encontró
  Responder, aunque la captura mostraba Responder/Rechazar. Se corrigió el ensayo para esperar
  la finalización de los comandos shell y la actualización de ventanas de Android, conservando
  la comprobación del botón real. La repetición pasó **11/11 en 40,756 s**; emulador pasó su
  prueba de notificación en 8,698 s y exportación en 17,994 s. El fallo inicial se conserva.
  No equivale a una llamada real entregada por FCM ni a probar el teléfono bloqueado.
- Capturas sintéticas revisadas: micrófono completo fuera del avatar, reacciones en panel inferior,
  código de siete dígitos en una línea y editor con filtro rojo renderizado. En la prueba de llamada
  de 320 dp y texto al doble se puede desplazar hasta Rechazar/Contestar; la captura muestra esa
  posición desplazada, no toda la cabecera simultáneamente.

Evidencia: `calidad-automatizada.json`, `interfaz-nativa.json`, `interfaz-repeticion-actual.json` y capturas `motorola-*.png`.

Evidencia: `../evidencias-fluidez-2026-09-30/modulos-https.json`, `smtp-verificacion.json`,
`correo-postgres.json`, `busqueda-postgres.json` y el intento `modulos-tras-despliegue.json`.

### Medios entre redes diferentes

Motorola moto g04 con Wi-Fi desactivado y transporte celular comprobado por la prueba nativa;
emulador con Wi-Fi comprobado. El Wi-Fi del Motorola se restauró al acabar. Las cuentas temporales
se eliminaron, los tokens quedaron revocados y los archivos de credenciales de ambos equipos se borraron.

| Flujo | Imagen y paquetes de audio | Cadencia recibida / primer cuadro |
|---|---|---|
| LIVE host → espectador | Ambos extremos recibieron paquetes de audio; el espectador dibujó imagen | 9,66 FPS / 2.963 ms en espectador |
| Llamada bidireccional | Imagen y paquetes de audio en ambos; ruta TURN relay/relay | 8,53–9,93 FPS / 3.112 y 3.055 ms |
| Sala, primera ejecución | Imagen/audio; falló el mínimo de 8 FPS en Motorola | 7,20 FPS / 8.321 ms en Motorola; 9,93 / 2.682 ms en emulador |
| Sala, repetición con diagnóstico | Ambos extremos pasaron el umbral de 8 FPS | 8,53–9,93 FPS / 3.109 y 3.083 ms |

La repetición de sala no incluyó una corrección de WebRTC: muestra variación, no la desaparición
del fallo anterior. RTT de las muestras: aproximadamente 281–309 ms. Recibir paquetes no demuestra
escucha humana, sincronía percibida ni funcionamiento con pantalla bloqueada. El umbral del ensayo
es 8 FPS para detectar falta de imagen; **no representa una calidad suficiente para producción**.

Se midió además la pista local de video del Motorola antes del envío: trasera **10 FPS**, delantera
**20 FPS**, tanto solicitando 30 como 60. En sala se observó codificación VP8 por libvpx y decodificación
c2.unisoc.vp8. No se atribuye toda la lentitud al servidor: la limitación ya está presente en la captura.
Iluminación/exposición o el controlador pueden influir; esa causa todavía no está comprobada.

La colaboración real SFU aprobó invitación y batalla PK, mostró ambos medios y permitió finalizar.
El host conservó su emisión y pudo apagar/encender y sustituir la captura. Invitado: primer cuadro
3.574 ms, conexión 2.289 ms, 19,90 FPS. Host: 8,30 FPS; sus 16.822 ms hasta el primer cuadro incluyen
la espera deliberada del coordinador antes de arrancar al invitado. **No son una medición válida del
arranque con un invitado ya publicando**. Falta un reloj común respecto al momento de publicación.
Los valores false de controles/conservación en el informe del invitado indican pruebas aplicadas
solamente al host; no son fallos del invitado.

Evidencias: `medios-redes-*.json`, `medios-sala-repeticion-*.json`, `captura-motorola.json`
y `sfu-pk-*.json`. Referencia: [Camera2Session oficial de WebRTC](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/Camera2Session.java).

### Búsqueda, ranking y editor

- Búsqueda por título, descripción, etiquetas y comentarios visibles; tildes normalizadas.
  Los `%` y `_` se buscan literalmente. Se excluyen comentarios eliminados/denunciados,
  videos borrados/privados ajenos y perfiles que no son visibles para el solicitante.
- Temas relacionados abren el buscador. Los temas de descubrimiento se calculan de videos
  públicos del feed del mes y su interacción; **no se presentan como conteos reales de búsquedas**.
  Falta el servicio de tendencias de búsqueda mensual y recomendaciones de consultas por gustos.
- Ranking de usuarios: filtros seguidores/siguiendo/publicaciones, lista de cien y puesto propio
  calculado antes de recortar. Empates deterministas. Se cancelan los observadores anteriores al recargar.
  Los datos legacy todavía tienen límites de lectura; el puesto representa el catálogo visible cargado.
- Ranking de Videos/Publicaciones sigue ordenando una selección reciente. Un ranking global de
  contenido y su posición propia sigue pendiente; no llamarlo «top global».
- Editor: filtro de color `.cube` 3D SDR importable, 2–33 puntos, máximo 2 MiB, ocho filtros por
  proyecto y 16 MiB de archivos locales. Lectura/validación fuera de UI, copia privada con hash,
  checkpoint conserva la referencia y la exportación exige renderizar el filtro.
  «Original» quita el importado; «Limpiar filtros» conserva los usados por el proyecto actual.
- Preview actualiza los efectos al pasar al siguiente clip. LUT 1D, dominios no normalizados,
  archivos incompletos y colores no finitos se rechazan. No se importan efectos ejecutables
  ni paquetes propietarios de TikTok/Instagram. Un LUT sintético de dos puntos se renderizó y exportó
  con Media3 en emulador y Motorola, verificando el píxel rojo en el MP4. No certifica todos los LUT/códecs/HDR.

- Editor/subida: videos y audios usan el selector OpenDocument/OpenMultipleDocuments y retienen
  únicamente el permiso de lectura. Categoría del editor, clip destinatario del LUT y miniatura
  pendiente sobreviven a recreación. Tres pruebas cubren permisos concedidos, rechazados y URI
  no persistible; no prueban todos los proveedores ni que un archivo borrado siga disponible.
  Esto conserva el acceso a la fuente; el proyecto multicapa todavía no se guarda de forma durable
  entre cierres forzados.

Referencia: [permisos persistentes del selector de Android](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions).

Referencia técnica: [SingleColorLut de Media3](https://developer.android.com/reference/androidx/media3/effect/SingleColorLut)
y [implementación oficial del lector Cube de three.js](https://github.com/mrdoob/three.js/blob/master/examples/jsm/loaders/LUTCubeLoader.js).

## Matriz de módulos y opciones

El inventario reproducible está en `../AUDITORIA-FLUIDEZ-INVENTARIO-2026-09-30.csv` y `.json`.
Incluye archivos Android, backend y web. Los conteos son léxicos; no cuentan opciones probadas.
Todas las áreas entran en compilación/análisis aplicable; la cobertura de ejecución es la siguiente:

| Área | Comprobación o corrección disponible | Falta para cerrar todas sus opciones |
|---|---|---|
| Inicio, registro, identidad, username | Unicidad, permanencia, privacidad, ciclo HTTP anterior y registro temporal actual | Idiomas/tamaños, cuentas legacy, errores de red y todas las validaciones visuales |
| Contraseña, 2FA, ayuda y revisión manual | Flujos de código/token anteriores, outbox real en esquema aislado, SMTP TLS, permisos y adjuntos en suites | Correo recibido y ciclo físico completo, 2FA perdido, contacto humano y entrega administrativa |
| Chats y bandeja | Lecturas autenticadas, guardados, marcadores, idempotencia, aislamiento y medios en suites | Cada acción entre dispositivos, reenvíos, búsquedas, grandes adjuntos y red intermitente |
| Grupos y comunidades | Directorio, roles/canales/perfiles/eventos y contratos de autorización | Altas/bajas simultáneas, moderación, llamadas de grupo, audio y todos los adjuntos |
| Stickers y teclado | Contratos de deduplicación, favoritos, ownership y reintentos; tamaño adaptable | Alternancia física de cuentas, teclado/scroll, envío rápido y pérdida de conexión |
| Notificaciones y amistades | Payloads/lecturas, solicitudes, badges y aislamiento; destinatario de llamada corregido | Push físico, aceptación/cancelación en ambos equipos y marcado leído en todos los accesos |
| Videos y audio | Reproducción/pausa previas, nueva medición de audio, búsqueda/comments, historial, colecciones y publicación en suites | Todas las acciones visuales, búsqueda a gran escala, permisos, exportación/subida y redes |
| Editor | Metadatos IO, checkpoint, importación de LUT; preview y exportación nativa probados en emulador/Motorola | HDR/códecs, revocación de permisos de proveedores, proyecto durable y exportación larga |
| LIVE, invitado, PK y regalos | Medios entre redes, invitación/PK SFU y controles del host; contratos de moderación/puntuación/límites | 60 FPS físico, SFU bajo carga, estabilidad del invitado y voz humana y compras externas |
| Salas de voz | Código corto, micrófono sin recorte, reacciones abajo; imagen/RTP entre redes (una ejecución falló y otra pasó) | Muchos participantes, Bluetooth, escucha humana, reproducción compartida y cambio de red |
| Llamadas | Imagen y RTP entre celular/Wi-Fi por TURN; acciones/recipient/lifecycle en pruebas | App cerrada/bloqueada, ahorro de batería, rechazo/aceptación cruzados, llamadas de grupo físicas |
| Social, momentos, música | Feed/galería de dos imágenes, comentarios/editado/reacciones y Momento con audiencia probados por HTTPS; vistas/reacciones privadas protegidas | Todas las pantallas, videos musicales, mezcla/exportación y concurrencia |
| Retos, rondas y ranking | Directorios, puntuación, layout anterior; ranking de usuarios nuevo | Ranking global por contenido, todas las rondas y concurrencia representativa |
| Juegos/PlayMatch | Catálogo/perfil/conexiones/versiones probados por HTTPS; aceptación idempotente autorizada; cancelación de tareas y guardas por cuenta añadidas | Cambio de cuenta visual completo, aceptación/cancelación simultánea y todas las pantallas |
| Perfil y enlaces | Búsqueda/tarjetas públicas, username, bookmarks y almacenamiento en suites | Previews externos, enlaces sin app y todos los ajustes/compartidos físicos |
| PDF/Office | Implementaciones y evidencia anterior | Documentos grandes/cifrados, fuentes, precisión visual y navegación física |
| Ajustes/accesibilidad | Lint, ventanas actuales y pruebas anteriores de texto ampliado | TalkBack, RTL, tablets, multiwindow, navegación/teclado de todos los módulos |
| Backend/operaciones | Nueve servicios, TLS, respaldo, PG, seguridad y lecturas públicas | Carga mixta/soak, continuidad de despliegue y recuperación de desastre representativa |
| Web | Inventario de rutas/áreas existente; corrección de un comentario con codificación inválida | Matriz de paridad por opción, QA visual/funcional y despliegue Vercel actualizado |

Retiros económicos permanecen fuera del alcance previamente acordado. No se hicieron compras reales.

## Prioridades restantes

1. **P1:** completar pruebas físicas de comunicaciones y entrega de correo; firmar/verificar el
   release con la clave original; medir capacidad de LIVE/TURN y cadencia real. No prometer cero delay.
2. **P1:** reducir los peores casos por encima de un segundo y cerrar despliegue sin errores transitorios;
   probar memoria/temperatura/cambio de red y pérdida de paquetes durante uso largo.
3. **P2:** guardado durable del editor, permisos de fuentes, QA de más LUT/códecs y recuperación por fallo.
4. **P2:** tendencias reales de consultas, ranking global de contenido y QA completo de opciones
   en mensajes, comunidades, Social, Juegos, administración y accesibilidad.
5. **P2:** paridad web por funcionalidades, navegación responsive y ecosistema de links;
   publicar GitHub/Vercel sólo con artefacto y configuración concretos comprobados.

## Diez heurísticas y operación

1. Estado visible: carga, importación, etapas de exportación y errores explícitos.
2. Lenguaje reconocible: etiquetas en español; descripción del formato de filtro permitido.
3. Control: pausar/cancelar/reintentar, Original y limpiar filtros no usados en el proyecto.
4. Consistencia: mismo reproductor/caché y contratos servidor/cliente; cuenta destinataria coherente.
5. Prevención: límites de archivos, valores, proyecto, clave original y lectura autorizada.
6. Reconocimiento: temas visibles, filtros del ranking y puesto propio sin buscar el UUID.
7. Eficiencia: preparación de audio, IO fuera de UI, cancelación de consultas/tareas antiguas.
8. Diseño: toolbar sin superposición y separación de estados/acciones; falta QA visual de cada módulo.
9. Recuperación: errores accionables, reintentos acotados y exclusión de resultados tardíos.
10. Ayuda: documentación canónica, evidencias reproducibles y límites publicados.

Revertir con `git revert` del cambio concreto. El servidor tiene respaldo y copias de fuente
`/tmp/novvin-mail-backup-20261001T010334Z` y `/tmp/novvin-search-backup-20261001T022022Z`.
También se conservan `/tmp/novvin-games-backup-20261001T025840Z` y
`/tmp/novvin-moments-backup-20261001T031209Z` y `/tmp/novvin-cleanup-backup-20261001T033345Z`.
El outbox es aditivo; conservar su esquema al revertir código y detener el worker antes de cambiarlo.
Si se necesita revertir una migración de datos, restaurar de forma ensayada el respaldo correspondiente.
Nunca publicar `.env`, tokens, códigos, archivos de fixtures privados ni contraseñas de firma.
