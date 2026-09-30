# Recuperación automática con Gmail: activación y pruebas

Fecha: 29/09/2026. Trabajo: `D:\red social - live-gifts`, rama `feature/live-gifts`.

## Configuración del servidor

- Remitente: `Novvin <novvin.recuperacion@gmail.com>`.
- SMTP Gmail, puerto 465, TLS; contraseña de aplicación en el archivo privado `.env` del servidor, modo 600.
- `PASSWORD_RESET_DELIVERY=smtp`. Se comprobó `transport.verify()` antes de modificar la configuración y se recreó únicamente el servicio API.
- No se guardan credenciales de correo en código, recursos Android, documentos ni commits.
- No se usa Gmail API ni se migran las cuentas a Firebase. La autenticación y los códigos siguen en el backend propio.

## Android

La recuperación por correo pasa a ser la opción principal de «Olvidé mi contraseña»: correo registrado → enviar código → pegar el código recibido → nueva contraseña → entrar.

El formulario valida el correo, desactiva el envío mientras espera la respuesta y muestra progreso. Hay un botón para reenviar después de 60 segundos; cada nuevo código invalida el anterior. Las contraseñas que ya se escribieron se conservan al reenviar. Se separan las alternativas «También perdí acceso al correo» (revisión manual) y código de respaldo guardado. Las nuevas instrucciones tienen recursos en español, inglés, portugués, francés, alemán, japonés y chino simplificado/tradicional; otros idiomas utilizan el recurso de respaldo de Android.

El código recibido es el token aleatorio completo generado por el backend; no se presenta como código de seis dígitos. Se pega desde el mensaje y dura 30 minutos. La API pública no lo devuelve. El restablecimiento por correo no desactiva el segundo factor.

## Prueba real por HTTPS y recepción de correo

Cuenta sintética dedicada, ID `6261a262-f394-4be1-9a7d-838204146590`. Se usó un alias del propio buzón, no cuentas personales. La consulta IMAP se restringió a mensajes dirigidos al alias de prueba, sin marcarlos como leídos. Las contraseñas y códigos de prueba no se imprimieron.

Resultados comprobados:

1. Gmail acepta la credencial SMTP y se reciben realmente cuatro correos de recuperación.
2. La petición responde `delivery=email` y no expone el token.
3. Código incorrecto: HTTP 401.
4. Reenviar invalida el código anterior: HTTP 401 para el anterior.
5. Código correcto: HTTP 200, contraseña actualizada y misma cuenta.
6. Reutilización del código: HTTP 401.
7. Contraseña anterior: HTTP 401; sesión anterior revocada: HTTP 401.
8. Contraseña nueva: HTTP 200; se conservan el ID y username del perfil.
9. Código vencido: HTTP 401 (solo se venció el token de la cuenta sintética).

El primer envío tardó 8,057 ms; los siguientes 1,322, 1,194 y 1,147 ms. Confirmación y nuevo login: 358 y 338 ms. Son mediciones del entorno de prueba, no garantías de latencia para cada teléfono o destinatario. La entrega puede depender de Gmail y de la bandeja de spam.

Se ejecutaron seis pruebas unitarias de SMTP, códigos de recuperación y segundo factor: seis aprobadas, ninguna fallida.

## Alcance

Esta evidencia cubre la recuperación por correo. No certifica todas las funciones de LIVE, salas o llamadas. La revisión manual continúa para quien perdió también el buzón o los medios del segundo factor.
