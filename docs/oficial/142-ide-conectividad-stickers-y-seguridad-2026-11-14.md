# Continuación 142: IDE, conectividad y recuperación web

Fecha: 14 de noviembre de 2026. Incidente urgente indicado por el propietario durante la continuación móvil 141. Esa continuación permanece local, con sus cuatro fallos de pantalla reproducidos y sin publicar. Este bloque no cierra la revisión integral ni entrega una APK.

## Diagnóstico y reparación del IDE

`D:/AndroidSTUDIO` contenía solamente parte de `bin`, `jbr` y `lib`: faltaban `product-info.json`, los plugins y las bibliotecas de la aplicación. El lanzador no podía encontrar su descriptor. No era un error del proyecto Android ni de la base de datos.

Se descargó la distribución completa oficial **Android Studio Rabbit 2 | 2026.2.2 Patch 1**, build `262.10968.63.2622.16840690`. El ZIP tiene 1.546.594.374 bytes y su SHA-256 coincide con el publicado por Google: `6813b0ce669844b87c5d56b9c5f661362bd3bebff55327415496bd7457a7b917`. Se comprobaron las rutas del ZIP, sus CRC durante la extracción y la firma Authenticode válida de Google LLC del lanzador.

La instalación incompleta se conserva en `D:/Novvin-IDE-Repair-20261114/incomplete-installation-before`; el paquete y los recibos permanecen en esa carpeta de reparación. No se borraron proyectos, perfiles, SDK, emuladores ni claves. El acceso directo existente sigue apuntando a `D:/AndroidSTUDIO/bin/studio64.exe`.

El IDE abrió **red social - live-gifts** y el registro confirma que selecciona el JDK 21 existente para Gradle 8.13. Se ajustó únicamente `.gradle/config.properties`, ignorado por Git, en los dos worktrees; se respaldó la configuración existente y se registró la ausencia previa en main. La sincronización del IDE quedó cancelada desde la interfaz según su registro: **no se afirma sincronización completa**. La apertura del IDE, su configuración Java y la comprobación CLI se distinguen de una compilación o entrega de APK.

La comprobación `gradlew help --offline`, con JDK 21 y un único JVM, terminó **BUILD SUCCESSFUL en 23 segundos**. El registro del IDE termina con cierre ordenado; no se volvió a abrir después de ese cierre.

Fuentes: [distribución y huellas oficiales de Android Studio](https://developer.android.com/studio), [instalación oficial](https://developer.android.com/studio/install).

## Incidente de red

Windows y Ubuntu resuelven DNS, pero las conexiones HTTPS por IPv4 a Novvin, Vercel, GitHub y Google agotan su tiempo de espera. En ambos equipos, la conexión IPv6 a Google completa TLS y responde. El gateway local contesta; la traza IPv4 posterior no responde. Tailscale no tiene un nodo de salida configurado en Windows. No se atribuye la causa exacta a router o proveedor sin acceso a su diagnóstico WAN.

El propietario confirma que **novvinapp.com abre desde datos móviles con Wi-Fi desactivado**. En Ubuntu, `/health`, `/ready`, PostgreSQL y el gateway local responden correctamente; los contenedores pertinentes figuran saludables. Esto separa la avería de salida IPv4 local de un supuesto fallo general del dominio o la base de datos. No demuestra que todos los recorridos autenticados sean correctos.

No se modificaron DNS, cookies, claves ni contratos; tampoco se reiniciaron servicios sanos. Un error de conexión anterior a HTTP pertenece al navegador y no puede reemplazarse por una página 404 de la aplicación. La reparación de salida IPv4 requiere comprobar el router o el proveedor; reiniciar adaptadores o cambiar DNS a ciegas no se considera una reparación demostrada.

## Correcciones web del bloque

- Stickers de conversación de **120 × 120 px**, con el marco y la imagen del mismo tamaño. Se mantiene la animación original y la lectura autenticada.
- Avisos de medios que distinguen falta de conexión, tiempo de espera, sesión, permisos, archivo no disponible, límites, formato, saturación y fallos temporales. No muestran mensajes arbitrarios del servidor, URLs privadas ni credenciales.
- Tarjeta de error apilada y botón **Reintentar** legible. El botón de abrir las opciones del sticker es nativo y no envuelve el botón de reintento: evita controles interactivos anidados.
- Una sección cuya importación tarda más de doce segundos ofrece un paso de recuperación; el estado también responde a cambios de conectividad. No recarga automáticamente ni abandona la navegación.
- Los reintentos manuales de módulos respetan la protección de llamadas, transmisiones y borradores ya existente. La pantalla explica por qué debe conservarse ese trabajo antes de recargar.

La página 404 personalizada ya existe. `robots.txt` permite el contenido público a `User-agent: *`; excluye rutas de API, sockets y almacenamiento privado. No bloquea a un rastreador concreto ni utiliza robots como mecanismo de autorización. No se ha abierto contenido privado para mejorar indexación.

## Validación y sus límites

La suite web completa superó **1.264 pruebas en 198 suites** antes del último ajuste del botón nativo. Después de ese ajuste se repitieron **29 pruebas de medios/stickers**, sin fallos. La compilación final de TypeScript/Vite y las comprobaciones de 21 páginas públicas aprobaron.

El navegador aislado, con API, sockets e imágenes ficticios, comprobó anchos de **1440, 768 y 375 px**: un rechazo 403 intencional del sticker, explicación visible, reintento y recuperación de la imagen a 120 px. También comprobó seguidores, asistente y Social. No hubo errores JavaScript, desbordamiento horizontal, imágenes rotas al recuperar ni infracciones axe en las pantallas ensayadas. Los tres avisos de consola son los tres 403 provocados, uno por ancho; no se presentan como cero avisos. El historial sigue desplazándose con barra oculta.

Estos ensayos no representan una sesión personal en producción ni prueban la entrega de todos los mensajes, los archivos históricos reales o el comportamiento físico Android. La evidencia y sus huellas se registran en [142-web-ide-network.json](../quality/evidence/142-web-ide-network.json).

## Los veinte controles solicitados

La tabla identifica el alcance comprobado y lo que continúa abierto. Un escaneo estático sin hallazgos no certifica todos los controles.

| # | Control | Evidencia actual y trabajo pendiente |
|---|---|---|
| 1 | Ocultar secretos y API keys | Auditoría estática de runtime sin hallazgos en sus reglas. Falta revisar todos los emisores y bundles. Las claves públicas de cliente no sustituyen la autorización ni deben confundirse con secretos privilegiados. |
| 2 | Eliminar secretos de Git | Rastreo de 12.856 blobs de texto del historial alcanzable, con patrones de alta confianza: cero candidatos. Excluye blobs grandes/binarios y patrones no contemplados; no es una auditoría exhaustiva. No se reescribió historia ni se borraron respaldos. |
| 3 | Claves públicas de base de datos | No se publicó ninguna clave privada. La API activa pertenece al servidor Ubuntu; los nombres de compatibilidad heredados no prueban uso de un servicio externo. Revisar todavía todos los contratos y permisos de cliente. |
| 4 | Row Level Security | **No habilitado**: 211 tablas en public y 54 en supabase_import sin RLS ni políticas. El rol de conexión no es superusuario y no puede saltarse RLS. La API aplica autorización, pero eso no equivale a RLS. Preparar políticas y contexto de sesión por transacción, con pruebas de dos cuentas, antes de activarlas sin romper consumidores. |
| 5 | Cifrar datos sensibles | No se cierra: falta completar inventario de datos, almacenamiento, copias y rotación de claves. HTTPS no prueba cifrado de la base en reposo. |
| 6 | Forzar autenticación | Perfiles, mensajes y búsqueda de stickers ensayados por el gateway local responden 401 sin credencial. Falta la matriz completa de rutas, sesiones y roles. |
| 7 | Restringir acceso a registros | PostgreSQL está publicado en loopback; las tres rutas anteriores no entregan registros anónimos. Falta validar horizontalmente cuentas diferentes y revocaciones en todos los recursos. |
| 8 | Bloquear manipulación de campos | Pendiente de revisar y ensayar cada operación sensible, campos permitidos y autoridad del servidor. |
| 9 | Proteger sesión y cookies | La protección de identidad en medios se conserva; los ensayos de cuenta existentes no sustituyen la revisión completa de cookies, CSRF, renovación y cierre de sesión. |
| 10 | Hashear contraseñas | Pendiente de revalidación del consumidor activo y sus parámetros; no se inspeccionaron ni imprimieron contraseñas ni hashes de usuarios. |
| 11 | Rate limiting | Los errores de saturación se explican en el cliente. Falta demostrar límites efectivos en autenticación, mensajería, medios y otras operaciones productivas. |
| 12 | Protección contra bots | Permitir rastreo público no elimina la necesidad de controles de abuso en endpoints. Falta validar su cobertura real; no se desactivó autenticación para bots. |
| 13 | Queries parametrizadas | El diagnóstico PostgreSQL se ejecutó en transacción de solo lectura. Falta revisar y ensayar todos los constructores de consultas de producción; ese diagnóstico no los certifica. |
| 14 | Validar entradas | Se conserva la política de origen, identidad, tamaño y MIME de medios. Falta cerrar el inventario de entradas y contratos de API. |
| 15 | Sanitizar contenido | Los nuevos avisos muestran texto fijo; no reflejan errores arbitrarios ni HTML. Falta la revisión completa de todos los renderizadores y contenido compartido. |
| 16 | Restringir archivos | `/ready` confirma escaneo ClamAV obligatorio; el lector web conserva autenticación y límites. Falta revalidar subida, cuarentena, firmas, tipos y descargas completas. |
| 17 | Devolver solo datos necesarios | Las consultas sin sesión ensayadas no entregan registros. Falta contrastar los campos de respuestas autenticadas y los permisos de cada recurso. |
| 18 | Security headers | `nosniff` confirmado en las seis peticiones locales y controles de CSP/SEO del build aprobados. La comprobación pública actual por esta red está afectada por el fallo IPv4; no se infiere toda la política desde una sola cabecera. |
| 19 | Forzar HTTPS | TLS IPv6 de control válido y dominio accesible con datos móviles según el propietario. Falta repetir redirecciones, certificado y cabeceras del dominio desde una salida funcional; no se deshabilitó TLS. |
| 20 | Escanear dependencias | `npm audit --omit=dev` web: cero avisos conocidos. Pendientes otras dependencias de desarrollo, backend, Android, imágenes base y el análisis de alcance de futuras alertas. |

## Publicación y continuidad

La selección de este bloque excluye los cambios móviles 141, los cuatro residuos SEO/Vercel de feature, `AGENTS.md` y los seis documentos ajenos de main. No modifica `correciones` ni incorpora experimentos 105. Los recibos de integración distinguirán commits locales, publicación remota y despliegue efectivo; ninguna de esas fases se presupone por haber compilado.

Siguen pendientes la revisión móvil integral, los consumidores retenidos LIVE, la entrega real de mensajes e historiales, las llamadas físicas, los archivos de cuentas reales, la aplicación de los veinte controles y la APK final identificada. No se declara preparación para marketing masivo.
