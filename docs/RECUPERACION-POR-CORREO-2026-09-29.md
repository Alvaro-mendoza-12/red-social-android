# Recuperación automática por correo en Novvin

Trabajo: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Información consultada el 29 de septiembre de 2026. Importes en USD, antes de impuestos; pueden variar por país.

## Qué existe en el proyecto

El backend propio ya tiene `/v1/auth/password-reset/request`, `/v1/auth/password-reset/confirm` y `password-reset-mailer.js`, con envío SMTP mediante Nodemailer. El envío automático requiere configurar un remitente real en el servidor. La revisión manual sigue disponible para quien también perdió el acceso al correo.

El código lo genera y comprueba el servidor de Novvin. El proveedor de correo transporta el mensaje. Las credenciales del remitente se guardan exclusivamente en el servidor.

## Opciones y costos

| Opción | Pago | Encaje en este proyecto |
| --- | --- | --- |
| Gmail personal por SMTP | Sin suscripción de Workspace. Google puede limitar o bloquear el envío al superar 500 mensajes al día. | Inicio de bajo volumen con cuenta dedicada y contraseña de aplicación, si la cuenta permite generarla. Compatible con el SMTP existente. |
| Gmail API | Uso estándar sin cargo adicional dentro de cuotas. Google anuncia cobros futuros por excedentes; aún no publica todos los importes. | Requiere proyecto Cloud, habilitar API y autorizar el remitente con OAuth. Una API key sola no autoriza a enviar. El adaptador SMTP existente evita introducir esta integración. |
| Google Workspace Business Starter | $8.40 por buzón/mes flexible; $7 por buzón/mes con compromiso anual ($84/año). | Correo propio, por ejemplo `acceso@dominio.com`. Se paga por el buzón del equipo, no por cada usuario de Novvin. El dominio se compra aparte. |
| Resend | Gratis: 3,000 correos/mes, máximo 100/día. Pro: $20/mes para 50,000; excedente $0.90 por 1,000 si se habilita. | Correo transaccional con dominio verificado. Compatible por SMTP. Se cobra por mensajes; reenvíos y avisos también cuentan. |
| Firebase Authentication | Spark: 150 correos de restablecimiento al día; Blaze: 10,000/día, sujeto al plan. | Gestiona usuarios Firebase y envía enlaces de recuperación. Las cuentas actuales están en el backend propio: cambiar una contraseña Firebase no cambia automáticamente la de Novvin. Usarlo exigiría migración o integración adicional. |

Para conservar las cuentas actuales y comenzar sin costo de envío, se puede conectar un Gmail dedicado por SMTP. Para operar con dominio propio y crecimiento, Resend ofrece un plan gratuito útil para el volumen inicial. No hace falta contratar Google Workspace para enviar a destinatarios Gmail.

## Configuración inicial con Gmail

1. Crear o elegir una cuenta dedicada al envío de Novvin.
2. Activar la verificación en dos pasos y generar una contraseña de aplicación si Google permite esa opción para la cuenta. No utilizar la contraseña habitual del buzón.
3. Guardar en la configuración privada del servidor:

```dotenv
PASSWORD_RESET_DELIVERY=smtp
SMTP_HOST=smtp.gmail.com
SMTP_PORT=465
SMTP_FROM=Novvin <remitente@gmail.com>
SMTP_USER=remitente@gmail.com
SMTP_PASS=CONTRASENA_DE_APLICACION
```

4. Reiniciar el API y comprobar envío real, llegada a bandeja/spam, código correcto, incorrecto, vencido, reutilizado y reenvío.
5. Verificar que la nueva contraseña permite entrar a la misma cuenta y conserva los datos, y que la contraseña anterior y las sesiones revocadas dejan de servir.

Actualización del 29/09/2026: se configuró Gmail SMTP con el remitente `novvin.recuperacion@gmail.com`. Se verificaron la autenticación SMTP, la recepción real en el buzón de prueba, el cambio de contraseña y el inicio de sesión en la misma cuenta. La credencial se conserva únicamente en el `.env` privado del servidor (permisos 600), sin incluirse en este repositorio. Evidencia inicial en `RECUPERACION-GMAIL-VERIFICADA-2026-09-29.md`. El flujo actual de seis dígitos, verificación separada y APK 9.0.2 está documentado en [Recuperación en tres pasos](RECUPERACION-CORREO-TRES-PASOS-2026-09-29.md).

## Flujo esperado

`Olvidé mi contraseña → correo registrado → recibir código → introducir código → nueva contraseña → iniciar sesión`.

El código debe ser aleatorio, guardarse como hash, tener vencimiento y servir una sola vez. Se limitan los intentos y reenvíos, y se responde de forma equivalente para correos registrados y desconocidos. Recuperar por correo no debe desactivar automáticamente el segundo factor. Si se perdió también ese factor, se necesita un código de respaldo o la revisión manual de propiedad.

## Fuentes oficiales

- [Cuotas y precios de Gmail API](https://developers.google.com/workspace/gmail/api/reference/quota?hl=en).
- [Límites de Gmail personal](https://support.google.com/mail/answer/22839?hl=es).
- [Contraseñas de aplicación Google](https://support.google.com/accounts/answer/185833).
- [Planes flexible y anual de Workspace](https://knowledge.workspace.google.com/admin/billing/compare-flexible-and-annual-fixed-term-payment-plans).
- [Límites de Firebase Authentication](https://firebase.google.com/docs/auth/limits).
- [Precios Resend](https://resend.com/pricing).
- [OWASP: recuperación de contraseña](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
