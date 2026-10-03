# Encuestas de Novvin

**Estado:** especificación del producto e implementación Android en la rama `feature/live-gifts`; pendiente de integración y despliegue del backend.

**Fecha:** 2026-10-03.

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
