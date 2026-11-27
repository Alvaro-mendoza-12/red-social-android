# Web activa y APK ARM64 de Android 192

27 de noviembre de 2026. Continúa la revisión de Novvin.

## Android 9.2.1, código 192

La APK para instalación directa con ABI `arm64-v8a` conserva la versión, el paquete y la firma de la [entrega 265](265-android-192-lectura-y-notificaciones-2026-11-27.md). Se comprobaron 2.172 archivos de origen contra el commit `12b93cc677926d90464e85b7a751cb2b14e74cd2`. Los cuatro DEX y las siete bibliotecas ARM64 coinciden byte por byte con la APK universal.

La variante ocupa 87.662.229 bytes (83,6 MiB), una reducción del 59,03 %. La firma, el destino Android 16 y la alineación de 16 KiB pasaron. La prueba de actualización y de invitado del informe 265 se realizó con la universal. Queda pendiente instalar esta variante en un dispositivo ARM64. La entrega para Google Play sigue siendo el AAB del informe 265.

| Archivo | SHA-256 |
| --- | --- |
| `Novvin-9.2.1-192-12b93cc6-arm64-v8a.apk` | `dc03b54474af5c2b2b4637761396906e0a6c631bff57e10bbbfc663fcb838440` |

## Acceso público y despliegue

La zona de GoDaddy ya publica la verificación de Firebase Hosting. Firebase registra la conexión, la propiedad y el certificado activos para `novvinapp.com` y `www.novvinapp.com`. Ambos certificados se validaron por HTTPS y vencen el 25 de febrero de 2027. Los desafíos temporales del certificado ya no se requieren.

Se compiló la web a partir de `ac6253a253fe3545a3f10e5fa3602efcf17d64a5`, con 697 entradas controladas verificadas y el origen de la API de Ubuntu. Los controles de la compilación cubrieron las 22 páginas públicas. El despliegue incluye el [ajuste de solicitudes de mensajes](268-solicitudes-web-y-validacion-2026-11-27.md). GitHub completó el control web y de servicios en ambas ramas. [Main](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/45984609982) · [Feature](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/45984610327).

Se comprobó el origen nuevo en el puerto privado 4211 antes de actualizar la raíz de Funnel. Los demás manejadores, el contenido de autorización de Capy y los fragmentos anteriores con nombre versionado se conservaron. El origen del puerto 4203 sigue disponible para revertir. La versión activa sirvió el HTML esperado en el inicio de sesión desde ambos dominios. Las comprobaciones también cubrieron las rutas de juegos, privacidad, eliminación de cuenta, el archivo de asociación de Android y la página 404.

WebKit obtuvo 24 vistas con HTTP 200, incluidas seis con el formulario de inicio de sesión, en tres tamaños de pantalla. La ruta solicitada llegó al formulario. La prueba registró cero errores JavaScript, cero solicitudes fallidas y cero desbordamientos. Las seis respuestas HTTP 503 pertenecen a `/ready`. Queda pendiente el flujo con una cuenta autenticada.

`/health` respondió 200 en Ubuntu antes y después de activar la web. `/ready` se mantuvo en 503. El antivirus de subidas y su actualizador registraron estado degradado, y la versión diaria de ClamAV era la 28194 publicada por el proveedor. La auditoría integral sigue abierta.
