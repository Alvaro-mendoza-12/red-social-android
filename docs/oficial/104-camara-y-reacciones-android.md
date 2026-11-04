# Cámara y reacciones en Android

El 3 de noviembre de 2026 se reprodujo el achatamiento al recortar JPEG verticales cuya orientación está en EXIF. El editor calculaba el rectángulo con el tamaño del archivo sin orientar, mientras el decodificador ya había orientado la foto. «Original» ahora conserva la proporción de la imagen orientada y devuelve el archivo intacto si no hay recorte. Los demás formatos recortan las coordenadas visibles, incluidas las orientaciones con reflejo. La ruta para Android 7 y 8 conserva la misma transformación que el decodificador moderno. [Referencia de ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface).

La cámara compartida de mensajes directos, grupos, comunidades y Momentos centra el disparador y usa fondo oscuro en la zona de navegación. Su botón de galería muestra la última foto que Android permite leer y abre el selector habitual. Cuando no hay una foto accesible muestra un icono. No solicita permisos adicionales y descarta archivos incompletos. La consulta limita el resultado a un archivo, ordena la fecha y el identificador en orden descendente y observa cambios de MediaStore mientras la cámara está activa. [Contrato de consultas Android](https://developer.android.com/reference/android/content/ContentResolver).

## Reacciones en Ubuntu

Las escrituras existentes usaban identificadores normalizados. La lectura de publicaciones y videos importados podía consultar la reacción con el identificador original y devolver «sin reacción» aunque PostgreSQL ya la hubiera guardado. Algunos registros de publicaciones importados también referían al post como `postId`. La proyección ahora usa la misma clave interna de escritura y conserva los identificadores públicos. Las marcas de eliminación prevalecen sobre el dato histórico.

Cinco regresiones de proyección y las suites existentes de rutas y configuración de producción pasaron. Los handlers HTTP reales se ejecutaron con PostgreSQL en una base vacía que copió el esquema de Ubuntu. Dos sesiones de prueba de una cuenta verificaron lecturas de feed y detalle, `LIKE`, `LOVE`, `FIRE`, repetición de escrituras y retiro de reacción. También se comprobó el aislamiento de otra cuenta, `401` sin credencial válida y persistencia al reabrir el pool. La autenticación del ensayo usó JWT exclusivos de prueba. Se retiró la base aislada al terminar.

El primer intento de activar el servicio superó el plazo y restauró automáticamente la imagen anterior. El arranque quedó esperando una descarga de FreshClam y la versión restaurada sufrió la misma espera. Se acotó esa descarga a 20 segundos más cinco para detenerla, bajo el mismo usuario que ejecuta FreshClam. La política de análisis de subidas sigue vigente. Una prueba en la imagen candidata con las capacidades de producción verificó el arranque tras éxito, fallo y espera indefinida. [Configuración oficial de FreshClam](https://github.com/Cisco-Talos/clamav/blob/main/docs/man/freshclam.conf.5.in).

La imagen activa `novvin-api:likes104-20261103_84ae4912` pasó `/health` y `/ready` con HTTP 200. El acceso público a `/ready` devolvió 200 y la lectura de feed sin sesión devolvió 401. Se verificó la huella del archivo de rutas y se mantuvo una réplica. La imagen de reversión es `novvin-api:r2-media-final-20261102` y la configuración anterior está respaldada en el directorio de operación de la entrega. El despliegue reemplazó sólo el contenedor de API.

## Android

Social, detalle, perfil y ranking usan los acuses de la misma operación del repositorio. Las respuestas anteriores a una reacción local no pueden reemplazarla en los recorridos protegidos, incluso si antes terminó otra consulta más reciente. Se actualiza la copia de Social por cuenta al confirmar la escritura y se serializan mutaciones simultáneas a la misma publicación en el dispositivo. La autorización de cuenta se captura antes de cambiar al hilo de entrada/salida y se comprueba antes de publicar el acuse local. Videos conserva la elección durante las lecturas que ya estaban en curso. Los botones del detalle usan el identificador de la publicación cargada para mantener los recorridos de enlaces con otro formato del identificador.

La primera batería completa aprobó 2.687 pruebas JVM en 507 clases. La segunda aprobó **2.689**, sin fallos, errores ni omisiones, con las protecciones finales de respuestas fuera de orden y cambio de cuenta del commit `1b3ff03b8851`. Se ejecutaron **22 pruebas de interfaz** en Android 15. Las siete de cámara y recorte incluyen JPEG vertical con EXIF, archivo original intacto, salida 4:5, las ocho orientaciones y reflejos, foto más reciente al cambiar la galería, exclusión de archivos incompletos y centrado de controles en dos anchuras. Las otras quince comprobaron paginación de Social, entrada de Videos, contenido multimedia, sesión invitada, ciclo de reproducción y diario de compartidos. La lectura nativa del mismo fragmento de 4 KiB por R2 y Ubuntu volvió a pasar, con respuesta R2 y rechazo 401 de ambas rutas con sesión inválida.

Estas pruebas usaron los paquetes de depuración 179 del commit `1b3ff03b8851`. La conexión posterior de tres callbacks del detalle está en `dde3c49f`. La entrega firmada 9.1.39 / 179 incluye ambos commits. La revisión integral de Android sigue abierta, incluida la prueba en el teléfono que reportó el problema y los pendientes de rendimiento de la revisión 103.

## Candidato firmado 9.1.39 / 179

El AAB se construyó desde `dde3c49f09651cc4d6ef98ee7c0e90272ef01ec9`, con árbol de fuentes `28077c696d96b11bd20a1c585a75835426289fad`. Se comprobaron en el DEX tanto el identificador de compilación como el origen multimedia R2 efectivo. Incluye las correcciones de acceso, bandeja y multimedia posteriores a la entrega 178, además de las de este documento.

- Archivo entregado en los dos proyectos, `artifacts/releases/9.1.39-179/Novvin-9.1.39-179.aab`.
- SHA-256 `F13F92AF8A8CA36F4CBC23F78F11C3A94D93FB47358A10E33AA045AF243CD751`.
- Tamaño de AAB de 132.593.006 bytes. La descarga comprimida máxima calculada para un dispositivo es de 66.933.289 bytes.
- Firma verificada con certificado de subida `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
- R8 aprobado para 45.413 clases y los 17 componentes declarados. Las 14 bibliotecas nativas de 64 bits y el empaquetado pasaron el control de 16 KiB.
- Los cuatro presupuestos de tamaño pasaron, incluidos DEX comprimidos y la mayor variante nativa comprimida.

La compilación de release y Lint K1 finalizaron correctamente. Lint informó cero errores, cero errores fatales y 318 avisos. Se mantienen sus reglas y se conservan los avisos. Las dos copias entregadas tienen la misma huella. Junto al AAB se guardaron mapping, presupuestos y metadatos de procedencia, fuera de Git. El AAB no se ha subido a Google Play y su aceptación física corresponde al propietario. El paquete firmado, las pruebas JVM, los ensayos de emulador y el despliegue del servidor tienen las identidades de fuente indicadas por separado.
