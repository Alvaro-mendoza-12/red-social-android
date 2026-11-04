# Contadores de Social y reproducción única de avatares

Correcciones del 4 de noviembre de 2026. La revisión integral móvil continúa abierta según [101](101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md). Este bloque responde a la publicación sin contadores después del apagón y a los videos de perfil que se repetían.

## Causa comprobada

La publicación «67» de la captura, `c5bdc7d2-f33e-4e4f-b662-9ec37ac57d2f`, conservaba en PostgreSQL cuatro reacciones —dos LIKE, una HAHA y una FIRE— y dos comentarios. No se habían perdido con el corte eléctrico. La fuente de candidatos de recomendaciones entregaba el texto y la multimedia sin los campos de interacción. Al sustituir la lista con ese resultado, el mapeo Android usaba sus valores predeterminados de cero.

El feed personalizado incorpora ahora las métricas autorizadas después de seleccionar su página. Comparte el lector por lotes con el feed normal, conserva identidades y orden y mantiene las métricas fuera del ranking y del cursor. Una lectura fallida devuelve un error identificable en lugar de fabricar una página con ceros. El cambio de servidor permite recuperar los contadores en los clientes existentes al actualizar Social.

Los avatares de video usan reproducción única en la imagen circular, la previsualización del editor y la apertura ampliada del perfil. El editor usa límites reales de Media3 y elimina el sondeo que regresaba al principio. Los demás videos conservan sus políticas de reproducción y las rutas anteriores siguen siendo válidas.

## Servidor instalado

Antes de modificar producción se creó un respaldo de PostgreSQL en formato personalizado de 7.244.032 bytes, se comprobó su catálogo de restauración y se verificaron las huellas del respaldo y del esquema. Se conservaron además las dos configuraciones Compose y la identidad de la API anterior. No se restauraron ni sustituyeron los datos reales.

La imagen activa es `novvin-api:engagement110-20261104_a0dbcc2d_p2`, ID `sha256:7bd40b404704bdd5353adcd3fad048124725876276cfb3ca74a483cd246e2132`. Instalada a las 22:40:54 UTC desde las cuatro fuentes API del commit `a0dbcc2d5e14ff9410bbd4b61f5f24cd962ddc40`. Se contrastaron esas fuentes con las del contenedor. La configuración persistente apunta a la imagen nueva; su SHA-256 es `8a36a3130018436b2d4649669d3b38739a0f23e4e3224e81e648be77bd27b849`. Sólo se reemplazó la API. Salud y disponibilidad aprobaron y la proyección activa de la publicación devolvió 4 reacciones y 2 comentarios mediante una transacción de sólo lectura.

El primer intento de empaquetado copió fuentes con permisos privados de QA y el proceso de producción no pudo leerlas. La API se revirtió antes de repetir el despliegue. La segunda imagen fija propietario y permisos de las cuatro fuentes y comprobó sus importaciones con UID/GID 10001 antes de instalarse. Se conservan los logs de ambos intentos. La imagen anterior `novvin-api:likes104-20261103_84ae4912` y la configuración respaldada permiten revertir sólo el código; no se debe retroceder la base de datos para revertir este cambio.

## Evidencia de este bloque

- Node local: 21 entradas del ejecutor, sin fallos ni omisiones, más la suite existente de `data-routes` aprobada. Algunos archivos agrupan sus comprobaciones en una sola entrada; la cifra no se presenta como número de recorridos de usuario.
- Ubuntu/PostgreSQL 16.15: nueve pruebas aprobadas. Se restauró únicamente el esquema en una base aislada y se insertaron datos ficticios. HTTP real contrastó feed normal, recomendaciones y detalle con cuatro reacciones y dos comentarios; después comprobó la retirada de una reacción, nuevas conexiones y rechazo sin autenticación. La identidad de este ensayo es ficticia y no equivale a iniciar sesión con una cuenta de producción.
- Android JVM: 388 pruebas seleccionadas en 63 clases, sin fallos, errores ni omisiones. Incluyen multimedia, perfil, publicaciones, Social y recomendaciones. La primera ejecución falló en tres casos por declarar un SDK no admitido por Robolectric; se corrigió a la configuración disponible y se repitió la selección.
- Emulador Android 15: 23 pruebas aprobadas en 35,332 segundos. La nueva comprueba el final de un clip real, una sola llegada al estado terminado y ausencia de reinicio al volver del segundo plano. La selección repite las regresiones de recorte, cámara, paginación, entrada de video, multimedia, invitado y compartidos.
- Lectura nativa R2/Ubuntu: una prueba aprobada en 4,6 segundos. Media3 leyó los mismos 4 KiB desde ambas rutas, confirmó el origen R2 y ambas rechazaron una sesión inválida con 401. El emulador recuperó tamaño, densidad y fuente y se apagó al terminar. No se operó el teléfono físico.

Los paquetes de depuración comprobados son **9.1.40-debug / 180**, construidos desde `b3a5343f77833755fdced03c022485cd2c8f5142`. Ese commit añade la configuración de pruebas a las correcciones de `e73ceda7` y al ensayo PostgreSQL de `a0dbcc2d`.

## Entrega firmada 9.1.40 / 180

El AAB se construyó desde el mismo commit `b3a5343f77833755fdced03c022485cd2c8f5142`, árbol `beb6ce09c0eb61583b9ef7759c51a2b086b14e33`. Se comprobaron versión, identificador de compilación y presencia del origen R2 efectivo dentro del DEX.

- Archivo en ambos proyectos: `artifacts/releases/9.1.40-180/Novvin-9.1.40-180.aab`.
- SHA-256 `11727A40B0C1D9B545F50A71F2F0E40B5567550C554DE79A662EF4462C0BA252`.
- AAB de 132.598.696 bytes; descarga comprimida máxima calculada para un dispositivo de 66.932.036 bytes.
- Firma verificada con certificado de subida `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
- R8 aprobó 45.448 clases y los 17 componentes declarados. Las 14 bibliotecas nativas de 64 bits y el empaquetado aprobaron 16 KiB.
- Los cuatro presupuestos de tamaño aprobaron. Lint K1 terminó con cero errores, cero errores fatales y 318 avisos, conservando las reglas anteriores.

Las dos copias tienen la misma huella y conservan mapping, controles y `delivery.json` fuera de Git. La entrega incluye las correcciones estables descritas aquí; no incluye las pantallas del plan experimental. El AAB no se ha subido a Google Play. La aceptación física y la publicación en Play corresponden al propietario. La selección JVM y los ensayos de emulador no se presentan como una nueva batería integral de todos los módulos.

## Qué ocurrió con R2 durante el apagón

El Worker entrega objetos de un bucket privado y consulta a Ubuntu para comprobar el acceso antes de servirlos. R2 conserva los bytes, pero no sustituye al catálogo ni al autorizador de la aplicación. Si Ubuntu está apagado, una lectura nueva puede fallar aunque el objeto esté replicado. El almacén local de identidad conserva imágenes de perfil por cuenta; por eso algunas seguían visibles desde caché. La corrección no convierte la multimedia privada en pública ni omite esa autorización. Una mejora futura de disponibilidad requiere diseñar y verificar también la continuidad del autorizador y del catálogo.

La lectura nativa aprobada no cierra la simulación de red limitada de [103](103-rendimiento-social-y-videos.md), la fluidez física, el feed completo con una sesión real ni los demás archivos y recorridos pendientes de la matriz. Los módulos experimentales de 105 permanecen exclusivamente en su rama.
