# 89 — Colas por cuenta y recuperación del visor

Fecha: 2026-10-29, Bogotá. Continuación de la auditoría de web y APK solicitada después de la revisión 88. Se revisaron recuperación de medios protegidos y selección de mensajes pendientes de grupos/comunidades al cambiar de cuenta.

## Android: mensajes pendientes que bloqueaban otra cuenta

Las consultas de grupos y comunidades limitaban a cuarenta filas pendientes antes de que el repositorio comprobara el remitente. Con mensajes antiguos de una cuenta inactiva, el lote podía no contener ningún envío de la cuenta actual. Los mensajes borrados para todos también ocupaban ese presupuesto aunque el repositorio posteriormente los descartase.

La selección ahora filtra en Room por la cuenta activa y excluye los borrados antes de aplicar el límite. Conserva la normalización del identificador y añade desempate por ID al ordenar por fecha. El repositorio mantiene la comprobación de sesión y vuelve a comprobar remitente, borrado y estado bajo el coordinador de envío. Los pendientes de otras cuentas se conservan; no se envían ni se eliminan por esta corrección. No cambia el esquema ni se requiere una migración.

La reproducción local ejecutó las consultas extraídas del código contra SQLite con datos sintéticos. Antes, ambas seleccionaban cuarenta filas sin ningún envío apto de la cuenta activa; después, seleccionaron su envío pendiente sin incluir otras cuentas ni borrados. Es una comprobación funcional local, no una medición de latencia del servidor.

Se incorporaron dos pruebas nativas sobre la base Room real en memoria, una por tipo de conversación. Comprueban cuarenta pendientes de otra cuenta, un mensaje borrado, dos envíos activos con la misma fecha, normalización del remitente, límite, orden estable y conservación de las filas excluidas. El uso de parámetros vinculados y la validación de consultas en compilación se contrastaron con la [documentación oficial de Room](https://developer.android.com/training/data-storage/room/accessing-data).

Validación Android cerrada: **1.956 pruebas unitarias en 414 suites**, sin fallos, errores ni omitidas; APK de QA y APK de pruebas compilados; **lint con cero errores y 320 advertencias existentes**. Los análisis de aplicación, pruebas nativas y unitarias se ejecutaron de nuevo; Gradle reutilizó el informe final porque su contenido no cambió.

En el emulador propio AOSP 35, el paquete separado `conectate.com.qa` aprobó **8/8 pruebas nativas**: las dos nuevas de Room, el recorrido de entrada y cinco casos de resolución de enlaces. El registro de crashes quedó vacío y el emulador se cerró al terminar. Son estas ocho pruebas de tres clases, no una repetición de toda la batería nativa histórica ni una verificación entre redes físicas.

La corrección Android está comiteada y subida en `9c8fb825` de `feature/live-gifts` y `a2c01a72` de `main`. Los cinco archivos de código/prueba se contrastaron en ambas carpetas antes de ejecutar la validación.

## Web: el visor podía quedar vacío sin explicar el fallo

El visor ampliado escuchaba el error del elemento multimedia. Si se agotaba el registro del origen protegido antes de asignar un `src`, ese evento podía no producirse y la pantalla quedaba sin una recuperación visible.

Ahora el visor recibe también el fallo de resolución del origen, muestra una explicación sencilla y ofrece «Volver a cargar». La acción monta un intento nuevo de la imagen o video y mantiene disponible la descarga. Se conserva la invalidación de medios al cambiar de sesión. No se aumenta el número de reintentos automáticos ni se exponen credenciales en las direcciones.

Los dos casos nuevos, imagen y video, fallaron antes de la corrección y aprobaron después. La regresión web completa aprobó **857 pruebas en 159 suites**; Node aprobó **96/96**. TypeScript, compilación y puerta SEO de **19 páginas públicas sin JavaScript** aprobaron. Lint terminó con **cero errores y siete advertencias existentes**; el aviso de tamaño de algunos chunks sigue visible.

La corrección web está en `85b7c809` de `feature/live-gifts` y `d91895d7` de `main`, subidos a GitHub. Se publicó en [Novvin](https://red-social-android.vercel.app), despliegue **dpl_AuxYm7MqkS9SotmVDivbrJbbcJV7**, estado READY/Production.

En el navegador, con una cuenta sintética propia, se hizo fallar únicamente `serviceWorker.register` dentro del contexto de QA. El visor mostró el aviso y el botón. Después de restaurar esa función y pulsar «Volver a cargar», la imagen protegida real se cargó y el visor permaneció abierto. Se contrastó que el módulo principal coincidía con el HTML público actualizado. El video se comprobó mediante la prueba automatizada del componente real; esta continuación no añade una prueba física de video. El navegador de QA se cerró al terminar.

La comprobación posterior de la web publicada aprobó **90/90 controles públicos HTTP/SEO**. También se observó recuperación automática ante un chunk anterior que dejó de existir durante la publicación: la bandeja volvió a mostrarse sin pantalla de error. No se modificó ese mecanismo, que ya estaba implementado.

## Alcance de publicación

La web incluye la recuperación del visor. Android conserva la versión candidata **9.1.30 / 170**, de QA local; no se ha distribuido una nueva actualización de Play en esta tanda. La validación disponible es mediante emulador. La comprobación entre dispositivos físicos y redes distintas y la huella SHA-256 del certificado de firma de Play para la asociación HTTPS siguen pendientes según las revisiones 85–86.

No se ha reemplazado la API de producción ni desplegado la optimización de base de datos medida en la copia aislada de la revisión 85. No se interrumpió ni saturó el servidor para reproducir estos fallos. Las credenciales y registros privados de QA quedan fuera de los commits y del informe entregable. Los resultados cubren los casos descritos y no garantizan ausencia de todos los defectos de producción.
