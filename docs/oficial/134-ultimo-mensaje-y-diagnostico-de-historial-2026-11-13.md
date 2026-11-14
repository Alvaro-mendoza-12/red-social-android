# 134 · Último mensaje y diagnóstico de historial

13 de noviembre de 2026, America/Bogota. Continúa la revisión abierta tras el bloque móvil 133 y atiende la corrección concreta de mensajería solicitada por el propietario. Las funciones y rediseños del plan 105 siguen separados. No se entrega una APK final ni se declara preparación integral.

## Correcciones de la candidata

Un sticker podía llevar su nombre interno en `content`. Tanto la vista previa de la API como la reparación del resumen preferían ese texto al tipo del mensaje, por lo que la bandeja mostraba una palabra que no era un mensaje de texto. Las dos rutas ahora presentan «Sticker». Se conservaron dos regresiones anteriores al parche y el control de texto normal y pie de foto. La función de la API activa reprodujo también el fallo con una entrada sintética, sin consultar el contenido de una conversación real.

La bandeja web conserva el tipo y el autor del último mensaje y muestra «Tú:» cuando corresponde. La API identifica la autoría con sus alias autorizados; la web también admite respuestas anteriores que solo incluyen el identificador del remitente. El formato se aplica al renderizar, sin acumular prefijos en caché y sin sustituir la etiqueta de un borrador. Un sticker procedente de una respuesta antigua se reconoce por su tipo aunque el texto de su resumen sea su nombre interno.

La ordenación de bandeja perdía los microsegundos y no desempataba por identificador, mientras el historial paginado sí lo hacía. Dos controles reprodujeron la discrepancia. Ahora se usa el comparador del historial. La lectura local de PostgreSQL también perdía precisión al convertir timestamps a `Date`; una regresión real falló antes de la corrección. La consulta devuelve el timestamp JSON exacto y mantiene el orden nativo de fecha e identificador. No cambia el esquema ni se actualizan mensajes almacenados.

## Validación

La batería general de API aprobó **1111 pruebas**, omitió 25 por dependencias de entorno y no tuvo fallos. Las veinte pruebas seleccionadas de vistas previas, borrado y paginación se solapan con esa batería; el control explícito adicional de PostgreSQL verifica la conservación de microsegundos y la elección del último mensaje. La selección conjunta aprobó 21 resultados sin omisiones.

La batería web aprobó **1237 pruebas en 193 suites**, incluidas seis nuevas de formato de vista previa y autoría. TypeScript aprobó. ESLint de los cinco archivos de producción modificados no tuvo errores ni avisos. La compilación Vite y las comprobaciones HTML, metadatos, CSP, rutas canónicas, robots, sitemap, recursos y 404 aprobaron para 21 páginas públicas; su JavaScript público prerenderizado sigue en cero. Se conserva el aviso existente de chunks mayores de 1000 kB; no se aumenta su umbral para ocultarlo.

Playwright con Chromium aislado comprobó la candidata compilada a 1440, 768 y 375 px. Pasaron la vista previa de sticker propio y entrante, el autor de texto con respuesta anterior, la apertura de conversación y la salida del menú por Escape. El historial se desplaza, tiene las barras ocultas y no desborda horizontalmente. Las imágenes de los fixtures cargaron sin roturas; esto no valida todos los objetos reales almacenados. La cabecera midió 58 px y el compositor 52 px: no se redujeron en este bloque.

axe-core 4.14.0 detectó inicialmente nombres accesibles que omitían parte del texto visible de la marca, del contacto y de las filas de conversación. Se corrigieron las etiquetas y se conservaron el acceso al perfil y la separación entre abrir un chat y abrir sus opciones. La última pasada no detectó infracciones automáticas WCAG A/AA en esos tres escenarios. No equivale a una auditoría manual con lector de pantalla. No hubo errores de consola o página ni solicitudes externas sin interceptar. Se usaron identidades y medios sintéticos, sin sesiones reales ni mensajes a personas. La comparación visual queda **inconclusa** por no disponer de una captura base versionada; no se inventa una aprobación visual.

## Chats vacíos: hallazgos y límites

Se reprodujo en una base PostgreSQL aislada que el límite global de 10000 mensajes importados puede dejar fuera el último mensaje de un chat antiguo cuando otra conversación llena la consulta. El mensaje sigue legible al consultar su chat por separado. Es una regresión de crecimiento de la consulta; el volumen real comprobado del servidor es menor y no basta para atribuirle el caso del propietario. Este bloque no corrige todavía esa proyección de bandeja. Descargar toda la historia sin límite tampoco resuelve el rendimiento solicitado.

El contraste de solo lectura del servidor distingue resúmenes que conservan un identificador de mensaje sin una fila correspondiente, cursores de limpieza que cubren un historial existente y conversaciones duplicadas por pareja. Los identificadores de los resúmenes sin historial comprobados no se encontraron en otro chat ni tenían una conversación hermana con mensajes. Son metadatos agregados del almacén local con mensajes locales/importados, no la respuesta de bandeja de una persona concreta. No prueban quién solicitó una limpieza ni que un caso pertenezca al chat de la captura. La evidencia privada se conserva fuera de Git; no se versionan mensajes, identidades, credenciales ni copias de base de datos.

Queda por contrastar la trazabilidad de borrados y sus respaldos, la deduplicación, la visibilidad por cuenta y la recuperación del historial antes de modificar datos. No se ha restaurado, borrado ni movido una conversación. La actividad optimista de la web también conserva una revisión pendiente de su precisión temporal. No se declara resuelto todo el requisito de coincidencia entre bandeja e historial por corregir estos formatos.

## Trazabilidad y continuidad

Los trece requisitos adicionales están en [la fuente de seguimiento](../product/WEB_MESSAGES_RELIABILITY_REQUIREMENTS_2026-11-13.md). La matriz conserva 387 entradas y mantiene explícitos los pendientes de avatares, seguidores y seguidos en tiempo real, grupos/comunidades, asistente, menú, archivos, tonos y densidad de conversación. El comprobador incorpora las tres fuentes adicionales de producto y sus líneas de contexto, además del paquete original. Sus catorce pruebas de trazabilidad y cobertura aprobaron. Registrar una línea no la convierte en implementada ni valida el Word por su huella.

Los cambios de cabecera, compositor y stickers solicitados en esta continuación todavía no se han aplicado. Tampoco se han completado la revisión móvil integral ni el ciclo global de propiedad de LIVE indicado en 133. El APK de laboratorio 133 no incorpora automáticamente esta continuación. Las correcciones terminadas se publican con árboles idénticos en `main` y `feature/live-gifts`, preservando los cambios ajenos y la rama `correciones`.

La comprobación del backend activo es una operación distinta del despliegue. Antes de activar una corrección debe preservarse la imagen y configuración vigentes, el respaldo y los contratos de los consumidores. La API existente conserva funciones anteriores que no se incorporan por ello al código estable; no se sustituye su árbol completo por una copia de `main`.

Se preparó `novvin-api:review134-b299955fa874-modes` sobre el identificador inmutable de la imagen activa. La comparación de sus 180 archivos de código con el contenedor y la imagen base confirma que solo cambian `data-routes.js` y `message-deletion-policy.js`, con los mismos propietarios y permisos. Las veinte pruebas sintéticas de vistas previas, borrado y paginación aprobaron con el usuario `10001:10001`, raíz de solo lectura y red desactivada, sin variables ni volúmenes de producción. Los primeros ensayos del arnés fallaron por quitar al usuario raíz el permiso para leer archivos de otro propietario y por exigir metadatos idénticos con un modo incorrecto; se corrigió el arnés y se conservó el modo de cada archivo. No se activaron esas candidatas ni se cambiaron datos del servicio real. La activación de la imagen final todavía está pendiente.

La [evidencia del bloque](../quality/evidence/134-resultados.json) identifica las huellas y los límites de estos ensayos.
