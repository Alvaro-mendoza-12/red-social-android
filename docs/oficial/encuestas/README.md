# Encuestas de Novvin

**Estado:** implementación Android en `feature/live-gifts`, API `f42a349` y antivirus desplegados; Encuestas activadas, 39 comprobaciones funcionales y 12 comprobaciones HTTPS correctas tras la entrega. APK `e729365` validado en Moto g04: 1.264 unitarias, R8 y 65 casos móviles correctos, incluidos 39 de interfaz/Encuestas. [Validación física y límites](../66-validacion-movil-e729365-2026-10-04.md). AAB firmado y criterios restantes pendientes en el informe; se conserva el historial del emulador `07e4989`.

**Última validación:** 2026-10-04.

**Producto:** Novvin para Android, conectado al backend Ubuntu.

Esta carpeta define el producto de encuestas sociales de Novvin: planillas reutilizables, publicaciones que invitan a responder, acceso mediante QR, una respuesta identificada por cuenta, resultados privados para su creador y análisis/exportación de datos.

La especificación completa, incluidos flujos, pantallas, controles, reglas de negocio, datos, seguridad, gráficos, análisis estadístico, exportaciones y criterios de aceptación, está en [ESPECIFICACION_FUNCIONAL_Y_TECNICA.md](ESPECIFICACION_FUNCIONAL_Y_TECNICA.md).

La propuesta toma patrones conocidos de formularios (preguntas, secciones, imágenes, publicación y revisión de respuestas), pero concreta las decisiones de Novvin: las respuestas individuales y la identidad del participante solo las consulta el creador; el público autorizado solo ve el contador de respuestas. El QR abre la encuesta; las exportaciones de Word, PDF y Excel se realizan desde el espacio privado del creador.

## Estado de la implementación Android

La rama incorpora el editor de planillas, biblioteca, publicación de encuestas en Social, consentimiento, una respuesta por cuenta, edición durante 30 minutos, resultados privados, gráficos y exportaciones. El editor se incluye como recursos locales del APK Android; no depende de un sitio web público. La API y las tablas nuevas deben desplegarse junto con el APK para que la función opere. La especificación describe el comportamiento esperado completo; se debe comprobar cada criterio de aceptación antes de declarar la función lista para producción.

## Archivos

- [Especificación funcional y técnica](ESPECIFICACION_FUNCIONAL_Y_TECNICA.md)
- [Estado de la implementación Android](IMPLEMENTACION_ANDROID.md)

- [Informe de preproducción y plan de habilitación](INFORME_PREPRODUCCION.md)
- [Evidencia PostgreSQL 16.15](VALIDACION_POSTGRESQL16.json)
- [Evidencia del antivirus privado](VALIDACION_ANTIVIRUS.json)
- [Evidencia de fixtures Linux](VALIDACION_LINUX.json)
- [Revisión de dependencias de la API](VALIDACION_DEPENDENCIAS.json)
- [Respaldo comprobado antes de la entrega](VALIDACION_RESPALDO.json)
- [Construcción de imágenes candidatas](VALIDACION_IMAGENES.json)
- [Arranque completo de la API en PostgreSQL aislado](VALIDACION_ARRANQUE_API.json)
- [Regresión conjunta del emulador](VALIDACION_ANDROID_COMBINADA.json)
- [Preparación comprobada de la entrega](VALIDACION_PREPARACION_ENTREGA.json)
- [Procedencia y hashes de los APK](VALIDACION_ARTEFACTOS_ANDROID.json)
- [Regresión móvil sin medios](VALIDACION_ANDROID_SIN_MEDIOS.json)
- [Salud de la API desplegada](VALIDACION_SALUD_API_DESPLEGADA.json)
- [Respaldo fresco utilizado para la entrega](VALIDACION_RESPALDO_ENTREGA.json)

- [Entrega verificada en el servidor](VALIDACION_ENTREGA_API.json)
- [Antivirus desplegado comprobado](VALIDACION_ANTIVIRUS_DESPLEGADO.json)
- [Comprobación funcional tras habilitar Encuestas](VALIDACION_PRODUCCION_FUNCIONAL.json)
- [Comprobación funcional tras actualizar Momentos](VALIDACION_PRODUCCION_FUNCIONAL_F42.json)
- [HTTPS, continuidad de Social y retirada de notificaciones por vistas](VALIDACION_HTTPS_SOCIAL_Y_ENCUESTAS.json)
- [Compilación, unitarias, R8 y procedencia del APK conjunto](VALIDACION_ANDROID_07E4989.json)
- [Interfaz del APK conjunto en el emulador](VALIDACION_INTERFAZ_07E4989.json)
- [Medios del APK conjunto en el emulador](VALIDACION_MEDIOS_07E4989.json)
