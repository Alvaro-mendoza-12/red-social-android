# Cambio de cuenta y verificación de la web pública

Fecha: 12 de noviembre de 2026. Continúa el pendiente de la [operación 116](116-ubuntu-tiempo-real-y-diseno-web-2026-11-12.md). No cierra la revisión integral de Android ni de la web.

## Causa y corrección

El error de cierre de `MessagesMultiAccountSwitchingTest` procede del seguimiento de ActivityScenario. La regla genérica inicia `MainActivity` con un intent que la app sustituye al consumirlo. La regla deja de reconocer las transiciones posteriores, incluida `DESTROYED`, aunque la Activity sí las ejecuta.

Se utiliza `createEntryComposeRule()`, ya existente y empleado por las pruebas de entrada del proyecto. Inicia con el mismo `ACTION_MAIN` que conserva la app tras consumir la entrega. Se mantiene la validación real de la interfaz; no se ignoran errores de cierre ni se fuerza la aprobación.

La prueba comprueba A → B → A, espera tanto la sesión publicada como el fin de la operación, comprueba la cuenta visible y el cierre del diálogo y exige que las visitas anteriores queden invalidadas. Un cambio provisional de la activación de sesiones se retiró: no fue necesario para corregir este fallo. El código de cuentas de producción permanece igual.

## Comparación y regresión

Se conservó el APK de 116, **9.1.40-qa / 180**, SHA-256 `8ad2431b492c48c752d9a2653969fcde9456cf4b6866ffd5ad92ee1494728352`. En el emulador nuevo y exclusivo `NovvinReview117` (Android 15/API 35):

| Comparación | Resultado |
| --- | --- |
| APK anterior y regla anterior | Falla al cerrar la Activity. |
| Mismo APK y prueba reforzada, todavía con regla anterior | Falla al cerrar la Activity. |
| Mismo APK y prueba reforzada con `createEntryComposeRule()` | Aprobada en 16,134 s, incluida la transición `DESTROYED`. |
| Repetición con datos QA reinicializados | Aprobada en 10,871 s. |
| Selección de regresión por clase con datos QA aislados | **Diez pruebas aprobadas en seis clases**, cero fallos y omisiones: cambio de cuenta, restauración de fixtures, búsqueda estrecha, cinco casos de navegación y dos accesos con cuentas guardadas. |

Los intentos de combinar varias clases con fixtures de autenticación en un solo proceso también detectaron contaminación entre fixtures y esperas de cuentas guardadas. No se cuentan como una suite combinada aprobada. La comprobación reproducible usa un proceso de instrumentación por clase y reinicializa únicamente `conectate.com.qa` en el AVD creado para esta tarea. No borra datos de la aplicación principal ni de un dispositivo del propietario.

No se entregó un APK nuevo de producción ni se modificó la API instalada en Ubuntu. Compilar la instrumentación no sustituye ejecutarla; los resultados anteriores corresponden a ejecuciones efectivas.

## Web pública

Sitio comprobado: `https://red-social-android.vercel.app`. Vercel identifica el despliegue productivo `dpl_8q4fk5sFBM4uLnzUtXkPcw8RQ4qd`, listo y creado el 12 de noviembre de 2026 a las 09:15:49 de Bogotá, después de la publicación de 116.

- **90 controles HTTP aprobados**: HTML público completo, títulos y descripciones, canonicals, datos estructurados y CSP, sitemap/robots, redirecciones, recursos de marca, páginas inexistentes y exclusión de pantallas privadas de la indexación.
- Navegador a **375, 768 y 1.440 px**: portada visible, sin desbordamiento horizontal ni errores JavaScript de la página. Se comprueba también el formulario de acceso y la 404 personalizada.
- Axe 4.10.3: cero infracciones detectadas en las tres vistas; contraste conserva comprobaciones incompletas y no se presenta como certificación de accesibilidad.
- Mediciones de laboratorio de la portada: LCP de **348, 244 y 236 ms** y CLS **0** en esas tres vistas. No son datos de campo ni prueban INP o el rendimiento de todos los recorridos.
- Las solicitudes de una extensión externa y las peticiones abortadas al abandonar el formulario se distinguen de errores del producto. No se crearon cuentas ni se modificaron datos de producción.

Evidencia local ignorada: `tmp/review117/`, capturas en `output/playwright/review117/`. La revisión de los veinte criterios solicitados posteriormente se mantiene como un bloque independiente; este resultado no afirma que exista un aviso legal completo, analítica opcional o una ficha de negocio de Google.
