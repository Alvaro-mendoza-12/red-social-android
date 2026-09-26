# Documentos internos en mensajes · 2026-09-20

Implementación en `D:\red social - live-gifts`, compartida por vista previa de envío y documento recibido.

## Comportamiento

- `DocumentSharePreviewDialog` abre directamente `InternalDocumentDialog`. No lanza `ACTION_VIEW` ni exige un editor externo para revisar el archivo.
- El parámetro `onConfirmDocument: (Uri) -> Unit` entrega el original o una copia editada/convertida al chat. Los tres flujos, directo, grupo y comunidad, ya lo utilizan; `onConfirm` se conserva únicamente como compatibilidad del API anterior.
- `MessageBubble` usa el mismo visor para `document`/`file`, con el resolvedor de URL autorizado que ya recibe del chat. Un mensaje borrado no abre el adjunto.
- PDF: lectura por páginas mediante `PdfRenderer`, solamente un bitmap actual y tamaño máximo 1200 × 1600 píxeles.
- TXT/CSV/TSV/JSON/MD/XML/LOG/YAML: lectura/edición de texto, guardado de copia y conversión a PDF real mediante `PdfDocument`. La edición no interpreta HTML, scripts o XML. Las copias de texto se guardan en UTF-8.
- DOCX: párrafos y texto de tablas. XLSX: nombres de hojas, celdas, huecos entre columnas, shared strings y resultado almacenado de fórmulas. PPTX: texto separado por diapositivas. La interfaz declara lectura sin diseño, gráficos ni macros; no promete edición Office completa ni recálculo.
- DOC/XLS/PPT binarios u otros formatos: ficha interna explicando el alcance. Se permite enviar el original. No se muestran como si fueran texto editable.
- Word/Excel/PowerPoint pueden exportar el contenido leído como TXT o PDF; el PDF generado contiene texto, no una réplica visual de Office.

## Recursos y límites

El botón Documento abre un selector propio con categorías PDF, Word, Excel, PowerPoint, Texto y Otros, búsqueda por nombre/carpeta sin distinguir acentos, navegación de carpetas y eliminación de accesos. Android solicita inicialmente autorización para cada carpeta mediante su diálogo de permisos; las selecciones posteriores de archivos ocurren en la aplicación. No se solicitan permisos de acceso a todos los archivos ni se promete encontrar documentos fuera de las carpetas autorizadas. El índice recorre hasta 2500 archivos, 180 carpetas y ocho niveles, con plazo de lectura y cancelación del proveedor. Las búsquedas filtran fuera del hilo de interfaz.

Lectura y conversión corren en IO. Cada diálogo tiene un snapshot privado que se elimina al cerrar. La apertura remota tiene plazos de conexión/lectura/llamada; cancelación cierra la lectura activa. No se registra ni modifica el origen. Para guardar se usa `CreateDocument` con nombre de copia y se rechaza la misma URI de origen. Las copias temporales para enviar viven en `cache/document-copies` y se purgan tras siete días cuando se genera otra copia, para no borrar un archivo mientras el chat todavía lo sube.

Límites: archivo 32 MB; texto editable 512 KB/200.000 caracteres; ZIP 512 entradas/16 MB expandidos; parte XML 4 MB; profundidad XML 64 y 100.000 elementos; hasta 100 hojas/diapositivas, 30.000 celdas por hoja, 256 columnas; PDF exportado hasta 300 páginas. No se extraen rutas ZIP al filesystem, no se siguen relaciones Office externas y se rechazan DTD/entidades antes de parsear. Macros y fórmulas no se ejecutan.

## Verificación

Se compilaron de forma independiente `DocumentContentParser.kt` y `DocumentContentParserTest.kt` con el compilador Kotlin 2.2.10 ya instalado en caché, sin ejecutar Gradle. JUnit 4.13.2: **8 pruebas aprobadas**. Cubren Word/tablas, Excel/shared strings/nombres de hojas/huecos/fórmulas almacenadas, orden numérico de diapositivas, DTD/entidades UTF-8 y UTF-16, ZIP inflado/rutas/entradas, profundidad XML/expansión de shared strings, cancelación, texto UTF-8/UTF-16 y rechazo de binarios/tamaño.

La compilación Android, pruebas unitarias y dos pruebas instrumentales del módulo aprobaron. Aún deben verificarse con dispositivos y cuentas reales: PDF multipágina, permisos de proveedores, guardar/enviar una copia desde los tres tipos de chat contra el servidor, documento recibido mediante URL firmada, archivo Office real y cancelación durante descarga. La lectura de Office no conserva maquetación, estilos, imágenes o gráficos. Los cambios de texto no son una modificación del documento original ni un editor Office universal.

`DocumentProcessingTest` aprobó en Android 15: crea un texto propio, genera copia editada y PDF real, vuelve a abrirlo con `PdfRenderer`, renderiza su página y comprueba que el archivo original permanece intacto.

`DocumentCatalogTest` aprobó cuatro casos de categorías MIME/extensión, búsqueda con acentos y configuración regional turca, combinación de carpeta/categoría sin mezclar carpetas hermanas y tamaños desconocidos. `DocumentPreviewUiTest` aprobó en Android 15: abre la interfaz compartida, edita el texto y comprueba que el callback de envío recibe la copia correcta y conserva el original. El resultado consolidado se registra en `audit-android-2026-09-20.md`.
