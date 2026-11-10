# Mensaje de acceso para un origen web rechazado

Base: `9bcb85c2076843673ab71a37f64c588286ea4fcd`. Rama exclusiva: `correcciones-dot`.

Login y los dos recorridos de recuperación muestran una explicación común únicamente cuando el backend devuelve el código exacto `UNTRUSTED_WEB_SESSION_REQUEST`. Incluyen un enlace fijo a `https://red-social-android.vercel.app/login`, sin copiar correo, códigos, tokens, rutas de retorno ni parámetros de la página actual; `noreferrer` evita transmitir el referente. Otros errores mantienen sus mensajes anteriores. No se cambian autenticación, CORS, orígenes permitidos, 2FA, contraseñas, variables Vercel ni políticas de confianza.

El cambio aclara un rechazo del origen; no diagnostica cualquier 403 ni acredita por sí mismo una corrección de acceso. Las pruebas del nuevo mensaje usan respuestas simuladas, no credenciales reales ni un ensayo visual del error en producción.

Verificación del parche integrado: **172 suites Jest y 972 pruebas aprobadas**, TypeScript/build y SEO de 19 páginas aprobados, lint con cero errores y siete avisos existentes. Se mantienen los avisos existentes de tamaño de JavaScript. Las cinco bases modificadas coinciden con las huellas publicadas; cuatro archivos son nuevos. `git diff --check` aprobado. La revisión independiente no encontró problemas de seguridad o corrección.

El commit base tuvo backend y client/realtime verdes en [CI 42758250430](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/42758250430). Android compiló Kotlin/Java, pero su quality gate falló por **49 errores MissingTranslation**; sus pruebas unitarias, los controles posteriores y release-smoke no se presentan como aprobados. Este commit integra además los 49 recursos españoles y la comprobación de cobertura de idiomas, sin suprimir lint. Las 41 pruebas Python combinadas aprobaron; el lint Android completo y las pruebas JVM siguen pendientes de la siguiente ejecución. El nuevo commit necesita su propia comprobación CI y de entorno Vercel; no se fusiona con ramas estables ni se promueve a Production.
