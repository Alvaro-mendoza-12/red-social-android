# Revisión 9.1.14 — adjuntos, lectura, voz y comunidades

## Cambios y relación con el resto de la aplicación

| Solicitud | Cambio | Alcance y regresiones revisadas |
|---|---|---|
| Chat de voz más amplio | Columna adaptable de 320–440 px en escritorio y área de mensajes de 300–640 px según altura. Cada mensaje conserva su alto al desbordar. | Misma sesión de voz, micrófono, audio y caducidad de 90 minutos. Una columna en pantallas pequeñas; modo cine mantiene el ancho adaptable. |
| Contador en la pestaña del navegador | El título utiliza el estado compartido de lectura y suma mensajes pendientes de chats, grupos y comunidades. | La navegación sigue contando conversaciones. Archivados conserva su indicador propio. Leer o cerrar sesión retira el contador del título. |
| Evitar contador tras enviar un sticker propio | Conservar los IDs entrantes confirmados como leídos cuando una actividad saliente cambia la fecha de la conversación. Revisar lectura al aparecer un mensaje nuevo en el chat visible. | Un ID entrante posterior conserva su contador; una lectura fallida no se confirma. No se basa el contador en la mera existencia de un envío o adjunto. |
| Adjuntos como una vista previa | Un componente compartido para privado, grupos y comunidades: imagen principal, miniaturas seleccionables, añadir archivos, quitar con X al pasar el puntero o enfocar y botón Enviar. | Los pegados reconocidos como stickers se preparan; se eliminó su envío automático. Cancelar también invalida la preparación pendiente. Los borradores permanecen aislados por cuenta/conversación. |
| Editar antes de enviar como sticker | La vista previa permite alternar imagen/sticker y abrir el editor existente con recorte, giro, reflejo y texto. Al editar un sticker se exporta PNG fijo de 512 × 512 con margen transparente. | La interfaz indica que el resultado editado es fijo. La foto original preparada se reemplaza únicamente al guardar; el archivo no se sube al editar. Se conservan los permisos y controles de almacenamiento. |
| Mensajes largos | Leer más / Ver menos en el componente web compartido y en las burbujas Android. | Copiar, responder y reenviar mantienen el texto original; se conservan URLs y menciones. |
| Corrector web | Sugerencias en español para la palabra bajo el cursor, elegibles o descartables. Motor y diccionario dentro de un worker local, cargados cuando se necesitan. | La escritura no se envía a un servicio de corrección. Siguen disponibles la revisión nativa del navegador y la del teclado Android. Se omiten enlaces, menciones, códigos, nombres de archivos y borradores demasiado grandes. Un fallo del diccionario permite seguir escribiendo y enviando. |
| Nubes dinámicas | Movimiento visible y desfasado, dimensiones según el texto y espacio reservado alrededor de la pregunta. Hasta cuatro líneas; la lista permite leer la respuesta completa. | Movimiento reducido respetado inicialmente; el usuario puede pulsar Animar nubes de forma explícita. Pausa al abrir respuestas. Android amplía el recorrido y el espacio disponible sin cambiar la política de publicación. |
| Permisos de comunidades | Revisión del contrato existente: comunidad nueva en modo administradores, roles y cupos semanales. | Se conservaron las políticas existentes de servidor. Responder preguntas y reaccionar no concede permiso para publicar mensajes ordinarios. No se modificaron cuotas ya configuradas. |

## Evidencia web

- 124 suites / **683 pruebas** aprobadas. TypeScript, compilación y ESLint de los módulos modificados aprobados.
- Dependencias de ejecución: auditoría npm sin avisos conocidos. No representa una garantía sobre vulnerabilidades desconocidas ni sobre todos los componentes del producto.
- Política de comunidades: 43 casos, 42 aprobados y uno omitido por requerir integración adicional. No se cuenta la omisión como verificación real de PostgreSQL.
- Pruebas locales de SEO aprobadas. Las rutas privadas mantienen su política de indexación.
- Vista local con componentes reales y datos ficticios en 320×640, 390×844, 768×1024, 1182×768, 1327×612 y 1920×1080: sin desbordamiento horizontal; vista previa y pie de envío dentro de pantalla.
- Navegador: añadir con selector y pegar otra imagen incrementa las miniaturas sin enviar. Quitar y cancelar deja cero adjuntos y cero envíos en la vista de prueba. Editor produce `-sticker.png` de 512 × 512. Corrector propone y aplica «nesecito → necesito»; las únicas peticiones de diccionario contienen las rutas públicas de sus archivos.
- Se midieron cambios reales de transformación de las tres nubes y separación respecto a la pregunta. La activación explícita bajo movimiento reducido recupera una animación de seis segundos.
- No se publicaron mensajes, regalos ni reacciones ficticias en cuentas reales. Estas vistas locales no equivalen a una conversación autenticada entre dos dispositivos.

Registros: `artifacts/release-9.1.14/`. Capturas locales: `NoWing-web/output/playwright/attachments-914-desktop.png`, `attachments-914-mobile.png`, `clouds-914-mobile.png` y `voice-chat-914-desktop.png`. La primera prueba del editor en la vista local omitía la hoja global `webParity.css`; se corrigió la vista de pruebas y se repitió el flujo completo. La primera comprobación del corrector coincidió con la reoptimización de dependencias de Vite; la segunda, después de estabilizar, pasó. No se atribuyeron esos fallos del entorno local a producción.

## Fuentes técnicas

- [MDN: título del documento](https://developer.mozilla.org/en-US/docs/Web/API/Document/title).
- [MDN: revisión ortográfica HTML](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/spellcheck).
- [nspell: API del motor](https://github.com/wooorm/nspell).
- [Diccionario español y licencia original](https://github.com/wooorm/dictionaries/tree/main/dictionaries/es).

Los archivos del diccionario se distribuyen sin modificar junto a sus avisos, fuente y copia de MPL 1.1; el motor conserva su aviso MIT. No se afirma certificación ISO/IEEE ni paridad completa con WhatsApp.

## Cierre pendiente de esta entrega

La publicación web, el cierre de APK/AAB firmado y la instalación conservando datos se documentarán al finalizar sus comprobaciones. Los riesgos globales de restauración, notificaciones entre dispositivos y validación real de redes continúan en la auditoría general; esta revisión no los da por resueltos.
