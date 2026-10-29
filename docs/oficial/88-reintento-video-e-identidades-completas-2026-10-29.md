# 88 — Reintento de video e identidades completas

Fecha: 2026-10-29, Bogotá. Revisión adicional solicitada después del cierre 87. El objetivo fue contrastar los límites de lo ya comprobado y buscar fallos de recuperación; se revisaron registro de medios protegidos, caché/identidad, cola de envíos y carga agrupada de perfiles.

## Dos fallos reproducidos

**Video: botón Reintentar sin recuperación real.** Cuando fallaban el registro del medio y sus tres reintentos automáticos, el elemento de video podía quedar sin `src`. El botón llamaba a `load()` y `play()` del elemento, pero no reiniciaba la resolución protegida del origen. Se reprodujo con un test que renderiza la tarjeta real y `StreamingVideo`, y también en el navegador contra la web publicada: se hizo fallar temporalmente `serviceWorker.register` sólo en ese contexto de QA, se restauró y se pulsó Reintentar; el video continuó sin origen.

La tarjeta pasa ahora una revisión explícita de reintento al componente protegido. Este reinicia su presupuesto de recuperación, invalida el estado de fallo del intento anterior y, al resolver el medio, asigna un identificador opaco con una revisión nueva. No se reintenta desde otra cuenta ni se expone el token en el enlace. Se conserva la cancelación de operaciones anteriores y el límite de reintentos. La acción explícita restablece la intención de reproducir.

La documentación de [HTMLMediaElement.load()](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/load) describe la recarga de las fuentes existentes; el registro del origen protegido es una operación de la aplicación. Se contrastó también [ServiceWorkerContainer.register()](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerContainer/register). La causa concreta se determinó en el código y la reproducción, no sólo por la documentación.

**Insignias: identidades omitidas después de cien perfiles.** `publicProfileSummaries` recortaba la lista a cien identificadores. El llamador esperaba la respuesta de todos, por lo que el resto no obtenía identidad. Una prueba con 125 perfiles reproducía 100 resultados antes de la corrección. Ahora se realizan lotes secuenciales de hasta cien, sin descartar los restantes; el mismo caso devuelve 125 en dos consultas. Se mantiene la vinculación con la cuenta que inició la operación: la prueba de cambio de sesión verifica que no se adopta la nueva identidad para los lotes posteriores.

## Verificación de código

- Los dos casos fallaron antes de las correcciones y aprobaron después.
- Regresión completa: **855 pruebas, 158 suites, todas aprobadas**.
- Node: **96/96**. TypeScript, build y puerta SEO: **19 páginas públicas sin JavaScript**.
- Lint final: **cero errores y siete advertencias existentes**. Se eliminó además un import sin uso que había quedado después de cambiar el icono de navegación a brújula. El aviso de tamaño de algún chunk permanece visible; no se desactivan reglas.
- Pruebas específicas: recuperación sin `src`, aislamiento entre cuentas, reintentos acotados, 125 identidades en lotes y cambio de sesión entre consultas. La última compilación incluye el restablecimiento explícito de la intención de reproducción al reintentar.

Los cambios funcionales están en `e5b9b9cb` de `feature/live-gifts` y `8aa4e3b8` de `main`, subidos a GitHub. Las carpetas conservan el mismo árbol versionado. No hay cambios de API, base de datos ni Android en esta continuación; siguen vigentes sus resultados y límites de 85–87.

La reproducción en navegador altera únicamente la función de registro en el contexto sintético. No se interrumpe el servidor, no se modifican los permisos del usuario ni se envían mensajes a otras cuentas. Las credenciales de QA permanecen fuera de Git y de los entregables.

## Publicación y repetición real

Publicado en `https://red-social-android.vercel.app`, despliegue **dpl_FZBDPpCuuYRsBnCaWox5fAiLzvpK**, origen de versión `red-social-android-k3jnq19zp-alvaro-s-projects1.vercel.app`, estado READY/Production. En la misma sesión sintética se repitió la interrupción del registro de medios con la versión corregida: tras restaurarlo y pulsar Reintentar, el video obtuvo su origen y volvió a reproducirse. Se observaron `readyState=4`, tiempo de reproducción avanzando y ausencia de error nativo. Se contrastó que el módulo principal cargado coincidía con el HTML de la versión pública actual. El navegador de QA se cerró al terminar.

Las esperas del controlador de prueba no se presentan como una medición de latencia de producción. El recorrido compara recuperación funcional antes/después, sin simular toda la API ni sustituir los bytes del video. Los 125 perfiles pertenecen al fixture automatizado; no se afirma haber creado 125 cuentas ni realizado esa carga en producción.

El cierre sobre el dominio publicado aprobó **90/90 controles públicos HTTP/SEO**, sin fallos. La revisión se limita a los flujos descritos; no certifica ausencia de todos los errores de producción.
