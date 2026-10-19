# Auditoría y mejoras de Novvin — 18 de octubre de 2026

## Resumen de la revisión

Las correcciones de interfaz, historial, QR, menciones, reacciones, contadores y medios descritas en este documento se implementaron en los bloques 9.1.10 y 9.1.11. La web y los servicios se publicaron con controles de regresión y comprobación de salud. Las ampliaciones posteriores de voz y lectura entre dispositivos figuran al cierre del documento.

Las prioridades urgentes que permanecen son: comprobar una restauración integral de respaldos; hacer reproducible la publicación desde la fuente que corresponde a las imágenes en ejecución; y definir el cifrado de extremo a extremo y del almacenamiento local antes de prometer la confidencialidad de WhatsApp. Además, tres audios antiguos no están en ningún respaldo disponible y la cadena de herramientas web conserva avisos de dependencias de desarrollo. No se acredita una aplicación universalmente libre de errores ni una auditoría externa de seguridad.

La revisión automática cubre lógica, permisos, contratos, compilación y recorridos indicados; no sustituye llamadas, cámara, segundo plano y gestos en dispositivos físicos. No se ha publicado esta actualización en Play Store.

## Entrega anterior cerrada: 9.1.9 (149)

- APK y AAB firmados: `artifacts/release-9.1.9/Novvin-9.1.9.apk` y `.aab`.
- Firmas verificadas. Certificado SHA-256: `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
- Las 14 bibliotecas nativas de 64 bits y el AAB pasan la verificación de páginas de 16 KB.
- Corrector Android: conserva selección/composición y permite aplicar sugerencias del diccionario local.
- Widgets: accesos con iconos, tamaños compactos y grandes, diseño y vistas previas renovados.
- Notificaciones: resolución de miniaturas y alternativa visible si un contenido no está disponible.
- Web publicada en https://red-social-android.vercel.app: fondo de perfil en Crear momento, consulta propia de Seguidos, paginación, protección del like optimista ante refrescos concurrentes.
- API publicada sobre la imagen activa preservando su configuración. Imagen fijada: `sha256:85fc5e7c0c192fe1743584419e84a91967ce0964463224c026c46e256c3e736a`.
- Lecturas verificadas en producción: 6 publicaciones en Seguidos, 3 likes persistidos coincidentes entre feed y detalle, 29 notificaciones y 2 miniaturas descargadas con HTTP 200.
- Comprobación pública de social, videos, perfiles y multimedia correcta. Auditoría npm de dependencias de ejecución: 0 vulnerabilidades reportadas.
- Sin teléfono conectado en ADB; esta entrega no se instaló en el dispositivo. No se publicó en Play Store.

## Alcance solicitado e implementado en 9.1.10

1. Comunidades: administradores publican por defecto al crear; propietario gestiona administradores; participación opcional con límite semanal configurable por integrante; reacciones y respuestas de participación independientes.
2. Respuestas: eliminar aparición tardía de la cita; navegar al original y resaltarlo temporalmente en DM, grupos y comunidades.
3. Selección múltiple: mantener pulsado en Android, selección visible y acciones de eliminar/reenviar; reenvío múltiple en web.
4. Contadores de seguidores y seguidos en tiempo real para distintas cuentas.
5. Adjuntos y herramientas: ubicación, documentos, contactos, mensajes programados, contextos/apodos/autotraducción; comprobar permisos y errores.
6. Editor de imágenes, precarga/caché de imágenes y zoom en publicaciones.
7. Botella al mar: conversaciones recuperables sin apertura automática, cierre claro, recepción/lectura y diseño adaptable en Android/web.
8. Modo invitado y carga de imágenes; auditoría por módulos y prioridades con evidencia, correcciones y riesgos pendientes.

## Hallazgos iniciales del nuevo alcance

- Comunidades se crean con `only_admins_can_talk = FALSE` en `collection-room-create-store.js`: contradice el valor por defecto solicitado.
- Existe límite diario, no semanal. Su comprobación cuenta mensajes antes de insertar sin una transacción común: envíos concurrentes pueden superar el límite.
- El envío de mensajes se centraliza en `insertMessageOnce`; la solución del cupo debe conservar su idempotencia para no cobrar reintentos.
- Web Seguidos ya consulta al servidor antes de paginar; esta corrección está publicada.

Los resultados reproducibles y las prioridades se presentan a continuación; esta revisión no certifica ausencia total de fallos.

## Correcciones implementadas en el segundo bloque (9.1.10, 150)

### Comunidades

- Nuevas comunidades: publicación reservada a administradores. Las existentes conservan su configuración.
- Participación opcional: cupo semanal por integrante, entre todos los canales; de 0 (sin límite) a 10.000. Renovación lunes 00:00 UTC. Administradores exentos. Android y web muestran la configuración.
- Cupo contado e inserción bajo transacción y bloqueo por comunidad/integrante. Reintentos con el mismo ID no consumen otro mensaje. Se conserva el antiguo límite diario para comunidades sin configurar el nuevo.
- La política se aplica también a RPC y encuestas. Reacciones, votos y respuestas a preguntas siguen disponibles por pertenencia.
- Solo el propietario asigna o retira roles; no puede degradarse al creador. El cambio conserva la fecha de entrada y sus permisos de historial. Los administradores siguen pudiendo expulsar según las reglas existentes.
- Los cambios de permisos y roles emiten actualización de sala; no dependen de reabrir la pantalla web.

### Mensajería y adjuntos

- Cita visible desde el primer render, usando el historial local si el evento no trae su objeto completo. Si el original no está disponible se muestra una referencia estable, no un espacio que aparece después.
- Navegación al original con resaltado temporal; web amplía la ventana de historial y sale del filtro al localizarlo.
- Android: pulsación larga inicia selección múltiple, selección visible, cancelar/volver, guardar, reenviar y eliminar para mí con confirmación. Las acciones individuales permanecen en Más.
- Web: reenvío a destinos elegidos (chat/grupo/comunidad), incluido reenvío múltiple desde selección. Antes reenviaba al mismo chat. Excluye mensajes de una vista, pendientes, eliminados y tarjetas interactivas.
- Cupos agotados no se consideran fallos transitorios ni se reintentan automáticamente.
- Ubicación: lectura actual con plazo y permisos, validación de latitud/longitud. El servidor reconoce el mapa como ubicación, sin exigir que sea un archivo subido. Solo admite HTTPS, dominio de mapas y coordenadas válidas; no admite URLs arbitrarias.
- La autorización de archivos también se aplica a RPC: propiedad, escaneo, lectura del origen al reenviar y restricciones de eliminación/una vista.
- Contactos web: representación legible con teléfono/correo en vez de JSON crudo; enlaces de ubicación separados de los documentos en grupos/comunidades.

### Botella al mar

- Una consulta periódica ya no abre de nuevo una conversación aceptada. Regresar conserva el chat en la lista; terminar exige confirmación explícita.
- La web añade lista de conversaciones anónimas recuperables, contadores y previsualización. No revela identidades que el servidor no haya autorizado.
- Muestra fotos, stickers y audio que recibía pero antes renderizaba como texto.
- Cursores de lectura limitados al último mensaje realmente recibido. Estado Visto/Enviado en Android/web. Notifica lectura mediante tiempo real; no marca lecturas cuando la web está oculta.
- Android escucha eventos de la conversación visible, con sondeo de respaldo de 5 s; cancela al salir y comprueba la cuenta/sesión antes de aplicar resultados.

### Seguidores

- Persistencia transaccional de seguir/dejar de seguir, identidades equivalentes consolidadas y anulación de relaciones importadas antiguas.
- Se recuperan relaciones guardadas por versiones anteriores mediante la API de compatibilidad, sin reactivar las que se dejaron de seguir.
- Perfiles escuchan cambios en contadores; web tiene actualización al enfocar y sondeo de respaldo. Android revierte el botón/contador si el servidor rechaza el seguimiento; antes ocultaba el error conservando un éxito aparente.

### Multimedia e invitado

- Editor Android: texto independiente del comentario, tamaño, selector de emoji, grosor, color por trazo, deshacer y restablecer. Paleta horizontal contextual que deja visible la imagen.
- Zoom con dos dedos dentro de fotos del feed, acotado y liberado al terminar el gesto; el desplazamiento con un dedo permanece disponible.
- Precarga de las últimas 24 piezas de chat priorizando imágenes/stickers. Copias offline delimitadas por cuenta, caché de memoria con generación de sesión; se excluye contenido de una vista e invitado de la persistencia de chat.
- Invitado: validación estricta de identidad/respuesta de sesión. Imágenes con versión ligera, alternativa al original y botón de reintento visible.

## Evidencia de verificaciones

- Android: 1.698 pruebas, 355 suites, 0 fallos y 0 omitidas (`artifacts/release-9.1.10/android-test-summary.json`). Incluye migración Room al esquema 57, permisos, caché, invitado y controles de mensajes programados.
- API: batería de 199 archivos: 857 pruebas aprobadas, 0 fallos, 9 omitidas. Nuevas pruebas adicionales: cupo (8), seguimiento (5), ubicaciones (2).
- Gateway: autenticación y autorización aprobadas, incluido canal de contadores autorizado mediante acceso al perfil.
- PostgreSQL real: pruebas con tablas temporales privadas por conexión; cupo, bloqueo, reintento idempotente, alias y anulación al dejar de seguir aprobados. No se insertaron mensajes, reacciones ni notificaciones en cuentas reales.
- Web: TypeScript, ESLint de archivos afectados y compilación de producción correctos. La primera batería reveló falta de mapeo CSS en Jest; corregido el mapeo, las 106 suites y 629 pruebas aprobaron.
- Estas pruebas no sustituyen observación manual de gestos/cámara en cada fabricante ni una prueba de carga sostenida.

## Riesgos y prioridades de la auditoría transversal

| Prioridad | Área | Evidencia y alcance | Trabajo pendiente o medida aplicada |
|---|---|---|---|
| Alta | Confidencialidad de mensajes | El servidor guarda contenido legible; `EncryptionManager` protege credenciales y cola local, no establece cifrado de extremo a extremo del chat. | Definir protocolo de claves, multidispositivo, recuperación y migración antes de anunciar confidencialidad tipo WhatsApp. No introducir cifrado casero en una corrección de interfaz. |
| Alta | Continuidad operativa | El código del directorio remoto no coincide íntegramente con la imagen que está en ejecución. Construir desde ese árbol podría retirar protecciones ya desplegadas. | Esta publicación usa capas sobre la imagen activa, hashes y reversión. Unificar fuente, imagen, migraciones y proceso de publicación reproducible. |
| Alta | Recuperación de datos | Existen scripts de respaldo de PostgreSQL y almacenamiento; esta auditoría no ha ejecutado una restauración integral en una instancia aislada. | Programar simulacro con RPO/RTO medidos y comprobar mensajes/archivos/autorizaciones restaurados. |
| Alta | Almacenamiento local | El historial y las copias offline residen en el sandbox de Android; no se acredita cifrado independiente del hardware para toda Room y todos los archivos retenidos. | Evaluar cifrado por cuenta, rotación y recuperación. Se preservaron aislamiento, cancelación al cambiar de cuenta y exclusión de copias sensibles del backup Android. |
| Media | Alcance del catálogo/editores | Las capacidades web y Android no son equivalentes en todos los editores. | Se mejoraron selección de videos y restablecimiento de color en 9.1.11. La paridad completa de edición requiere una entrega propia con exportación y validación por dispositivo. |
| Media | Caché offline | Las copias abiertas se retienen, la precarga tiene presupuesto de 192 MiB y 3 descargas simultáneas; no equivale a guardar todo el historial y todos los videos para siempre. | Mostrar uso y limpieza de copias; ampliar precarga por ventana visible sin descargar años de contenido ni contenido de una vista. |
| Media | Rendimiento | El empaquetado web advierte un fragmento de aproximadamente 1 MiB; R8 Android es costoso. | Medir arranque en móvil modesto y dividir dependencias opcionales. No confundir build correcto con baja latencia en toda red. |
| Media | Verificación física | ADB no detectó teléfono en la entrega anterior. Las pruebas actuales son automatizadas, de código y servidor. | Verificación física de gestos, permisos, editor, segundo plano y llamadas antes de un despliegue masivo. |
| Media | Dependencias | La auditoría de dependencias web de ejecución anterior reportó 0 avisos; no equivale a ausencia de vulnerabilidades de toda la cadena. | Mantener revisión de SDK nativo, imágenes Docker, herramientas de build y dependencias de desarrollo. |
| Baja | Mantenimiento | Hay advertencias de deprecación y archivos de pantalla grandes. | Separar estado, render y transporte gradualmente. Evitar refactorizaciones generales durante una entrega de correcciones. |

### Cobertura por módulos

- Autenticación/dispositivos: pruebas de sesiones, revocación, invitado, suspensión, recuperación y permisos del servidor. No se enviaron correos de recuperación durante esta revisión.
- Mensajes/grupos/comunidades: revisión de historial por integrante, roles, respuestas, selección, adjuntos, programación, borrado y cola. Espacio privado usa preferencias cifradas y claves por cuenta/conversación; programación conserva lista/errores y aísla cuentas.
- Social/perfil: reacciones persistidas, feed Seguidos, publicación, momentos, miniaturas y seguimiento. No se modificaron datos reales para simular likes.
- Videos/directos/voz/llamadas: batería del servidor y pruebas web de estado/negociación. No se acredita aquí una llamada física entre laptop y Motorola ni comportamiento en todos los operadores.
- Juegos/economía/admin: pruebas de reglas, autorización, moderación, regalos y compras de la batería existente. Sin compras, cobros, cambios de saldo ni cambios de permisos en cuentas reales.
- Almacenamiento: pruebas de escaneo, archivos privados, solicitudes autenticadas, límites y caché. Enlaces y tokens no se imprimieron.

## Ampliación posterior solicitada (implementada en el bloque 9.1.11)

Después de 9.1.10 se implementaron las nubes blancas, selector de videos con alternativa del sistema, restablecer color del editor, personalización de portada/perfil, invitaciones QR de grupos/comunidades, avisos de reacciones, menciones y reparación del periodo de historial. Los cambios se detallan en NEXT-REQUIREMENTS.md y en el cierre 9.1.11 de este informe.

## Publicación de 9.1.10

- Web en producción: despliegue `dpl_5RUQeaJKkHQDVRSyWgeL3p1AFk2T`, estado READY; URL pública responde HTTP 200. La portada abrió en Chromium sin errores ni advertencias de consola.
- API y gateway publicados y saludables. Imágenes fijadas: API `sha256:ef846b877d16185a838ad614297a516e0857771eecce53bb54702d8749778661`; gateway `sha256:5627893b959c03b98aa81b273c2720b6a6c3189d3fcb3672667bd414ce9eaeed`.
- Antes de publicar: esquema completo ejecutado dentro de una transacción revertida, seguido de regresiones con tablas temporales. Un primer candidato falló por una llamada SQL con parámetros incorrectos; se restauró automáticamente la versión anterior, se corrigió y el nuevo candidato superó el control.
- Lecturas públicas autenticadas de producción: Seguidos 26 publicaciones; 3 likes guardados coincidentes entre feed y detalle; 29 notificaciones; 2 miniaturas HTTP 200. Social, perfiles y videos pasan los plazos de la comprobación; video HTTP 206 con dos segmentos de 1 MiB correctos.
- APK/AAB 9.1.10 firmados y verificados: firma, R8 y catorce bibliotecas nativas compatibles con páginas de 16 KiB. El Motorola desapareció de USB al instalar; no se acredita instalación física.

## Hallazgos y cambios 9.1.11

### Historial y permisos (prioridad alta, corrección aplicada)

Una edición de metadatos de una sala importada podía crear una pertenencia local con la fecha actual. Eso desplazaba la ventana de historial y la sincronización Android podía borrar su copia local al considerarla inaccesible. Se conserva la fecha original cuando la pertenencia es continua; los reingresos reales siguen sujetos a sus periodos de ausencia. Android filtra acceso sin borrar físicamente por ese motivo. No se promete reconstruir mensajes que ya no existan en servidor ni en respaldo.

### Invitaciones y notificaciones

- Compartir enlace y QR para todos los integrantes de grupos/comunidades; guardar QR, enviar como texto al chat, cámara, linterna donde exista y galería. Previsualización y aceptación explícita. Tokens aleatorios con hash, caducidad, usos limitados y revocación por administradores; comprobación del estado del invitador. Rutas privadas excluidas de indexación y de caché HTTP.
- Menciones enlazan a perfiles únicos. Una mención directa puede superar el silencio de grupo; no supera bloqueos, baja de sala ni permisos del sistema.
- Reacciones avisan al autor sin insertar mensajes, sumar no leídos ni sobrescribir el historial de la notificación. Evita avisos propios, retirada y duplicados rápidos; elimina el aviso al eliminar el mensaje. Metadatos de deduplicación con retención de 30 días y limpieza al eliminar cuenta.
- Visitas al perfil emiten actualización al propietario. El servicio de avisos a seguidores al iniciar un directo ya cuenta con salida deduplicada/reintentos y está habilitado en producción; no se generó un directo falso en cuentas reales.

### Interfaz y creación

- Nubes blancas con texto oscuro, hasta tres respuestas, movimiento suave, pausa y movimiento reducido. Se mantiene acceso a la lista completa y los IDs/API originales.
- Galería de video consulta videos, sin cortar antes el catálogo mezclado de fotos. Alternativa del sistema, permisos parciales y estado de error visible. Editor restablece color sin perder recorte, velocidad, audio o textos; herramientas con objetivos táctiles más grandes.
- Portadas con lluvia, ritmo, destellos, constelación, ondas, órbita o confeti, pausa y animación acotada a la portada. En Android el tema del perfil ya no cambia automáticamente el tema de toda la aplicación.
- Voz web con selector único de reacciones, controles compactos y chat reversible en modo cine. Enlaces activos y fotos de sesión en web/Android: vista previa, JPEG de hasta 720 px y aproximadamente 97 KiB, máximo diez fotos en memoria y cien entradas. No se crean URLs públicas. No se presenta como historial permanente ni como envío de documentos originales.
- Corrección de una causa de contadores fantasma: la vista previa optimista de un envío propio no cambia el tiempo con el que se aplica el estado de lectura. En Android llamadas/invitaciones con chat_id no generan avisos pendientes de mensaje.
- Escáner para vincular Novvin Web accesible desde la cabecera de la bandeja Android. Reutiliza el flujo de vinculación existente.
- Comentarios web conservan la URL del audio y usan reproducción autenticada ya existente; compositor compacto, fechas adaptables y sin UUID bajo el nombre. Fallos definitivos de carga ofrecen reintento visible.
- Anuncios: recorridos existentes de crear con imagen, revisar, publicar, editar, pausar/activar, cancelar borrado y borrar confirmado cubiertos por seis pruebas del gestor web. Android ofrece eliminar y cancelar. No se borraron anuncios reales para comprobarlo.

### Hallazgo adicional: archivos de audio históricos ausentes (prioridad alta)

Se revisaron los tres comentarios de audio de la publicación mostrada en la captura. Son importados de enero de 2026 y conservan referencias a un túnel anterior. Sus objetos no coinciden con los dos archivos actuales del bucket post-comments-media y no aparecen en su ruta esperada del volumen actual. Las consultas no modificaron filas ni archivos. Una respuesta HTTP 401 sin credenciales de un archivo actual es el control de acceso esperado, no prueba de audio averiado.

La búsqueda de solo lectura abarcó los nueve respaldos fechados disponibles del 10 al 18 de octubre y el enlace al respaldo más reciente, sin encontrar ninguno de los tres archivos. El usuario confirmó que tampoco conserva originales ni copia de seguridad. La reparación del reproductor no recupera bytes ausentes: estos tres audios históricos siguen sin ser recuperables con las fuentes disponibles. Se mantienen sus registros y el aviso de reintento, sin sustituirlos por otro archivo ni relajar la autorización. Evidencia: artifacts/release-9.1.11/reported-post-audio.json, audio-object-comparison.json, audio-file-presence.json, audio-backup-search.json, audio-oldest-backup.json y audio-remaining-backups.json.

### Dependencias y verificación complementaria

- Dependencias de ejecución web: auditoría npm con cero avisos. Esto no acredita ausencia de vulnerabilidades desconocidas ni cubre todos los SDK nativos o contenedores.
- Herramientas de desarrollo: se actualizó Handlebars de 4.7.9 a 4.7.10, retirando el aviso crítico detectado. La auditoría completa conserva 38 avisos (33 altos, 5 moderados), todos en dependencias de desarrollo. La corrección automática compatible no los resolvió; no se aplicaron actualizaciones mayores forzadas durante esta publicación. Se requiere migración controlada de la cadena de pruebas/build y nueva comprobación de compatibilidad.
- La prueba local de SEO dependía de la posición de la primera regla de cabeceras; la protección añadida a invitaciones cambió esa posición. Se corrigió la prueba para buscar la regla global por su ruta y se añadió una comprobación independiente de noindex/no-store de grupos y comunidades. La política desplegada ya era correcta.
- Fotos del chat de voz: límite de una foto cada dos segundos en el servidor; envío optimista sin confirmación individual de entrega en la interfaz. Prioridad media: incorporar confirmación/reintento sin duplicados, especialmente ante desconexiones. El contenido sigue siendo de sesión, no un archivo permanente.

### Diez heurísticas aplicadas

| Criterio | Aplicación concreta |
|---|---|
| Estado visible | Preparando foto, envío, carga y reintento; caducidad de invitación. |
| Lenguaje familiar | Compartir, Unirme, Reacciones, Restablecer color. |
| Control y salida | Cancelar foto, cerrar selector, pausar movimiento, revocar invitación. |
| Consistencia | Mismo enlace/QR y mismo tipo de reacción en ambos clientes. |
| Prevención | Límites de foto, vista previa y comprobación de pertenencia en servidor. |
| Reconocimiento | Escáner visible y acciones de publicidad con nombre. |
| Eficiencia | Menú compacto, enlaces pulsables y actualización por evento. |
| Minimalismo | Reacciones agrupadas, sin IDs internos bajo nombres, controles adaptables. |
| Recuperación | Reintento de audio, errores de invitación claros y borradores conservados. |
| Ayuda | Alcance de sesión del chat de voz y disponibilidad de historial explicados. |

La revisión sigue siendo interna. No acredita certificación ISO/IEEE, ausencia universal de errores ni equivalencia completa con WhatsApp, TikTok o Discord.

## Evidencia de la publicación inicial de web y servidor — 9.1.11

- Web: despliegue `dpl_EuADa6LuMYaQjfS2UZbc36NE6eEB`, READY y alias de producción https://red-social-android.vercel.app. TypeScript y build remoto correctos; 111 suites / 638 pruebas Jest, nueve pruebas de transmisión de medios y 52 pruebas locales de SEO aprobadas. La prueba visual usa los componentes reales con datos ficticios, no una llamada real.
- Navegador de producción: en modo invitado se comprobó carga de miniaturas visibles y metadatos de video. Las imágenes fuera de pantalla usan carga diferida; no se tomó su estado previo a la descarga como un fallo. No se publicaron likes, anuncios, mensajes o compras ficticias en cuentas reales.
- Comprobación HTTP de producción: portada, guía de producto, login, shells de invitación y readiness del servidor con HTTP 200. Invitaciones con `noindex, nofollow, noarchive` y `no-store`. Comprobación completa de SEO público aprobada; no garantiza primera posición ni inclusión en buscadores o respuestas de terceros de IA.
- API: imagen `sha256:ed1051d056904cf0ac08bcaa1e5464f4858052047fe26afd707a3e1e1f9ba3ce`; 878 pruebas totales, 869 aprobadas, nueve omitidas y cero fallos. Se verificó la protección de destinatarios que ya ocultaron o eliminaron un mensaje y la vigencia de la reacción antes de emitir el aviso.
- Señalización: imagen `sha256:68f002486178d84c8315938e9488f738209d1caeeb01d8809c7a4888826062eb`; 54 pruebas totales, 53 aprobadas, una omitida y cero fallos. Las fotos del chat mantienen el control de autenticación y pertenencia de sala.
- Gateway: imagen conservada `sha256:5627893b959c03b98aa81b273c2720b6a6c3189d3fcb3672667bd414ce9eaeed`. API, señalización, gateway y PostgreSQL estaban saludables en la comprobación posterior a la publicación.
- Publicación del servidor mediante capas sobre las imágenes activas, comparación de módulos, respaldo de compose, control de salud y reversión. Se evitó reconstruir desde el checkout remoto desactualizado.
- Evidencia local: `artifacts/release-9.1.11/web-release-deploy.log`, `web-release-tests.log`, `web-release-media-tests.log`, `web-release-seo-tests.log`, `production-release-seo.log`, `production-browser-check.log`, `production-verification.json`, `api-final-tests.log`, `signaling-tests.log` y registros de publicación del servidor.


## Ampliación: bandeja, voz y navegación web

- Un único estado de bandeja por cuenta alimenta filas, filtros y contadores. Las respuestas antiguas de red y la caché no reemplazan una lectura más reciente. Archivados muestra su propio contador; no se eliminan sus mensajes pendientes.
- La pestaña Voz y su caché excluyen canales internos de comunidades. La exclusión utiliza los identificadores de comunidad/canal, no el nombre de la sala. Directorio e inbox comparten el mismo criterio.
- La conexión de voz espera la confirmación de pertenencia del socket actual antes de emitir audio, señalización, mensajes o reacciones. Una reconexión vuelve a solicitar pertenencia; se conserva la autorización del servidor.
- Salir cierra medios y socket inmediatamente, consume el ingreso automático y reemplaza la ruta por el directorio. Volver desde el directorio conduce a Mensajes. Abrir una URL histórica no incorpora automáticamente al usuario a una llamada.
- Ctrl+V permite enviar una imagen desde el portapapeles. No se descargan automáticamente imágenes de URLs o HTML pegados. El visor usa un diálogo accesible, mantiene proporciones y se cierra con Escape; no abandona la sala.
- La web cambia el escáner de la cabecera por Buscar personas. Se conservan los QR compartibles de grupos y comunidades y el escáner Android. La búsqueda consulta el directorio del servidor con el texto solicitado, conserva el foco y reconoce nombres con acentos.
- La primera conversación entre desconocidos crea una solicitud pendiente y permite una sola introducción. La respuesta del destinatario o su aceptación habilita el chat y acepta la amistad. No se puede aceptar una solicitud propia. El disparador SQL cubre todos los transportes INSERT y reintentos con el mismo mensaje.

## Lectura entre dispositivos — 9.1.12 (152)

### Comportamiento y alcance

La lectura se guarda en el servidor y se distribuye por canales privados de las identidades de la misma cuenta. Los dispositivos Android reciben además un control FCM de datos, sin texto de mensaje ni notificación visible adicional. En lectura parcial se transmiten únicamente los IDs autorizados que la base confirmó; no se adelanta un cursor global de la conversación. En lectura completa se utiliza el mismo límite de tiempo para actualizar mensajes, cursor y control remoto.

Android actualiza mensajes y contador local en una transacción, filtra la notificación del sistema por cuenta y conversación y conserva mensajes posteriores no leídos. Un registro local acotado evita que un FCM o evento atrasado vuelva a publicar un mensaje leído. Las actualizaciones de mensajes conservan una lectura ya conocida. Las fechas de mensajes del push proceden del servidor, para no basar el descarte en el reloj del teléfono.

La web descuenta IDs vistos por otro dispositivo de la instantánea compartida y solicita actualización al servidor. Controles duplicados no vuelven a descontar; una lectura antigua no oculta mensajes posteriores. La sincronización requiere conectividad y una versión Android que procese el nuevo control. No se promete una latencia máxima de FCM ni se da por realizada una prueba física con el Motorola mientras ADB no lo detecta.

### Evidencia reproducible

- Web: TypeScript/build y ESLint de los módulos modificados aprobados; 116 suites, 657 pruebas Jest y 52 pruebas locales de SEO aprobadas. Incluye lectura parcial, duplicados, mensajes posteriores, aislamiento de cuenta/tipo, solicitud entrante/saliente y búsqueda global sin perder el foco.
- API: 883 pruebas, 874 aprobadas, nueve omitidas y cero fallos. Control privado con lotes de 32 IDs y tamaño inferior al límite FCM; sin cuerpos ni URLs de mensajes en el control. La pertenencia y autorización se comprueban antes de modificar o publicar.
- PostgreSQL real: esquema completo y disparador de introducción verificados dentro de una transacción revertida. Una introducción crea pendiente, la segunda se rechaza, responder acepta la amistad y permite continuar. Los datos sintéticos no se publicaron ni permanecieron en la base.
- API publicada: `sha256:7c7b13de685bfc0d14af11a4a7595d1fa068cc96e9cdd032bcba48b1b47b7044`, saludable. Se compararon los cuatro módulos previos y se añadió un módulo nuevo sobre la imagen activa `895dbcff...`; respaldo de compose y reversión conservados.
- Android 9.1.11 anterior: APK/AAB firmados, 1.707 pruebas de 359 suites sin fallos; certificado esperado, R8 y páginas de 16 KiB verificados. La nueva 9.1.12 incorpora los cambios de lectura y tiene su verificación final separada.
- Registros: `artifacts/release-9.1.12/web-tests.log`, `web-build.log`, `web-lint.log`, `web-seo-tests.log`, `api-tests.log`, `postgres-candidate.log` y `api-deploy.log`.

### Documentación consultada

El control usa datos FCM para que la aplicación procese la lectura en segundo plano, conforme a [recepción de mensajes Android de Firebase](https://firebase.google.com/docs/cloud-messaging/android/receive-messages). El borrado o reconstrucción de la notificación se realiza en el cliente con las APIs de [notificaciones Android](https://developer.android.com/develop/ui/compose/notifications/create-notification). El estado compartido de React conserva instantáneas consistentes mediante [useSyncExternalStore](https://react.dev/reference/react/useSyncExternalStore) y cancela resultados obsoletos de efectos de búsqueda.


### Publicación web y comprobación posterior — 9.1.12

- Web `dpl_C4De6nvuReUNfmyrX8kaHfhKyTCV`, estado READY, alias de producción https://red-social-android.vercel.app. El lector QR no se monta en la cabecera web; Buscar personas lo reemplaza.
- Portada, guía, login, invitaciones de grupo y comunidad y readiness de API respondieron HTTP 200. Las invitaciones mantienen noindex/no-store. API, señalización, gateway y PostgreSQL estaban saludables tras publicar.
- La comprobación de SEO desplegado aprobó. La posición o aceptación por buscadores y asistentes de terceros no está garantizada.
- El navegador de producción en modo invitado cargó las miniaturas visibles y metadatos de video. Se mantuvo la carga diferida de imágenes fuera de pantalla y no se hicieron publicaciones falsas en cuentas reales.
- Los cinco módulos de API desplegados coinciden por SHA-256 con las fuentes verificadas localmente. Registros adicionales: `api-source-verification.json`, `web-deploy.log`, `production-verification.json`, `production-seo.log` y `production-browser-check.log` en `artifacts/release-9.1.12/`.


### Pruebas Android y límites operativos de lectura

Las 1.712 pruebas Android de 360 suites terminaron sin fallos, errores ni omisiones. Las cinco pruebas de ConversationReadSync incluyen SQLite/Room real en memoria, corte temporal, conservación del mensaje posterior, aislamiento de cuentas, retirada selectiva de la notificación y rechazo de una notificación atrasada.

La distribución por socket/FCM sigue siendo de mejor esfuerzo: el estado leído permanece en base de datos, pero no existe un tiempo máximo garantizado de retirada del aviso si el dispositivo está desconectado, el sistema retrasa FCM o el servicio se reinicia justo después de guardar. Como mejora de resiliencia de prioridad media, incorporar cola duradera de controles y reconciliación de notificaciones al recuperar conexión; comprobar los casos de ahorro de batería y proceso terminado en dispositivos físicos.

Los seis endpoints de lectura completa/visible de chats, grupos y comunidades rechazaron solicitudes de producción sin sesión con HTTP 401. No se modificaron conversaciones en esta comprobación; evidencia: `artifacts/release-9.1.12/read-auth-smoke.json`.


### Cierre Android 9.1.12 (152)

APK y AAB firmados y verificados con el certificado esperado `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`. Pasaron las verificaciones R8 y de páginas nativas de 16 KiB. Compilación final correcta, 1.712 pruebas de 360 suites sin fallos, errores u omisiones. La instantánea de fuentes coincide con la verificada al compilar.

- APK: `artifacts/release-9.1.12/Novvin-9.1.12.apk`; SHA-256 `34a03123d8be123dab526d1d89181234a7caa92dd749f26f1a222921d38ddce8`.
- AAB: `artifacts/release-9.1.12/Novvin-9.1.12.aab`; SHA-256 `bc02936721d7cd3e5bb549e3b21b073ebc8a5c1434b4cfaf88fb18b486594b56`.
- Certificado, optimización, alineación y pruebas: `artifacts/release-9.1.12/release-result.json`, `apk-signature.log`, `aab-signature.log`, `page-size.log`, `r8-report.md` y `android-final-verified.log`.

El Motorola no figuró disponible en ADB para instalar esta entrega. La instalación y la prueba física siguen pendientes; no se borraron datos ni se desinstaló la app. No se publicó en Play Store.


## Revisión 9.1.13 (153) — implementación, publicación y límites

### Cambios

- Búsqueda web con tarjetas compactas y reporte desplegable. Comentarios de Videos/Social comparten compositor de texto, grabación de audio con vista previa y cancelación, adjunto de audio, stickers, menciones, edición y eliminación propias. Respuestas agrupadas en hilos expandibles; reacciones de video y votos de comentario social reutilizan los contratos protegidos existentes. La API valida el origen del padre, visibilidad y referencia autorizada del medio. No se convierte cualquier URL en un adjunto propio.
- Amigos consulta videos de amistades aceptadas en ambas direcciones, con alias de identidad y paginación de esa fuente antes de mostrar. Recomendados conserva su sesión y cursor. La consulta de amigos mantiene el límite actual del catálogo legado; catálogos de más de 5.000 candidatos requieren ampliar a paginación SQL/cursor específica.
- Bandeja Voz muestra ocupación y refresca el directorio autorizado al recibir invalidaciones de presencia. Los eventos globales ya no distribuyen IDs de participantes privados ni metadata de salas.
- Chat de voz: pegar prepara vista previa; Enviar/Cancelar explícitos, eco y confirmación del servidor, reintento sin duplicado y menú para borrar para todos con validación de autor y sala. Clientes nuevos descartan texto/foto caducados a los 90 minutos y coordinan borrado. Datos JPEG acotados a 720 px; listas limitadas a 100 entradas y diez fotos por cliente.
- Las fotos de este flujo no crean objetos en almacenamiento de archivos. Se detectó que el adaptador Redis Streams sí podía conservar antiguos paquetes de recuperación: la nueva distribución del contenido usa Pub/Sub transitorio. La migración elimina únicamente paquetes antiguos del chat de voz con más de 90 minutos, conserva los demás eventos de señalización y vuelve a revisar los restantes cada minuto. Se observaron 27 paquetes antiguos, 25 ya caducados; tras limpiar quedaron dos todavía dentro del plazo y cero caducados. El borrado lógico de Redis no constituye borrado forense de discos o respaldos anteriores. Los clientes antiguos deben actualizarse para aplicar caducidad local y borrado.
- Fechas de comentarios Android convertidas de instante UTC a la zona del dispositivo; las fechas sin hora no se desplazan. Nubes con ancho adaptable, movimiento más visible, pausa y respeto por movimiento reducido. Se conservaron las políticas existentes de administración, cuotas semanales atómicas, preguntas y reacciones.
- Lectura: al combinar participantes importados/locales se conserva el cursor máximo. Persistencia acotada de marcas confirmadas por cuenta; no se confirma una petición fallida. Un mensaje posterior sigue mostrando su contador. Continúa el límite ya documentado de distribución socket/FCM de mejor esfuerzo, especialmente con el teléfono desconectado.
- Indicador de voz web movible, límites de ventana, alternativa mediante botones/teclado y control de audífonos asociado al estado real de audio. El retorno a la sala está en el título.
- Fallos de módulos: el archivo de perfil reportado devolvía 404. Se agregó recuperación limitada, límites de error por ruta y no-cache en HTML; el error técnico ya no se presenta como una recuperación exitosa. Las sesiones multimedia y formularios con contenido bloquean la recarga automática.

### Verificación acumulada

La compilación Kotlin de release pasó. Las tres clases Android dirigidas (39 casos) pasaron. La batería final completa terminó con **1.715 pruebas de 361 suites, cero fallos, errores u omisiones**, ejecutando cada clase en un proceso aislado tras dos interrupciones del ejecutor de pruebas de Windows. Los registros de las interrupciones se conservan y no se cuentan como baterías aprobadas.

Web: 121 suites y 671 pruebas Jest aprobadas, TypeScript, ESLint de módulos modificados y compilación correctos. API final tras la autorización del antivirus: **892 casos, 883 aprobados, nueve omitidos y cero fallidos**. Señalización: 59 casos, 58 aprobados, uno omitido y cero fallidos. Gateway: una batería aprobada. PostgreSQL candidato: esquema completo y solicitud/aceptación de mensajes dentro de una transacción revertida. Se verificaron ocho fuentes desplegadas mediante SHA-256. Pruebas de SEO locales: 52 aprobadas; las comprobaciones del SEO publicado pasaron.

Web publicada como `dpl_8VdPiUC79Gvi3v6oscK9X22HGXnL`, READY, alias https://red-social-android.vercel.app. HTML raíz/app/perfil con no-cache confirmado; invitaciones siguen no-store/noindex. La revisión visual local de componentes y controles no detectó desbordamiento horizontal en 320, 390, 768, 1182 y 1920 px. El panel se arrastró correctamente dentro de un viewport de 390 px. Estas vistas usan datos ficticios y no sustituyen pruebas autenticadas entre dispositivos.

### Riesgos que impiden afirmar que toda la aplicación está lista sin reservas

1. **Alta: recuperación operativa.** Falta demostrar una restauración integral de respaldo. Los tres audios heredados ya reportados no tienen copia recuperable en los respaldos revisados ni originales aportados; la interfaz de reintento no puede recrear esos archivos.
2. **Alta: validación física pendiente.** Motorola no disponible por ADB al comprobar esta entrega. No se certifican aún instalación, notificaciones con proceso terminado, ahorro de batería, Bluetooth, voz/cámara/pantalla entre redes ni latencia real móvil-web.
3. **Alta si se promete privacidad equivalente a WhatsApp: cifrado.** No se ha establecido cifrado de extremo a extremo para toda la mensajería; no anunciar esa garantía.
4. **Media: lectura entre dispositivos.** Socket/FCM pueden retrasarse o perderse tras una caída entre guardar y publicar. Falta una cola duradera de controles y reconciliación de avisos al reconectar; el cursor de base de datos y la caché confirmada no reemplazan esa entrega.
5. **Media: rendimiento y escala.** Chunk web de aproximadamente 1,1 MB, APK grande, candidatos legados acotados para Amigos y pruebas pendientes con redes móviles lentas y concurrencia de producción.
6. **Mantenimiento:** siguen las advertencias conocidas de herramientas de desarrollo y dependencias indirectas; no se hizo una actualización masiva forzada. Las versiones y cambios de esta revisión están separados de los artefactos 9.1.12 cerrados.

Fuentes: [Vite](https://vite.dev/guide/build.html#load-error-handling), [W3C](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html), [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder), [confirmaciones de Socket.IO](https://socket.io/docs/v4/emitting-events/). La política de Pub/Sub se contrastó además con el código de la versión instalada de @socket.io/redis-streams-adapter.


### Hallazgo operativo crítico durante las comprobaciones posteriores

Alrededor de 06:24 UTC del 19 de octubre, `/ready` comenzó a responder HTTP 503 con `STORAGE_SCAN_SIGNATURES_STALE`. La salud HTTP básica de la API sigue en 200. El antivirus y su actualizador pasaron a unhealthy al superar el límite local de 24 horas desde la publicación de la base; FreshClam fue despertado de nuevo a las 06:27 UTC y confirmó que la base oficial 28157 seguía al día. Esto bloquea nuevas subidas protegidas por el escáner: **no se considera resuelta la preparación para producción mientras siga así**.

Se preparó una propuesta de límite de 48 horas en `artifacts/release-9.1.13/antivirus-proposal/`, con pruebas que aceptan 25–47,9 horas y rechazan más de 48 horas, fechas futuras y comandos ausentes. No se aplicó al servidor; se pidió la decisión del usuario por ampliar un límite de seguridad existente. La comprobación automática de actualizaciones conserva su frecuencia de dos horas. La documentación de ClamAV establece la comprobación y validación de versiones y firmas; las 48 horas son una propuesta operativa local, no una exigencia de ISO o de ClamAV.

La carga invitada en el navegador de pruebas necesitó autorizar Local Network Access para acceder al endpoint Tailscale desde esta máquina. Tras autorizarlo en ese contexto, el contenido público se mostró. Esto se documenta como condición del entorno local de prueba; no se generaliza a todos los navegadores o redes.

### Resolución autorizada del bloqueo de subidas — 19 de octubre, 06:35 UTC

El usuario autorizó explícitamente **«Aplicar el límite de 48 horas»**. Se aplicó ese mismo límite en el cliente de escaneo de la API, el healthcheck del daemon y el del actualizador. FreshClam conserva `Checks 12`: doce comprobaciones al día. Se conservaron la validación de fecha futura, comandos de protocolo, integridad de configuración y política comprobada mediante hash, límites de archivo/cola/tiempo y rechazo de archivos detectados o cifrados. Una base de más de 48 horas vuelve a bloquear las subidas; no existe ampliación automática adicional. La disponibilidad depende de que la fuente oficial publique firmas dentro de ese plazo.

Los tres servicios recuperaron estado **healthy**. `/health` y `/ready` respondieron HTTP 200. La comprobación mediante el cliente real de API y el daemon real aceptó un texto inocuo y rechazó el patrón inerte de prueba EICAR con `STORAGE_FILE_REJECTED`. Los archivos temporales se retiraron; no se subieron objetos ni se modificaron cuentas o publicaciones reales. Las 30 pruebas específicas cubren el límite exacto de 48 horas, fechas futuras, comandos ausentes, cabecera CVD y fallos de protocolo.

Las imágenes nuevas son API `sha256:4ca17b769dc4335cf92d3db3bd980b8db2c721e5a188d13d659b88e8f16cec1a`, daemon `sha256:d37bf3b686a9f7cb68e733afe64e98ed2ac1a0770c18c78a9dad49e62bbc1ce8` y actualizador `sha256:b577be32a0be06b12f0a0570c761eaa7680f7c335b4c6e7e2943ff07c285ff0a`. Las fuentes activas coinciden con las verificadas localmente. Se conservaron los respaldos de compose y la reversión de cada paso. Evidencia: `artifacts/release-9.1.13/antivirus/production-verification.json`, `live-scan.log`, registros de publicación, `antivirus-freshness-tests.log` y `api-tests-final.log`.

Referencias oficiales: [FreshClam](https://docs.clamav.net/faq/faq-freshclam.html), [configuración de ClamAV](https://docs.clamav.net/manual/Usage/Configuration.html) y [configuración de ejemplo del actualizador](https://github.com/Cisco-Talos/clamav/blob/main/etc/freshclam.conf.sample). Las 48 horas son la decisión operativa local aprobada por el usuario, no una certificación ni una recomendación universal de ClamAV.

### Cierre de artefactos Android y comprobación pública — 9.1.13

Compilación final `BUILD SUCCESSFUL` en 20 min 57 s; APK y AAB **9.1.13 (153)** firmados con el certificado esperado `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`. La instantánea de fuentes Android coincide con la verificada al comenzar. R8 aprobado: ningún componente del manifest ni clase crítica ausente o renombrada indebidamente. Comprobación de 16 KiB aprobada, incluidas las 14 bibliotecas nativas de 64 bits y la alineación del bundle.

- APK: `artifacts/release-9.1.13/Novvin-9.1.13.apk`, 220.929.223 bytes; SHA-256 `9df6a0eee67094b5a1f41c9b928c62604257afb04346919c9219dcff272191d4`.
- AAB: `artifacts/release-9.1.13/Novvin-9.1.13.aab`, 131.443.958 bytes; SHA-256 `b18160411d292ff2c5d33aecc63d9e0d694dce20c8d029aab283613d64d40b98`.
- Registros: `release-result.json`, `android-test-summary.json`, `android-final-verified.log`, `apk-signature.log`, `aab-signature.log`, `page-size.log` y `r8-report.md` en ese mismo directorio.

La última comprobación ADB siguió sin mostrar dispositivos; no se instaló la versión en el Motorola, no se desinstaló la aplicación ni se borraron datos. La prueba móvil física sigue pendiente. No se publicó en Play Store.

Después del ajuste del antivirus se recargó la web de producción: diez tarjetas públicas, imagen visible cargada, video con metadatos sin error y `/ready` HTTP 200 desde el navegador. La comprobación final de SEO publicado aprobó; evidencia en `production-browser-final.log` y `production-seo-final.log`. Los resultados no acreditan latencia entre dispositivos ni posición en buscadores.

La verificación final de las 06:38 UTC confirmó seis servicios saludables (API, señalización, gateway, PostgreSQL, antivirus y actualizador) y once módulos activos idénticos a sus fuentes locales. Registro: `artifacts/release-9.1.13/server-final-verification.json`.
