# Auditoría Novvin: rendimiento, stickers, búsqueda, seguridad y widgets

Fecha: 21 de octubre de 2026. Entrega Android: 9.1.18, código 158.

## Cambios y análisis de impacto

| Área | Hallazgo y corrección | Integración y límite |
| --- | --- | --- |
| Chats web | Se conservan GIF/WebP originales; se capturan datos del pegado antes de esperar operaciones asíncronas. Se impide sustituir una imagen por contenido anterior no relacionado. | Comparte el mismo lector entre chat, grupos y comunidades; conserva cuenta y conversación durante la preparación. Texto, documentos y adjuntos múltiples mantienen su ruta. |
| Stickers de Windows | Algunos originales llegan como archivos sin extensión. Se reconoce su cabecera antes de comprimirlos y se permite importarlos. | Conserva los bytes animados; formatos admitidos restringidos a PNG/JPEG/GIF/WebP. No ejecuta HTML ni descarga URLs arbitrarias del portapapeles. |
| Pegado vacío | El navegador puede no exponer FileDrop de WhatsApp para Windows. | Se informa cómo importar el original. No se reconstruyen fotogramas inexistentes ni se afirma haber corregido todas las combinaciones de aplicación y navegador. |
| Botella al mar | Cabeceras y acciones fijas reducían el historial y el editor al aparecer el teclado. | Cabecera compacta, detalles y adjuntos desplegables, insets aplicados al contenedor, editor de mínimo 56 dp, historial anclado al último mensaje. Sin cambios de permisos ni endpoints. |
| Social y Videos Android | La primera lectura de SharedPreferences y la deserialización de hasta 60 publicaciones o 50 videos podían ejecutarse en Main. | Lectura y ordenación de la instantánea en Dispatchers.IO. Una lectura pendiente se descarta al cambiar la cuenta. Se conserva la paginación y la autoridad de las respuestas de red. |
| Búsqueda pública | Faltaba una explicación visual conjunta de funciones y ayuda específica de stickers y privacidad. | Tres páginas útiles nuevas, 19 rutas públicas totales, HTML legible sin JavaScript. Chats, perfiles privados e invitaciones siguen fuera del índice. |
| Variantes de nombre | El usuario busca Novin, Novyn, Novvyn, Ovin y Vvvin. | Se explican una vez como errores de escritura en la guía visible. La marca real sigue siendo Novvin; sin listas ocultas, páginas duplicadas por cada variante ni promesas de posición. |
| Imágenes en buscadores | Logo e ilustraciones públicas sin una galería descriptiva conjunta. | Galería de funciones con alternativas, dimensiones y pies; sitemap de imágenes y datos estructurados ImageObject. No se publicaron capturas ni stickers privados. |
| Errores API | Un error inesperado 4xx podía transformar su mensaje técnico en una cadena pública. | Solo códigos de aplicación con formato estricto; diagnósticos inesperados se sustituyen por REQUEST_REJECTED o INTERNAL_ERROR. Se conserva requestId y el registro interno protegido. |
| Despliegue API | La carpeta de despliegue tenía 79 archivos atrasados frente al contenedor que realmente atendía usuarios. | Respaldo y sincronización desde la imagen activa verificada antes de compilar la mejora; evita reintroducir código antiguo. No se eliminaron archivos ni datos. |
| Widgets Android | La selección de diseño solo consideraba altura; los accesos individuales no respondían al redimensionado y compartían una vista previa genérica. | Diseño adaptable por ancho y alto, alternativa de iconos accesibles para fuente grande, tarjetas y estilo minimalista. Rutas existentes y PendingIntent explícito e inmutable; sin datos privados, consultas periódicas ni permisos nuevos. |
| Audio Android | Un audio sin duración conocida producía NaN en el deslizador de progreso y cerraba la aplicación. | Fracción finita entre 0 y 1, duración desconocida tratada como cero, misma protección al buscar posición. Se ocultan nombres técnicos de archivo sin borrar leyendas escritas por la persona. |
| Orden de envío | Todos los mensajes pendientes recibían la misma fecha artificial y se ordenaban por UUID aleatorio. Agruparlos por estado también permitía que una segunda subida confirmada primero se adelantara. | Toda la cronología usa fecha y desempate estable, independientemente de sending/sent/delivered/read/failed. Se conserva la autoridad de las fechas recibidas del servidor; no cambia la base de datos ni los identificadores de reintento. |
| Conectividad pública | El servidor interno estaba saludable, pero ambas entradas públicas de Tailscale cortaban TLS. Tres mensajes del Motorola quedaban pendientes. | Tras reiniciar tailscaled, cuatro pruebas con las IP públicas y TLS estricto respondieron 200; la conexión propia de Android respondió 200 y su cola quedó vacía, sin eliminar mensajes. |
| Descripción del buscador | El resultado tomaba la explicación que deletreaba la marca. | Se prioriza una descripción útil de funciones, se mantiene la ayuda visible y se limita ese texto con data-nosnippet para los motores que lo admiten. No se controla el diseño ni el orden final del buscador. |

## Pruebas y evidencias

Los resultados de cada ejecución están en `artifacts/release-9.1.18/`. Las pruebas automatizadas con datos locales se distinguen de las verificaciones contra producción y el Motorola.

- Web: 70 pruebas de pegado, preparación de archivos, selector de stickers y vista previa, todas correctas (`web-final-accessibility-tests.log`). Incluye botones compactos con texto oculto y nombres accesibles independientes del CSS.
- Original real problemático: 332 682 bytes, WebP 220 × 220, 78 fotogramas, SHA-256 idéntico tras importar incluso el nombre sin extensión; contador de envíos cero (`actual-sticker-import-browser.log`).
- Botella al mar: cuatro pruebas de interfaz con ventana reducida, fuente al 200%, acciones de perfil y teclado real; todas correctas (`android-bottle-layout-final-tests.log`).
- Regresión Android final: 161 pruebas, 32 suites, cero fallos, errores u omisiones (`android-complete-final-test-summary.json`, `android-regression-ack-order.log`). Incluye caché por cuenta, widgets, Social, Videos, perfil, comentarios, políticas de Juegos, progreso de audio, orden temporal de pendientes, coordinación de envío, permisos y reintentos del outbox. Se repitió la tanda completa después de añadir el caso de confirmaciones fuera de orden; esa ejecución terminó en 15 s con compilación incremental.
- Interfaz: diez pruebas correctas, sin omisiones: tres de widgets, cuatro de Botella al mar, una del progreso NaN y dos que reproducen los originales reales de stickers (78 y 11 fotogramas) y comprueban cambios de píxeles en Compose (`final-ui-and-animation-tests.log`). Esto comprueba el renderizado de esos originales; no demuestra que una copia antigua convertida a PNG conserve animación.
- SEO: 62 pruebas locales correctas. Comprobación HTTP de todas las páginas públicas, canonical, CSP, metadatos, recursos y exclusión de rutas privadas en producción.
- Galería publicada: cuatro imágenes legibles y sin desbordamiento horizontal en 320, 390, 768 y 1440 píxeles (`public-pages-browser.log`).
- Exploración pública real: la primera carga de Videos agotó su espera; Reintentar mostró diez videos y se comprobaron sin errores de reproducción. Videos y Social no desbordaron a 320 px; Social mostró diez publicaciones con imágenes cargadas o pendientes de carga diferida, sin fallos de imagen en esa lectura. Momentos mostró un vacío válido y un perfil público se abrió correctamente (`guest-*-check.log`, `guest-*-snapshot.log`). El primer fallo de carga queda registrado; estas comprobaciones no demuestran conectividad perfecta ni todas las funciones con sesión.
- IndexNow aceptó HTTP 200 para las cuatro guías modificadas y, después, para la portada y la guía del nombre. Es recepción del aviso, no garantía de indexación o posición (`indexnow-final-search.json`).
- Backend: 95 pruebas de seguridad y regresión correctas: autenticación, invitado, sesiones, lectura privada de archivos, contenedores y errores públicos (`backend-security-tests.log`).
- Producción: seis comprobaciones de API correctas; salud y disponibilidad 200, endpoint administrativo sin sesión 401, `.env` y `.git/config` 404, JSON malformado 400 con REQUEST_REJECTED, sin contenido técnico de la excepción. Las primeras se hicieron por la ruta privada del PC (`production-security-checks.json`); después del reinicio se repitieron forzando la IP pública, conservando SNI y validación TLS (`public-security-after-restart.json`).
- Auditor estático de seguridad: cero hallazgos en sus reglas; no equivale a una prueba de penetración completa (`runtime-security.json`). Dependencias web de producción: cero vulnerabilidades conocidas en la consulta realizada (`web-production-dependencies.json`).
- Servidor: la mejora API fue publicada y su disponibilidad comprobada. Se mantuvieron raíz de contenedor de solo lectura, eliminación de capacidades y no-new-privileges; la configuración de entorno no cambió.

## Estado de cobertura por módulo

| Módulo | Evidencia disponible | Lo que esta auditoría no certifica |
| --- | --- | --- |
| Mensajes | Room y caché multimedia por cuenta; pruebas de apertura de originales, persistencia, cancelación y compositor. Actualización optimizada instalada conservando datos. | Todos los historiales, tamaños de archivo y condiciones de red posibles. |
| Social | Instantánea local acotada, corrección del trabajo en Main y pruebas de paginación y estado. | Toda edición/publicación real de cada tipo de contenido en todas las cuentas. |
| Videos | Metadatos locales, caché de reproducción admitida y pruebas de continuación, vacíos autoritativos e hilos. | Disponibilidad sin conexión de videos nunca descargados ni todos los códecs de terceros. |
| Juegos | Comprobación de navegación y código de acceso al módulo. | Partidas reales entre múltiples usuarios, compras, premios y reconexiones de cada juego. |
| Perfil | Lectura local previa a red, aislamiento de lecturas por cuenta, pruebas de visitantes. | Cada configuración visual en todos los tamaños y cada integración externa. |
| Botella al mar | Cuatro pruebas de interfaz sin enviar mensajes reales. | Todas las etapas del emparejamiento entre cuentas reales. |
| Widgets | Pruebas de selección de tamaños, rutas e inflación de RemoteViews; diseños sin contenido privado. | Todos los launchers de fabricantes; la instalación física y la vista real se registran por separado. |

### Estado de la prueba física y la entrega final

La entrega optimizada usa versión 9.1.18/código 158. Después del caso adicional de confirmaciones fuera de orden, la fuente Android quedó fijada al commit `e738416f`; `artifacts/release-9.1.18/delivery.json` registra su hash completo, firmas, comprobaciones y estado de instalación. Una comprobación adicional busca el identificador de compilación en los DEX del APK para rechazar un archivo de una compilación anterior. Los commits posteriores de documentación no cambian el código Android.

La publicación web final es `dpl_HV34HdYC6CvaetbsgMZG8JtSsuvu`, disponible en `https://red-social-android.vercel.app`. Se comprobaron sus 67 recursos de la aplicación hasta localizar el lector de formatos originales y los avisos de pegado vacío. La web y la mejora de errores de API están publicadas; las ramas `main` y `feature/live-gifts` tienen los mismos cambios confirmados localmente. La edición local de facturación de la segunda carpeta se conserva; no se afirma haber subido estos commits a GitHub.

Durante el diagnóstico se instaló temporalmente un APK debug conservando los datos. Las dos pruebas de entrada/navegación con ActivityScenario en el Motorola agotaron su tiempo de espera y no se contabilizan como correctas (`motorola-five-sections-smoke.log`). El arranque normal posterior sí mostró la bandeja y permitió abrir chats, pero no sustituye una medida de la compilación final optimizada.

Al intentar reemplazar el APK de diagnóstico, Windows detectó el Motorola como «Dispositivo MTP USB» con código de problema 10 y ADB vacío. El reinicio de ese dispositivo USB desde esta sesión fue rechazado por falta de permisos. La instalación final, la repetición física del audio y la comprobación final sin conexión quedan pendientes de recuperar USB. No se desinstaló la aplicación ni se borraron sus datos; las pruebas de audio/animación del emulador y las firmas del APK son independientes de este bloqueo.

## Aplicación de skills de Vercel

Se revisaron e instalaron para uso del agente `react-best-practices`, `web-design-guidelines` y `composition-patterns` desde `vercel-labs/agent-skills`, revisión `063bee94c3f4df8453406c830b0a7df0f2860278`. Son instrucciones para el desarrollo, no dependencias añadidas a Novvin ni procesos ejecutados en el servidor. Android utiliza Kotlin/Compose; no se aplicaron recetas de React Native a la APK.

Revisión acotada a los cambios web de esta entrega:

- `NoWing-web/src/components/ChatAttachmentPreview.tsx:27`: corregidos nombres accesibles de Editar/Enviar como sticker cuando el CSS oculta sus textos en pantallas pequeñas; iconos decorativos del encabezado ocultos a lectores de pantalla.
- `NoWing-web/src/__tests__/components/ChatAttachmentPreview.test.tsx:7`: regresión que oculta las etiquetas visuales, activa ambos botones por su nombre accesible y comprueba sus callbacks.
- Portada y guías: HTML público sin JavaScript de la aplicación; tamaños de imágenes, foco visible, enlace para saltar al contenido y cuadrículas adaptables ya comprobados. No se incorpora una dependencia para resolver contenido estático.
- Composición: busy y preparing describen estados reales independientes del envío. No se crean modos nuevos ni una refactorización general del compositor para seguir una guía sin evidencia de necesidad.

No se afirma haber aplicado las 70 reglas de rendimiento a todos los archivos de la aplicación ni que una skill garantice ausencia de fallos.

## Riesgos que requieren seguimiento

1. **Alta: regresiones de despliegue.** Se corrigió la divergencia de las fuentes de API frente a la imagen activa. Mantener una comprobación de digest y metadatos en cada publicación; no reconstruir desde carpetas históricas sin comparar.
2. **Alta: recuperación ante pérdida de archivos.** Los tres audios antiguos mencionados por el usuario no estaban en los respaldos disponibles y no existe copia personal. El reproductor no puede recuperar datos ausentes. Es necesario comprobar restauraciones de base de datos y objetos, además de la existencia de respaldos.
3. **Media: rendimiento físico.** La persistencia local evita esperar la red, pero no demuestra por sí sola que toda animación alcance el presupuesto de un fotograma. Registrar medidas de apertura y desplazamiento en el Motorola con APK optimizado; no comparar cifras de emulador o debug como si fueran del teléfono.
4. **Media: estados sin conexión.** No precargar indiscriminadamente todos los archivos: agota espacio, datos y memoria. Mantener cachés acotadas, separadas por cuenta, miniaturas estables, originales ya admitidos y controles de descarga.
5. **Media: peso web.** La compilación informa de un fragmento de aplicación de unos 1,1 MB sin comprimir. Las páginas públicas ya no cargan ese JavaScript; revisar la división del fragmento de la aplicación con mediciones antes de modificar sus dependencias.
6. **Media: combinaciones de portapapeles.** La muestra real valida la importación, pero el evento vacío del navegador impide una corrección universal de Ctrl+V desde Windows. Conservar el acceso a Adjuntar/Importar y el aviso comprensible.
7. **Media: auditoría de seguridad continua.** Las URLs públicas de API no son credenciales y deben ser visibles para los clientes. La protección depende de autorización, privilegios mínimos, controles de archivos, sesiones y registros. Los resultados actuales no garantizan ausencia absoluta de ataques.
8. **Alta: supervisión de la entrada pública.** La salud interna no detectó el corte de Tailscale Funnel. Se recuperó con reinicio del servicio a las 09:23 del 21 de octubre. Comprobar salud desde una ruta pública externa en cada despliegue; no confundir el DNS privado del equipo con la disponibilidad para usuarios de Internet.
9. **Rendimiento medido con límites.** Una apertura del chat en el Motorola optimizado produjo 9 fotogramas con retraso entre solo 16 medidos; la muestra es pequeña y corresponde a la primera apertura. El usuario confirma mejoras al abrir chats e imágenes. No se declara fluidez perfecta para toda la aplicación.

## Almacenamiento del servidor (sin porcentajes)

Lectura de `lsblk` y `df` realizada el 21 de octubre; GB decimales, no GiB. Cuatro discos físicos. Espacio disponible para usuarios del sistema de archivos en el momento de la lectura:

| Uso | Tamaño del disco físico | Disponible en el volumen |
| --- | ---: | ---: |
| Sistema | 240,06 GB | 118,83 GB |
| Volumen rápido | 512,11 GB | 411,57 GB |
| Archivos multimedia | 1 000,20 GB | 901,77 GB |
| Respaldos | 500,11 GB | 436,03 GB |

Ninguno de esos volúmenes está próximo a quedarse sin espacio en esta lectura. Capacidad libre y contenedores saludables no sustituyen una revisión SMART ni una restauración ensayada.

## Referencias primarias y criterios

- [Android: layouts adaptables de widgets](https://developer.android.com/develop/ui/views/appwidgets/layouts): RemoteViews por tamaños desde Android 12 y alternativa por opciones en versiones previas.
- [Android: vistas previas de widgets](https://developer.android.com/develop/ui/views/appwidgets/previews): vistas previas que representan el acceso correspondiente.
- [Android: insets de Compose](https://developer.android.com/develop/ui/compose/system/insets-ui): consumo de insets y espacio del teclado.
- [W3C Clipboard API](https://www.w3.org/TR/clipboard-apis/) y [Chrome custom formats](https://developer.chrome.com/blog/web-custom-formats-for-the-async-clipboard-api): formatos que el navegador realmente expone y preservación del original.
- [Google Search Essentials](https://developers.google.com/search/docs/essentials), [sitemaps](https://developers.google.com/search/docs/crawling-indexing/sitemaps/overview) y [Organization](https://developers.google.com/search/docs/appearance/structured-data/organization): contenido útil, marca verdadera y descubrimiento; no garantizan posiciones.
- [Google: descripciones de resultados](https://developers.google.com/search/docs/appearance/snippet), [data-nosnippet](https://developers.google.com/search/docs/crawling-indexing/robots-meta-tag) y [enlaces de sitio](https://developers.google.com/search/docs/appearance/sitelinks): el buscador decide la descripción y los enlaces complementarios; se mejora el contenido que puede utilizar.
- [Agent Skills de Vercel](https://github.com/vercel-labs/agent-skills) y [Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines): revisión de rendimiento, composición y accesibilidad web, adaptada al código existente.
- [OWASP Error Handling](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html) y [MASVS Storage](https://mas.owasp.org/MASVS/05-MASVS-STORAGE/): errores públicos genéricos y cuidado de los datos locales.

Estos criterios orientan la implementación. No se declara certificación ISO, IEEE u OWASP ni una auditoría exhaustiva de todas las combinaciones de la aplicación.
