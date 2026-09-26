# 08 — Pruebas, release y handoff

Este documento define cómo probar, publicar y entregar el proyecto a otro equipo o IA.

## Estados permitidos

Usar siempre una de estas etiquetas:

- **Implementado**: el código existe.
- **Compila**: pasó build local.
- **Probado local**: probado en emulador/local.
- **Probado Android real**: probado en teléfono real.
- **Probado redes distintas**: probado con Wi-Fi/datos u otras redes.
- **Producción observado**: usuarios reales lo probaron.
- **Pendiente**: no validado aún.

No usar “listo” sin decir en qué nivel.

## Checklist antes de tocar producción

1. Revisar estado Git:

```powershell
git status --short
```

2. Crear backup si se toca backend/datos:

```bash
cd ~/conectate-local-fallback
./scripts/backup-ubuntu-primary.sh
```

3. Verificar backup:

```bash
./scripts/verify-backup.sh
```

4. Ver estado servidor:

```bash
~/bin/conectate-status
```

5. Ejecutar la puerta pública no destructiva después de actualizar API, edge o
storage:

```bash
cd ~/conectate-local-fallback
public_base_url="$(sed -n 's/^PUBLIC_BASE_URL=//p' .env | tail -1 | tr -d '\r"')"
docker compose exec -T \
  -e SMOKE_API_BASE_URL="$public_base_url" \
  -e SMOKE_REQUIRE_CONTENT=true \
  api npm run verify:social-read-paths
docker compose exec -T \
  -e SMOKE_API_BASE_URL="$public_base_url" \
  -e SMOKE_REQUIRE_CONTENT=true \
  api npm run verify:messaging-read-paths
```

Debe terminar con `"ok": true`, elementos en Social/Videos/Perfiles, imágenes
`200 image/*` y dos rangos `206 video/mp4` cuando el archivo mida más de 1 MiB.
La puerta de Mensajes debe registrar inbox de chats, grupos y comunidades,
historial con mensajes, perfil asociado y al menos un avatar `200 image/*`.

Después de cambiar permisos, imagen o montaje del volumen, ejecutar también:

```bash
docker compose exec -T api npm run verify:registration-avatar-e2e
```

Debe confirmar registro, subida del avatar, persistencia en perfil y lectura
pública. El fixture se elimina en `finally`; comprobar cero cuentas
`registration-avatar-e2e-*` si la ejecución fue interrumpida abruptamente.

## Pruebas Android mínimas

### Compatibilidad Android 16 / API 36

- instalar el AAB desde una pista interna de Google Play en un teléfono API 36;
- abrir todas las pestañas y confirmar que edge-to-edge no tapa contenido con barras de estado, navegación o recortes de pantalla;
- abrir teclado en login, búsqueda, comentarios, chat, editor de Momentos y formularios; confirmar resize/pan, foco y botón enviar;
- probar gesto predictivo atrás y botón atrás en feed, video, cámara/editor, llamada, LIVE, sala de voz, chat y modales;
- conceder, denegar y volver a conceder cámara, micrófono, notificaciones y selector multimedia;
- tomar y recortar foto de perfil, foto/video de reto y captura de Momento;
  confirmar que la cámara recibe la URI, que el resultado se abre y que no se
  concede acceso a otros archivos privados de caché;
- iniciar y detener llamadas, LIVE, pantalla compartida y audio con la app en segundo plano; revisar foreground services y sus notificaciones;
- probar teléfono, tablet/plegable y ventana redimensionable; comprobar que el modo cine no dependa de forzar orientación en pantallas de 600 dp o más;
- repetir inicio en frío, retorno desde segundo plano, cambio de cuenta y recreación de Activity.

Estas pruebas deben registrarse como **Probado Android real**. Un `qualityGateDebug` verde solo acredita **Compila** y pruebas automatizadas.

### Auth

- correo inexistente;
- correo Supabase migrado;
- correo local correcto;
- contraseña incorrecta;
- logout/login;
- token expirado.

### Inicio resiliente de Social y Videos

- abrir Social con una instantánea previa y red lenta: las publicaciones deben
  aparecer antes de la respuesta remota y seguir desplazables;
- repetir en modo avión: conservar la instantánea y no mostrar un loader que
  ocupe indefinidamente el feed;
- abrir Videos con catálogo previo: el primer elemento debe aparecer sin que
  una recuperación paralela borre o reinicie la lista;
- simular timeout de catálogo sin caché: después del límite debe verse error
  recuperable, máximo dos reintentos automáticos y luego botón `Reintentar`;
- cambiar de cuenta durante una carga: ningún post/video de la cuenta anterior
  puede reaparecer cuando complete o se cancele la solicitud vieja;
- devolver una página remota vacía con caché no vacía: la caché no se elimina;
- comprobar que el endpoint público entrega dos rangos consecutivos `206` y que
  el video visible reanuda desde su posición tras una rotación firmada.

### Mensajes

- abrir la bandeja con Room poblado y red lenta: los chats deben aparecer de
  inmediato y permanecer utilizables durante el refresco;
- abrir con Room vacío y servidor detenido: abandonar `Loading` al vencer el
  plazo y mostrar `Reintentar`;
- provocar dos colecciones del inbox, `ON_RESUME` y refresco manual simultáneos:
  sólo puede existir una lectura remota activa;
- abrir una conversación cacheada en modo avión: mostrar historial local sin
  esperar perfil, badges ni presencia remota;
- abrir una conversación no cacheada con endpoint bloqueado: después de 12
  segundos debe mostrarse error recuperable, nunca spinner infinito;
- reiniciar con un perfil previamente visitado y sin red: debe mantenerse el
  nombre y la foto cacheada, no degradarse innecesariamente a una inicial;
- enviar 1 a 1;
- recibir 1 a 1;
- último mensaje cambia;
- contador sube;
- contador baja al abrir;
- notificación desaparece al leer;
- fijar chat persiste;
- archivar persiste;
- favorito filtra;
- eliminar/vaciar chat no reaparece si esa es la regla funcional.
- con descarga automática activa, recibir una imagen, un audio, un GIF/sticker y un video pequeño en un chat directo; esperar a que aparezcan y volver a abrirlos en modo avión;
- repetir la verificación offline con un grupo y una comunidad;
- cerrar por completo y reabrir la aplicación todavía en modo avión: la conversación local y la multimedia ya descargada deben seguir disponibles;
- cambiar de cuenta y comprobar que ninguna copia multimedia de la cuenta anterior se resuelve;
- enviar contenido de foto/video **ver una vez**, abrirlo y confirmar que no aparece en `filesDir/chat-media-offline` ni vuelve a abrirse sin red;
- comprobar que un video con tamaño conocido mayor de 12 MiB no se descarga automáticamente.

### Grupos

- crear grupo;
- agregar usuario;
- ver participantes;
- enviar mensaje;
- contador por grupo;
- llamar grupo;
- salir/eliminar según permisos.

### Comunidades

- explorar comunidades;
- mis comunidades;
- unirse;
- contador;
- roles;
- admin-only messages;
- expulsar;
- promover/degradar admin;
- editar info.

### Momentos, cámara y música

- abrir el Momento propio desde la tarjeta **Tú**;
- tomar una foto con la cámara, pulsar **Listo**, recortar y llegar al editor;
- repetir la captura forzando rotación o recreación de Activity al volver de la cámara;
- publicar una imagen sin música y medir 6.000 ms desde imagen cargada hasta cierre;
- publicar una imagen con música y confirmar audio audible durante los mismos 6.000 ms;
- confirmar que el audio se detiene al cerrar, avanzar o salir del visor;
- validar el mismo Momento desde una segunda cuenta y un segundo dispositivo;
- seleccionar **Mi música > Elegir para publicar** y comprobar que aparece la revisión;
- seleccionar **Usar la canción completa** y comprobar que también aparece la revisión;
- seleccionar una canción comunitaria de varios minutos, mover el recorte más
  allá de los primeros 40 segundos y confirmar reproducción y selección;
- publicar en Comunidad, observar progreso, confirmación y aparición según moderación.

### Botellas anónimas

- lanzar con backend compatible y comprobar estado `searching`/`offered`;
- rechazar desde una segunda cuenta y confirmar que la botella busca otra persona;
- aceptar desde otra cuenta y confirmar apertura de un chat anónimo en ambos lados;
- cancelar la botella del emisor y comprobar que no vuelve a ofrecerse;
- salir y volver rápidamente de la pestaña sin perder disponibilidad;
- verificar que `/ready` anuncie `anonymous_bottles_v1` y que las rutas sin sesión respondan `401`, nunca `404`.

### Videos

- entrar al feed y comprobar que el primer video no vuelve a cero después de un segundo;
- cambiar estadísticas (vista/like/comentario) y confirmar que no se reconstruye el playlist ni se reinicia el decoder;
- hacer scroll, cargar la página siguiente y confirmar que Media3 agrega elementos sin reemplazar los ya reproducidos;
- salir a Social, volver a Videos y hacer doble toque en la pestaña: cada acción inicia una sesión de feed nueva;
- con videos ya visibles, confirmar que ese refresco conserva el reproductor
  mientras llega la página; deslizar durante un reintento lento no debe volver
  al video anterior ni mostrar el error de otra cuenta;
- cambiar entre dos cuentas y confirmar que cada una usa preferencias, historial y semilla propios;
- comprobar que una sesión nueva varía el orden sin destruir el ranking de afinidad del servidor;
- no exigir que dos feeds sean matemáticamente distintos cuando hay un catálogo menor que la página solicitada; registrar ese caso como catálogo insuficiente;
- publicar un video desde una segunda cuenta y observar su inserción en tiempo real sin reiniciar la app;
- eliminar ese video y confirmar su retirada inmediata de todos los feeds abiertos;
- probar autoplay, pausa/reanudación, barra de progreso, loading y recuperación de red;
- arrastrar la barra fina desde distintos puntos y confirmar que adelanta y
  retrocede sin reducir la zona táctil ni reiniciar el video;
- pulsar el `+` del avatar, confirmar que desaparece al seguir, el seguimiento remoto y
  la reversión visual cuando se simula un fallo;
- retrasar una consulta de estado de seguidos hasta después de confirmar
  el seguimiento: el resultado antiguo no debe hacer reaparecer el `+`;
- desde los tres puntos descargar un video, verificar audio, marca `NOVVIN`,
  crédito `@usuario`, aparición en `Películas/Novvin` y limpieza ante cancelación;
- abrir Ajustes → Videos y multimedia → Videos sin conexión, elegir una cantidad,
  completar descargas, activar modo avión, reproducir el feed privado y borrar
  uno/todos los archivos; repetir tras cambiar de cuenta para confirmar aislamiento;
- abrir un video desde una notificación, comentario o enlace profundo; simular una
  pérdida breve de red y confirmar hasta tres reintentos acotados, conservación de posición
  al renovar URL y botón de reintento al agotar la política;
- retrasar la carga de comentarios en ese visor: el video debe empezar sin
  esperar por ellos; volver atrás durante la recuperación cancela su consulta;
- verificar respuestas HTTP `206`, `Accept-Ranges`, ETag y `Server-Timing` para multimedia;
- abrir también un video desde desafío, replay, avatar y visor completo para
  confirmar que usa la misma política de red y que un fallo es recuperable;
- probar Wi-Fi y datos móviles con un video admitido: máximo 1080p, 31 fps, 4.5 Mb/s, 45 s y 32 MiB;
- para un archivo de más de 6 MiB en el fallback Ubuntu, interrumpir una petición
  después de un fragmento TUS, consultar `HEAD` y confirmar que la continuación
  empieza en el `Upload-Offset` confirmado; repetir la prueba con dos clientes
  simultáneos y confirmar que uno recibe `409` sin corromper el archivo;
- matar el proceso después del primer fragmento, volver a intentar con la misma
  URI legible y confirmar que Android reutiliza la sesión persistida; repetir
  tras expirar/inutilizar la sesión y comprobar que crea una nueva sin enviar
  bytes al origen equivocado;
- comprobar que un archivo fuera de política se transcodifica o se rechaza con explicación, incluso desde un cliente antiguo;
- publicar un video usando el audio de otro, comprobar que conserva sonido e identidad y que la página del audio lista todos sus usos;
- eliminar un video propio desde los tres puntos y confirmar eliminación realtime;
- validar thumbnails y MP4 `faststart`.

### Perfil

- abrir perfil propio;
- abrir perfil ajeno;
- comprobar que el avatar circular usa una miniatura nítida y no descarga el original repetidamente;
- abrir avatar y portada a pantalla completa, ampliar a 3× y confirmar que el visor sustituye la previsualización por el objeto original, sin mantener una imagen de 96/384 px;
- durante una conexión lenta, confirmar que la previsualización permanece visible y que no aparece un spinner central encima cuando ya hay imagen;
- interrumpir la red después de visitar el perfil y confirmar que avatar/portada precargados vuelven a abrirse desde disco;
- simular fallo del original: debe conservar la previsualización y ofrecer **Cargar calidad original**;
- editar perfil;
- foto/video corto si se implementa.

### Llamadas y salas

Probar con dos Android reales:

- misma Wi-Fi;
- Wi-Fi + datos móviles;
- llamada saliente;
- llamada entrante;
- aceptar;
- rechazar;
- colgar;
- sala de voz;
- micrófono;
- cámara;
- pantalla;
- reacciones;
- chat de sala;
- reconexión.

Para web, probar además llamada privada, sala y LIVE entre dos navegadores o un
navegador y Android:

- activar cámara o compartir pantalla después de establecer audio y confirmar
  que el peer remoto recibe el track sin salir/reentrar;
- detener pantalla desde el navegador y desde la UI; en ambos casos debe volver
  la cámara y finalizar el track de pantalla;
- provocar ofertas simultáneas desde ambos extremos y confirmar que no aparece
  `InvalidStateError` ni se pierde audio;
- cortar la red durante más de dos segundos, restaurarla y confirmar ICE restart
  y recuperación automática;
- mantener la red caída y confirmar que se detiene después de tres intentos con
  un estado visible, sin bucle infinito de signaling.

## Build release

Desde Windows:

```powershell
Set-Location "D:\red social - live-gifts"
python -m unittest discover -s tools/tests -p "test_*.py"
.\gradlew.bat :app:qualityGateDebug :app:assembleRelease :app:bundleRelease
python tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab --expected-cert-sha256 $env:NOVVIN_UPLOAD_CERT_SHA256
python tools/verify_r8_release.py --report build/reports/release/r8-release-verification.md --json build/reports/release/r8-release-verification.json
python tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab
python tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab
```

Para un smoke local firmado con una clave **efímera**, definir además
`NOVVIN_AUDIT_BUILD=true`. Esto hace que
`uploadCrashlyticsMappingFileRelease` quede en `SKIPPED` y evita subir a
Crashlytics un mapa que no corresponde al binario de Play. Nunca definir esa
variable en el workflow de producción. El artefacto de smoke debe moverse fuera
de `app/build/outputs/` y nombrarse `AUDIT-NOT-FOR-PLAY`.

La línea Android validada para 3.3.4/66 es Kotlin 2.2.10, KSP
2.2.10-2.0.2, Media3 1.11.0, Compose BOM 2026.06.00, Room 2.8.4 y Hilt
2.57.2. `tools/tests/test_media3_dependency_guard.py` falla si esas versiones
se desalinean o regresan APIs de Transformer retiradas.

`testDebugUnitTest` debe incluir
`DatabaseMigrationSchemaContractTest.latestNormalizerExactlyMatchesAuthoritativeRoomSchema`.
Cuando cambie `APP_DATABASE_VERSION`, el JSON exportado, las entidades y los
planes del normalizador deben entrar juntos. Antes de publicar también se deben
ejecutar en emulador las migraciones instrumentadas con filas históricas; el
contrato JVM prueba esquema, no sustituye la prueba de preservación.

`tools/quality_gate.ps1` comprueba el código de salida de cada proceso y falla
cerrado. Además usa `.gradle-user-home/` y `tmp/quality-gate/` dentro del disco
del workspace, evitando resultados falsos cuando la unidad del sistema no
tiene espacio. No se debe sustituir este gate por una lista de comandos que
continúe después del primer error.

AAB:

```text
app/build/outputs/bundle/release/app-release.aab
```

Antes de subir:

- confirmar `versionCode` mayor que Play Store;
- confirmar endpoint/discovery;
- confirmar firma release;
- confirmar alineación de 16 KiB del AAB y de todas las bibliotecas nativas de 64 bits;
- instalar en dispositivo real;
- revisar crash al abrir.

El workflow manual `.github/workflows/android-production-release.yml` es la ruta canónica para producir un candidato. Usa el entorno protegido `production`, exige la clave de carga y compara su huella SHA-256 con `ANDROID_UPLOAD_CERT_SHA256` antes de publicar el AAB como artefacto.

Secrets requeridos en el entorno de GitHub `production`:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`;
- `ANDROID_UPLOAD_STORE_PASSWORD`;
- `ANDROID_UPLOAD_KEY_ALIAS`;
- `ANDROID_UPLOAD_KEY_PASSWORD`;
- `ANDROID_UPLOAD_CERT_SHA256`;
- `ANDROID_FALLBACK_API_URL`;
- `ANDROID_SIGNALING_URL`;
- `ANDROID_BACKEND_DISCOVERY_HOST`.

Antes de cerrar un despliegue backend:

- construir la imagen API real y ejecutar `docker compose config`;
- comprobar que `/proc/1/status` dentro de `api` reporta UID/GID 10001;
- subir, leer con `Range` y eliminar un archivo de prueba en cada bucket
  multimedia crítico;
- confirmar que Nginx entrega el archivo autorizado mediante
  `X-Accel-Redirect` y que Node no transporta el cuerpo completo;
- reproducir al menos diez videos consecutivos en red móvil y Wi-Fi, registrando
  tiempo a primer frame, rebuffering y errores de carga;
- repetir momentos con imagen, video y música comunitaria en un dispositivo
  físico. Una compilación verde no reemplaza esta matriz.

## Handoff para equipo o IA

Antes de pedir cambios, entregar:

- esta carpeta `docs/oficial/`;
- salida reciente de `~/bin/conectate-status`;
- versión actual de Play Store;
- `versionCode` actual;
- endpoint discovery actual;
- descripción exacta del bug;
- pasos para reproducir;
- capturas;
- logs si existen.

Prompt recomendado:

```text
Lee primero docs/oficial/README.md y sigue el índice.
No asumas estado: valida con código y comandos.
No toques producción sin backup.
No declares listo WebRTC sin prueba en Android real y redes distintas.
Mantén Supabase solo como legado.
```

## Qué revisar si una IA anterior hizo cambios

- ¿Cambió endpoints a URL temporal?
- ¿Subió secretos?
- ¿Rompió discovery?
- ¿Tocó auth migration?
- ¿Modificó schema sin migración?
- ¿Cambió WebRTC solo en UI sin signaling/TURN?
- ¿Subió AAB con `versionCode` correcto?
- ¿Documentó pruebas reales?

## Reporte final esperado de cualquier cambio

### Gate de administración empresarial

Cuando cambien rutas, roles, moderación, catálogos, pagos o borrado de cuenta:

```bash
cd infrastructure/local-fallback/api
npm run test:admin
npm run test:music
npm run test:playmatch
npm run test:creator-payouts
npm run test:security
npm run check
```

La evidencia debe probar denegación de escalamiento (`view` no escribe, `write` no
administra finanzas/catálogos/sistema y `full` no delega ni borra) además del camino
permitido. En producción sólo se ejecutan lecturas autenticadas; el borrado se valida
con fixtures aislados.

Debe incluir:

```text
Cambios:
- ...

Archivos tocados:
- ...

Pruebas:
- ...

No probado:
- ...

Riesgos:
- ...

Rollback:
- ...
```

## Priorización de bugs

P0 — Servicio caído:

- app no abre;
- backend no responde;
- login roto para todos;
- base de datos caída;
- URL pública rota.

P1 — Producción crítica:

- llamadas/salas crashean;
- mensajes no llegan;
- notificaciones rotas;
- pérdida de datos;
- auth muestra flujo incorrecto.

Para el canario de push legacy/Supabase, comprobar además: petición sin secreto
`401`, configuración ausente `503`, payload mayor de 256 KiB `413`, entrega
autorizada sin PII/token en logs y webhook configurado exclusivamente con
HTTPS. Si la API Ubuntu ya es la única emisora, eliminar el webhook remoto en
vez de mantener dos productores de la misma notificación.

P2 — Experiencia importante:

- contadores incorrectos;
- perfil lento;
- videos lentos;
- stickers mal dimensionados;
- filtros no hacen nada.

P3 — Mejora:

- UI polish;
- animaciones;
- documentación;
- optimización secundaria.

## Evidencia Android de estabilización — 2026-09-04, 20:40 -05:00

- `:app:testDebugUnitTest`: 455 pruebas en 124 suites, 0 fallos, 0 errores y
  0 omitidas.
- `:app:assembleDebug`: aprobado con JDK 21.
- `:app:lintDebug`: aprobado, 0 errores; quedan 192 advertencias no bloqueantes
  del proyecto y ninguna apunta a los módulos nuevos de identidad/póster.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 274,194,560 bytes, SHA-256
  `374B6F84EE5F9D5DC1B028DE9EF819D114E83FC3E8690CC36F2A63855B18C2B7`.
- Regresiones cubiertas: una respuesta parcial del inbox no borra nombre/foto
  local; la precarga de avatares está acotada y deduplicada; los avatares de
  video no se descargan completos con Coil y conservan un póster durable después
  del primer fotograma; las fotos estáticas ya vistas también tienen copia WebP
  durable fuera de la caché evictable; las fotos de grupo sobreviven el viaje Room; un token
  vencido no bloquea el inbox ya cacheado; un chat directo conocido se resuelve
  localmente antes de usar la red; recuperación de reproducción y precarga
  multimedia conservan sus pruebas existentes.
- Regresiones adicionales: perfiles lentos no bloquean posts; su respuesta no
  resucita publicaciones borradas ni revierte reacciones/ediciones; cambio de
  cuenta/página o un reintento más reciente descarta la recuperación anterior;
  las consultas bloqueadas respetan sus plazos; un seguimiento confirmado no
  se deshace por un lote antiguo. Los errores de red conservan bytes cacheados.
- El visor individual también publica media antes de comentarios y reutiliza
  `VideoPlaybackRecoveryRunner`; fue compilado y analizado por lint. Su primer
  fotograma real y los cambios visuales requieren la prueba de dispositivo.
- Servidor: tests locales de los verificadores Social/Mensajes y de cuerpos
  HTTP lentos aprobados (3 archivos, 0 fallos); self-test del auditor de host
  aprobado en WSL y Ubuntu. Scripts instalados con SHA-256 coincidente. El
  monitor programado continuaba activo, estado OK y 0 fallos consecutivos a
  las 02:36 UTC del 2026-09-05. Mediciones detalladas en el runbook §6.

Pendiente en dispositivo físico: instalar este APK, visitar Mensajes con red
para calentar fotos, cortar Wi-Fi/datos y reabrir el inbox; recorrer Social y
Videos con red estable, red lenta y sin red. La compilación no sustituye esa
prueba de Media3/codec/GPU en el teléfono reportado.

## Evidencia de Mensajes durables — 2026-09-04

Suite completa: 475 pruebas en 128 suites, 0 fallos, errores u omitidas.
`:app:lintDebug`: 0 errores y 191 advertencias; informe final del 2026-09-04,
22:06 -05:00. La puerta test/assemble/lint se revalidó el 2026-09-05, 06:31,
con `BUILD SUCCESSFUL` tras compilaciones posteriores del IDE.
APK debug regenerado a las 06:31 -05:00 del 2026-09-05: 268,956,855 bytes, SHA-256
`3B41C0FC20686439DFA9B65AEB1A7B8411441F4C594821148C2515B032E2D9F5`.
Ruta: `app/build/outputs/apk/debug/app-debug.apk`. Sustituye el artefacto
anterior de la sección de estabilización de Social/Videos, cuyos cambios incluye.
Las 22 pruebas enfocadas incluyen las 2 previas de búsqueda directa y 20
regresiones nuevas:

- Filtrar cuenta antes del límite; cola vacía sin solicitar token; renovación
  fallida, error temporal, lote restante y rechazo definitivo.
- Cambio de cuenta durante renovación o envío; cancelación propagada;
  confirmación realtime concurrente sin volver a marcar `sending`.
- Bloqueo por ID entre pantalla/worker; mensajes distintos independientes y
  cancelación de un solicitante sin romper la exclusión mutua.
- Selección del chat exacto cuando varias cuentas escriben a la misma persona;
  exclusión de borrados/JSON inválido y preferencia por actividad reciente.
- Room real bajo Robolectric: consulta de candidatos, UPDATE de fallo que no
  revierte confirmaciones y reapertura del archivo conservando historial,
  referencia de foto y cola pendiente.
- Política del worker: un pendiente durable sobrevive el límite genérico de
  intentos; los errores ajenos a esa categoría mantienen su límite.

La primera corrida completa encontró `SQLITE_CANTOPEN` en la apertura WAL de
la prueba de archivo bajo Windows. Se aisló su archivo en `TemporaryFolder`
con nombre corto y la suite completa pasó. No se modificó el journal de Room
de producción ni se sustituyó la prueba por una base en memoria.

Checklist en teléfono con este bloque instalado (sin desinstalar ni borrar
datos): abrir chats conocidos desde inbox y perfil, enviar texto/foto en modo
avión, cerrar/reabrir, confirmar historial y pendientes, reconectar y verificar
una sola entrega; alternar dos cuentas con el mismo contacto y comprobar que
ninguna abre la conversación de la otra. Los módulos tienen pruebas locales;
esta prueba física sigue pendiente porque ADB no detectó dispositivos.

Cobertura que no se cierra con este bloque: pendientes de edición/borrado
entre cuentas (su propietario no equivale necesariamente al remitente),
creación de la primera conversación sin red y entrega multimedia entre dos
dispositivos. No extrapolar las pruebas de cola de envío a esos recorridos.

La inspección siguiente detectó lecturas de chats/historial sin comprobar
membresía y un fallback por ID que no seleccionaba Ubuntu. El bloque
`AccountChatAccess` implementa esas comprobaciones en búsqueda por ID, lectura
cacheada y observadores. La validación y el APK de ese bloque se registran
por separado al terminar sus pruebas; no atribuirlo al APK anterior.

## Evidencia de acceso a chats por cuenta — 2026-09-05

`:app:testDebugUnitTest`: 488 pruebas en 129 suites; 0 fallos, errores u
omitidas. `AccountChatAccessTest` añade 13 casos sobre el módulo utilizado
por el repositorio: lectura local sin red, ausencia de cuenta, caché de otra
cuenta, respuesta remota autorizada, ID/membresía remotos inválidos, cambio
de sesión durante lectura local/remota, grupos, limpieza de metadatos al
cambiar cuenta, cierre de sesión, retirada de membresía, respuesta tardía
y transición entre dos participantes del mismo chat.

APK debug generado a las 06:45 -05:00: 271,974,351 bytes, SHA-256
`CEE21947D00476537348AE8D946D293AA94F5F72E37F9235E23434F7B6C41E4C`.
Ubicación: `app/build/outputs/apk/debug/app-debug.apk`; incluye las correcciones
de envío y acceso por cuenta y sustituye el APK anterior de las 06:31.
Puerta completa terminada a las 06:52 -05:00 con `BUILD SUCCESSFUL`:
test/assemble/lint aprobados, 0 errores de lint y 191 advertencias.

No extrapolar estos casos a llamadas/reproducción ni a las colas de
edición/borrado. En particular, `retryPendingDeletes` aún selecciona filas
sin propietario explícito; `deleteMessage` reutiliza el único `pending_action`
de la fila y `editMessage` continúa dependiendo de una respuesta remota.
La siguiente implementación debe modelar el actor y la operación durable,
incluyendo edición/cancelación de un envío todavía pendiente y confirmaciones
concurrentes, antes de declarar equivalencia offline con WhatsApp.

Otro hallazgo para el siguiente bloque: `MessageRepository.clearData()`
cancela `sessionStatusJob`, pero ese observador sólo se crea en el `init` del
singleton. Revisar y probar logout/login dentro del mismo proceso para que
la recepción realtime vuelva a enlazarse sin reiniciar la app. No basta con
validar la lectura de mensajes ya cacheados.

## Revalidación de conectividad y cambio de cuenta — 2026-09-05, 12:28 -05:00

El estado saludable registrado por la mañana no describe el estado actual:
SSH a `100.91.84.2:22` agotó su tiempo de conexión; Tailscale muestra el
equipo `conectate` offline, visto por última vez hace dos horas, sin bytes
recibidos. `closest-dept-pay-sport.trycloudflare.com/ready` falla en DNS
(curl 6), mientras `cloudflare.com` resuelve y esta PC tiene red activa.
Esto demuestra indisponibilidad de las vías comprobadas, no permite decidir
si el mini-PC está apagado, sin internet o si fallaron servicios del host.
Se pidió comprobar alimentación/conexión; no se reinició ni se desplegó
nada a ciegas. Los tiempos de respuesta anteriores son evidencia histórica.

La prueba instrumentada `MessagesMultiAccountSwitchingTest` de las 06:58
falló esperando el rótulo de cuenta activa. La pantalla de producción no
tenía ese rótulo ni selector: el botón enviaba a Ajustes. Se implementa un
diálogo y ViewModel separados para cambiar desde Mensajes y permanecer en
la bandeja. La prueba conserva la comprobación de la identidad final; no
se considera aprobada hasta ejecutar de nuevo ese recorrido.

`SessionListenerBinding` mantiene vivo el observador de autenticación al
limpiar los datos de una cuenta y cancela/espera los consumidores anteriores
antes de enlazar los siguientes. Incluye pruebas para logout/login, vuelta
a la misma cuenta, renovación y limpieza asíncrona. Este bloque está en
validación y todavía no debe atribuirse al APK de las 06:45.

## Actualización del 9 de septiembre de 2026

La validación posterior terminó: 498 pruebas en 131 suites, cero fallos,
assemble aprobado y lint con cero errores/191 advertencias. El observador de
sesión y los cambios de feed/mezcla de momentos están incluidos en el APK del
8 de septiembre, 22:54 -05:00. Consulte el hash, incidente DNS, recuperación y
límites de evidencia en [recuperación de túnel y feed](16-recuperacion-tunel-y-feed-2026-09-09.md).
Las descripciones de conectividad del 5 de septiembre son históricas, no el
estado actual. Los cambios siguientes de la cola de borrados requieren su
propia validación y no se atribuyen a ese APK.

## Borrado durable y captura de programados — 2026-09-09

La puerta completa de borrado terminó a las 17:58 -05:00: 520 pruebas, 134 suites,
sin fallos/errores/omitidas; assemble y lint aprobados. APK de las 17:48:
269.038.775 bytes, SHA-256
`92131581D537BBADFC34A3AA28DDC88DBD103EAB8C4B15A03B835A38BEBA40EE`.
Detalle y límites en [borrado por cuenta](17-borrado-durable-por-cuenta-2026-09-09.md).

La captura posterior corresponde a una lectura de mensajes programados que
publicaba su excepción en el chat. La corrección de ese recorrido está en
validación separada; no está incluida en el APK anterior. Consulte
[timeout de programados](18-mensajes-programados-timeout-2026-09-09.md) para
las mediciones actuales, monitor desplegado y estado de las pruebas.

## Programados y bandeja: continuación del 10 de septiembre

El bloque de programados/cuentas migradas aprobó 551 pruebas en 136 suites
y test/assemble/lint terminó el 9 de septiembre a las 23:18 -05:00.
APK de las 23:08: 272.064.588 bytes, SHA-256
`E45E5599ECDD2DBB45ADD74702E5BF678A35BBB2D91FCE3200E69081D110D236`.
Es posterior al APK de borrado de las 17:48. No incluye la renovación de token
ni el nuevo lector de vistas previas, implementados después.

La puerta enfocada de renovación de token y vistas previas pasó 60 casos el
10 de septiembre a las 02:11. Se ejecuta la suite completa y el mismo recorrido
Room de bandeja con SQLite nativo. Véase
[visibilidad de bandeja](19-visibilidad-bandeja-2026-09-10.md) para fallos
encontrados, corrección, límites y resultados finales cuando estén disponibles.

