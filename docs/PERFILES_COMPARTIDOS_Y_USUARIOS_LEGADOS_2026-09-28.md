# Perfiles compartidos y @usuarios legados — 28/09/2026

Rama de trabajo: `feature/live-gifts` en `D:\red social - live-gifts`. La carpeta `D:\red social - copia` no se usó para estos cambios.

## Cambios

- Android 3.3.24 (código 86) comparte una sola URL HTTPS para permitir que la app receptora solicite la vista previa. El QR interno sigue abriendo el perfil en Novvin.
- La página pública entrega título, descripción y una imagen Open Graph de 1200 × 630 con foto, nombre y @usuario. Si la foto no es pública o no se puede leer, aparece la inicial. Se usa un origen de imagen permitido y límite de tamaño para evitar solicitudes a direcciones arbitrarias.
- Los identificadores importados sin origen `chosen` o `migrated_name` dejaron de mostrarse como @usuarios en la vista pública. Esto cubre los antiguos derivados del correo, incluso con sufijos.
- Se corrigió el contador de solicitudes de amistad: cuenta solicitudes pendientes no vistas, por cuenta, y se limpia al entrar tras una carga correcta. Antes contaba cuatro solicitudes ya confirmadas.

## Migración de cuentas existentes

- Antes de escribir se comprobó: 70 cuentas locales, 24 perfiles con otros ajustes, cero @usuarios permanentes, 54 perfiles importados. Se ejecutó el modo de simulación: 70 asignaciones, una coincidencia con sufijo y un perfil importado sin cuenta local (`Test User`).
- Respaldo PostgreSQL previo: `/home/alvaro/conectate-local-fallback/backups/legacy-usernames-2026-09-28-before.dump`, SHA-256 `878d665e144ee3f5b0f899010882dcd1bbf52e16031ac8ad400d48421486b0ac`. Se comprobó con `pg_restore -l` que contiene cuentas y perfiles.
- La transacción de migración asignó 70 identificadores únicos a 70 cuentas; la cuenta oficial conservó `@novvin`. Se comprobó que los 70 tienen origen `migrated_name`, que no existen duplicados y que el disparador de inmutabilidad sigue habilitado. El perfil importado sin cuenta local no se convirtió en cuenta nueva.
- La migración reproducible está en `api/scripts/migrate-legacy-usernames.js`: por defecto simula; `--apply` escribe después de un respaldo. El identificador oficial reservado está limitado al ID conocido de la cuenta oficial.

## Comprobaciones

- Android: `:app:assembleDebug :app:testDebugUnitTest`: 874 pruebas, 192 suites, cero fallos, errores o saltos. APK `Novvin-3.3.24-debug-v86-99d4239.apk`, SHA-256 `138E553BA5D8F1CA5CD8B8B23ADD312EF67FC99995E5692D3056906849E36A81`, instalada con actualización en Motorola `ZY22JSH8CF`, sin borrar datos. Mensajes abrió al iniciar. En Social ya no apareció el falso 4 de solicitudes confirmadas.
- Backend: `npm test`: 253 correctas, una saltada, cero fallos. La prueba real de registro, invitado, perfil y recuperación pasó 78 comprobaciones y retiró sus cuentas sintéticas; quedaron 70 cuentas y 70 identificadores únicos. API reconstruida y saludable. `/ready` público respondió con acceso a PostgreSQL; `/v1/public/profiles/novvin?preview=2` respondió con título `Novvin (@novvin)` y la imagen JPEG pública respondió 200. La URL antigua por ID también muestra `@novvin` sin identificador derivado del correo; el identificador anterior derivado del correo responde 404. La búsqueda pública de `keiver` devolvió nombre `keiver joya` y `@keiverjoya`.
- Se inspeccionó visualmente la tarjeta renderizada. La primera imagen mostró caracteres cuadrados por falta de fuentes en el contenedor; se instaló `font-dejavu`, se reconstruyó API y la segunda imagen mostró texto y foto correctamente.

## Límite de la comprobación

WhatsApp decide si muestra la vista previa y mantiene su propia caché. La tarjeta, la URL y la imagen respondieron desde Internet en esta PC, pero no se envió un mensaje real desde el Motorola para confirmar la renderización final de WhatsApp. El servicio de correo para recuperación sigue sin credenciales de remitente configuradas; se mantiene la recuperación manual.

## Continuación: perfiles, galerías y stickers

- La página del perfil compartido muestra una tarjeta de perfil adaptable y ofrece «Ver en la web», «Abrir en Novvin» y descarga. La vista web permite consultar publicaciones públicas del perfil. Las publicaciones con varias fotos muestran una galería horizontal con indicación para deslizar. Se corrigió la lectura de publicaciones antiguas cuyas fotos están guardadas como objetos `{url, media_type}` y no solo como cadenas.
- El modo invitado de Android abre una publicación social con varias fotos en un visor desplazable; también ofrece botones anterior/siguiente y un contador visible. El tamaño del visor se adapta a pantallas bajas o en horizontal. La API de invitado normaliza ambos formatos de almacenamiento de fotos para que Android reciba una lista uniforme de URL.
- Se corrigió el rechazo del servidor al reenviar un sticker guardado desde un chat. Ahora permite reutilizarlo cuando el objeto aún existe y el usuario tenía acceso al mensaje original; se conserva la verificación de acceso al chat, mensajes eliminados, contenido de una sola vista y bloqueo. El selector de stickers aprovecha el alto disponible en horizontal, donde antes la rejilla quedaba fuera de la pantalla.
- Se comprobaron cuatro stickers guardados en la base real sin enviar mensajes: tenían mensajes de origen y la autorización de reutilización fue positiva para los participantes. Esta comprobación confirma las condiciones del servidor; el envío real desde el teléfono queda pendiente para evitar mandar stickers a otro usuario durante la prueba.

### Verificaciones de la continuación

- Backend: `npm test` terminó con 258 pruebas, 257 correctas, una saltada y cero fallos. La prueba integral de registro, invitado, perfil y recuperación pasó 78 comprobaciones. La API actualizada respondió `200` en `/ready` con PostgreSQL disponible.
- Una publicación pública real de cinco fotos (`53a5a2dd-605e-49f8-b768-be5a076e44e3`) mostró las cinco imágenes en la página compartida; la API de invitado autenticada devolvió cinco URL de texto. La página pública respondió `200` y la revisión de navegador móvil no mostró errores de consola. Se revisó la captura a 390 px de ancho. La vista previa en WhatsApp aún depende de la caché y decisión de esa aplicación.
- Android 3.3.27 (código 89): `:app:assembleDebug :app:testDebugUnitTest` terminó correctamente: 874 pruebas en 192 suites, cero fallos, errores o saltos. APK `build/deliverables/Novvin-3.3.27-debug-v89-9d7c32a.apk`, SHA-256 `1131F2338A9F46890D21D57740C964B3AD7DC805AF042A2661011154864A338C`. Se instaló sobre la versión anterior en el Motorola `ZY22JSH8CF`, sin borrar datos; conservó la sesión y abrió en Mensajes.
- Se capturó el selector de stickers en vertical (`build/motorola-v89-sticker-portrait.png`) y horizontal (`build/motorola-v89-sticker-landscape.png`). En vertical las pestañas se desplazan sin comprimir «Explorar»; en horizontal se ven búsqueda, pestañas y rejilla de stickers a la vez. Se restituyó la orientación original del teléfono tras inspeccionarla.
- No se cambió el teléfono a modo invitado, para conservar los datos y la sesión de la cuenta; el modo invitado se verificó por compilación, pruebas de API y la página pública. No se envió un sticker real a otra persona, aunque la autorización se verificó con datos reales y las pruebas del servidor pasan.

