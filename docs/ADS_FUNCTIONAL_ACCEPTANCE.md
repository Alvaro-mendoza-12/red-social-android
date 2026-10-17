# Publicidad de Novvin: requisitos y aceptación

Fecha: 17 de octubre de 2026. Alcance solicitado: publicidad de la propia aplicación, con carrusel similar a las capturas aportadas, creación sencilla y aparición entre publicaciones.

## Funciones y reglas de negocio

| ID | Requisito | Evidencia requerida |
| --- | --- | --- |
| AD01 | Gestión desde el menú de Social, incluso después de ocultar un anuncio. | Navegador y Android. |
| AD02 | Crear en tres pasos: contenido, tarjetas y revisión, con publicación explícita. | Flujo completo, atrás conserva datos. |
| AD03 | Una imagen o carrusel de hasta diez tarjetas. Título, imagen, descripción opcional, destino y botón por tarjeta; reordenar y quitar. | Dos imágenes reales subidas y persistidas en orden. Validación del límite en servidor. |
| AD04 | Anuncio identificado como publicidad después de cuatro publicaciones orgánicas. | Contar posición en el DOM; no mostrar primero. |
| AD05 | Imágenes solo del almacén post-media del propietario, con revisión antivirus limpia. | Subida real; objeto ajeno o no limpio rechazado. |
| AD06 | Gestión reservada a cuentas administrativas con capacidad de moderación; usuarios ordinarios ven anuncios activos. | Servidor devuelve 403 a creación/cambio no autorizados. |
| AD07 | Pausar retira del catálogo público; editar conserva tarjetas y estado de pausa; activar lo vuelve a incluir. | Servidor real y cliente. |
| AD08 | Guardar y cambiar estado actualiza el feed abierto, sin exigir recarga. | Publicar desde el gestor, cerrar y ver el nuevo contenido. |
| AD09 | Destinos internos válidos y enlaces HTTPS sin credenciales. No javascript/data ni origen local permitido por la política. | Pruebas de validación del servidor. |
| AD10 | Fallo al publicar mantiene los campos y permite reintentar; progreso visible. | Prueba de fallo controlado y posterior publicación. |

## Requisitos no funcionales

- Distribución adaptable a 320 px, escritorio y orientación horizontal; pasos y acciones sin recorte.
- Diálogo con título accesible, foco contenido, navegación por teclado, campos etiquetados y errores anunciados. Objetivo WCAG 2.2 AA; comprobar estos controles no acredita toda la web.
- Ningún recurso de prueba permanente, cuenta personal modificada o token en los informes.
- Autoría, autorización y escaneo se verifican en el servidor, sin confiar en controles ocultos del cliente. Referencia técnica OWASP ASVS y guía de subida de archivos; no implica una auditoría completa de seguridad.
- El flujo está inspirado en la referencia visual y las diez heurísticas de usabilidad. No se ha implementado una plataforma de compra de campañas, pagos, subastas, segmentación o medición de conversiones: no se solicitaron para los anuncios propios de esta fase.

## Estado de aceptación

Aceptación funcional aprobada en web 9.0.49 y API desplegada: permisos reales, dos imágenes subidas y legibles por otra cuenta, orden de tarjetas persistido, publicación, botón hacia Mensajes, edición, pausa sin reactivación accidental y actualización del feed sin recarga. El gestor se comprobó a 320×800, 844×390 y 1440×960. Se eliminaron el anuncio y ambas cuentas temporales. Recibos: `ads-acceptance-live.json`, `ads-acceptance-cleanup.json` y `ads-9049-api-rollout.json` en audit/2026-10-07-integral. Las pruebas unitarias web suman 557 y la API 841 aprobadas con siete omisiones; las integraciones PostgreSQL de preguntas y sorteos pasaron por separado.

La certificación integral de toda la web sigue separada: cobertura real completa por función, accesibilidad integral, navegadores no Chromium, redes/dispositivos físicos y recuperación/capacidad vigentes requieren evidencia específica. El despliegue público por sí solo no cierra estos puntos. Tampoco se declara cumplimiento de todas las leyes aplicables sin identificar jurisdicción y tratamiento de datos.

## Referencias

- [WCAG 2.2, W3C](https://www.w3.org/TR/WCAG22/).
- [OWASP ASVS](https://owasp.org/projects/asvs).
- [OWASP: subida de archivos](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html).
- [Nielsen: diez heurísticas](https://www.nngroup.com/articles/ten-usability-heuristics/).
- La página pública de carruseles de Meta redirigió al inicio de sesión; las capturas del usuario son la referencia visual disponible. No se afirma haber verificado una reproducción idéntica de Ads Manager.
