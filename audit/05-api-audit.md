# 05 — APIs (inventario y plan de autorización)

Fecha: 2026-09-26. En 29 archivos de rutas API se localizaron 408 declaraciones estáticas `app/router.method`. No son necesariamente 408 rutas únicas: hay montajes, variantes y definiciones dinámicas. La cifra sirve para dimensionar revisión, no como cobertura.

La entrada `server.js` aplica Helmet, CORS y autenticación de sesión en rutas específicas; módulos como `message-markers-routes.js`, `storage-routes.js`, `music-routes.js`, `creator-payout-routes.js` y `live-draw-guess-routes.js` separan dominios. La API tiene 232 pruebas aprobadas y una omitida, incluidas pruebas de propiedad de media, lectura por usuario, pago/regalos y recuperación. No se ha confirmado la autorización de todas las declaraciones ni se ha ejecutado una matriz HTTP en producción.

Para cada ruta se registrará METHOD/PATH, actor público/autenticado/admin, parámetro de objeto, comprobación de propietario o membresía, límite de tasa, idempotencia, código ante recurso inexistente/no autorizado, impacto de datos y test. Prioridad: almacenamiento privado, chat/grupo/comunidad, operaciones de monedas/regalos, administración, subida/borrado de videos y LIVE. Una respuesta 401 de `/v1/data/videos` o `/live` sin token sólo confirma que esas dos llamadas denegaron el acceso, no que el resto esté cubierto.

Criterio externo: [OWASP BOLA](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/) y [BFLA](https://api-security.owasp.org/editions/2023/en/0xa5-broken-function-level-authorization/). El avance se seguirá en `01-coverage-ledger.md` con evidencia por ruta.
