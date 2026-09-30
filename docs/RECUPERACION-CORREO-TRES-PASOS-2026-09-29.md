# Recuperación por correo en tres pasos

Fecha: 29/09/2026. Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.

Esta entrega sustituye la pantalla de recuperación por correo de la APK 9.0.1. El informe anterior conserva la evidencia de aquella versión; el comportamiento actual corresponde a este documento.

## Flujo Android

1. «Olvidé mi contraseña»: introducir el correo registrado y solicitar el código.
2. Introducir un código de **seis dígitos**. Al completar los seis dígitos se intenta verificar automáticamente; también hay un botón explícito para verificar y otro para pegar el código copiado desde el correo.
3. Solo después de verificar, mostrar «Nueva contraseña» y «Repite la nueva contraseña». Se exige un mínimo de ocho caracteres y coincidencia entre ambos campos. Guardar e iniciar sesión en la misma cuenta; si existe segundo factor, se solicita antes de entrar.

La interfaz normal de recuperación no muestra el formulario de revisión manual ni el código de respaldo. «También perdí acceso al correo» abre la ayuda separada. Desde una sesión de otra cuenta se puede abrir Ajustes → Ayudar a recuperar una cuenta, sin cerrar la sesión existente. La revisión manual previa sigue disponible para cuentas robadas o pérdida de correo/segundo factor: acepta relato, datos recordados y enlaces. **No se añadió en esta entrega carga de archivos de evidencia**; ese requisito permanece separado del flujo automático por correo.

No se concede a la app acceso al buzón del usuario ni se lee Gmail. «Pegar código» utiliza el portapapeles solo tras una acción del usuario; la verificación automática se refiere a completar el campo de seis dígitos, no a interceptar correos.

Actualización del 30/09: la carga de evidencias ya está implementada, desplegada y probada en la entrega [Evidencias privadas y controles de llamadas](EVIDENCIAS-Y-LLAMADAS-2026-09-30.md). El texto anterior describe el alcance histórico de la versión 9.0.2.

## Persistencia y presentación

- `EmailRecoveryDraftStore` guarda correo, etapa, código o permiso verificado y fechas en preferencias cifradas mediante Android Keystore.
- Se guarda el correo antes de iniciar el envío y se restaura la etapa al recrearse el proceso o arrancar de nuevo. Restaurar no solicita un correo nuevo automáticamente.
- Las contraseñas nuevas se mantienen únicamente en memoria. Tras una recreación del proceso deben escribirse de nuevo; el permiso verificado se conserva mientras sea válido.
- El almacenamiento de recuperación queda fuera de las listas explícitas de respaldo y transferencia del proyecto.
- Reenvío habilitado después de 60 segundos; cada nuevo código invalida el anterior. La app no puede impedir que Android cierre su proceso, pero implementa restauración del avance.
- Formularios con desplazamiento, separación de 16 dp, campos de ancho completo, botones de al menos 48/56 dp y margen para el teclado.
- Recursos de los nuevos textos en español, inglés, portugués, francés, alemán, japonés y chino simplificado/tradicional; el resto utiliza el recurso de respaldo.

## Protocolo y seguridad del servidor

- `POST /v1/auth/password-reset/request` con `codeFormat=numeric`: código aleatorio de seis dígitos, válido por 15 minutos. La respuesta pública no devuelve el código ni un permiso para cambiar la contraseña. Para correos desconocidos conserva una respuesta genérica.
- Código almacenado como HMAC ligado al correo y a una clave privada del servidor; comparación en tiempo constante. Máximo cinco intentos incorrectos por código y límite distribuido de ocho verificaciones por IP/correo cada 15 minutos.
- `POST /v1/auth/password-reset/verify`: consume el código correcto y genera un permiso aleatorio de un solo uso, válido por diez minutos. El permiso se guarda como hash y se entrega con `Cache-Control: no-store`.
- `POST /v1/auth/password-reset/confirm`: cambia la contraseña y revoca sesiones anteriores. No desactiva el segundo factor mediante recuperación por correo.
- Reenviar invalida códigos y permisos previos. Emisión, verificación y confirmación utilizan bloqueo de la cuenta para serializar operaciones.
- Clientes instalados que no solicitan formato numérico conservan compatibilidad con el token largo anterior y la recuperación manual.
- Gmail SMTP ya configurado en el archivo privado del servidor, modo 600. No hay contraseñas SMTP en la APK, el repositorio o este informe.

## Despliegue y prueba real

Se comprobó que el `server.js` remoto coincidía con el HEAD de esta rama tras normalizar finales de línea. Se creó una copia de respaldo del código y se desplegaron `server.js` y `password-reset-email-code.js` en `/home/alvaro/conectate-local-fallback`; se recompiló y recreó únicamente el servicio API.

Prueba por HTTPS público con una cuenta sintética dedicada (ID `d9800843-aadc-40d0-9ee1-228ce08bb67e`). Cinco correos de seis dígitos recibidos realmente en un alias del remitente; la comprobación de buzón se restringió a ese alias. No se imprimieron códigos, contraseñas, permisos ni tokens.

Comprobaciones aprobadas:

1. Registro y solicitud de código; respuesta sin permiso de restablecimiento.
2. Un código de seis dígitos no permite omitir la etapa de verificación: confirmación directa rechazada con HTTP 401.
3. Código incorrecto o correo diferente: HTTP 401.
4. Reenvío invalida el código anterior: HTTP 401 para el anterior.
5. Código correcto: verificación HTTP 200 y permiso de diez minutos, sin sesión anticipada.
6. Reutilizar el código: HTTP 401.
7. Crear nueva contraseña: HTTP 200, misma cuenta; reutilizar el permiso: HTTP 401.
8. Contraseña antigua y sesión anterior: HTTP 401; contraseña nueva: HTTP 200, ID y username conservados.
9. Código vencido y permiso vencido: HTTP 401. Solo se modificaron vencimientos de la cuenta sintética.
10. Con segundo factor habilitado, el cambio de contraseña exige ese factor, no entrega sesión anticipada y permite entrar con un código de respaldo válido.

El primer envío tardó 3,976 ms y los siguientes 1,069/1,053/1,079/1,082 ms. Verificación válida: 33–41 ms; cambio de contraseña: 355–357 ms; login: 373–386 ms. Son mediciones del entorno de prueba, no garantías para cada usuario.

Pruebas unitarias de código de correo, SMTP, recuperación y segundo factor: **11 aprobadas, cero fallidas**. Comprobación de sintaxis Node aprobada.

Comprobación final de `/health` público: HTTP 200, `ok=true`. Los ocho servicios Docker seguían `running/healthy`: API, PostgreSQL, edge, realtime-gateway, signaling, Redis, coturn y ai-chatbot. Esto comprueba disponibilidad de servicios; no certifica todas las funciones multimedia de la app.

## APK y límites de validación

- APK: `build/Novvin-9.0.2-live-gifts-debug.apk`.
- Versión: **9.0.2-debug**, código **99**.
- Compilación: **BUILD SUCCESSFUL in 10m 57s**.
- SHA-256: `2860C6E0DD99238C6354B2C1F5912EC61189D9E1A89227B2DF1F036784FCBBCD`.
- No se instaló ni se completó el recorrido visual de esta versión: el Motorola no aparece en ADB. Los intentos anteriores de emulador terminaron por un fallo de QEMU en Windows (`0xc0000005`).
- La restauración tras recreación de proceso, la distribución visual y la interacción completa con Gmail están implementadas y compilan, pero **requieren comprobación en un Android operativo**. Las pruebas reales completadas corresponden al servidor, correo, contraseña e inicio de sesión descritos arriba.

## Referencias Android

- [Guardar estado de interfaz y recreación de proceso](https://developer.android.com/topic/libraries/architecture/saving-states).
- [Autofill en Compose](https://developer.android.com/develop/ui/compose/text/autofill).
