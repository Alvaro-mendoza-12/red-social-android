# Citas y reintentos de documentos: comprobación física

Fecha: 2026-10-02. Android conserva versionCode 117 y versión base 9.0.20.

## Correcciones

Las respuestas muestran el autor del mensaje citado: «Tú» solo cuando pertenece a la cuenta activa; para otra persona se usa su nombre visible. En conversaciones individuales se resuelve desde el interlocutor y en grupos/comunidades desde sus perfiles. No se usa el correo como sustituto. La regla anterior podía atribuir a «Tú» una respuesta de otra persona a su propio mensaje.

Los documentos nuevos enviados desde conversaciones individuales muestran el nombre proporcionado por el archivo cuando no hay comentario. Se conserva un comentario explícito; nombres con rutas o caracteres de control se limpian. No se modifican los mensajes antiguos ya guardados con «Archivo».

## Pruebas

- Compilación debug y audit correcta; **1.067 pruebas unitarias en 238 suites, cero fallos, errores u omisiones**. Incluye diez pruebas nuevas para autores y nombres.
- Instalación sobre el Motorola y Novvin QA de la tablet, conservando sesiones y datos; el Novvin original de Play en la tablet permanece instalado.
- En la misma respuesta a un sticker, el Motorola muestra el nombre de la cuenta QA y la tablet muestra «Tú». Se inspeccionaron las pantallas nativas.
- Se eligió `Novvin-QA-reintento.txt` desde la lista interna de documentos previamente autorizados, sin abrir el selector del sistema. El nombre llegó visible a la tablet.
- En la prueba previa sin datos móviles, el archivo mostró «Reintentar». Al restablecer la red se entregó una sola copia y desapareció el reintento. Se comprobó integridad SHA-256 del archivo recibido de 2.203 bytes y del documento de 4.194.306 bytes enviado desde la tablet; ambos quedaron leídos.

Los APK y resultados se identifican en [la evidencia sanitizada](../evidencias-movil-citas-2026-10-02/resultados.json). Capturas y registros completos se conservan solo en el directorio local ignorado de QA, porque pueden incluir información de la cuenta real.

## Límites y siguientes comprobaciones

Los archivos autorizados ya se eligen dentro de Novvin. Autorizar nuevos archivos o carpetas todavía utiliza el permiso de Android; se revisará la presentación de ese paso y la reutilización del catálogo. [Android documenta el acceso mediante permisos del usuario](https://developer.android.com/training/data-storage/shared/documents-files).

La cita de un sticker todavía carece de una descripción del tipo de contenido. Se mantiene como observación visual para el siguiente bloque.

Esta revisión no certifica todo el módulo de mensajes. Continúan las comprobaciones de sus restantes opciones, grupos y comunidades, y las mediciones físicas de llamadas, salas y LIVE. Los tiempos de conexión y los 60 FPS no están certificados por estas pruebas.

## Reversión

Revertir el commit de esta revisión y recompilar Android restaura las etiquetas anteriores. No requiere migración de base de datos ni modificación del servidor.
