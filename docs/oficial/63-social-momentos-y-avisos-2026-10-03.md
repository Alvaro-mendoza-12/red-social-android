# Social, Momentos y avisos: cambios solicitados

**Rama:** `feature/live-gifts`. **Plataforma:** Android.

**Código Android conjunto:** `07e4989`. **API desplegada:** `f42a349`. La compilación y las pruebas de interfaz de esta combinación están en curso. Los checkpoints `6adc49b` y `c424929` pasaron sus suites; esos resultados no se trasladan automáticamente a este APK.

## Notificaciones por vistas de Momentos

Ver un Momento continúa registrando la vista y actualizando el estado de lectura. Esa acción deja de crear una notificación al autor. El servidor filtra además los registros históricos de tipo `view_moment` de las listas y los contadores antes de aplicar la paginación; los registros se conservan en la base.

Android rechaza esos eventos en la recepción de Firebase, la publicación local, el control de eventos y el repositorio. La retirada cubre los tipos dentro de los metadatos y los registros antiguos. No consume el identificador de otra notificación válida. Se mantienen las notificaciones de nuevos Momentos y reacciones.

La corrección del servidor está desplegada y beneficia también a los APK anteriores para nuevas vistas. La comprobación HTTPS verificó que la lista omite esos eventos y que su creación explícita se rechaza antes de generar filas, eventos o push.

## Social: continuar descubriendo y volver al inicio

La primera carga pública obtiene información de continuidad del servidor. Al acercarse al final, Android solicita la página siguiente. Una página sin publicaciones visibles después de aplicar los controles de la cuenta continúa hacia la siguiente cuando el servidor indica que queda contenido.

El servidor amplía la ventana de candidatos cuando la privacidad deja pocos resultados. Las filas locales sustituyen las copias importadas antes de aplicar la privacidad. Las publicaciones repetidas se unifican por identificador. La exploración respeta el límite existente de 10.000 candidatos; no se promete una lectura ilimitada.

Actualizar Descubrir restablece el desplazamiento al inicio y solicita un nuevo orden. Un error al cargar más conserva las publicaciones presentes y ofrece un reintento explícito. Las solicitudes antiguas no pueden sustituir el resultado de una actualización posterior. Un cursor repetido termina la continuidad para evitar un bucle de solicitudes.

Tres pruebas de interfaz reproducen la página filtrada sin crecimiento visible, el regreso al inicio con contenido presente y la pausa de solicitudes automáticas tras un error.

## Avisos de novedades, mantenimiento y actualización

Al abrir o retomar la aplicación se muestran los avisos activos que la cuenta no ha cerrado. El cuadro contiene tipo, título, contenido, horario cuando corresponde y una X con área táctil de 48 dp. Los mensajes largos se desplazan dentro del cuerpo, manteniendo accesibles el cierre y las acciones.

Los avisos se ordenan por mantenimiento activo, mantenimiento próximo, actualización y novedades. Si hay varios, se indica cuántos quedan. «Entendido» y la X cierran el aviso para esa cuenta. Una actualización ofrece «Actualizar» y «Más tarde»; si Google Play no puede abrirse, se muestra el error y se mantiene el aviso.

El administrador puede usar «Retirar aviso» antes de su fecha de vencimiento. El usuario puede cerrar su copia sin retirar el aviso para los demás. Mientras se muestra un aviso con la aplicación en primer plano, se revisa su vigencia cada 15 segundos para detectar una retirada. Ese sondeo se detiene cuando no hay avisos visibles o la aplicación pasa a segundo plano.

Cuatro pruebas de interfaz comprueban la X, el contenido largo y las acciones administrativas; cuatro pruebas unitarias cubren el orden.

## Selector nativo de música para Momentos

Se implementa con Compose, siguiendo el tema del aplicativo y las once referencias enviadas por el usuario. «Añadir música» abre la comunidad con buscador. «Mi música» lleva a la selección de audio del dispositivo. Las canciones muestran portada, título y artista; publicar una canción y abrir la biblioteca son acciones del menú.

Tocar una canción selecciona e inicia su reproducción. La confirmación ofrece tres presentaciones: tarjeta con portada, etiqueta compacta y solo audio sin elemento sobre la imagen. La misma presentación se usa en selector, editor, Momento publicado y destacados; se guarda en el servidor. No se ofrece letra sincronizada ni una onda de audio simulada.

El inicio se ajusta con un deslizador. La duración ofrece como máximo tres opciones (15/30/60 segundos, recortadas a la duración válida del contenido y la pista); las fotos conservan los seis segundos que usa el servidor. «Escuchar/Pausar» y «Usar audio» permanecen en la barra inferior. Las opciones de mezcla existentes se conservan al confirmar; el selector no añade esos controles a la pantalla principal. La información de autoría y licencia se abre desde el menú. Volver detiene la reproducción y regresa al catálogo; cerrar libera el reproductor.

La reproducción comienza con una sola interacción; el tiempo hasta escuchar sonido depende de la red y del archivo. El indicador de carga refleja el estado del reproductor. Un fallo muestra un mensaje; no se declara reproducción instantánea garantizada. La comunidad utiliza el audio original aprobado para poder seleccionar un fragmento fuera de la vista previa.

Siete pruebas del selector cubren búsqueda y limpieza, reproducción de un WAV local propio con una sola interacción, texto ampliado, reintento, tres presentaciones, duración de fotos y límites de duración de videos. Tres pruebas adicionales cubren la presentación compartida. Las pruebas separadas de audio remoto mantienen su límite de diez segundos.

## Comprobaciones disponibles

- API completa tras persistir los estilos de música: 440 correctas, dos omitidas en Windows y cero fallos; los fixtures de Linux se comprobaron por separado. Los casos focalizados se superponen con ese total.
- Encuestas habilitadas tras entregar `f42a349`: 39 comprobaciones HTTP correctas y limpieza completa de los recursos ficticios. [Evidencia](encuestas/VALIDACION_PRODUCCION_FUNCIONAL_F42.json).
- HTTPS público: 12 comprobaciones correctas, continuidad de Social sin duplicados y rechazo de notificaciones por vistas. [Evidencia](encuestas/VALIDACION_HTTPS_SOCIAL_Y_ENCUESTAS.json).
- Compilación Android conjunta, pruebas de interfaz y capturas: pendientes de registrar al finalizar la ejecución actual.

La revisión se realiza en el emulador elegido por el usuario. No constituye una certificación externa de toda la aplicación ni acredita todos los modelos de teléfono.
