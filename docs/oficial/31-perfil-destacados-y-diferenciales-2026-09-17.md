# Próxima etapa: destacados del perfil y funciones diferenciales

Fecha: 2026-09-17. Rama de trabajo: `feature/live-gifts`. Este documento distingue las correcciones implementadas de la ampliación pendiente; no certifica producción.

## Orden pedido

1. Terminar la verificación de identidades offline de 30.
2. Corregir el destacado de video que, al terminar, deja la imagen detenida y vuelve a oírse el audio. Mostrar autor y foto/logo en la cabecera del destacado.
3. Mejorar las fechas de Momentos: antigüedad reciente, y desde unas cinco horas una fecha/hora concreta.
4. Investigar e implementar tres funciones diferenciales en cada sección: Mensajes, Videos, Social, Juegos y Perfil (quince en total). Deben ser fáciles de usar y útiles, con comparación de funciones documentadas de competidores y pruebas de cliente/servidor.

Se preguntó de forma no bloqueante si priorizar uso diario, gamers o un equilibrio. Mientras se espera esa preferencia, las correcciones anteriores siguen dentro del alcance y no requieren detenerse.

## Hallazgos de código del destacado

- `MomentHighlightViewerScreen.kt` pasa `onVideoEnded = {}` a `MomentContent`. Tampoco avanza ni cierra al terminar la última imagen. `paused` está fijado a `false`.
- `MomentContent` configura el video con `REPEAT_MODE_OFF`. `MomentBackgroundMusic` repite su fragmento mientras el contenido permanezca visible y activo. Esto explica un camino concreto compatible con el síntoma: termina el video, queda el último fotograma y el audio separado sigue repitiéndose. No se ha reproducido todavía en un teléfono en esta etapa; no descartar además problemas de superficie/decoder.
- El visor de destacados construye un `Moment` con `userId = ""` y sin autor, avatar ni `createdAt`. Su cabecera muestra sólo el título del destacado, no reutiliza `MomentAuthorHeader`.
- `MomentHighlightRepository.getHighlightItems` consulta explícitamente los momentos porque el endpoint compatible no expande relaciones anidadas. El `select` actual no solicita `user_id`. `MomentHighlightItemPreview` tampoco tiene campos de autor.
- El mapeo de fecha prioriza `created_at` del vínculo al destacado sobre la fecha del momento original. Hay que distinguir ambos eventos para no presentar la fecha de destaque como fecha de publicación.
- `MomentHighlightViewerViewModel.load` no conserva/cancela su trabajo ni comprueba generación de respuesta; el guard de ID también impide reintentar un mismo destacado tras error. Revisar la carga acotada y la separación de datos del autor para no añadir espera innecesaria.

## Criterios para la corrección

- Un final real del elemento visible debe detener conjuntamente video y música, avanzar al siguiente elemento o cerrar al terminar el último. No deben actuar callbacks de una página anterior ni producir avances dobles durante un gesto de paginación.
- Pausar al salir a segundo plano o dejar de ser la página activa; liberar los reproductores al cerrar. Verificar volver atrás y reproducir nuevamente, con y sin música externa/embebida, una o varias páginas e imágenes mezcladas con videos.
- Cabecera con nombre y avatar obtenidos del autor real; sin atribuir el contenido al espectador actual. Reutilizar el componente de autor que ya separa canción/nombre y respeta insets.
- No añadir N consultas por cada fotograma ni usar una relación anidada que el backend no resuelve. Obtener los autores necesarios por lote o reutilizar la información disponible; tolerar que sus fotos o metadatos no lleguen aún.
- Mantener las reglas de expiración, privacidad y contenido eliminado; la corrección visual no autoriza publicar destacados privados ni revivir momentos que el servidor niega.

## Fechas

`formatMomentTimestamp`, actualmente privado dentro de `MomentViewerScreen.kt`, sólo acepta `Instant.parse`, usa `Ahora` ante error y muestra hora más antigüedad hasta días. Hay que extraer una política comprobable, revisar los formatos realmente recibidos y compartirla entre el visor normal y los destacados.

Propuesta de criterio coherente con la petición: menos de un minuto «Hace un momento», luego minutos y horas hasta antes de cinco horas; desde cinco horas, fecha y hora local concretas, con año cuando corresponda. Mantener acceso a la hora exacta también en contenido reciente si el espacio lo permite. Una fecha ausente o inválida no debe fingir que acaba de publicarse. Probar exactamente los límites de minuto/hora/cinco horas, cambio de día/año, zona horaria y relojes desajustados. La etiqueta debe actualizarse mientras está visible sin recargar el feed.

## Investigación de diferenciales

No es comprobable una ausencia absoluta en todas las aplicaciones del mundo. El compromiso es comparar documentación oficial vigente de un conjunto explícito de productos, registrar equivalentes encontrados y no llamar «inédita» a una función conocida con otro nombre. La diferenciación puede ser una combinación útil y bien implementada; no se presentará como novedad universal garantizada.

Antes de implementar cada propuesta, registrar: problema que resuelve, recorrido de uso, comparación y fuentes, datos/permisos que necesita, funcionamiento offline, límites de abuso, módulos de Android/API, pruebas y reversión. Evitar añadir botones vacíos, pantallas que simulen envíos, gastos de regalos o permisos nuevos sin necesidad. Priorizar completar los recorridos y conservar el contenido existente.

### Primera criba documental (17 de septiembre)

Estas referencias oficiales sirven para descartar falsas novedades; no constituyen una auditoría exhaustiva de competidores ni pruebas de ausencia de otras funciones:

- Mensajes: WhatsApp ya documenta listas personalizadas que agrupan chats individuales y grupos. No proponer una lista de chats como función inédita. [WhatsApp: listas](https://blog.whatsapp.com/focus-on-what-matters-with-custom-lists?lang=nb).
- Mensajes: Instagram documenta traducción, programación de mensajes, música, contenido fijado y QR de grupos. Un cambio de nombre no los convierte en diferenciales exclusivos. [Meta: funciones de Instagram DM](https://about.fb.com/news/2025/02/new-instagram-dm-features-stay-connected/amp/).
- Videos: TikTok ya permite ajustar cuánto contenido de ciertos temas aparece en Para ti. Un selector simple de temas no cumple por sí solo la diferenciación buscada. [TikTok: Manage topics](https://support.tiktok.com/en/account-and-privacy/account-privacy-settings/manage-topics).
- Videos: YouTube documenta transcripciones navegables que llevan a un instante al tocar el texto. No considerar el salto desde una transcripción una novedad universal. [YouTube: transcripciones](https://support.google.com/youtube/answer/15930243?hl=en).
- Social: X ya tiene notas comunitarias para añadir contexto y calificar su utilidad. Una nota adjunta a una publicación tampoco basta como propuesta inédita. [X: Community Notes](https://help.x.com/using-twitter/community-notes).
- Juegos/Perfil: Discord documenta widgets con juegos favoritos, pendientes y estadísticas, además de actividades compartidas en voz. No presentar un listado de juegos, estadísticas o «jugar juntos» como innovación exclusiva. [Discord: widgets](https://support.discord.com/hc/en-us/articles/35344672307607-Profile-Widgets-FAQ), [Discord: actividades](https://discord.com/blog/server-activities-games-voice-watch-together).

Falta ampliar la comparación de candidatos concretos a Messenger, Telegram, Signal y otras herramientas relevantes antes de escoger las tres propuestas de cada módulo. «No encontrado en esta documentación» nunca se convertirá en «no existe».

## Primera corrección local: formato de fechas del servidor

Se reprodujo un defecto adicional del feed real con `buildMomentsFeed` y un pool sintético: `textOf` convertía las fechas nativas devueltas por PostgreSQL mediante `String(Date)`. La prueba esperaba `2026-09-17T12:34:56.789Z` y recibió `Thu Sep 17 2026 07:34:56 GMT-0500 (hora estándar de Colombia)`. Esto no es el ISO que `Instant.parse` espera en el visor. El fallo se observó antes de aplicar la corrección; no se usaron filas ni contenido de usuarios.

Implementado localmente `api/src/moment-timestamps.js`, separado de las rutas: normaliza fechas nativas y cadenas con zona horaria a UTC ISO, conserva milisegundos y no inventa una publicación reciente si falta la fecha. `moments-routes.js` lo usa para `created_at` y `expires_at`. Una expiración presente pero inválida excluye el elemento; nunca se transforma en una expiración vacía que lo vuelva permanente. No se cambia el valor almacenado en PostgreSQL. Las cadenas sin zona son ambiguas y no se adivinan según la zona de la máquina.

`npm run test:moments` pasó el 17 de septiembre aproximadamente a las 14:01: **20 pruebas, cero fallos y cero omitidas** (9 de rutas, 3 del renderer y 8 nuevas de fechas/feed). Evidencia: `tmp/moment-timestamps-validation-20260917.log`. Casos: Date nativo, formatos ISO/PostgreSQL y offsets, cambio de año, ausencia de fecha, milisegundos, fechas inválidas, expiración y conservación del instante. La prueba sintética usa el generador real de feed, no sólo la función auxiliar.

Preflight de sólo lectura a las 14:05 mediante `scripts/audit-moment-timestamp-shapes.js`: transacción `BEGIN READ ONLY`, timeout de cinco segundos, sin exponer IDs, URLs ni contenido. Se encontraron 12 momentos locales, con publicación y expiración de tipo `timestamp with time zone`, sin fechas ausentes; y 15 importados, sin fechas ausentes ni formatos temporales sin reconocer por la expresión completa con zona. Evidencia: `tmp/moment-timestamp-shapes-20260917.log`. La comprobación de forma no sustituye la validación del calendario ni garantiza datos futuros, pero no encontró las fechas sin zona que impedirían avanzar con esta normalización. El script se pasó por stdin; no se copió código nuevo a la aplicación en ejecución.

## Despliegue acotado de fechas

A las 19:05 del 17 de septiembre se recreó exclusivamente API con la imagen `conectate-api-moment-timestamps:20260917`, ID `sha256:4770d6cf6bcaaac3c04bd0282ce41861b59767efc735e10ef099d881a886c8b7`. La candidata es una capa sobre la imagen musical verificada del 14 de septiembre, con sólo dos módulos de producción: `moments-routes.js` y el nuevo `moment-timestamps.js`. La comparación contra el archivo realmente desplegado mostró únicamente el import y la conversión de publicación/expiración. No se reconstruyó desde toda la copia de trabajo sucia ni se aplicaron migraciones.

Antes de cambiar la imagen se comprobaron el hash de la imagen activa, el de las rutas tanto en contenedor como en host y el respaldo. Se adquirió el bloqueo de Compose y se ejecutaron 20 pruebas en la imagen candidata, todas satisfactorias, más la comprobación de dependencias. Respaldo: `/home/alvaro/conectate-releases/api-moment-timestamps-20260917/host-before.jabCRI/moments-routes.js`. Imagen de reversión: `conectate-api-before-moment-timestamps:20260917` = `sha256:33508c74f6d10b89630285ccc7d9558274bfb11324aded2dc1fcfc3753d2a51c`. Evidencia: `tmp/moment-timestamps-rollout-20260917.log`. El script incluye rollback automático si falla el cambio o readiness; no fue necesario activarlo. Los fuentes del host se reconciliaron y compararon con la candidata.

Hashes desplegados: rutas `c1654100abf377f7c19988c7daa2fe7901a7b97f99eb23d519812576c1a77f0a`; helper `818b6dcddfe39d311f25be5e6c9fafafac196c6258d542954238957d063ddf0f`. `music-routes.js` conserva `255e421e29097f1631e07e18e2495f1efda64f4ba2093924e3767a3cce958a9e`; no se modificó música comunitaria ni permisos multimedia.

`verify-moment-feed-timestamps.js` ejecuta un SELECT dentro de una transacción read-only y una lectura HTTP autenticada, sin publicar, marcar vistas ni imprimir contenido/identidades/tokens. El feed interno devolvió 200 en 139 ms y un momento con publicación y expiración ISO válidas. Se añadieron dos pruebas locales de este diagnóstico, además de las ocho de serialización/feed. Un feed vacío se registra como cero filas comprobadas, no como evidencia de fechas reales.

### Rotación pública observada durante la verificación

El origen temporal anterior `abstracts-toolkit-equivalent-reasons.trycloudflare.com` devolvió HTTP 530/Cloudflare 1033 tanto desde la laptop como desde el host; el backend interno seguía saludable. Se observaron reconexiones del conector de sistema y un nuevo conector propio. El supervisor **de usuario** `conectate-cloudflare-tunnel.service`, distinto del servicio de sistema `conectate-cloudflared.service`, publicó automáticamente `https://participation-broker-rise-crew.trycloudflare.com` a las 19:08:28. No se reinició manualmente otro servicio para tratar este incidente. La reconciliación existente del supervisor puede recrear los servicios dependientes del origen al rotarlo; no confundirla con el rollout acotado de API.

El nuevo origen devolvió el feed autenticado HTTP 200 en 1446 ms, con una fila real y ninguna fecha ausente/malformada (`tmp/moment-feed-public-timestamps-recovered-20260917.log`). `/ready` desde la laptop respondió 200 en 2,083 s. El TXT público de `conectate-alvaro.duckdns.org` se verificó criptográficamente con `config/backend-discovery-public-key.txt` y resolvió ese nuevo origen. No se desactivaron verificaciones TLS ni se fijó el nombre temporal en Android. No se demostró que el cambio de API causara la pérdida del nombre anterior; tampoco se oculta la interrupción externa observada.

El monitor existente completó su siguiente ronda a las 19:14:54: `status=ok`, cero fallos consecutivos. Por el origen nuevo: Social 37 elementos/565 ms; Videos 12/528 ms; búsqueda de perfiles 20/653 ms; inbox 7 chats/792 ms, 7 grupos/643 ms y 3 comunidades/494 ms; historial de un chat 194 mensajes/712 ms; imágenes 200; dos rangos MP4 de 1 MiB 206 en 996/759 ms. Son lecturas, no envíos ni reproducción audiovisual real. Ocho contenedores estaban saludables. Tras la reconciliación automática del origen, API, signaling, realtime y edge mostraban uptime nuevo; no se presenta esto como «ningún otro servicio se recreó».

La documentación de Cloudflare atribuye 1033 a la falta de un conector saludable para ese túnel. [Cloudflare: error 1033](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1033/). Esta recuperación no convierte Quick Tunnel en un endpoint de producción estable ni verifica automáticamente un teléfono con descubrimiento cacheado.

## Android implementado, validación cerrada sin dispositivo

- `MomentHighlightLoader` separa carga y enriquecimiento del autor. Hay límite de diez segundos por lectura, cancelación/generación para respuestas obsoletas y reintento del mismo destacado tras un error. El contenido aparece antes de esperar el lote de perfiles; una foto/autor lento no vuelve a vaciar el visor. No se atribuye un autor desconocido al espectador.
- El repositorio pide `user_id` del momento original. `MomentHighlightMapper` exige ese original legible y conserva su fecha de publicación, expiración, mezcla musical, atribución y overlays. No utiliza una copia huérfana ni la fecha de creación del enlace al destacado como publicación.
- `MomentHighlightContent` convierte a `Moment` conservando metadatos. El visor reutiliza `MomentAuthorHeader` para nombre/avatar, fecha y canción, con insets de la barra de estado y distribución medida. Una actualización tardía del nombre no recrea el reproductor.
- `HighlightPlaybackSession` admite una única finalización de la visita visible. Descarta callbacks duplicados, de otra página, de una visita anterior incluso al mismo índice, durante el gesto o en segundo plano. Finalizar avanza al siguiente elemento o cierra el último, también para imágenes. Las páginas vecinas son posters, sin reproductores de audio competidores. Hay un control de pausa/reanudación accesible.
- El salto automático es inmediato y bloquea nuevos arrastres durante su transición: no deja una página marcada como terminada a mitad de una animación cancelada. `MomentPlaybackLifecycle` comparte la pausa por ciclo de vida con el visor normal, incluidos los relojes de imágenes; volver de segundo plano no consume el tiempo que se estuvo fuera.
- El tiempo de imagen sólo corre después de que la imagen se resuelva y su música arranque (o agote el fallback existente); conserva progreso al pausar. El video mantiene su posición al pausar. Ambos se pausan con el ciclo de vida y se liberan al salir.
- En el componente compartido `MomentContent`, la música separada sólo recibe reloj mientras el video informa reproducción activa: espera durante buffering y se detiene al final. `AndroidView.update` vincula el reproductor vigente y `onRelease` lo separa de la vista, evitando conservar una vinculación obsoleta.
- `MomentTimestamp` acepta ISO/offsets de PostgreSQL con zona; muestra «Hace un momento», minutos u horas con hora exacta, y desde cinco horas fecha/hora local, con año cuando corresponde. Ausencias/fechas inválidas no fingen «Ahora». `MomentTimestampLabel` refresca sólo la etiqueta mientras la pantalla está activa; no recarga el feed.

Se añadieron 23 pruebas: fechas (5), sesiones de reproducción (6), mapeo (3), concurrencia/carga/autores (6) y reloj de música (3). La primera ejecución `tmp/highlight-playback-validation-20260917.log` generó un APK intermedio, pero no terminó: el código de producción ya había sido compilado antes de añadir la última firma de sesión/reloj y los tests nuevos no encontraban esos símbolos. No cuenta como validación final. La siguiente ejecución `tmp/highlight-playback-final-validation-20260917.log` pasó 720 pruebas en 159 suites, sin fallos/errores/omitidas, a las 19:13:11; Lint seguía en curso. Su APK de las 19:11:56, 270.983.212 bytes y SHA-256 `f747e7a9224ce9f708a0c1b74bfde8c05b44dde5e0d2714162b54cf678ff25ca`, tiene firma debug verificada, pero es intermedio: la revisión posterior añadió el cierre de la carrera de gesto y la pausa compartida del visor normal. Falta una nueva validación cerrada de esos últimos cambios; no entregar ese hash como si ya los incluyera.

### Cierre de validación: 18 de septiembre, 00:11:42 (Bogotá)

El resultado pendiente del párrafo anterior ya se resolvió. La ejecución de las 19:07 terminó fallando Lint por dos casos de `SuspiciousIndentation` en las expresiones de progreso/fin del visor. Se corrigieron expresamente, sin cambiar el baseline ni silenciar reglas. La ejecución de las 23:09 (`tmp/highlight-playback-verified-validation-20260917.log`) compiló el código final y ejecutó **720 pruebas en 159 suites**, con cero fallos, errores u omitidas (XML más reciente: 23:18:30), pero quedó interrumpida durante Lint y sin resultado global. No se atribuye una causa a esa interrupción que no se ha demostrado.

Se retomaron `assembleDebug`, `testDebugUnitTest` y `lintDebug`, conservando los resultados vigentes de los pasos ya completados. `tmp/highlight-playback-resumed-final-validation-20260918.log` terminó **BUILD SUCCESSFUL en 11m34s**, a las 00:11:42. El XML nuevo de Lint contiene **cero errores y 196 advertencias**. No son 720 pruebas adicionales: son las mismas 720 del código final, reutilizadas por Gradle por estar actualizadas.

APK final de esta etapa: `app/build/outputs/apk/debug/app-debug.apk`, 269.235.383 bytes, generado el 17 a las 23:17:29, SHA-256 `592a7179a061c2f762819513b8123c428d31fd41ed30e3ece0d50df86b987337`. Firma debug verificada con `apksigner`, certificado SHA-256 `36d82212584fbfe06adcb38f99590e45bca492c52e8432aab42eb9f0fbf290f9`. Este artefacto sí incluye el control de gesto, las correcciones de Lint y la pausa compartida; sustituye al APK intermedio `f747…`. No es un AAB de producción ni se instaló o publicó.

El conjunto local `npm run test:moments` quedó en 22 pruebas aprobadas, sin fallos ni omitidas, incluyendo los dos casos del diagnóstico (`tmp/moment-timestamps-final-tests-20260917.log`). La imagen del servidor contiene las 20 pruebas de la candidata, no se reconstruyó sólo para incorporar un script de diagnóstico que se ejecutó por stdin.

Sin dispositivo físico por instrucción vigente. Una prueba de la política de fin/pausa no demuestra reproducción continua ni composición visual correcta en el moto g04. Tampoco es una prueba de actualización en Play.

## Criba adicional de diferenciales

La segunda revisión descartó más propuestas que ya tienen equivalentes documentados: checklists compartidas (Telegram), mensajes fijados (Signal), notas personales y mensajes programados (Signal), álbumes compartidos/HD (Messenger). Fuentes oficiales: [Telegram: checklists](https://telegram.org/blog/checklists-suggested-posts), [Signal: fijados](https://signal.org/blog/pinned-messages/), [Signal: notas personales](https://support.signal.org/hc/en-us/articles/360043272451-Note-to-Self), [Signal: programar](https://support.signal.org/hc/en-us/articles/5365881590682-Schedule-a-Message-on-Signal-Android), [Meta: álbumes de Messenger](https://about.fb.com/news/2024/04/hd-photos-shared-albums-and-more-on-messenger/).

Además, NoWi ya tiene «Resumen del chat» en su código actual; no se contará como una función nueva. Falta comparar candidatos concretos, comprobar lo ya implementado en cada módulo y definir quince recorridos completos. No se añadieron botones de muestra ni se cambió el diseño de esos módulos durante la validación del destacado.

## Estado

Fechas del servidor implementadas, desplegadas y comprobadas en el feed interno y público. Destacados/fechas de Android implementados, compilados y validados automáticamente; comprobación física pendiente por la instrucción de continuar sin dispositivo. Las quince funciones diferenciales siguen **pendientes de selección, implementación y validación**. Identidades offline se registra por separado en 30; el cierre de reutilización indebida como sticker se continúa en 32. El objetivo general y los bloqueos de producción (privacidad de multimedia de chats, dominio estable, validación física y multiparte de LIVE/llamadas) siguen abiertos.
