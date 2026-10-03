# Especificación funcional y técnica: Encuestas de Novvin

**Estado:** definición oficial propuesta para diseño, implementación y validación.

**Versión:** 1.0.

**Fecha:** 2026-10-03.

**Producto:** Novvin.

**Cliente:** aplicación Android.

**Backend principal previsto:** API propia de Ubuntu y PostgreSQL.

**Audiencia del documento:** producto, diseño, desarrollo, QA, operación y moderación.

## 1. Propósito

Encuestas de Novvin permite que una persona cree formularios de investigación o consulta, los guarde como planillas reutilizables, publique una invitación en Social y reciba respuestas de cuentas autenticadas. El creador dispone de una planilla privada de respuestas, análisis visual y exportaciones organizadas en Word, PDF y Excel.

El flujo debe servir, por ejemplo, a estudiantes que preparan una tesis y desean difundir un formulario en su universidad mediante una publicación o un código QR. El sistema no debe confundir una encuesta con una votación rápida: puede contener varias preguntas de distintos tipos, instrucciones, imágenes, secciones y reglas de validación.

La experiencia toma como referencia patrones familiares de Google Forms —editor de preguntas, vista previa, publicación, cierre de respuestas y panel de resultados— sin copiar su marca ni asumir paridad técnica. Google documenta preguntas con imágenes, secciones, limitación de una respuesta por cuenta de Google, edición de respuestas y exportación de resultados. Novvin define sus propias reglas de identidad, audiencia, consentimiento y privacidad. [Editar formularios](https://support.google.com/docs/answer/2839737?hl=en) · [Publicar y compartir formularios](https://support.google.com/docs/answer/2839588?hl=en) · [Ver y administrar respuestas](https://support.google.com/docs/answer/139706?hl=en).

## 2. Principios del producto

1. **La encuesta y la publicación son objetos relacionados, pero diferentes.** La publicación atrae participantes; el formulario recoge datos; el libro de respuestas y los análisis son privados para el creador.
2. **Una cuenta, una respuesta por publicación de encuesta.** La cuenta puede actualizar esa misma respuesta durante la ventana permitida. Novvin no afirma verificar que una persona física no tenga varias cuentas.
3. **La identidad no es anónima.** Antes de consentir y responder, el participante ve que el creador podrá asociar su cuenta y sus respuestas. El creador puede consultar cada envío. Otros participantes y visitantes no pueden ver respuestas individuales ni gráficos de respuestas.
4. **La audiencia se valida en el servidor.** Compartir un enlace o QR no amplía los permisos de una publicación privada.
5. **Una publicación activa usa una definición versionada e inmutable.** Editar la planilla base no cambia preguntas ni significado de respuestas ya recogidas.
6. **El análisis describe datos; no inventa conclusiones.** Una relación estadística no prueba causalidad. El sistema comunica supuestos, datos válidos, faltantes y límites.
7. **El propietario conserva su trabajo.** Borrar una publicación no borra automáticamente la planilla ni su libro privado de respuestas. El creador puede archivar o solicitar la eliminación definitiva de cada elemento con confirmación.
8. **La operación debe ser comprensible en Android.** Los formatos exportados reflejan la definición exacta de la encuesta y filtros visibles al momento de exportar.

## 3. Situación en la rama `feature/live-gifts`

La aplicación ya tenía encuestas simples en mensajes de grupos y comunidades mediante `poll_id`. La implementación de Encuestas de Social en esta rama agrega un modelo propio de planillas, campañas y respuestas, independiente de esas votaciones rápidas.

Referencias locales revisadas:

- Android: `app/src/main/java/conectate/com/data/repository/CommunityRepository.kt` incluye el flujo de encuesta para mensajes de comunidad.
- Android: `app/src/main/java/conectate/com/presentation/surveys/SurveysScreen.kt` integra el editor local del APK, y `Post.kt` enlaza las publicaciones con campañas.
- Backend: `infrastructure/local-fallback/api/src/surveys/` define formularios, revisiones, campañas, respuestas, análisis y exportaciones en PostgreSQL.

Esta especificación conserva el producto final esperado. La presencia del código en la rama no certifica por sí sola su despliegue ni el cumplimiento de todos los criterios de aceptación.

## 4. Vocabulario y objetos

| Término | Significado |
|---|---|
| Planilla | Formulario privado editable y reutilizable, equivalente a una plantilla de trabajo. Puede estar en borrador, publicada como versión, archivada o en papelera. |
| Revisión de planilla | Instantánea inmutable del contenido de una planilla en un momento de guardado/publicación. |
| Encuesta publicada | Instancia difundida que usa una revisión fija, tiene propietario, publicación, audiencia, estado, fechas y conjunto independiente de respuestas. |
| Publicación de encuesta | Tarjeta visible en Social que enlaza una encuesta publicada; no contiene las respuestas. |
| Respuesta | Envío de una cuenta para una encuesta publicada. Conserva autor, versión de preguntas, fecha de creación/última edición y estado. |
| Libro de respuestas | Vista privada del propietario con resúmenes agregados e individuales, filtros y exportaciones. |
| Participante | Cuenta autenticada que tiene permiso para ver la encuesta y puede consentir y responder. |
| QR de respuesta | Código que codifica un enlace opaco a una encuesta publicada; no contiene datos personales ni respuestas. |

## 5. Mapa de navegación

### 5.1 Android

En la pestaña **Social**, el botón principal de crear publicación abre un menú con acciones identificables: **Publicación**, **Encuesta** y las demás acciones que ya existan. Si el diseño conserva el menú de tres puntos, allí debe aparecer **Crear encuesta** como acción nombrada; el menú no debe esconderla detrás de iconos sin etiqueta. La ruta no debe confundirse con acciones de tres puntos de una publicación individual.

Desde Social se accede a **Mis encuestas** por una opción visible en el menú de creación o en el espacio personal de Social. La biblioteca presenta pestañas o filtros **Planillas**, **Publicadas**, **Archivadas** y **Papelera**. Un elemento de la lista abre su editor o su panel de resultados según su tipo.

### 5.2 Pantallas dentro de Android

Android expone biblioteca, editor, vista previa y panel de resultados dentro de la aplicación. La vista de respuesta se abre desde Social, un enlace profundo o QR. El QR requiere la aplicación Android instalada y una cuenta autenticada. No se necesita comprar un dominio para este flujo móvil.

### 5.3 Acceso por QR

El QR abre la página de responder, no el editor ni el panel de resultados. Si la persona no tiene sesión, se le pide iniciar sesión o crear una cuenta; después del acceso vuelve a la misma encuesta. Si no tiene permiso, ve una explicación y una acción para volver a Social. El QR no permite eludir una audiencia privada.

## 6. Estados del producto

### 6.1 Planilla

`borrador → publicada como revisión → borrador actualizado` es el ciclo normal. Al guardar, se genera una revisión; la persona puede seguir editando una copia de trabajo. También puede duplicar una planilla para otro estudio. `archivada` oculta la planilla de la vista principal sin borrar respuestas. `papelera` es recuperable durante el plazo que configure la política de retención; la purga definitiva requiere confirmación.

La planilla por sí sola no recibe respuestas. Publicarla crea una encuesta publicada con un identificador de campaña y una revisión congelada.

### 6.2 Encuesta publicada

Estados: `programada`, `abierta`, `cerrada`, `archivada`, `eliminación solicitada`, `eliminada`. Una fecha futura programa la apertura. Al llegar a la fecha de inicio, se abre automáticamente si la publicación sigue disponible. Una fecha de cierre cierra nuevas respuestas. El propietario puede cerrar manualmente o reabrir; el sistema registra autor, fecha y cambio de estado.

Cerrar impide nuevos envíos y ediciones posteriores a la respuesta. Reabrir admite nuevas cuentas elegibles, conserva todas las respuestas existentes y no concede otro envío a cuentas que ya respondieron.

### 6.3 Respuesta

Estados: `borrador local`, `enviando`, `enviada editable`, `bloqueada por tiempo`, `retirada/eliminada`. Una respuesta completada se crea una sola vez por cuenta/encuesta. La edición dentro de los 30 minutos actualiza las mismas respuestas y su marca de tiempo de edición; no aumenta el conteo.

Regla propuesta para el límite de edición: vence a los 30 minutos del primer envío o al cerrar la encuesta, lo que ocurra primero. La pantalla de confirmación indica la hora límite concreta. Un envío hecho tras una reconexión utiliza el mismo identificador de operación y no crea duplicados.

## 7. Pantallas y comportamiento de cada control

### 7.1 Menú de creación en Social

- **Encuesta / Crear encuesta:** crea un borrador nuevo y abre el editor.
- **Mis encuestas:** abre la biblioteca propia.
- **Cancelar / cerrar:** cierra el menú, no descarta ningún contenido.

El menú informa si se pierde un borrador sin guardar. No crea una encuesta publicada de forma accidental.

### 7.2 Biblioteca «Mis encuestas»

Cada fila/tarjeta muestra título, última modificación, estado y, si fue publicada, número de respuestas, fecha de cierre y audiencia resumida. El conteo de respuestas es visible al propietario.

Controles:

- **Buscar:** busca por título y descripción entre elementos propios.
- **Filtros:** planilla, abierta, programada, cerrada, archivada y papelera.
- **Ordenar:** modificación reciente, creación, título, estado y mayor cantidad de respuestas.
- **Nueva encuesta:** abre un borrador vacío.
- **Abrir/Editar planilla:** abre la revisión editable.
- **Ver resultados:** abre el libro de respuestas de una encuesta publicada.
- **Duplicar:** crea una planilla independiente sin participantes, publicaciones ni respuestas copiadas.
- **Publicar:** inicia la revisión y publicación de la planilla.
- **Cerrar/Reabrir:** cambia el estado de recolección después de confirmación y explica el efecto en respuestas ya enviadas.
- **Archivar/Restaurar:** cambia visibilidad en la biblioteca, sin borrar el original o sus resultados.
- **Eliminar:** mueve a papelera y ofrece elegir qué hacer con publicaciones asociadas; nunca borra respuestas silenciosamente.
- **Eliminar definitivamente:** borra irreversible tras reautenticación/confirmación y aviso de qué datos y archivos se eliminarán.
- **Más opciones:** permite renombrar, ver historial de revisiones, descargar una copia del formulario vacío y administrar la publicación.

Estados vacíos explican cómo crear la primera planilla. Errores de red permiten reintentar sin duplicar publicaciones.

### 7.3 Editor de planilla

El editor se compone de encabezado, lienzo ordenable de bloques/preguntas y barra de acciones.

#### Encabezado

- **Título:** obligatorio, visible en la biblioteca, página del encuestado e informes.
- **Descripción/instrucciones:** explica propósito, población, duración, criterios y uso de datos. Texto enriquecido limitado y seguro.
- **Imagen de portada:** opcional, con vista previa, recorte, reemplazo y eliminación.
- **Estado de guardado:** `Guardando…`, `Guardado`, `Sin conexión` o `No se pudo guardar`; el borrador se recupera al volver a abrir.
- **Vista previa:** abre el formulario como lo verá el participante, sin enviar.
- **Guardar:** guarda revisión de planilla; no publica ni cambia campañas existentes.
- **Publicar:** valida campos, muestra resumen y pasa a configuración de publicación.
- **Más:** renombrar, duplicar, historial, archivar, eliminar.

#### Insertar bloques

- **Añadir pregunta:** inserta pregunta en la posición seleccionada.
- **Añadir imagen:** agrega una imagen informativa en portada, sección o pregunta; ofrece texto alternativo, descripción, reemplazo y eliminación.
- **Añadir sección:** crea una página lógica con título, descripción y siguiente sección.
- **Importar preguntas:** permite seleccionar preguntas de otra planilla propia; se copian como contenido, sin datos personales.
- **Deshacer/Rehacer:** recupera operaciones de la sesión de edición.

Cada bloque muestra agarradera de orden, menú **Duplicar**, **Mover a sección**, **Obligatoria**, **Lógica condicional** cuando sea válida y **Eliminar**. El borrado pide confirmación cuando esa planilla ya tiene una campaña publicada; borrar la planilla de trabajo no cambia la instantánea publicada.

#### Tipos de pregunta

1. **Respuesta corta:** texto breve, máximo configurable, validación opcional de patrón/longitud.
2. **Párrafo:** texto largo, límite de longitud visible, saltos de línea preservados.
3. **Opción única:** selección de una opción. Cada opción puede tener imagen y texto alternativo.
4. **Casillas de selección:** selección múltiple; mínimo/máximo de opciones seleccionables opcional.
5. **Lista desplegable:** una opción de catálogo.
6. **Escala lineal:** extremos configurables, etiquetas para extremos y cantidad de pasos limitada a valores enteros aprobados por diseño.
7. **Número:** entero o decimal, mínimo/máximo opcionales, unidad y precisión declaradas.
8. **Fecha:** selector validado; almacena fecha en formato normalizado y presenta zona/convención local.
9. **Matriz de opciones:** conjunto corto de filas por columnas; regla explícita de una elección por fila, con límite de dimensiones.
10. **Carga de archivo:** archivo del participante si el creador la habilita; límites de tipo/tamaño, almacenamiento privado y aviso visible de qué cuenta/creador podrá acceder. No se ofrece de forma predeterminada.

Para todas: **Obligatoria**, título, ayuda, imagen opcional, duplicar y vista previa. Validaciones se muestran junto a la pregunta y no borran respuestas previas.

#### Lógica condicional

Una respuesta de opción única puede dirigir a una sección o finalizar el formulario. El editor impide destinos inexistentes, bucles y rutas que saltan preguntas obligatorias sin decisión definida. La vista previa recorre cada rama. El informe identifica preguntas no vistas por lógica como “no aplican”, separadas de respuesta vacía.

### 7.4 Configuración y publicación

El diálogo/página **Publicar encuesta** se organiza en bloques:

- **Destino en Social:** texto introductorio de la tarjeta, publicación asociada y previsualización.
- **Audiencia:** pública, seguidores o audiencia específica que el producto soporte. La opción debe corresponder a permisos reales de Social; no mostrar opciones aún no implementadas.
- **Quién puede responder:** cuentas autenticadas que pueden ver la publicación; no permitir respuesta de invitado mientras la regla sea una respuesta por cuenta.
- **Identidad y privacidad:** aviso destacado de que el creador verá nombre/identificador de cuenta y respuestas; indicar si el creador solicita datos personales en preguntas.
- **Propósito y consentimiento:** texto obligatorio del creador sobre propósito, uso, voluntariedad, contacto o responsable y retención. El participante confirma antes de contestar.
- **Fechas:** abrir ahora o programar inicio; cierre automático opcional; zona horaria explícita; el creador siempre puede cerrar/reabrir manualmente.
- **Edición de respuestas:** fija los 30 minutos, con cierre adelantado si la encuesta se cierra.
- **Resultados visibles:** política fija de Novvin: nadie salvo el creador ve respuestas individuales o resúmenes de resultados. En Social solo se expone el conteo de envíos, si el usuario puede ver la publicación.
- **Vista previa:** revisa la tarjeta y el formulario completo en móvil antes de publicar.
- **Publicar:** confirma, crea la campaña y la tarjeta en una operación idempotente. Al terminar ofrece abrir publicación, copiar enlace, crear QR o ir a resultados.
- **Cancelar:** vuelve a edición sin perder cambios.

No se permite publicar formularios sin título, propósito, preguntas válidas, audiencia y texto de privacidad/consentimiento. El creador revisa una declaración de responsabilidad del contenido antes de difundirla.

### 7.5 Tarjeta en Social

La tarjeta muestra nombre/imagen del creador, título, descripción breve, distintivo **Encuesta**, audiencia aplicable, fecha de cierre o estado `Sin fecha de cierre`, y contador de respuestas. No muestra nombres de participantes, texto respondido, gráficas ni datos agregados.

Botones:

- **Ver encuesta:** abre detalle e instrucciones.
- **Responder:** abre consentimiento y luego formulario, si la cuenta es elegible.
- **Continuar:** vuelve a borrador local de respuesta si el usuario empezó y aún no envió.
- **Ver estado:** informa que ya respondió, que cerró, que está programada o que no tiene acceso.
- **Compartir:** usa enlace/QR según permisos. Al compartir una publicación restringida no convierte la encuesta en pública.
- **Menú de publicación:** reportar encuesta/publicación, ocultar, bloquear al creador y, para el propietario, editar el texto de la tarjeta dentro de las restricciones, cerrar/reabrir o retirar la publicación.

Las métricas públicas no deben permitir inferir quién respondió. Para audiencias pequeñas, el contador podría revelar participación por deducción; la publicación no ofrece lista de participantes ni notifica públicamente sus respuestas.

### 7.6 Detalle de encuesta y consentimiento

Antes del formulario, la pantalla enseña creador, título, propósito, duración estimada, fecha de cierre, audiencia, identidad visible al creador y política de respuestas. El botón **Acepto y continuar** habilita el formulario; **No participar** sale sin guardar respuestas. El consentimiento registra versión del aviso y fecha; no se interpreta como asesoría o consentimiento institucional de investigación.

Si falta texto de propósito o aviso de identidad, se bloquea la participación y se explica por qué. Si el formulario incluye carga de archivo o preguntas sobre información sensible, se muestra advertencia contextual antes de aceptar.

### 7.7 Formulario de respuesta

Preguntas ordenadas por secciones, indicador de progreso, navegación anterior/siguiente, validación de requeridas, imágenes con texto alternativo y guardado local de borrador. El borrador local es privado del dispositivo/cuenta y se elimina al enviar o abandonar expresamente. El envío requiere conexión para confirmar que la encuesta sigue abierta y la cuenta tiene acceso.

Botones:

- **Anterior/Siguiente sección:** avanza conservando borrador.
- **Guardar y salir:** conserva borrador privado para continuar más tarde, salvo que el dispositivo compartido esté en modo invitado/seguro o la encuesta deshabilite borradores; siempre permite descartar.
- **Enviar respuesta:** ejecuta validación completa y envía con clave idempotente.
- **Descartar:** elimina el borrador local tras confirmar.

Una pantalla de revisión puede resumir respuestas antes del envío cuando el formulario lo requiera. Errores de red conservan el contenido y ofrecen reintento. No afirmar “enviada” hasta recibir confirmación del servidor.

### 7.8 Confirmación después del envío

Muestra **Respuesta enviada**, identificador/fecha de envío, mensaje personalizado del creador, aviso de que el creador puede identificar la cuenta y hora exacta hasta la que se permite editar. Ofrece **Editar respuesta** mientras siga abierta y dentro del plazo. No enseña gráficos ni respuestas ajenas.

Tras vencer el plazo, la vista muestra **Edición finalizada**. Si se cierra antes, muestra **Encuesta cerrada** y explica que ya no se puede modificar. El participante puede contactar al creador solo mediante las herramientas habilitadas; no se expone correo personal automáticamente.

### 7.9 Panel privado de resultados

Solo el creador autenticado puede abrirlo. No se comparte el libro de respuestas con seguidores, otros participantes ni colaboradores de una publicación. Secciones:

- **Resumen:** total de respuestas únicas, completadas, periodo, estado y tasa de finalización. La tasa requiere base de participantes alcanzados fiable; si no existe, no se inventa.
- **Preguntas:** una tarjeta por pregunta con tipo, respuestas válidas, no respuesta/no aplica y visualización apropiada.
- **Individuales:** envíos paginados por fecha, identificador/nombre de cuenta y respuestas. Búsqueda por participante/ID, filtros de fecha y acceso a la revisión de pregunta que contestó.
- **Relaciones:** análisis entre variables elegidas por el creador.
- **Exportar:** genera documentos del formulario, informe y conjunto de respuestas.
- **Historial:** registra cierres, reaperturas, eliminación de respuestas, cambios de publicación y exportaciones cuando la política de auditoría los requiere.

Controles de panel:

- **Actualizar:** recupera datos del servidor y respeta filtros.
- **Filtro de fecha/estado:** define la población del análisis; se muestra en título y exportación.
- **Vista individual / resumen:** cambia entre registros y agregados sin cambiar permisos.
- **Mostrar filtros:** selecciona opciones, secciones o condiciones válidas; reporta cuántas filas quedan.
- **Limpiar filtros:** restaura todas las respuestas elegibles.
- **Exportar:** abre un asistente de formato, contenido, anonimización/pseudonimización y filtros.
- **Cerrar/Reabrir encuesta:** confirma impacto y estado.
- **Eliminar respuesta:** solo el propietario; confirma la fila y recalcula análisis. Registra evento de auditoría sin conservar innecesariamente la respuesta borrada.
- **Retirar publicación:** saca tarjeta de Social, mantiene encuesta y resultados privados.

### 7.10 Gestión del QR

Panel del propietario: **Ver QR**, **Descargar PNG**, **Descargar SVG**, **Copiar enlace**, **Regenerar enlace/QR** y **Imprimir**. El QR debe tener margen, contraste suficiente y una etiqueta legible con título corto. La descarga no incluye resultados, cuenta del creador, tokens de sesión ni parámetros con información personal.

Si se regenera/revoca el enlace, el anterior deja de abrir la encuesta. Regenerar no borra respuestas. Para la audiencia pública, cualquier persona puede escanear, pero debe autenticarse para continuar. Para audiencia restringida, la comprobación del servidor se ejecuta después del inicio de sesión.

### 7.11 Exportación

El asistente separa tres productos para que no se confundan:

1. **Formulario vacío:** copia del instrumento sin respuestas, para revisión o impresión.
2. **Informe de resultados:** documento narrativo/visual con título, versión, periodo, metodología declarada, número de respuestas, filtros, tablas y gráficos.
3. **Datos:** respuestas tabulares para análisis externo.

Formatos:

- **Word (.docx):** informe editable con portada, propósito, definición/versión de formulario, periodo, método de inclusión, notas de limpieza, resultados por pregunta, tablas, gráficos incrustados, limitaciones, glosario y fecha de generación. También ofrece instrumento vacío en DOCX.
- **PDF (.pdf):** informe de lectura/impresión con paginación, encabezados, pie con número de página, texto seleccionable, leyenda de figuras y etiquetas accesibles. Puede exportar formulario vacío en PDF.
- **Excel (.xlsx):** libro con hojas `Portada`, `Diccionario`, `Respuestas`, `Frecuencias`, `Descriptivos`, `Cruces` y `Metadatos`, incluidas solo las necesarias. Una fila representa una respuesta y una columna una variable; para preguntas de selección múltiple se usa una hoja normalizada auxiliar o columnas documentadas, sin destruir la estructura.
- **CSV (.csv):** datos planos para R, SPSS, Python, Excel u otra herramienta, UTF-8, delimitador indicado, encabezados claros y archivo de diccionario opcional. CSV no conserva gráficos ni varias hojas.

Antes de crear, el usuario selecciona incluir respuestas individuales, nombres de cuenta, identificadores técnicos, gráficos, filtros, preguntas abiertas y hojas de trabajo. La identidad se incluye solo tras una selección explícita; se advierte que el archivo puede salir de Novvin y que el propietario responde por su custodia. Valores textuales que comienzan con caracteres de fórmula se escapan al exportar Excel/CSV para evitar ejecución como fórmula.

Se presenta progreso, cancelación, error recuperable y enlace de descarga privado de vida limitada. El servidor elimina temporales al completar la expiración configurada. La exportación siempre incluye fecha, zona horaria, filtros, versión, número de filas y tipos de dato para hacerla reproducible.

## 8. Análisis estadístico

### 8.1 Principio de selección

El sistema clasifica por tipo declarado y configuración, no por adivinación silenciosa:

| Tipo de variable | Ejemplos | Resumen válido | Gráficos iniciales |
|---|---|---|---|
| Categórica nominal | Carrera, ciudad, respuesta única | conteos, frecuencias absolutas/relativas, moda | barras, bastones para pocos valores, sectores opcionales |
| Categórica ordinal | Satisfacción 1–5, nivel educativo ordenado | frecuencias, mediana/percentiles si orden válido, moda | barras ordenadas, bastones |
| Numérica discreta | número de cursos | n, mínimo/máximo, media, mediana, moda, varianza/desviación, frecuencias | barras/bastones, caja y bigotes si tiene distribución suficiente |
| Numérica continua | edad, tiempo, puntaje | resumen descriptivo, cuantiles, rango intercuartílico | histograma, caja y bigotes; densidad solo cuando tenga sentido |
| Texto abierto | comentario | cantidad de respuestas, respuestas individuales privadas, exportación; agrupación de texto solo con función explícita | no inventar escala numérica; tabla de texto o temas exploratorios claramente etiquetados |
| Fecha/hora | fecha de evento | recuentos por periodo y rango temporal | línea temporal/barras temporales |

El creador puede marcar una variable como **independiente**, **dependiente**, **control** o **descriptiva** en el diccionario, con hipótesis y etiqueta opcionales. Esto documenta el diseño de investigación, no convierte una correlación en efecto causal. El sistema muestra “asociación observada” y nunca “X causó Y” por sí solo.

### 8.2 Estadísticas y definiciones

- `n válido`: número de respuestas con valor analizable para esa variable.
- `frecuencia absoluta (fᵢ)`: cantidad de observaciones de la categoría/valor.
- `frecuencia relativa (hᵢ)`: `fᵢ / n válido`; porcentaje `100 × hᵢ`.
- Frecuencia acumulada/relativa acumulada: solo para categorías ordinales o valores numéricos ordenados, nunca para nominales sin orden.
- Media, mediana y moda: visibles únicamente cuando la escala de medición lo permite. Una escala tipo Likert puede resumirse como ordinal; si el autor solicita media, se etiqueta la decisión de tratar los puntos como intervalares.
- Varianza y desviación estándar muestrales: se documenta el denominador y el tratamiento de faltantes.
- Cuartiles y rango intercuartílico `IQR = Q3 − Q1`: método de cuantil fijo, documentado en el informe para obtener resultados reproducibles.
- Valores atípicos en caja y bigotes: método seleccionado y mostrado, por defecto regla exploratoria de 1,5 × IQR. Un punto atípico no se elimina automáticamente.
- Datos ausentes, no aplica, respuesta omitida y pregunta no mostrada por salto condicional permanecen como estados diferentes.

### 8.3 Gráficos

- **Barras:** comparar frecuencias o porcentajes de categorías; orden nominal, ordinal o descendente configurable.
- **Pastel/dona:** proporciones de una sola variable nominal con pocas categorías y denominador visible. Se desactiva o advierte con demasiadas categorías, valores múltiples o faltantes que hagan engañosa la suma al 100%.
- **Bastones:** valores discretos/categorías en eje X con líneas desde cero hasta frecuencia/valor; se usa para conteos discretos o una distribución pequeña. No es la opción predeterminada para texto nominal extenso.
- **Histograma:** numérica continua agrupada en intervalos, con ancho/criterio de intervalos visible y eje de conteo o densidad identificado.
- **Caja y bigotes:** mediana, cuartiles, rango y atípicos para numéricas; comparaciones por grupo con escala común y tamaños de grupo visibles.
- **Línea temporal:** fecha, frecuencia por intervalo y zona horaria explícita.
- **Barras apiladas/agrupadas:** cruces categóricos, mostrando N y denominador.
- **Dispersión:** dos variables numéricas; eje, n válido y correlación seleccionada cuando aplique.
- **Tabla de frecuencias:** siempre disponible como alternativa accesible y exportable, sin depender de interpretar un color.

Cada gráfico tiene título, pregunta/variable, periodo/filtros, tamaño de muestra, etiquetas/unidades, leyenda, texto alternativo y tabla de datos. Permite cambiar tipo cuando sea compatible, descargar PNG y enviar al informe. No oculta categorías sin indicarlo; `Otros` solo se genera por una acción visible y reversible.

### 8.4 Cruces y relaciones entre variables

El usuario elige variable resultado y variables explicativas desde **Relaciones**. La interfaz muestra tipo de escala, valores faltantes, filtros, tamaño de muestra, diseño independiente/dependiente declarado y análisis permitido.

- Categórica × categórica: tabla de contingencia, porcentajes por fila/columna, gráfico agrupado/apilado; prueba chi-cuadrado solo con frecuencias esperadas apropiadas, y alternativa exacta/aviso si no se cumplen sus supuestos. Reporta estadístico, grados de libertad, p, tamaño de efecto (por ejemplo V de Cramér), n y cautela por comparaciones múltiples.
- Ordinal × ordinal o numérica con relación monotónica: correlación de Spearman cuando sea válida; mostrar coeficiente, intervalo de confianza si está implementado, n y faltantes.
- Numérica × numérica: dispersión y Pearson bajo supuestos declarados; ofrecer Spearman cuando el tipo/distribución o los supuestos lo recomienden.
- Resultado numérico por dos grupos: resumen por grupo y prueba comparativa configurada con sus supuestos; alternativa no paramétrica donde corresponda.
- Resultado numérico por tres o más grupos: resumen, visualizaciones comparables y método inferencial compatible con diseño/supuestos.
- Muestras relacionadas: el creador debe indicar emparejamiento/medición repetida; no tratar observaciones dependientes como independientes.

El sistema no calcula pruebas cuando el tipo, n o supuestos no son adecuados; explica el motivo y ofrece análisis descriptivo. Los valores p no se presentan como importancia práctica ni como prueba de hipótesis causal. No se generan recomendaciones académicas automáticas sin límites visibles. Los tests inferenciales muestran supuestos, nivel alfa, método de faltantes, corrección por comparaciones múltiples si aplica y referencias metodológicas internas. Cuando Novvin no soporte un análisis con fiabilidad, exporta datos y diccionario para usar software especializado.

### 8.5 Resumen automático

El resumen se limita inicialmente a hechos calculados: número de respuestas, periodo, pregunta con más/menos respuestas, distribución principal y faltantes. Cualquier resumen narrativo generado por IA debe ser opt-in, indicar que es automático, enlazar cada afirmación con cifras reproducibles y no enviar datos a proveedores externos sin aviso y consentimiento. Nunca redacta una conclusión causal por defecto.

## 9. Requisitos funcionales

| ID | Requisito | Criterio de aceptación |
|---|---|---|
| FR-01 | Crear/guardar/recuperar planillas | El propietario puede crear, editar, guardar y retomar tras cierre/reinicio; se distingue claramente guardado de publicado. |
| FR-02 | Duplicar e importar preguntas | Copia definiciones sin copiar respuestas, campañas, seguidores ni identificadores de participantes. |
| FR-03 | Versionar cambios | Publicación fija una revisión. Modificar plantilla no altera cuestionario ni análisis de campañas existentes. |
| FR-04 | Editor de preguntas | Los tipos descritos, imágenes, obligatoriedad, validación, orden, secciones y ramificaciones válidas persisten y se reproducen en Android. |
| FR-05 | Previsualizar y publicar | Se bloquea contenido inválido y se muestra tarjeta/formulario/audiencia final antes de publicar. La operación es idempotente. |
| FR-06 | Distribuir desde Social y QR | La tarjeta muestra invitación y contador. QR/enlace llevan a la misma campaña y no revelan respuestas. |
| FR-07 | Aplicar audiencia | APIs de lectura, respuesta y exportación validan autenticación, bloqueo, privacidad y membresía en servidor. |
| FR-08 | Consentimiento informado | Quien participa ve propósito, identidad visible, uso, contacto/retención declarados y acepta antes de responder. Se conserva evidencia/versionado del aviso. |
| FR-09 | Una respuesta por cuenta | Restricción única en base de datos por encuesta/cuenta; reintentos y solicitudes concurrentes no duplican. |
| FR-10 | Editar durante ventana | Solo actualiza la respuesta original antes de los 30 minutos y mientras esté abierta. Después el servidor rechaza cambios. |
| FR-11 | Cerrar/reabrir | Cierre automático/manual bloquea nuevos envíos; reapertura conserva resultados y unicidad previa. Cada cambio deja auditoría. |
| FR-12 | Biblioteca de encuestas | El creador encuentra planillas y campañas, busca/filtra/duplica/archiva/restaura y abre resultados. |
| FR-13 | Resumen privado | Muestra contadores y resúmenes por pregunta solo al propietario autorizado. En Social solo aparece el conteo permitido. |
| FR-14 | Respuestas individuales privadas | Lista paginada con respondente y envío completo accesible solo al propietario; cambio de ID en URL no cambia permisos. |
| FR-15 | Visualización analítica | Presenta estadística y gráficos compatibles con tipo de variable, con tabla accesible y denominadores explícitos. |
| FR-16 | Relaciones de variables | Permite declarar roles y explorar cruces compatibles; separa asociación de causalidad y muestra supuestos. |
| FR-17 | Exportar Word/PDF/Excel/CSV | Exporta instrumento, informe y/o datos en formatos definidos; conserva tipos, filtros y metadatos, protege identidad por opción explícita. |
| FR-18 | Gestionar publicación y borrado | Retirar post no borra planilla/resultados automáticamente; eliminación definitiva explica alcance y ejecuta reglas de retención. |
| FR-19 | Moderar contenido | Reportar encuesta/publicación, ocultar y bloquear funcionan con las herramientas actuales; hay estado del reporte y controles admin. |
| FR-20 | Errores y recuperación | Pérdida de red, respuesta duplicada, cierre durante el llenado, vencimiento, permisos revocados y errores de exportación tienen mensajes accionables y no pierden silenciosamente trabajo confirmado. |

## 10. Reglas de negocio completas

1. Solo una cuenta autenticada puede responder y cada cuenta mantiene como máximo una respuesta por encuesta publicada.
2. Una edición no aumenta el número de respuestas ni crea una nueva identidad lógica.
3. La ventana editable es de 30 minutos desde el primer envío y termina antes si se cierra la encuesta.
4. El cierre impide envíos nuevos; la reapertura no limpia datos ni elimina la marca de ya respondió.
5. La fecha programada usa zona horaria explícita; internamente se almacena UTC y se presenta localmente.
6. El cierre automático gana frente a una solicitud de envío que llegue después del instante de cierre, según reloj del servidor.
7. Cambiar audiencia aplica inmediatamente a vistas y respuestas nuevas. No borra respuestas anteriores.
8. El permiso de creador se valida en cada petición, no solo ocultando botones. Transferir propiedad requiere confirmar titularidad y auditar el cambio.
9. El resumen público consiste únicamente en conteo de envíos y datos descriptivos de la tarjeta; no publica participantes ni respuestas. La política no cambia por encuesta.
10. La descarga con nombres de participantes requiere confirmación separada. Los archivos exportados quedan bajo custodia del creador.
11. El creador puede retirar una tarjeta y conservar una encuesta privada. El borrado permanente de una encuesta elimina respuestas y archivos asociados tras mostrar exactamente qué se purgará y si existe retención obligatoria.
12. Borrar la cuenta del creador debe aplicar una política definida para campañas y respuestas: transferir si el usuario lo designó y el sistema lo permite, o cerrar/anonimizar/purgar conforme a política de datos; nunca dejar propietario huérfano sin tratamiento documentado.
13. El autor de una respuesta no puede consultar, corregir o retirar una respuesta cerrada salvo que el flujo permitido por producto o una solicitud de privacidad lo habilite. La solicitud de datos no otorga acceso a otras personas.
14. Formularios con archivos definen formato/tamaño, finalidad, visibilidad y retención. Archivos nunca son públicos por URL predecible.
15. Valores vacíos por omisión, salto lógico y “prefiero no responder” se codifican distinto.
16. El orden aleatorio de opciones, si se activa, conserva el orden original para el informe y registra la configuración de presentación.
17. Una versión con respuestas no se edita retroactivamente. Corrección requiere publicar una nueva revisión/campaña o retirar y volver a publicar con mensaje claro.
18. Cada tabla/gráfico exportado informa N válido, filtro y método, y los porcentajes usan un denominador etiquetado.
19. Resultados de audiencia extremadamente pequeña no se desglosan públicamente; la vista pública no permite inferir la identidad del participante.
20. Novvin no promete una respuesta por persona física, solo por cuenta activa de la plataforma.

## 11. Datos y persistencia

Modelo lógico previsto; los nombres finales de tablas/endpoints se afinan durante implementación, conservando las invariantes:

| Entidad | Campos principales | Invariantes |
|---|---|---|
| `survey_templates` | id, owner_id, title, description, status, current_revision_id, created_at, updated_at, deleted_at | Propietario autenticado; plantilla privada por defecto. |
| `survey_template_revisions` | id, template_id, revision_number, schema_json, created_at, editor_id, checksum | Revisión inmutable, orden único por planilla, definición validada y auditable. |
| `surveys` | id, owner_id, template_id, revision_id, social_post_id, title_snapshot, audience, status, opens_at, closes_at, edit_window_minutes, created_at | Campaña apunta a una revisión; estado de servidor autoritativo. |
| `survey_questions` / esquema revisionado | id estable en revisión, type, prompt, help, required, order, config, media_ref, section_id, branch_rules | Identidad de pregunta estable solo dentro de revisión; constraints validadas. |
| `survey_responses` | id, survey_id, account_id, consent_revision_id, submitted_at, edited_at, edit_until, status, idempotency_key | Único `(survey_id, account_id)`; asociación a cuenta protegida; timestamps de servidor. |
| `survey_answers` | response_id, question_revision_id, value_json, normalized_value, answered_at | FK a respuesta/pregunta; tipo y límites validados por servidor. |
| `survey_media` | id, owner_id, survey_id/revision, storage_key, mime_verified, size, alt_text, visibility, checksum | Clave opaca, acceso con autorización o URL firmada breve, eliminación ligada al dueño. |
| `survey_events` | actor_id, survey_id, event_type, timestamp, safe_metadata | Auditoría de publicación, audiencia, cierre, reapertura, exportación y cambios delicados; no duplicar el texto sensible de respuestas. |
| `survey_exports` | id, owner_id, survey_id, format, filters_hash, options, status, object_key, expires_at, created_at | Solo dueño, autorización al descargar, expira y limpia temporales. |

Las respuestas abiertas y adjuntos pueden contener información personal sensible. La base y las copias deben aplicar mínimo privilegio, cifrado en tránsito y en reposo disponible en infraestructura, backups protegidos y procesos de retención. No se publican IDs de respuesta secuenciales.

## 12. API y consistencia

La API Ubuntu es fuente de verdad para identidad, permisos, estado de campaña, unicidad de respuesta, ventana de edición y datos exportables. La implementación debe usar transacciones para guardar una respuesta completa, respuestas por lote y cambio de estado cuando sean operaciones dependientes.

Operaciones lógicas requeridas:

- Crear/leer/actualizar/duplicar planilla y crear/leer revisión.
- Crear campaña a partir de revisión, configuración y publicación Social.
- Leer encuesta pública autorizada, audiencia y estado sin exponer datos del creador.
- Crear respuesta con `Idempotency-Key`; consultar estado propio; actualizar la misma respuesta dentro de plazo.
- Cerrar/reabrir campaña con control de rol y evento de auditoría.
- Consultar resumen, páginas de respuestas, distribuciones, cruces y exportaciones con autorización de propietario.
- Generar QR/enlace revocable; crear y descargar exportaciones privadas.
- Retirar post, archivar plantilla, mover a papelera y purgar según confirmación/política.

Al recibir solicitudes simultáneas, una restricción única de base de datos por encuesta/cuenta determina una sola respuesta. La aplicación traduce conflicto a mensaje claro **“Ya registraste una respuesta; puedes editarla hasta [hora]”**, no a error genérico. Una respuesta de varias páginas se confirma atómicamente; si no se pudo guardar, el cliente conserva borrador y puede reintentar.

## 13. Seguridad, privacidad y abuso

- Autenticación obligatoria para responder, administrar y exportar; rotación/revocación de sesión se respeta.
- Autorización por propietario y audiencia en backend en cada endpoint, incluidas rutas de QR, objetos y exports.
- SQL parametrizado, límites de tamaño, paginación, rate limiting por cuenta/IP/ruta y protección contra abuso automatizado.
- Validar esquema por tipo de pregunta; normalizar y limitar textos; sanitizar HTML/Markdown y nombres de archivo.
- Verificar MIME real, extensión, peso, dimensiones y contenido de fotos/archivos; aislar cargas; no servir ejecutables; controlar enlaces remotos.
- Enlaces a respuestas/exportaciones opacos, revocables y de corta duración; nunca incrustar tokens de autenticación en QR.
- No exponer datos por logs, mensajes de error, notificaciones push ni telemetría. Métricas operativas usan IDs pseudónimos y agregación.
- El propietario ve identidad de la cuenta junto a respuestas. El participante lo sabe antes de aceptar; no hay modo “anónimo” en la definición base.
- La biblioteca privada no indexa planillas ni informes en buscadores. La audiencia de cada publicación se respeta en cachés y previews.
- Botón para reportar formularios abusivos y controles admin de retiro/limitación, con trazabilidad y apelación si el sistema ya la soporta.
- Definir retención por clase de dato y país antes de producción; permitir solicitudes de eliminación de cuenta y tratamiento de exportaciones conforme a la política de Novvin.
- Si se ofrece participación de menores, establecer controles específicos; en ausencia de política y edad objetivo configurada, no habilitar recolección de información sensible de menores.

## 14. Requisitos no funcionales

| ID | Requisito | Objetivo de aceptación propuesto |
|---|---|---|
| NFR-01 | Disponibilidad funcional | Acciones confirmadas no se pierden; estado de publicación y respuesta se recupera tras caída/reinicio según backup y sincronización configurados. |
| NFR-02 | Integridad de respuestas | Cero duplicados por concurrencia/reintento en pruebas de carga previstas; cada envío contiene todas sus respuestas o ninguna. |
| NFR-03 | Rendimiento del participante | Abrir encuesta mediana <2 s p95 con red estable; guardar envío <3 s p95 sin adjuntos o mostrar progreso. |
| NFR-04 | Rendimiento del creador | Resumen común <3 s p95; individual y exportación paginadas/asincrónicas, no bloquean la app. Objetivos finales se ajustan a datos observados. |
| NFR-05 | Capacidad de encuesta | Límites por formulario, preguntas, opciones, imágenes, archivos, respuestas y exportaciones son configurables y presentados en editor; saturación devuelve error recuperable. |
| NFR-06 | Accesibilidad | TalkBack/lector de pantalla, teclado, foco, contraste, zoom y texto alternativo; no se comunica resultado solo por color. |
| NFR-07 | Adaptación | Flujo funcional en los tamaños de pantalla Android soportados; se conserva progreso y destino tras autenticación. |
| NFR-08 | Idioma y formato | Español claro inicial; fechas, decimales, zona horaria, unidades y CSV declarados; estructura interna normalizada. |
| NFR-09 | Seguridad de exportación | Solo dueño puede solicitar/descargar; descargas expiran; datos/identidad se incluyen conforme a selección explícita. |
| NFR-10 | Observabilidad | Métricas de tasas de error, demora, envíos duplicados rechazados, exportaciones fallidas y abuso, sin registrar respuestas personales. |
| NFR-11 | Recuperabilidad | Borradores locales y exportaciones con estado verificable; restauración de datos de servidor sigue runbook y pruebas de restauración. |
| NFR-12 | Compatibilidad estadística | Mismo dataset/filtro/metodología produce cifras iguales en tabla, gráfica y archivos exportados. |
| NFR-13 | Diseño tolerante a fallos | Errores de red, sesión vencida, imagen faltante, encuesta cerrada y archivo inválido tienen estado, explicación y salida. |
| NFR-14 | Mantenibilidad | Esquema versionado; preguntas nuevas no rompen campañas existentes; migraciones reversibles o con estrategia de recuperación documentada. |

Los umbrales son metas iniciales medibles, no evidencia de rendimiento actual. Se deben validar con dispositivos, volumen y red de referencia antes de afirmar cumplimiento.

## 15. Criterios de análisis reproducible

Cada análisis y exportación conserva: ID/revisión de encuesta, fecha de corte, filtros, población incluida, definición de cada variable, categorías y orden, n bruto/n válido, tratamiento de faltantes, método de cuartiles, intervalos del histograma, prueba/estadístico/hipótesis cuando aplique, software/versión de motor analítico y fecha de generación.

El autor puede corregir metadatos (rol de variable, unidad, etiquetas) sin mutar respuestas. La corrección crea una revisión del diccionario y se refleja en exportaciones posteriores; informes anteriores identifican su versión.

## 16. Notificaciones

- Al creador: encuesta publicada, cierre programado ejecutado, error de exportación y, optativamente, aviso periódico de participación. La frecuencia puede configurarse para evitar mensajes excesivos.
- Al participante: confirmación de envío; opcional recordatorio local de cierre solo si la app puede hacerlo sin revelar contenido sensible.
- No se envían respuestas individuales ni identidad del participante en notificaciones externas.
- Notificaciones pueden desactivarse y respetan estado de cuenta y preferencias.

## 17. Administración y moderación

El panel de administración permite buscar campañas reportadas por ID/título/creador, revisar motivo y metadatos mínimos, ocultar temporalmente publicación, restringir campaña, preservar evidencia necesaria, resolver reporte y registrar acción. Los administradores no acceden automáticamente al libro completo de respuestas: el acceso excepcional requiere política, rol elevado, motivo auditado y minimización. Las denuncias no incluyen respuestas confidenciales por defecto.

El creador puede editar la tarjeta mientras está publicada según reglas de Social; cambiar preguntas significa una revisión/campaña separada. No se permite cambiar retroactivamente el aviso de consentimiento de forma que invalide la autorización ya dada. Un nuevo propósito sustancial requiere nueva campaña/consentimiento.

## 18. Métricas de producto y éxito

Eventos agregados/pseudónimos: planilla creada/guardada/duplicada, vista previa, publicación iniciada/completada, apertura elegible, consentimiento aceptado/rechazado, respuesta confirmada/edición, cierre/reapertura, exportación por formato y error por tipo. No guardar texto de preguntas o respuestas en analítica.

Indicadores de producto: creadores que finalizan publicación, encuestas con primera respuesta, tasa de formulario completado, tiempo de respuesta, abandono por sección, fallos de envío, duplicados bloqueados, exportaciones satisfactorias y retorno de creadores. La métrica `participantes alcanzados` solo se usa si el sistema puede contar impresiones elegibles reales y debe diferenciarla de la cantidad de seguidores.

## 19. Pruebas y aceptación del producto

### Editor y versiones

- Crear, editar, guardar, cerrar la app y recuperar borrador; validar que un guardado y una publicación son acciones diferentes.
- Duplicar una planilla y verificar que no copia personas ni respuestas.
- Publicar v1, editar planilla a v2 y demostrar que v1 mantiene pregunta, imagen, orden y resultados originales.
- Probar reordenamiento, requeridas, todos los tipos, límites, imágenes y ramas válidas/invalidas en Android.

### Difusión, audiencia y QR

- Abrir QR como usuario con sesión, sin sesión, bloqueado y fuera de audiencia; comprobar retorno tras login y que nunca se filtra un formulario restringido.
- Retirar/regenerar enlace y confirmar invalidación esperada sin borrar respuestas.
- Publicación pública/restringida muestra solo contador y metadatos permitidos.

### Respuestas

- Dos cuentas: una responde, el creador ve identidad/datos; la segunda no puede consultar respuesta ajena ni panel.
- Una cuenta responde dos veces en paralelo y reintenta con timeout: una sola fila, una sola unidad de conteo.
- Editar a los 29:59, rechazar una vez vencidos 30 minutos; cerrar antes del límite y aplicar regla de cierre; reabrir sin reset ni segundo envío.
- Probar preguntas omitidas, “no aplica”, salto lógico, borrador local, archivo, sesión caducada, mala red y encuesta cerrada.

### Estadística y exportaciones

- Con dataset de referencia conocido verificar cada frecuencia, porcentaje, acumulado ordinal, media/mediana/desviación, cuartiles/IQR, tabla cruzada, n y faltantes.
- Confirmar que pastel suma las categorías incluidas al denominador mostrado; barras/bastones ordenados correctamente; caja y bigotes y atípicos siguen método informado.
- Contrastar cada gráfico con la tabla y el Excel usando el mismo filtro y corte.
- Generar DOCX/PDF y verificar paginación, leyendas, acentos, ejes, unidades, texto alternativo/estructura, metadatos y fuentes.
- Verificar Excel con varias hojas, encabezados, tipos, fechas, múltiples selecciones y prevención de fórmulas; CSV UTF-8 y escapado seguro.
- Confirmar que export no autorizado falla, link expira y archivo temporal se limpia.

### Eliminación, seguridad y moderación

- Intentar IDOR de campañas, respuesta, archivo, resultados y exports cambiando IDs.
- Verificar que borrar el post no borra resultados sin elección; purga definitiva elimina datos/adjuntos según política y registra cierre del trabajo.
- Reportar y moderar encuesta; comprobar respuesta y auditoría.
- Verificar que logs, push, telemetría y errores no contienen respuestas o tokens.

## 20. Decisiones por defecto consolidadas

| Tema | Decisión de producto |
|---|---|
| Identidad del encuestado | Identificada para el creador; nunca anónima por defecto. Aviso previo al consentimiento. |
| Resultados para público/participantes | Solo contador de respuestas en tarjeta; resultados individuales y gráficos solo para propietario. |
| Respuestas por cuenta | Una por campaña; edición de la misma respuesta por 30 minutos o hasta cierre, lo que ocurra primero. |
| Edición posterior | Bloqueada. Solicitudes excepcionales no crean otra respuesta. |
| Cambios de plantilla | Guardan nueva revisión y no cambian encuestas ya publicadas. |
| Reapertura | Conserva respuestas; solo cuentas que no respondieron pueden enviar. |
| Eliminación del post | Retira la distribución; planilla y resultados permanecen en biblioteca hasta acción del creador/política de cuenta. |
| Acceso por QR | Lleva a encuesta; requiere login y respeta audiencia. No revela el panel. |
| Estadística | Métodos declarados, denominadores explícitos, sin inferir causalidad. |
| Exportaciones | DOCX, PDF, XLSX y CSV desde panel autenticado del creador; identidad se incluye de forma explícita. |
| Fuente de verdad | API propia Ubuntu/PostgreSQL; el cliente Android no decide permisos ni unicidad. |

## 21. Fuentes y referencias

- Google Docs Editors Help, [editar formularios, preguntas, imágenes y secciones](https://support.google.com/docs/answer/2839737?hl=en).
- Google Docs Editors Help, [publicar y compartir, limitar respuestas, editar y mostrar resúmenes](https://support.google.com/docs/answer/2839588?hl=en).
- Google Docs Editors Help, [ver, administrar y descargar respuestas](https://support.google.com/docs/answer/139706?hl=en).
- NIST/SEMATECH e-Handbook, [gráficos exploratorios y caja y bigotes](https://www.itl.nist.gov/div898/handbook/eda/section3/boxplot.htm).
- NIST/SEMATECH e-Handbook, [tablas de contingencia y prueba chi-cuadrado](https://www.itl.nist.gov/div898/handbook/prc/section4/prc46.htm).
- Las referencias describen capacidades documentadas de Google Forms para inspirar convenciones conocidas. No definen políticas de Novvin ni significan que Novvin ya tenga esas funciones.
