# Arquitectura que ejecuta el proyecto

| Componente | Entrada en el repositorio | Responsabilidad |
| --- | --- | --- |
| Android | `app/src/main`, `app/build.gradle.kts` | Aplicación Kotlin/Compose, repositorios, almacenamiento del dispositivo y reproducción. |
| Web | `NoWing-web/src/App.tsx` | React, autenticación, conversación, visualización y descarga bajo la identidad activa. |
| API propia | `infrastructure/local-fallback/api/src/server.js` | Sesión, validación, almacenamiento, operaciones y política de acceso. La base es PostgreSQL. |
| Eventos | `infrastructure/local-fallback/realtime-gateway/src/server.js` | Suscripciones WebSocket autorizadas por recurso y señales de actualización de la bandeja. |
| Llamadas | `signaling-server/index.js` | Negociación por Socket.IO, presencia y soporte entre nodos mediante Redis Streams cuando se configura. |
| Multimedia | `infrastructure/local-fallback`, cliente de almacenamiento web | El origen autoriza lecturas y el despliegue puede servir los objetos desde R2. Las vistas previas y descargas usan límites distintos. |
| LIVE | `api/src/live-routes.js` y `api/src/live-draw-guess-routes.js` dentro de infraestructura | Roles y tokens de SFU, juego, temporización y moderación. |

En laboratorio se ejecutaron la API, PostgreSQL, el gateway, señalización y las dos sesiones del navegador en la misma máquina. R2 remoto, TURN y SFU externos quedan fuera de esa topología. Los adaptadores de importación histórica todavía tienen consumidores. [Topología de medios](REALTIME_MEDIA_ARCHITECTURE.md)
