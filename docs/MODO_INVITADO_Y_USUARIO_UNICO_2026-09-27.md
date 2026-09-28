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

- Compartir perfil usa HTTPS del servidor y metadatos Open Graph cuando el endpoint activo es HTTPS. El enlace muestra nombre, @usuario, descripción y avatar; incluye un botón para abrir la app instalada. El servidor tiene Tailscale Funnel habilitado, por lo que ese acceso es público. La presentación final de la tarjeta depende de la aplicación receptora y su caché. El QR interno conserva `conectate://profile/<id>`.
- El historial de videos vistos en modo invitado no se transfiere a una cuenta registrada. No se crea una cuenta de base de datos para cada visitante; esto evita datos personales innecesarios y deja la sesión temporal limitada a lectura.

## Cierre de los hallazgos adicionales

- El username automático se normaliza con NFKD, elimina tildes y espacios, convierte ñ en n, conserva números y usa hasta 24 caracteres ASCII. La consulta tiene debounce de 400 ms, plazo total de 8 s y conserva la cancelación al seguir escribiendo. Cuando la primera propuesta está ocupada, el cliente elige una alternativa comprobada por el servidor. Las recomendaciones manuales se despliegan con un botón.
- Los formularios conservan datos entre pasos y rotación. Atrás vuelve al paso anterior. Los errores se anuncian mediante una región accesible; Next/Done del teclado siguen el flujo. Una doble pulsación al finalizar no inicia dos registros. Si la disponibilidad cambió durante el registro, vuelve al paso de username y vuelve a comprobarlo.
- El índice único reserva el username dentro de la misma transacción que crea la cuenta. La migración `036_immutable_profile_usernames.sql` añade una protección de base de datos que impide cambiar o retirar un username asignado. La API de compatibilidad también rechaza `null`; reenviar un identificador heredado sin cambiarlo sigue permitiendo editar el nombre visible.
- Las lecturas de detalle y comentarios de invitado aplican la misma privacidad que las listas. Una copia pública importada no puede recuperar contenido que ya tenga una versión local privada. Los comentarios locales sobre contenido importado también se muestran, y una copia antigua no recupera comentarios eliminados.
- Las sesiones de invitado se crean bajo exclusión mutua para evitar altas duplicadas. La API renueva una sesión rechazada una sola vez; las lecturas tienen un plazo total. La interfaz distingue carga, vacío, contenido privado o eliminado, exceso de consultas y fallo de conexión, y ofrece reintentos. Una visita nueva abre Mensajes. La altura del reproductor se adapta a la orientación.

## Cobertura por módulo

En esta tabla, «registro» implica también los permisos de propiedad, membresía o administración que ya exige cada función. El invitado no recibe privilegios persistentes de esos módulos. El [inventario de rutas](INVENTARIO_RUTAS_MODO_INVITADO_2026-09-27.md) incluye las declaraciones del backend y sus controles.

| Módulo o función | Invitado | Control del servidor |
| --- | --- | --- |
| Videos: listado, reproducción y detalle público | Lectura | GET `/v1/guest/videos` y detalle filtrado |
| Videos: publicar, editar, eliminar, capítulos y clips | Registro | `authRequired` y propiedad |
| Videos: comentarios, respuestas, reacciones, favoritos, compartir y reportar | Lee comentarios; escribir requiere registro | `authRequired` |
| Colecciones de videos y ver después | Registro | `authRequired` y propietario |
| Social: publicaciones públicas y detalle | Lectura | GET `/v1/guest/posts` y detalle filtrado |
| Social: publicar, editar, eliminar y remix | Registro | `authRequired` y propiedad |
| Social: comentarios, respuestas, reacciones y compartir | Lee comentarios; escribir requiere registro | `authRequired` |
| Momentos e historias | Lee solo públicos vigentes | GET `/v1/guest/moments`; escribir requiere `authRequired` |
| Música, añadir música a contenido y biblioteca | Registro para operaciones del módulo | `authRequired` |
| Personas: buscar y ver perfiles públicos | Lectura sin correo ni teléfono | GET `/v1/guest/profiles/search` y perfil filtrado |
| Seguir, amistad, bloqueo y denuncias | Registro | `authRequired` |
| Perfil: edición, privacidad, presencia y configuración | Solo identidad temporal | `authRequired`; username permanente |
| Chats: conversaciones y mensajes privados | Solo buscar personas y ver perfiles | `authRequired` y participantes |
| Chats: adjuntos, traducción, reacciones y mensajes programados | Registro | `authRequired` y participantes |
| Grupos: crear, unirse, invitar, administrar y mensajes | Registro | `authRequired` y membresía/rol |
| Comunidades: canales, perfiles internos, roles y eventos | Registro | `authRequired` y membresía/rol |
| Salas de voz, escenario y subtítulos | Registro | `authRequired` y membresía/rol |
| Llamadas y señalización | Registro | Sesión registrada y autorización de sala/participantes |
| LIVE: iniciar, participar, moderar, comentar y gestionar repetición | Registro | `authRequired` y anfitrión/rol |
| LIVE: PK, invitados, puntos y juegos | Registro | `authRequired` y reglas del LIVE |
| Regalos, compras y saldo | Registro | RPC autenticado y validaciones de transacción |
| Juegos, PlayMatch y retos | Registro para participar | `authRequired` |
| Botellas anónimas, proximidad y tarjetas sociales | Registro | `authRequired`; anonimato no elimina autenticación |
| Guardados y colecciones de publicaciones | Registro | `authRequired` y propietario |
| Notificaciones y tokens push | Registro | `authRequired` y propietario |
| IA, búsqueda semántica y resumen de hilos | Registro | `authRequired` y acceso al contenido |
| Estadísticas de creadores | Registro y permisos | `authRequired` y propietario/rol |
| Administración, moderación y revisión de recuperaciones | Administrador | Autenticación registrada y capacidad administrativa |
| Vincular sesión web y administrar sesiones | Cuenta registrada | Validación propia de sesión y prueba de vinculación |
| Registro, acceso y recuperación de cuenta | Flujos públicos con pruebas correspondientes | Credenciales/código, límites de solicitudes y validación de propiedad |
| Subida de imágenes, videos, archivos y firma de acceso | Registro | Control central de autenticación de storage |

No se encontró un colector general de eventos de analítica de uso activo. `CreatorAnalytics` genera informes del creador; no constituye ese colector. No se añadió un proveedor ni una transmisión externa de eventos para este trabajo.

## Archivos y comprobaciones

El flujo reutiliza `RegisterScreen`, `AuthViewModel`, `AuthRepository`, `AuthService`, `LocalFallbackApiClient` y la navegación existente. Los componentes específicos del invitado son `GuestModeApi`, `GuestScreen` y `guest-mode-routes.js`. La identidad pública, búsqueda, perfil y navegación principal se integran con `ProfileIdentity`, `UserRepository`, `ProfileScreen`, `SearchUsersScreen`, `HomeScreen`, `HomeViewModel` y `MainActivity`. Las reglas del servidor están en `username-policy.js`, `server.js`, `data-routes.js`, `postgrest-compat-routes.js` y las migraciones de PostgreSQL.

- Pruebas del backend después de estas correcciones: 247 correctas, 0 fallidas y 1 omitida por plataforma Linux.
- Pruebas específicas de invitado y username: 7 correctas; incluyen la separación del JWT, privacidad de detalle, comentarios y recomendaciones.
- Pruebas Android añadidas: `UsernamePolicyTest`; se ejecutan junto a `ProfileIdentityTest` y `ReadDeadlineTest`.
- Verificación real reproducible: `api/scripts/verify-username-guest-e2e.js`. Crea cuentas exclusivamente sintéticas, comprueba carrera, acceso, edición del nombre, inmutabilidad, lectura pública y 22 rutas de escritura bloqueadas, y limpia sus datos. El resultado de la ejecución se registra al terminar el despliegue.

Las comprobaciones de este documento no certifican que todas las pantallas históricas de la aplicación estén libres de errores. La prueba visual disponible es en emulador; el Motorola debe figurar en ADB para poder comprobar esta versión en ese dispositivo. La vista previa pública se valida como HTML y metadatos; la tarjeta final en WhatsApp no se ha probado enviando mensajes reales.
