# Novvin Web 9.0.41 y Android 9.0.35 (132)

Fecha: 16 de octubre de 2026.

## Cambios

- Carpetas: compresión ZIP incremental en bloques de 1 MiB, sin rechazar el conjunto al superar los antiguos 128 MiB. Hasta 5000 entradas y 32 niveles; cada archivo hasta 500 MiB. Las carpetas grandes se reparten automáticamente en ZIP independientes con sus rutas originales. Cada parte agrupa hasta 450 MiB de origen, salvo un archivo individual mayor. No son archivos ZIP partidos que requieran una herramienta especial.
- Documentos: hasta 500 MiB por archivo en chat, grupo y comunidad. La API mantiene escaneo antivirus, identificación del contenido, autorización, cuotas y admisión. Las imágenes y el audio conservan 60 MiB. El límite de clips del feed continúa en 45 segundos; la nueva política de 10 minutos corresponde a videos enviados en mensajes.
- Videos en mensajes: duración real máxima de 600 segundos, verificada por metadatos en navegador y ffprobe en servidor, con tolerancia de 250 ms por redondeo del contenedor.
- Los errores de preparación o envío conservan el chat y el borrador, con aviso descartable junto al compositor. Los errores de acceso inicial siguen siendo una pantalla de acceso fallido.
- Descarga web y exportación multimedia Android: límite alineado a 500 MiB. La vista previa documental permanece limitada a 32 MiB; los originales mayores se descargan.
- Botones de adjuntos: el nombre se trunca sin ocultar «Editar imagen»; los controles de edición quedan separados de la vista previa en pantallas amplias.
- Stickers: se sustituyen etiquetas técnicas de relleno como `string` por «Sticker».
- Perfil: corregida una condición de escaneo duplicada después de `ORDER BY`, que causaba SQL 42804 al validar la propiedad de un avatar nuevo. La condición permanece en `WHERE`; no se relaja la autorización ni el escaneo.
- Recorte: dibujo libre, ocho bordes ajustables, controles por teclado y texto con color, tamaño y posición.
- Fotos: ampliación de avatar y portada en perfil y panel del contacto; compatibilidad con campos antiguos `image`, `icon`, objetos anidados y rutas que incluyen `group-media` o `community-media`.
- SEO: la guía de acceso incluye Novvyn en su título y descripción, explicando que la marca es Novvin. Se mantienen las 14 páginas públicas, sus enlaces canónicos, sitemap, imágenes y datos estructurados. No se declaran variantes como empresas distintas ni se repiten listas artificiales de palabras.

## Evidencia y alcance

Las comprobaciones y el despliegue se registran en `audit/2026-10-07-integral/web-9041-*`. El fallo inicial de configuración de arranque del candidato bajo concurrencia queda registrado; la repetición con contexto completo en serie pasó sin cambiar ni relajar la prueba.

El servidor volvió a responder a `/ready` y a SSH el 16 de octubre a las 21:18 UTC. La indisponibilidad de la mañana es un incidente histórico, no su estado actual.

La aceptación de llamadas, videollamadas, salas de voz y directos en móvil fue comunicada expresamente por el propietario el 16 de octubre. Se registra como aceptación del usuario; no como una prueba física nueva ejecutada por este agente.

## Comprobaciones ejecutadas

- Web: 480 pruebas unitarias, 79 suites; 44 comprobaciones de SEO.
- Android: compilación Kotlin y 1667 pruebas unitarias en 347 suites, sin fallos. No se instaló esta nueva versión en un teléfono durante esta entrega.
- API: la primera imagen pasó 810 casos, 6 omitidos que necesitan servicios de prueba; se repite la batería sobre la imagen que incluye la corrección de avatares.
- Prueba real previa al arreglo de avatares: el recorte libre, sus ocho controles, texto y envío de la imagen editada pasaron en escritorio, teléfono vertical y horizontal. Los errores del adjunto conservaron el chat y el borrador. El avatar nuevo detectó el SQL 42804; queda registrado como fallo descubierto y no como caso aprobado.

## Pendientes de certificación integral

1. Confirmar en el teléfono la recepción y descarga de archivos grandes con Android 9.0.35; compilar no equivale a esta aceptación física.
2. Reproducir el caso concreto de música comunitaria que desaparece y comprobar sus referencias históricas. La persistencia del catálogo y sus contratos no prueban que todos los archivos antiguos existan.
3. Comprobar en el móvil reinicio/cierre de sesión y restauración de favoritos de stickers; el repositorio ya guarda decisiones locales y sincroniza favoritos por cuenta.
4. Completar el inventario de funciones web y pruebas de navegadores ofrecidos. Esta entrega no sustituye la auditoría integral anterior ni certifica apartados no recorridos.
5. Cerrar los requisitos históricos de recuperación, carga y disponibilidad del servidor con evidencia actual. Un `/ready` correcto no certifica continuidad del servicio.
6. Observar el nuevo rastreo e indexación. La posición y la fecha de actualización de cada buscador dependen de ese servicio; no se promete el primer resultado ni una actualización inmediata.

Referencias: [ZIP incremental de fflate](https://github.com/101arrowz/fflate), [políticas de Google sobre repetición artificial de palabras](https://developers.google.com/search/docs/essentials/spam-policies#keyword-stuffing).
