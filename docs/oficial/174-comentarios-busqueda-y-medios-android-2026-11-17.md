# 174 · Comentarios, búsqueda contextual y medios en Android

## Correcciones

- La búsqueda contextual del video entrega el tema al navegador raíz, tanto en el feed como al abrir un video por sus rutas de detalle o perfil. El destino admite el término codificado y selecciona Contenido cuando hay una consulta inicial. El buscador de personas conserva su entrada sin término.
- En la hoja de comentarios de Videos se agrupan fecha, respuesta y reacción principal. La fila se adapta a pantallas estrechas sin desplazamiento horizontal. Las demás reacciones y las acciones autorizadas se encuentran en el menú de tres puntos. Un enlace a una respuesta abre su hilo.
- Llamada, directo y sala de voz comparten la exclusión de medios en el proceso Android. La llamada reclama la reserva antes de iniciar o aceptar en el servidor. Una reserva fallida no cambia la ruta de audio de la sesión que ya está activa. Al colgar, la llamada devuelve la reserva tras limpiar la sesión de audio y completar el reinicio de su motor.
- Una escritura accesoria de la caché puede fallar sin cambiar el resultado confirmado por Ubuntu al publicar o eliminar un video. La respuesta de borrado de una visita anterior queda fuera de la interfaz de otra cuenta.
- Las rutas de Videos y la fuente del recomendador dejan de publicar el correo como nombre visible o crédito del sonido original. Los créditos y títulos normales se conservan. El cambio de servidor requiere desplegar la API.
- Android y las páginas públicas explican cómo retirar contenidos, ejercer derechos sobre datos, pedir la eliminación de cuenta, denunciar problemas de seguridad infantil y solicitar el retiro de material por derechos de autor. La versión candidata es 9.1.43 con código 183.

## Alcance de la revisión

- Comentarios. Social, detalle de publicación, hoja genérica e hilos reutilizan `CommentItem`. Videos y sus rutas de detalle comparten su hoja propia. La captura motivó comprobar los dos componentes. El cambio de Social tiene una reserva de validación separada.
- Publicación y eliminación. Se siguieron los recorridos Android para videos, publicaciones y Momentos, incluidos el permiso del propietario y la confirmación de la API. La prueba de hacer visible una subida en Android y borrarla desde un teléfono sigue siendo independiente de las pruebas de los contratos de cliente y servidor.
- Medios. La exclusión cubre dentro del proceso Android el acceso simultáneo a tipos de sesión distintos. La convivencia con otra aplicación o navegador, la señalización y recuperación real y la liberación de micrófono, cámara y audio requieren ensayos físicos.
- Conservación. La API distingue la baja del registro activo del retiro físico de objetos. Queda por comprobar la purga en réplicas y respaldos, la recuperación ante fallos parciales de almacenamiento, los plazos operativos y la respuesta a una solicitud real.
- Requisitos legales. La identificación publicable del responsable, su domicilio, dirección y teléfono siguen pendientes de datos confirmados. Para la declaración de seguridad infantil de Play falta comprobar el contacto designado y el procedimiento operativo para escalar material confirmado a la autoridad competente.

## Pruebas

- La API y PostgreSQL en loopback aprobaron 29 comprobaciones HTTP con dos cuentas ficticias. Se subió un MP4 de prueba, se publicó, se repitió la solicitud con el mismo identificador y la segunda cuenta pudo abrirlo y encontrarlo con «Kirito». Después del borrado del propietario, la ficha y el archivo devolvieron 404. También se comprobaron publicación, acceso y borrado en Social y Momentos, y creación, respuesta, reacción, edición y borrado de comentarios de Social. Se verificaron el rechazo de borrados ajenos y la ausencia del correo sintético en las respuestas de Videos. La primera ejecución terminó en 415 porque a la API de laboratorio le faltaba `ffprobe`. La repetición utilizó `ffprobe` y `ffmpeg`.
- La batería dirigida del servidor aprobó 13 casos de rutas, atribución, reintentos y recomendaciones. Los controles de acceso y presencia de llamadas, salas y LIVE aprobaron 29 entradas del ejecutor Node, incluida una suite interna de llamadas con 67 casos sobre una base simulada.
- La compilación web y las 76 pruebas de páginas públicas aprobaron. Las dos políticas se pueden leer directamente en el HTML generado y las huellas de sus entradas del sitemap fueron actualizadas.
- La primera ejecución Audit aprobó 34 pruebas JVM y generó los APK de laboratorio. La segunda aprobó 35 pruebas JVM en siete clases, incluida la exclusión de los tres tipos de sesión, y compiló la instrumentación de navegación y comentarios. Las dos conservaron entradas y HEAD estables. Los archivos locales reservados de Social estaban presentes en la compilación de laboratorio y quedan pendientes de su propia publicación.
- La prueba JVM directa de la exclusión de medios aprobó tres casos. Los ensayos de Android real, la creación de un AAB de producción y la publicación de la versión 183 en Play siguen pendientes.

Logs y recibos locales en `D:/Novvin-Entregas/evidencias/2026-11-17-mobile183/`.

Para revertir el cambio, aplica el revert del commit correspondiente en las dos ramas estables y compara sus árboles. Si se llegó a desplegar la API, la reversión operativa utiliza la imagen anterior verificada. Conserva un código de versión Android superior al mayor que Play haya aceptado.

## Referencias

- [YouTube sobre responder, reaccionar, editar y eliminar comentarios](https://support.google.com/youtube/answer/6000964?co=GENIE.Platform%3DAndroid&hl=es).
- [Google Play sobre privacidad y eliminación de cuenta](https://support.google.com/googleplay/android-developer/answer/10144311?hl=es).
- [Google Play sobre contenido generado por usuarios](https://support.google.com/googleplay/android-developer/answer/9876937?hl=es).
- [Google Play sobre propiedad intelectual](https://support.google.com/googleplay/android-developer/answer/9888072?hl=es).
- [Google Play sobre la política de seguridad infantil](https://support.google.com/googleplay/android-developer/answer/14747720?hl=es).
- [Decreto 1074 de 2015, artículo 2.2.2.25.3.1](https://www.cancilleria.gov.co/sites/default/files/Normograma/docs/decreto_1074_2015_pr025.htm).
