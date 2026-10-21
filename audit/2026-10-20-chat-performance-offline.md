# Chats, archivos sin conexión y respuesta del compositor — 20 de octubre de 2026

## Hallazgos y correcciones

| Hallazgo confirmado | Cambio |
|---|---|
| La observación del chat se reconstruía con cambios del registro de conversación (lectura, entrega, vista previa) y de la bandeja, aunque el acceso y los historiales asociados fueran los mismos. | Suscripción estable según conversación, participantes, historiales asociados y fecha de vaciado. Se conservan la cancelación al cambiar de cuenta y el cierre de acceso al retirar participantes. |
| Conversión, deduplicación, reconciliación y ordenación de mensajes podían ejecutarse en el contexto del colector de UI. | El productor de historial se ejecuta en IO. El ViewModel utiliza el orden ya calculado. Se evita consultar la sesión una vez por cada mensaje al aplicar redacciones. |
| Una miniatura y el original retenido tenían claves diferentes; la miniatura no encontraba el original sin conexión. | La búsqueda local comprueba la variante y después el original, siempre en el directorio privado de la cuenta activa. |
| Cargar una miniatura solo iniciaba la retención del original; una conexión interrumpida antes de terminar el original podía dejar sin copia local la imagen que ya se había mostrado. | La miniatura estática decodificada se guarda en el mismo almacén privado sin otra descarga. Los GIF/WebP originales conservan sus bytes y animación. |
| Sustituir la URL remota por su archivo local cambiaba el modelo visible de la imagen y podía reiniciar la carga. | Las imágenes de almacenamiento conservan un modelo estable; el interceptor resuelve internamente la copia local. |
| El visor completo esperaba a resolver el original antes de componer cualquier imagen. | La vista previa se muestra durante la resolución. Su petición y el estado de zoom permanecen estables al llegar el archivo original. El contenido de una sola visualización conserva su ruta separada. |
| La precarga podía iniciar hasta 24 peticiones de imágenes sin respetar la preferencia de descarga automática. | Se comprueba esa preferencia y se limita el trabajo especulativo a dos cargas simultáneas. La decodificación de vistas previas tiene un tamaño máximo de 1080 px. |
| El compositor mantenía texto visible mientras preparaba su guardado local. | Al pulsar enviar se despeja el campo; el borrador persistido solo se retira al aceptar el mensaje la cola local. Un fallo anterior restaura el texto si no existe un borrador posterior. Aplica al compositor compartido de chats, grupos y comunidades. |
| La web prefería la vista PNG del portapapeles incluso cuando el origen proporcionaba un sticker WebP/GIF. | Ante metadatos de sticker se prefieren los bytes originales entregados por Clipboard API; se conserva el archivo del evento si se deniega el acceso. No se descargan URLs arbitrarias pegadas. |
| El texto enriquecido web solo enlazaba HTTP explícito y Android excluía los correos. | Correos con destino mailto y direcciones web con/sin esquema. Se conserva puntuación y se impiden credenciales y esquemas ejecutables en los enlaces web. |
| El modo de movimiento reducido ocultaba las reacciones web en 0,45 segundos. El directo conservaba un temporizador de 3 segundos frente a la animación compartida más larga. | Efecto discreto de 3 segundos en movimiento reducido; duración normal de 5,8 segundos y limpieza posterior al vuelo en salas y directos. |
| Las reacciones de salas Android leían el progreso durante composición y desplazaban el layout cada fotograma. | Progreso leído en dibujo y graphicsLayer, con ascenso y deriva suave, arte de reacción y claves estables por evento. |

## Comentarios y stickers

Los comentarios usan el mismo selector de catálogo que el chat. Las pruebas dirigidas comprueban que abrir el botón funciona, insertar un emoji cambia el borrador y seleccionar un sticker envía su URL real. Se conserva la retirada del envío de audio en comentarios de publicaciones y videos. Estas comprobaciones de componentes no sustituyen un recorrido físico de la nueva APK.

## Límites de esta validación

No se atribuye una reducción porcentual de latencia ni se declara toda la aplicación tan fluida como WhatsApp. En este recorrido ADB no detectó un teléfono, tampoco después de que se confirmara su conexión; Windows no enumeró un dispositivo Motorola/ADB. No se borraron datos ni cachés para preparar una comparación. Falta medir en el dispositivo la entrada a chats, desplazamiento y apertura de medios con red normal, red lenta y modo avión.

Los archivos que nunca terminaron de descargarse requieren conexión para obtener el original. La miniatura permite mantener visible lo ya cargado; no inventa un original ausente. Los stickers mantienen animación si el portapapeles entrega GIF/WebP original; un PNG estático ya convertido por el navegador o el sistema no contiene los fotogramas originales.

Los historiales largos todavía se observan completos desde Room. Una migración a paginación requiere medir tamaño real, citas, búsqueda, selección y navegación al mensaje antes de cambiar ese contrato. La retención de archivos vistos sigue siendo privada por cuenta y permanece disponible hasta borrar las copias desde la configuración; la descarga especulativa mantiene su presupuesto existente.

## Fuentes primarias

- [WhatsApp: historial en la base local del dispositivo vinculado](https://engineering.fb.com/2021/07/14/security/whatsapp-multi-device/).
- [Android: aplicaciones con lectura local y sincronización sin conexión](https://developer.android.com/topic/architecture/data-layer/offline-first?hl=en).
- [Android: fases de Compose y lecturas de estado](https://developer.android.com/develop/ui/compose/phases).
- [Coil: cargadores, memoria y caché](https://coil-kt.github.io/coil/image_loaders/).
- [MDN: datos disponibles al leer el portapapeles](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/read).

## Evidencia de entrega

Resultados finales y artefactos de la versión 9.1.17: `artifacts/release-9.1.17/`.
