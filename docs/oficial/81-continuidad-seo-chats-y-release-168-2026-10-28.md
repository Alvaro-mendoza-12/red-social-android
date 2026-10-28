# 81 — SEO, imágenes, chats y candidato Android 168

Fecha: **28/10/2026, America/Bogota**. Continúa las revisiones [80](80-auditoria-seo-y-candidato-local-2026-10-27.md) y [75](75-cierre-movil-167-2026-10-25.md).

## Publicación SEO autorizada

La petición de continuar la indexación y publicar las imágenes autoriza esta publicación. Se desplegó la web en Vercel, despliegue `dpl_6S8mRUUJ1D8EdVibWbYgBQDDYe1K`, asociado a `https://red-social-android.vercel.app`. La vista previa protegida confirmó HTTP 308 para `/mensajes/`. Su exclusión de buscadores procede de la protección de previews y no se extrapola a producción.

La producción anterior aprobó 69/87 comprobaciones: fallaron las 18 variantes de páginas informativas con barra final. Tras publicar aprobaron **90/90 controles HTTP**, incluidas esas redirecciones, HTML estático, canonical, robots, CSP, sitemap, imágenes y exclusión de rutas privadas. No se modificaron los servicios o datos del servidor.

Se publicaron tres capturas reales nuevas en `/images/`: `novvin-chat-android-demo.png` (1080 × 2400), `novvin-chat-web-demo.png` (1440 × 900) y `novvin-perfil-android-demo.png` (1080 × 2400). Son las capturas de las cuentas sintéticas Level Up/Juancito preparadas para publicidad. Los pies las identifican como demostración; no pertenecen a conversaciones de otras personas. Se conservan las seis ilustraciones anteriores.

Las guías Mensajes, Novvin Web, Descargar y Funciones incorporan las capturas pertinentes mediante `img`, texto alternativo, pies visibles, `ImageObject` y sitemap de imágenes. Se conserva la exclusión de perfiles y conversaciones de usuarios en buscadores. A 390 px la página Mensajes conserva `scrollWidth=390`, sin desbordamiento horizontal.

IndexNow respondió **HTTP 200** al aviso de esas cuatro páginas canónicas. Significa aceptación del aviso; no acredita rastreo, indexación ni posición. Las instrucciones se contrastaron con [Google Images](https://developers.google.com/search/docs/appearance/google-images) y [sitemaps](https://developers.google.com/search/docs/crawling-indexing/sitemaps/overview).

## Nuevos fallos de mensajería

### Persona encontrada sin mensajes previos

La vista dividida exigía que la conversación seleccionada estuviera en la bandeja. La bandeja excluye correctamente los chats sin actividad, por lo que descartaba inmediatamente el chat recién abierto. La corrección conserva una selección temporal asociada a la cuenta activa, sin insertar una fila vacía en la bandeja ni esperar su actualización remota. La llegada del primer mensaje recupera la fila real. Cambiar de cuenta descarta la selección.

Dos regresiones comprueban apertura, continuidad tras refrescar una bandeja vacía, ausencia de fila artificial y aislamiento de cuentas. La apertura no enfoca el editor; responder/editar conserva el foco explícito existente. También se repitieron las interacciones de chats, grupos y comunidades.

El propietario autorizó expresamente publicarla. La primera corrección se desplegó como `dpl_CDqhdoU2an7DokVkCyGfwiTj6i7c`; su continuación contra el parpadeo está publicada como `dpl_B4XwLKEVaoxL8jvGSGiG5higgGcG`. El dominio público conserva **90/90 controles HTTP aprobados** y el módulo servido contiene ambas correcciones.

Los cambios se guardaron y subieron a GitHub en `main` (`c15470e7`) y `feature/live-gifts` (`4279e6f4`). Vercel terminó también el despliegue de Git de `main`, `red-social-android-e8ggsn6xj-alvaro-s-projects1.vercel.app`. Después de este despliegue se repitieron los 90 controles públicos y se comprobó el módulo real enlazado por la aplicación; ambos aprobaron. Las ramas contienen los mismos 24 archivos revisados de esta entrega.

La actualización de bandeja construía un nuevo conjunto de perfiles aunque solo cambiara la fecha/mensaje, reiniciando la consulta de personas. La pantalla sustituía las filas por «Buscando…». Ahora el conjunto depende de los identificadores ordenados; se conservan las filas al refrescar la misma búsqueda y se excluyen inmediatamente los resultados de otra consulta/cuenta. Dos regresiones adicionales comprueban que la fila conserva el mismo nodo y que el directorio se consulta una sola vez durante un refresco de bandeja.

### Android: archivo compartido que reaparece

La Activity eliminaba su intent local al completar/descartar el envío, pero `onCreate` podía reprocesar un `ACTION_SEND` antiguo al restaurar la tarea. Ahora rechaza entregas marcadas por Android como procedentes del historial y recreaciones cuyo estado guardado indica que se consumieron. Las entregas nuevas de `onNewIntent`, incluido compartir otra vez el mismo archivo, siguen permitidas. Rotar con una entrega pendiente conserva su tratamiento habitual.

Se añadieron cuatro regresiones unitarias y una comprobación nativa de Activity/restauración. La comprobación nativa pasa sobre las fuentes 168; no se atribuye a la APK anterior.

### PDF grandes

La subida existente ya usa streaming para PDF de tamaño conocido. El visor descarga una copia local seekable y renderiza páginas bajo demanda con bitmaps acotados a 1200 × 1600. Su límite interno sigue siendo 32 MB; el número de páginas no determina el tamaño del archivo.

El lector utilizaba un cliente HTTP independiente. Ahora deriva del pool de medios existente, reutiliza TLS/conexiones y conserva los interceptores de autorización y cambio de cuenta. Se añade una prueba Android con un PDF sintético de 300 páginas que abre el archivo y renderiza solo la primera y última solicitadas, sin modificar el original. Falta medir el archivo exacto reportado: no se atribuye una mejora de segundos al cambio del pool sin una comparación equivalente. La primera descarga depende del tamaño y la red.

## Evidencia y límites

- Antes de publicar SEO: build, TypeScript y puerta SEO aprobados; **790 Jest / 144 suites**, **94 Node**, lint **0 errores / 7 advertencias**. HTML público con cero bytes de JavaScript de aplicación.
- Tras corregir apertura y parpadeo: build/puerta SEO aprobados; **794 Jest / 145 suites**, cero fallos. Lint web: cero errores y siete advertencias. Cuatro regresiones específicas de descubrimiento aprobadas.
- APK 167: firma comprobada de nuevo, SHA-256 `0db4e9515a2ecc905c6c1d08b0f5339ed649691f3de830fce5ff9c85fca984f1`. AAB válido y alineación ZIP/ELF de 16 KB aprobada para 14 bibliotecas de 64 bits.
- En un emulador nuevo Android 15/API 35, la APK 167 instaló y abrió en frío (`TotalTime=6045 ms`). Se abrieron login, recuperación y primer paso de registro; Atrás regresó a bienvenida sin cierre de Novvin. El buffer general contiene un fallo previo del Bluetooth del emulador, ajeno a Novvin. La entrada invitada no quedó acreditada en esa tanda.
- Candidato actual: **9.1.28 / código 168**, coincidiendo con el incremento local previamente preservado en la otra rama. **1.913 pruebas unitarias en 405 suites, cero fallos/errores/omisiones** en la repetición completa. La primera tanda tuvo un fallo antes de empezar un caso de invitado: Room/WorkManager en Robolectric registró `Illegal connection pointer` desde su tarea de invalidación. La suite pasó aislada y se repitió toda la batería sin modificar producción para ocultar el fallo. Se conservan ambos logs.
- El análisis estático completo se interrumpió al quedar recorriendo fuentes en `lintAnalyzeReleaseUnitTest`; la traza identifica `UastEnvironmentVirtualFileUtils.collectFilePaths`. No se modificaron reglas ni se declara ese control aprobado. El empaquetado normal de release y las pruebas nativas se completaron por separado. Los resultados de 167 no se atribuyen a 168.

## Artefactos 168 y ejecución firmada

El empaquetado normal `assembleRelease bundleRelease` terminó correctamente, incluido `lintVitalRelease`, sin excluir tareas ni modificar reglas. La comprobación R8 conserva los 17 componentes de manifiesto y las clases críticas de ejecución; no se añadieron reglas globales de conservación. Esto no sustituye al análisis estático completo de las fuentes de prueba que se interrumpió.

| Artefacto | Bytes | SHA-256 |
| --- | ---: | --- |
| `Novvin-9.1.28-168-release.apk` | 221101005 | `bc159f2025b30ad228094d9d87f866b81302349eb8acb0e231797309b675cdaf` |
| `Novvin-9.1.28-168-release.aab` | 131850459 | `6bbdea000fc9e94a00d35b59ea257bb859bb81df6ee1ed2d4af7905fe5a46990` |

Ambos usan el certificado de subida esperado, SHA-256 `623cdb12bad08fcca8a4a2f62eb6b5c91583d2cd14ced4ae63864066e036d84d`. Bundletool valida el AAB; ZIP y 14 bibliotecas de 64 bits aprueban 16 KB. Se verifican paquete `conectate.com`, mínimo API 24, objetivo API 36, versión 9.1.28/168 y APK sin `debuggable`.

Los artefactos están en `artifacts/production-continuity-20261028/release168/`, fuera de Git. La compilación comenzó antes del commit: el identificador embebido es `1fb1e9eedc53`, mientras que las fuentes revisadas se guardaron en `c15470e7`. El manifiesto de artefactos registra ambos y se conservan sus huellas de fuentes. El DEX firmado contiene la nueva protección de restauración de archivos compartidos.

La APK firmada se instaló sobre la versión 167 en el emulador API 35 sin borrar datos. Arranque frío observado: `TotalTime=3658 ms`, una medición del emulador, no de rendimiento físico. Se verificaron bienvenida, login, recuperación, primer paso de registro, retorno, entrada invitada, perfil invitado y salida a bienvenida. No se enviaron credenciales, registros ni correos de recuperación. El buffer de fallos observado no contiene Novvin; el historial de salidas registra la sustitución del paquete, no un crash de la aplicación. El emulador había mostrado una falta de respuesta de System UI durante el arranque bajo carga del host; se conserva esa evidencia y no se atribuye a Novvin.

En el paquete QA separado, compilado con las mismas fuentes de producción, aprobaron **4/4 pruebas instrumentadas** en 20,544 segundos: recorrido de entrada/registro sin envío/invitado, PDF de 300 páginas con render de primera y última, copias de documentos y restauración de archivo compartido con dos recreaciones de Activity. La primera tanda tuvo 3 aprobadas y un fallo del seguimiento de ActivityScenario al cambiar intencionadamente el filtro del Intent; el test ahora observa los callbacks de ciclo de vida y conserva sus aserciones. Se repitió toda la tanda, sin cambiar producción para eludir el fallo. Los logs de ambas ejecuciones se conservan.

Las capturas del 28/10 que muestran emojis superpuestos, recarga de avatares, demora de multimedia y la nueva petición de foco automático abren la siguiente revisión. No se declaran resueltas por esta tanda ni por el paquete 168 aquí firmado.

Evidencias locales fuera de Git: `artifacts/production-continuity-20261028/`. Siguen pendientes la medición física de 60 FPS, todas las variantes de autenticación real, notificaciones/LIVE entre redes y el informe previo al lanzamiento de Play Console. Esta revisión no certifica de forma absoluta toda la aplicación ni publica el AAB en Play Store.
