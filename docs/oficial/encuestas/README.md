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
