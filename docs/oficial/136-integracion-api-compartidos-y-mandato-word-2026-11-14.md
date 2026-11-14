# 136 · API integrada, compartidos públicos y mandato Word contrastado

14 de noviembre de 2026, America/Bogota. Continúa [135](135-api-bandeja-y-candidata-web-2026-11-14.md). Se integran en Ubuntu correcciones estables ya publicadas, preservando las funciones que el servidor tenía instaladas. No se entrega una APK nueva ni se cierra la revisión integral.

## Estado efectivo de API y web

Está activa `novvin-api:review136-c2c4e24b93d3`, índice OCI `sha256:df8f7d1bf728dd542f5e6c2c88289ddc0f8e6c47b78c261a5f57d11728514bfe`. La base inmutable es la imagen 134 `sha256:a2d746a6f748568bf09d415f549e445c7b4df472602aab0169e954ea1ec700c4`. La huella de superposición es `c2c4e24b93d333a97a11fdf5f032788d9446cb5cfc31919e5ea6222012d2c1c9`; la revisión estable que aporta los cambios es `cc6a022ee123f99d84b97adc2136c92d1a2f6782`.

La combinación agrega seis módulos y modifica ocho de los 180 archivos activos; conserva los otros 172. Se verificaron los 186 archivos resultantes, sus propietarios y sus permisos. Los paquetes y el lock del servidor permanecen iguales; `sharp` 0.35.5 ya estaba instalado. Se conservaron los contratos de importación, los módulos e indicadores experimentales anteriores. El árbol completo del servidor no se atribuye a `main` y no se incorpora el plan 105 a las ramas estables.

La combinación de tres versiones conserva el código activo y aplica el delta estable desde 116. Dos conflictos requirieron revisión: finales de línea de `live-routes.js`, conservados como estaban, y el destino de apertura del perfil, que usa la ruta publicada `/app/profile/:id` con el dominio oficial. La corrección de stickers de 134 sigue presente. La [huella del delta](../quality/evidence/136-api-overlay.json) permite contrastar cada archivo.

Se activaron las rutas y reglas pendientes de invitación de llamada, participación de LIVE, acceso de invitados, canjes y compartidos. El arranque añadió `fallback_live_audience_items`, `fallback_live_audience_votes` y la columna booleana `fallback_call_sessions.expanded`, con valor inicial falso y sin nulos. Antes se comprobaron los permisos del rol real para esas operaciones. No se ejecutó un proceso de recuperación o borrado de historiales.

Pasaron salud interna, gateway y HTTPS público, las huellas de código dentro del contenedor, el usuario de ejecución 10001, una vista previa de sticker y el renderizado sintético de tarjeta JPEG de 1200 × 630. Las rutas nuevas devolvieron el rechazo previsto para identificadores inválidos; la bandeja sin sesión devolvió 401. Se conservaron las 25 capacidades, el entorno, los montajes, las opciones de contenedor y los identificadores de los otros catorce servicios. Esta activación terminó sin reversión.

### Rectificación del estado web de 135

La API de Vercel confirmó que el dominio ya servía `dpl_7SAVGakXwAS6YZ8BXV3FhkBug5s5`, creado automáticamente desde `main` en `4a2801326a96d752a8fd6c34e830791bec53addb`. Esa revisión ya incluía las correcciones web de 134. El nombre distinto del paquete respecto de la compilación local no demostraba que el código público fuera anterior; la conclusión de que esas correcciones web seguían sin publicar era incorrecta.

Se conserva ese despliegue Git. La candidata CLI de 135 sigue sin promover y no sustituye el sitio. El pendiente real era integrar la API que consume la web. Las verificaciones siguientes se ejecutaron sobre el dominio efectivo, no sobre una copia HTML local. No se publicaron los cuatro cambios CORS ajenos del workspace.

## Pruebas y límites

| Alcance | Resultado y límite |
| --- | --- |
| Código combinado en copia local, Node 22 | **1137 aprobadas, 28 omitidas, cero fallos**, de 1165 resultados. Incluye seis archivos de regresión de las funciones que ya estaban instaladas. Usa dependencias locales; no se presenta como una ejecución Linux completa. |
| Imagen Linux exacta, usuario 10001, sin red | **81 aprobadas, cero fallos ni omisiones**, en 16 archivos seleccionados. Se solapan con la batería anterior; no se suman como casos nuevos. |
| PostgreSQL nativo en entorno aislado | **Ocho resultados aprobados, sin omisiones**: votos y preguntas, identidades de invitados, vencimiento concurrente de rondas y propiedad/idempotencia de canjes. Incluye resultados padre e hijos. |
| Compartidos por HTTP y PostgreSQL, imagen exacta | **Nueve recorridos aprobados**: subida/lectura pública de raster, perfil y avatar, post con foto, privacidad/sensibilidad, cambio a privado, restricciones del autor, suspensión, video con miniatura y borrado. Se verifica un píxel de la imagen subida dentro de las tarjetas. |
| Invitación de llamada por HTTP y PostgreSQL | **Nueve recorridos aprobados** del verificador existente: permisos, bloqueos, cinco reintentos concurrentes, aislamiento del chat privado, vencimiento, bloqueo posterior, salida, rechazo y cierre por el propietario. Prueba autorización y estado; no incluye medios SFU ni tonos físicos. |
| Interfaz publicada | Chromium aislado a **375, 768 y 1440 px**: sticker propio/entrante, «Tú:», apertura, Escape, desplazamiento útil sin barras y sin desbordamiento. Sin errores de página/consola ni infracciones automáticas axe en esos escenarios. El HTML y los recursos son los publicados; los datos de cuenta, API y medios están interceptados con fixtures, sin sesión personal ni mensajes enviados. |
| HTTP del sitio real | **97 controles aprobados** de páginas, metadatos, CSP, redirecciones, recursos, robots/sitemap y exclusión de la SPA del índice. No acredita aceptación por buscadores ni ranking. |
| Enlaces públicos de las capturas del propietario | Perfil y publicación responden 200 al agente de enlace y contienen el identificador solicitado, Open Graph del objeto e imagen JPEG propia de **1200 × 630**. No usan la imagen genérica de portada. No se guardaron sus nombres, texto ni fotografías en la evidencia versionada. |

El laboratorio partió de una exportación del esquema real sin registros de personas y de la misma imagen PostgreSQL. No se copiaron credenciales productivas. El arranque y su repetición conservaron las columnas anteriores de una llamada sintética preexistente y añadieron `expanded=false`. Los secretos y registros del laboratorio permanecen privados en el servidor; sus contenedores se detuvieron y su volumen se conservó.

El arnés local necesitó declarar explícitamente que su fixture de borrado no contiene las tablas experimentales y enlazar las dependencias de señalización. El arnés Linux necesitó los archivos de migración y configuración pública que leen las pruebas. El caso PostgreSQL de puntos requirió su esquema base. Se conservaron las aserciones y la candidata no se modificó para ocultar esos fallos. La reserva de espacio se ajustó únicamente en el tmpfs sintético: la reserva productiva de 8 GiB permanece igual y su capacidad libre fue comprobada. La lectura HTTPS de fotos propias se mapeó al almacenamiento aislado en ese ensayo; no certifica R2 externo ni el antivirus de producción.

Las imágenes de [perfil](../quality/evidence/136-perfil-linux.jpg), [publicación](../quality/evidence/136-publicacion-linux.jpg) y [video](../quality/evidence/136-video-linux.jpg) son fixtures sintéticas de Linux. El raster naranja permite reconocer el píxel verificado; no es una foto real ni material de marketing. La comparación visual automática sigue **inconclusa** sin una base de capturas versionada. No se sustituyó por una aprobación inventada.

El verificador de llamadas conserva sus nueve recorridos y añade dos guardas: base dedicada de QA y cuentas `@example.invalid`. También se comprobó el rechazo de una base no QA antes de leer credenciales o conectar.

## Respaldo y continuidad operativa

Se guardaron en Ubuntu configuración, entorno, marcador, inspección, imagen anterior y volcado PostgreSQL. Se comprobaron SHA-256, los 75 blobs y sus descriptores OCI y el catálogo del volcado. La activación usó el bloqueo Compose y comprobó cambios de imagen/configuración antes de escribir. Una reversión de código conserva las tablas y la columna aditivas; no elimina datos ni restaura a ciegas mensajes de usuarios. El respaldo PostgreSQL no se presenta como una instantánea atómica conjunta del almacenamiento multimedia.

La [evidencia de 136](../quality/evidence/136-resultados.json) identifica pruebas, huellas y despliegue efectivo. Los archivos privados de configuración, bases, cuentas y logs de operación no se incorporan a Git.

## Mandato Word y trabajo restante

Se leyó el texto del Word del paquete. Sus **214 párrafos de cuerpo** coinciden en orden con el mandato Markdown al retirar únicamente marcas de formato, numeración y espacios; los cuatro párrafos adicionales son la portada. No contiene imágenes, tablas, comentarios, cambios controlados ni relaciones externas que agreguen instrucciones. La [evidencia de paridad](../quality/evidence/136-word-parity.json) identifica las huellas XML y de ambos archivos. No certifica igualdad visual y leer el mandato no implementa sus diecisiete fases.

Los chats vacíos y el límite global de mensajes importados siguen abiertos. También la precisión optimista, seguidores en tiempo real, imágenes de todas las cuentas, latencia de grupos/comunidades, asistente, menú, archivos, tonos, mayor compactación, medios físicos, interoperabilidad Android–web, R2 y recuperación operativa. La cabecera y el compositor siguen midiendo 58 y 52 px en el ensayo; no se redujeron en esta continuación. La visualización y las cachés de WhatsApp no se verificaron en la aplicación física.

La revisión móvil de propiedad global de LIVE continúa según 133. Esta integración compartida no reemplaza esa corrección ni la entrega de una APK final identificada. Las ramas `main` y `feature/live-gifts` publican las correcciones terminadas con árboles iguales, preservando los cambios ajenos y sin modificar `correciones`.
