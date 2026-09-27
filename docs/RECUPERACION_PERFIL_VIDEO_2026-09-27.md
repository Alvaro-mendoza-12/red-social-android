# Ajustes de perfil, videos y recuperación — 27/09/2026

## Perfil

El arroba público se forma a partir del nombre visible, sin usar el correo ni
el nombre de usuario importado de él. A su lado se muestra una referencia
visual de cinco cifras. Esa referencia puede coincidir entre personas; el UUID
completo sigue siendo la identidad única para enlaces y búsquedas exactas, y
está disponible en «Ver ID». No se modifican direcciones de correo ni claves
internas de las cuentas existentes.
Los nombres en alfabetos no latinos conservan sus caracteres. Si el nombre
visible contiene una dirección de correo, se usa otro nombre disponible o
«usuario» para no exponerlo en el arroba.

## Videos

El botón para seguir se superpone al borde inferior del avatar del creador.
Permanece oculto para videos propios y para personas que ya se siguen.

## Recuperación asistida

1. La persona indica la cuenta que recuerda, un WhatsApp o correo accesible y
   detalles que ayuden a comprobar que le pertenece. Los códigos de respaldo
   antiguos son opcionales y no son un requisito para pedir ayuda.
2. El administrador lee la solicitud, selecciona la cuenta y registra una
   comprobación concreta de propiedad. La coincidencia de correo o contacto
   por sí sola no basta para aprobarla.
3. Al aprobar, se genera un código aleatorio de 96 bits y se abre un borrador
   de WhatsApp o correo con el destinatario y mensaje preparados. El
   administrador revisa y envía el mensaje; no hay envío automático de correo.
4. La persona pega el código y elige una contraseña nueva. El servidor anula
   el código usado y todas las sesiones antiguas al completar el cambio.

El código de revisión no tiene una fecha de vencimiento fija, como se solicitó.
Solo se almacena su hash, es de un solo uso y se anula cuando el administrador
genera otro código para esa cuenta o el titular cambia la contraseña por otra
vía. Emitir el código ya no bloquea la contraseña vigente ni cierra sesiones.
Los códigos heredados de seis dígitos para confirmar un contacto conservan
su vencimiento corto, pero la nueva interfaz no los necesita.

La entrega manual requiere que el administrador compruebe que el contacto y
la cuenta pertenecen al solicitante antes de enviar el borrador. El flujo no
afirma verificar automáticamente un correo porque el servidor todavía no
tiene un servicio de correo configurado.
