# Rendimiento móvil y comentarios — 19 de octubre de 2026

## Alcance y conclusión actual

Se revisaron las rutas de inicio, navegación, Videos, publicaciones, stickers y animaciones de comunidades. Se encontraron costes concretos en el código y se corrigieron. La conexión del Motorola se recuperó después de un error USB de Windows; la medición física está en curso. No se puede atribuir un porcentaje de la lentitud a cada hallazgo ni afirmar que toda la app ya es fluida sólo por compilar.

## Hallazgos corregidos

| Hallazgo confirmado en el código | Efecto posible | Corrección |
|---|---|---|
| `VideoRepository.getFollowingVideos` ejecutaba su flujo en el contexto de quien lo recogía. Tanto el feed como la comprobación de novedades de Home lo recogen desde el hilo principal. | Decodificación, normalización y serialización de la caché compiten con los toques y el dibujo, incluso desde otras secciones durante la comprobación de novedades. | El productor del flujo se ejecuta en `Dispatchers.IO`; las actualizaciones de la UI conservan su contexto. |
| Los comentarios de Videos componían `StickerPanel(visible = false)` y su argumento por defecto creaba el ViewModel antes de comprobar `visible`. | Leer la colección local, buscar y comenzar sincronizaciones de stickers al abrir comentarios, aunque nadie pulse el selector. | El selector de comentarios se crea al abrirse y utiliza un diálogo visible. El panel compartido también resuelve su ViewModel después de comprobar la visibilidad. |
| Abrir o cambiar la cuenta en la biblioteca volvía a serializar y guardar el catálogo, packs, favoritos, descargas y recientes que acababa de leer. | Trabajo de CPU y almacenamiento sin cambios del usuario. | Se conserva la lectura local y se persisten las mutaciones; se eliminan las reescrituras de la misma instantánea. El cambio de cuenta de `bootstrap` se hace en IO. |
| Cada carácter de una búsqueda lanzaba otra búsqueda sin cancelar la anterior. | Trabajo acumulado y posible sustitución de resultados nuevos por otros anteriores. | Espera de 180 ms, cancelación de la búsqueda previa y comprobación del texto vigente. |
| Cada nube leía el progreso durante la composición y modificaba su desplazamiento de layout en cada fotograma. | Recomposición y layout repetidos para respuestas cuyo texto no cambia. | El progreso se lee en `graphicsLayer` y sólo modifica la traslación visual. Se mantiene la trayectoria, la pausa y la interacción. |
| Comentarios observaban el grabador de voz compartido. | Más dependencias y actualizaciones de estado ajenas a comentarios de texto/stickers. | Se retiran los controles, estados y métodos de envío de audio de publicaciones y Videos. |

## Emojis, stickers y audio

- Los comentarios móviles tienen un diálogo con emojis locales, búsqueda y pestañas de stickers. Los emojis se insertan en el borrador y se envían con el botón de enviar.
- Se ocultan entradas de catálogo sin archivo que antes parecían stickers y no producían ningún envío.
- La web conserva selección de catálogo, emojis y vista previa de una imagen importada; se retira su grabador de comentarios.
- Los endpoints de comentarios rechazan `audio` y tipos `audio/*`, incluidas las variantes de nombres de campos que usan los clientes. La compatibilidad tampoco permite convertir un comentario a audio mediante edición ni sustituir el adjunto de un audio histórico; las ediciones de sólo texto se conservan.
- Los buckets `post-comments-media` y `video-comments-media` aceptan imágenes, incluidos stickers animados, y rechazan nuevas subidas de audio. Antes aceptaban audio y rechazaban las imágenes importadas por el compositor web.
- Los comentarios de audio históricos mantienen su reproducción. Las notas de voz de chats, grupos y comunidades conservan sus rutas de envío.
- La comprobación pública descubrió una foto 404 en las publicaciones de `profile/me`: el archivo existía y tenía una prueba antivirus válida, pero la respuesta conservaba el avatar histórico de la publicación. Las publicaciones propias de esa respuesta usan ahora el avatar vigente del perfil, incluida su eliminación explícita. No se amplían permisos de lectura del archivo antiguo.

## Verificación

- Web: 125 suites / 695 pruebas previas aprobadas; 2 pruebas nuevas del compositor aprobadas. Build de producción y TypeScript aprobados.
- Servidor: pruebas de almacenamiento, data routes, seguridad de PostgREST y 12 pruebas de autorización de stickers aprobadas. Siete pruebas nuevas confirman el rechazo de comentarios de audio, también para inserciones y ediciones de compatibilidad, antes de escribir en base de datos. Se comprobó la sintaxis de las rutas y los scripts de verificación social y de la matriz de medios.
- Android: compilación `compileDebugKotlin` aprobada y 33 pruebas dirigidas en 9 suites aprobadas, sin fallos ni omisiones, mediante `testDebugUnitTest` (comentarios, stickers, novedades de Home y movimiento de nubes).
- Servidor publicado: 24 pruebas en la imagen candidata (reglas de comentarios, autorización de stickers, seguridad de compatibilidad, almacenamiento y avatar de publicaciones propias). Las puertas públicas de Social y Mensajes aprobaron: JSON, fotos WebP, historial y rangos MP4. La foto que devolvía 404 dejó de referenciarse tras corregir la respuesta del perfil.
- Teléfono: Motorola moto g04 / Android 14. Se actualizó `9.1.16-debug` (156) mediante `adb install -r`, verificando el hash del APK anterior y del nuevo y la conservación de `firstInstallTime`. No se desinstaló ni se borraron datos. Se conserva la escala de animación del usuario (`0.0`). En comentarios de una publicación real y en la hoja de comentarios del feed de Videos se comprobó apertura del diálogo, inserción de un emoji en el borrador, retirada sin enviarlo, apertura de la pestaña Catálogo con stickers cargados y cierre. Se comprobó la ausencia del grabador. No se publicaron comentarios de prueba.
- APK optimizada: después de instalarla se repitió la apertura de selectores en publicaciones y Videos, la pestaña Catálogo de publicaciones y la ausencia del grabador. Se dejó el teléfono en la bandeja de entrada mediante la entrada del lanzador. El cierre completo de la hoja con Atrás no se presenta como validado: el recorrido automatizado no completó ese paso y requirió volver por el lanzador; merece una comprobación manual de foco/teclado/modal.

## Publicación y ramas

La web se publicó en `https://red-social-android.vercel.app`; `/login` respondió HTTP 200 y se comprobó que el bundle servido contiene el nuevo código de comentarios. Las 25 fuentes de esta tanda se sincronizan entre `main` y `feature/live-gifts`, con hashes por archivo; esto no equivale a afirmar que cualquier otro archivo de ambas carpetas sea idéntico. Las reglas del servidor y la corrección del avatar están publicadas: API y proxy respondieron `ok: true`. La imagen candidata contiene únicamente los tres módulos revisados sobre la imagen anterior; se conservaron dependencias, configuraciones de seguridad y contenedores ajenos. Hay copia de reversión y comprobación de hashes del código en ejecución.

## Medición física en curso

Tres lanzamientos secuenciales de la APK debug anterior registraron 4799, 4618 y 4585 ms (mediana 4618 ms; Android los clasificó WARM). La debug actualizada registró 5377, 4790 y 4552 ms (mediana 4790 ms; clasificados COLD después de instalarla). Se esperaron ocho segundos antes de recoger `gfxinfo`, sin vaciar caché ni datos. Hay pausas en el arranque, pero estas muestras no demuestran una reducción: cambian la clasificación del arranque y la condición posterior a instalación. Tampoco miden todo el recorrido de la aplicación.

La misma versión anterior optimizada registró 2314, 1676 y 1803 ms (mediana 1803 ms; COLD/WARM/COLD). Hay una diferencia importante frente a la versión debug instalada, pero no debe atribuirse a los cambios nuevos de código: aquí sólo se compara la configuración de compilación de la versión anterior.

La nueva optimizada registró 1152, 497 y 447 ms (mediana 497 ms; COLD/COLD/COLD). La muestra es favorable para el arranque de esta compilación y este teléfono. Mide el tiempo de lanzamiento informado por Android, no que todas las imágenes, conversaciones y peticiones ya estén listas. Cada variante tuvo tres lanzamientos, con la misma cuenta y red y sin vaciar caché o datos; no es una batería de Macrobenchmark ni un resultado general de todos los dispositivos. Persisten fotogramas lentos durante el arranque; navegación, desplazamiento prolongado y red necesitan mediciones separadas.

| Compilación medida | Mediana de tres lanzamientos |
|---|---:|
| Anterior debug | 4618 ms |
| Actualizada debug | 4790 ms |
| Anterior optimizada | 1803 ms |
| Actualizada optimizada | 497 ms |

Para conservar los datos del dispositivo, el APK de referencia anterior conserva su contenido byte a byte y se vuelve a firmar sólo para QA con el mismo certificado debug ya instalado. El archivo de publicación original se conserva. La nueva compilación `auditRelease`, con el paquete primario y las optimizaciones de release, compiló y pasó la comprobación de R8: 16 componentes del manifiesto, sin componentes ni clases críticas ausentes. Se instaló `9.1.16-qa-optimized` (156), sin depuración, conservando la fecha original de instalación y verificando el hash instalado. Es una APK de QA firmada con el certificado debug del dispositivo; no es una entrega de Google Play. Las capturas vacías de perfilado de métodos se excluyen: no contienen evidencia de CPU utilizable.

Tres solicitudes de salud al backend público respondieron HTTP 200 con `ok: true` en 1105, 1103 y 591 ms. La muestra incluye la ruta de red de este PC; no mide consultas autenticadas de contenido ni la red del Motorola.

## Puntos pendientes que necesitan medición

1. Ampliar las muestras de arranque ya capturadas con una batería Macrobenchmark y medir cambios de pestaña, desplazamiento prolongado y apertura de comentarios en una compilación representativa. La prueba física actual corresponde al arranque y a interacciones de comentarios de este Motorola.
2. Correlacionar los eventos existentes `main_thread_stall`, `memory_pressure_event`, `feed_load`, `direct_chat_open`, `group_chat_open` y `community_chat_open` con una traza de CPU, GC, disco y red.
3. Home consulta varios módulos de novedades cada dos minutos; la consulta de videos descarga hasta 60 registros en el backend actual. Un endpoint de novedades con fecha/identificadores reduciría más el trabajo. No se cambió el significado de los contadores en esta corrección.
4. Los avatares de video usan reproductores independientes mientras están visibles. Medir decodificadores, memoria y batería en listas densas antes de definir un límite de reproducción simultánea.
5. La construcción inicial de la biblioteca de stickers aún lee preferencias y JSON. Se eliminó su creación anticipada; una traza debe confirmar si el primer acceso visible requiere una carga completamente asíncrona.
6. El build web sigue avisando de un chunk de JavaScript de aproximadamente 1,1 MB minificado. Esto pertenece a la carga web; no demuestra la causa de la lentitud de la aplicación Android.
7. `AuthService.getCurrentUserId`, `isLocalFallbackSession` y los getters de tokens consultan varias preferencias cifradas por llamada. Medir su coste acumulado y evaluar una instantánea de sesión en memoria con invalidación segura al salir, renovar o cambiar de cuenta. No se modifica la semántica de autenticación sin esas comprobaciones.
8. Repetir manualmente el cierre de comentarios de Videos con teclado abierto/cerrado y después de cerrar el selector. La automatización confirmó los controles, pero no resolvió con fiabilidad el regreso por Atrás. No se oculta ese límite atribuyéndolo al teléfono ni se afirma una regresión sin reproducirla manualmente.

## Documentación usada

- [Rendimiento de aplicaciones Android](https://developer.android.com/topic/performance/overview).
- [Medición con Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile): comparar una compilación representativa y métricas de arranque y fotogramas.
- [Fases de Compose](https://developer.android.com/develop/ui/compose/phases): leer el estado de animación en la fase que lo necesita para evitar composición y layout innecesarios.

No se declara una mejora porcentual de toda la aplicación, ausencia total de fallos ni aprobación completa para producción. Las pruebas físicas realizadas y los límites que siguen abiertos están diferenciados arriba.
