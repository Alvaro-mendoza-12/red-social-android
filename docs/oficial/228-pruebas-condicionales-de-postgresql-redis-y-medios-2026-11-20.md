# 228. Pruebas condicionales de PostgreSQL, Redis y medios

**NO APTA PARA PRODUCCIÓN.** En la revisión de pruebas `74239260` pasaron 123 casos de 25 archivos con PostgreSQL 16.15 y Redis 7.0.15, más dos casos en un contenedor Linux sin red externa. Los informes TAP permiten identificar por nombre 38 de los 38 casos que habían quedado omitidos en las suites generales de GitHub. [Contadores, correspondencia individual y huellas](evidencias/228-pruebas-condicionales-de-postgresql-redis-y-medios.json)

| Suite general del commit anterior | Casos con resultado aprobado en GitHub por rama | Omitidos por rama | De esos omitidos, aprobados en el laboratorio |
|---|---:|---:|---:|
| API | 1.168 | 36 | 36 |
| Signaling | 60 | 2 | 2 |

En `7e9e07c4`, la web aprobó 1.346 pruebas Jest y 129 pruebas de Node por rama. El trabajo dedicado de PostgreSQL también aprobó cinco pruebas de puntos y una de migración. Los dos CI web y servicios terminaron con seis trabajos aprobados cada uno. [main](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44769072180) · [feature/live-gifts](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44769072155)

## Recuperación de contraseña

**CORREGIDO Y VERIFICADO.** Ocho de once casos devolvían un resultado inesperado cuando se habilitaba la base real. La ruta de la API ya comunicaba la revocación de sesiones. El entorno que ejecutaba la ruta en la prueba carecía de ese componente. Ahora llama al publicador del producto con un transporte local y comprueba el identificador de la sesión revocada. También comprueba que una transacción revertida deja vacío el registro de eventos. Pasaron los once casos con un rol propietario de su base de prueba, sin privilegios de superusuario. El workflow crea ese rol en el PostgreSQL temporal de GitHub. [Corrección](https://github.com/Alvaro-mendoza-12/red-social-android/commit/61d1dd4d5eefeda163e3e39bcc0b8ebe603f488f)

## Almacenamiento y mensajería

**CORREGIDO Y VERIFICADO.** Los roles restringidos de dos pruebas de almacenamiento se creaban sin contraseña y sus clientes heredaban la credencial del administrador. En el clúster con SCRAM falló la autenticación. Ahora cada rol recibe su propia contraseña aleatoria. Pasaron los nueve casos de referencias de archivos y los 17 de reprocesamiento histórico con archivos sintéticos. [Corrección](https://github.com/Alvaro-mendoza-12/red-social-android/commit/a00bdaa7629db59bd1c9130e0b634f7622138268)

El comprobador privado de mensajería reutilizaba amistades ya aceptadas. También excluía de su recuento el identificador histórico del remitente. El producto registra la amistad al responder a la solicitud desde la revisión `462a3a45`. El comprobador ahora usa destinatarios sintéticos independientes cuando exige un primer contacto y comprueba la fila aceptada con ambos identificadores canónicos. Pasaron los 18 casos internos del archivo. Las notificaciones usaron el transporte local del ensayo. [Corrección](https://github.com/Alvaro-mendoza-12/red-social-android/commit/7423926084336d1868ec81506fb19df4de1887fc)

Para probar mensajes y contadores de publicaciones se aplicaron las 37 migraciones y se ejecutó el inicio de la API. Esto preparó las tablas que el producto inicializa al arrancar. El escenario de recomendaciones también recibió una clave sintética para firmar el cursor. El comprobador volvió a leer los contadores con una conexión nueva. Las reservas de anfitrión de directos se verificaron con dos instancias del componente de estado y un Redis privado.

## Medios y arranque

**VERIFICADO Y APROBADO en laboratorio.** El contenedor de la API usó el código, scripts y pruebas de `74239260` montados en lectura y las dependencias de la imagen privada documentada en 225. El sistema de archivos raíz era de solo lectura. La prueba del antivirus usó los cuatro capacidades Linux del servicio y un directorio temporal exclusivo para los ejecutables sintéticos. El proceso arrancó cuando el actualizador de firmas respondió, falló o quedó esperando. La prueba de avatar se ejecutó como UID 10001 y verificó la miniatura WebP, la lectura parcial del vídeo original y un WebP animado con FFmpeg. Estas pruebas emplearon archivos sintéticos.

Los cambios de esta entrega afectan al CI y a las pruebas. La medición de 45 minutos del informe 227 sigue correspondiendo al código del producto. Permanecen pendientes el aviso de libxml2, la capacidad y restauración conjunta en la infraestructura objetivo, la inspección interna del Ubuntu y los teléfonos físicos. [Capacidad y límites](227-espacio-ci-y-carga-de-la-candidata-2026-11-20.md) · [Evaluación de 23 puntos](224-certificacion-independiente-2026-11-20.md)
