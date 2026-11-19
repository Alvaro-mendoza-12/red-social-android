# Restauración del registro y contraseñas en Android

18 de noviembre de 2026. Correcciones de Android sobre las ramas estables. Se identificó por lectura de código un bloqueo de registro al recrear el formulario y el guardado de las contraseñas de acceso y registro en el estado restaurable de Compose.

## Cambios

- La pantalla de registro comprueba el identificador también al restaurar la etapa final. Conserva la comprobación vigente mientras se pasa del selector a los datos de cuenta. Si falla la consulta en la etapa final, muestra el motivo y ofrece un reintento para un fallo de conexión. El registro exige un resultado disponible para el identificador actual.
- El acceso y el registro retienen la contraseña y la confirmación solo en memoria de la composición. La recreación de la pantalla exige volver a escribirlas y restablece su visibilidad. El nombre, el identificador elegido y los otros campos siguen sus reglas de restauración existentes.

## Verificación

Se compilaron las dos pantallas reales con el compilador de Kotlin y el complemento Compose del proyecto. Ocho casos JVM existentes de política de identificador y edad aprobaron. Los recibos, el log y las fuentes exactas están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit195-registration/candidate/`. La recreación interactiva y el reintento en Android siguen pendientes.

En un emulador de prueba, recrear `MainActivity` con `ActivityScenario.recreate()` o mediante la opción de desarrollador «No mantener actividades». La rotación conserva `MainActivity` por configuración del manifiesto y no reproduce este recorrido. Avanzar a la última etapa después de comprobar un identificador y recrear la actividad. Confirmar que el nombre y el identificador se recuperan, se repite la comprobación y requieren volver a escribir ambas contraseñas. Repetir sin conexión, constatar el error, reconectar y pulsar «Reintentar comprobación». En acceso, introducir una contraseña ficticia y mostrarla. Recrear la actividad y comprobar que se conserva el correo, la contraseña queda vacía y el control muestra «Mostrar contraseña». El ensayo del formulario de acceso no requiere enviar credenciales.

## Validación de edad pendiente en el contrato de registro

Android solicita fecha de nacimiento y comprueba el mínimo de 13 años en `RegisterScreen` y `AuthRepository`. En el camino hacia Ubuntu, la solicitud de alta que construye `LocalFallbackApiClient.register` envía correo, contraseña, nombre e identificador. La fecha se escribe más tarde mediante la actualización del perfil. El formulario web `RegisterPage` y `signUpWithFallbackAuth` tampoco envían fecha. La ruta `POST /v1/auth/register` valida correo, contraseña, nombre e identificador y crea la cuenta en una transacción que no recibe fecha. La validación de `birth_date` de `data-routes.js` pertenece a la edición de perfil y comprueba que la fecha sea real y no futura.

Se confirmó la diferencia por lectura de los consumidores y de la ruta versionada en Git. Los clientes históricos, incluida la web y varias herramientas de verificación, aún usan este contrato. La validación de elegibilidad antes de insertar la cuenta requiere una migración comprobada con los consumidores y los flujos de acceso federado, incluida una política por mercados revisada con [190](190-revision-juridica-inicial-por-mercados-2026-11-18.md). Sigue abierta como N017. Esta entrega conserva el contrato del servidor y la política actual de Android.
