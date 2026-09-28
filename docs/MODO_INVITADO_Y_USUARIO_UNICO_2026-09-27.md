# Modo invitado e identificador público

Rama de trabajo: `feature/live-gifts` del checkout `D:\red social - live-gifts`.

## Auditoría previa

El acceso existente exigía una sesión de usuario registrado antes de abrir las rutas principales. No había una identidad de invitado, una API de lectura separada ni una pantalla para explorar sin registro. Los perfiles heredados podían usar identificadores derivados del correo, la búsqueda podía mezclar el correo con criterios públicos y el registro no pedía un identificador único permanente. El control de acceso de las rutas de escritura ya dependía de la autenticación del servidor; una pantalla de invitado que reutilizara el token de una cuenta real habría roto esa separación.

## Implementación

- El servidor emite un token de invitado firmado con audiencia `novvin-guests`, distinta de la audiencia de cuentas registradas. Su ID usa UUID aleatorio y su nombre y @usuario se generan sin datos del dispositivo. El token dura 30 días. La identidad se conserva en preferencias privadas de la app hasta que vence o el usuario sale.
- `/v1/guest/session` tiene límite de 20 altas por hora e IP. Las lecturas de invitado tienen límite de 120 solicitudes por minuto e IP. Solo existen rutas GET para videos, publicaciones, momentos, comentarios y perfiles públicos. Las listas combinan contenido local e importado y filtran visibilidad, sensibilidad y vencimiento cuando corresponda.
- La interfaz ofrece Videos, Social, búsqueda de personas, vista de Mensajes y perfil temporal. Al seguir, comentar, reaccionar o enviar un mensaje, muestra una explicación y lleva al registro existente. El token de invitado no autentica en las rutas de escritura de cuentas registradas.
- Al registrarse se borra el token de invitado. Se conservan preferencias generales que no pertenecen a la identidad temporal, como idioma y tema. El perfil abierto antes de registrarse se conserva como destino de navegación; no se transfieren mensajes, publicaciones ni estadísticas de invitado.
- El registro separa nombre visible, que puede repetirse y cambiarse, de @usuario, que debe ser único y permanente. El servidor comprueba disponibilidad, propone variantes y aplica un índice único sin distinguir mayúsculas. Las rutas de edición rechazan cambios posteriores. La búsqueda pública ya no usa el correo como criterio.

## Permisos

| Acción | Invitado | Cuenta registrada |
| --- | --- | --- |
| Ver contenido y comentarios públicos | Sí | Sí |
| Buscar y abrir perfiles públicos | Sí | Sí |
| Mensajes, grupos y comunidades | Solo exploración | Según permisos de la cuenta |
| Comentar, reaccionar, seguir y solicitar amistad | Registro requerido | Según permisos de la cuenta |
| Publicar, subir archivos, iniciar LIVE | Registro requerido | Según permisos de la cuenta |
| Compras, regalos y pagos | Registro requerido | Según permisos de la cuenta |

Las rutas de invitado no implementan mutaciones. Las rutas existentes para acciones persistentes usan la autenticación de cuenta; la audiencia del JWT de invitado es rechazada por ese verificador. La interfaz no representa una autorización de seguridad por sí sola.

## Límites conocidos

- Un enlace de perfil `conectate://profile/<id>` abre la app instalada. La vista previa rica en WhatsApp u otras redes requiere un dominio HTTPS público y metadatos Open Graph; el dominio privado de Tailscale no sirve para destinatarios externos.
- El historial de videos vistos en modo invitado no se transfiere a una cuenta registrada. No se crea una cuenta de base de datos para cada visitante; esto evita datos personales innecesarios y deja la sesión temporal limitada a lectura.
