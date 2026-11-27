# Subidas reanudables durante incidentes de almacenamiento

27 de noviembre de 2026 · La [verificación de Android 190 y Ubuntu](259-novvin-190-dns-y-procedencia-ubuntu-2026-11-27.md) detectó que la API general seguía respondiendo mientras el control de almacenamiento rechazaba firmas caducadas. Este tramo comprueba la respuesta de las subidas reanudables en ese escenario.

## Android

La subida reanudable distingue los rechazos de autenticación, permisos, tamaño, formato, frecuencia e indisponibilidad. Cuando el servidor devuelve 503, la operación termina con un aviso para reintentar más tarde. La sesión reanudable se conserva y el siguiente intento puede consultar el progreso guardado. Se mantiene la recuperación mediante consulta del offset ante un conflicto o una respuesta ambigua de transporte.

La subida directa de archivos grandes usa los mismos mensajes para Ubuntu. El texto de una respuesta HTTP fallida de Ubuntu se descarta. Si el servidor devuelve un identificador de seguimiento con formato UUID, el aviso lo incluye para facilitar el diagnóstico.

La extracción aislada de la versión 190 con el parche superó 27 pruebas JVM. Incluyó la política de subida, el protocolo reanudable y el manejo de errores y reintentos. La APK y el AAB 190 se generaron antes de esta corrección.

## API

El controlador TUS aplica el mismo contrato de errores públicos que la API general. Conserva el estado HTTP y las cabeceras del protocolo. La respuesta expone un código público y su identificador de petición. El registro de fallos del servidor conserva el identificador, el código y una huella del mensaje.

En el ensayo HTTP local, una indisponibilidad simulada al crear una sesión devuelve 503, `INTERNAL_ERROR` y el identificador de seguimiento. La petición de prueba no crea una sesión. Al restablecer la dependencia, el mismo conjunto verifica la creación, la subida de audio, la consulta del offset y la finalización pendiente. Otra prueba conserva el offset y los bytes ante un 503 durante la consulta y finaliza la sesión cuando vuelve el servicio. Pasaron seis comprobaciones de Node en la revisión estable con el parche.

También se preparó una imagen candidata a partir de la imagen activa de Ubuntu. Cambia un único módulo JavaScript y valida el comportamiento con la red externa desactivada. El contenedor en servicio conservó la imagen que tenía antes del ensayo. Los procedimientos del repositorio comprueban `/ready` para promover o revertir una imagen. El despliegue continúa pendiente porque el antivirus de almacenamiento aún carece de firmas vigentes.

## Dominio público

GoDaddy publica la IP de Firebase Hosting para `novvinapp.com` y el CNAME del sitio Novvin para `www.novvinapp.com`. Se retiró el reenvío temporal anterior y el registro de estacionamiento que GoDaddy añadió al retirarlo. Los dos registros MX de correo conservan sus destinos y prioridades. Firebase emitió certificados activos para ambos nombres. El inicio de sesión, la aplicación y los datos continúan en Ubuntu.

El ensayo por HTTPS con destino de Firebase fijado validó en el dominio raíz 22 páginas, diez imágenes, 43 redirecciones canónicas, ocho redirecciones hacia Ubuntu, la asociación de Android y la página 404 personalizada. Una ejecución pública también completó el mismo conjunto en `www`. Otras consultas a `www` todavía devolvieron 404 de Firebase durante la propagación. La comprobación de inicio de sesión cubre el destino y la conservación de la ruta solicitada. Sigue pendiente el recorrido con una cuenta autenticada.

La revisión integral permanece abierta. Las pruebas de este tramo cubren las subidas grandes y reanudables y la entrada pública de la web.
