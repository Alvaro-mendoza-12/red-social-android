# Dominio propio, correo y recuperación de bandeja — 12 de noviembre de 2026

## Estado de publicación

El dominio adquirido `novvinapp.com` está vinculado al proyecto Vercel existente. En GoDaddy se sustituyó el estacionamiento por los registros A recomendados por Vercel (`216.198.79.1` y `64.29.17.1`) y se configuró `www` como CNAME a `44a7b6d2fbbf11a0.vercel-dns-017.com`. Se conservaron los servidores de nombres y los registros del correo. Vercel emitió el certificado de ambos hosts; `https://novvinapp.com` respondió HTTP 200 con validación TLS. `www` tiene redirección 308 al dominio principal.

La propiedad de dominio `sc-domain:novvinapp.com` está verificada en Google Search Console mediante TXT público. La web del repositorio GitHub se cambió a `https://novvinapp.com`. La publicación del código y el envío del sitemap se registran al finalizar; verificar propiedad no equivale a estar indexado ni garantiza posiciones o plazos en los buscadores.

## Enlaces y presentación pública

Los productores de enlaces web y Android usan el dominio nuevo para videos, perfiles, publicaciones, salas e invitaciones. Se conservan los hosts anteriores en los lectores de enlaces. Se actualizaron canónicos, Open Graph, datos estructurados, sitemap, robots y la información visible de acceso oficial. Las guías existentes describen funciones reales y variantes de búsqueda de la marca; no se añaden listas artificiales de palabras clave ni identidades empresariales inventadas.

Las redirecciones desde el host Vercel se limitan a las páginas públicas y los documentos de rastreo. Las rutas API, WebSocket y aplicación del host antiguo continúan funcionando para los clientes instalados. El transporte público HTTP, el relay WebSocket y la separación de cookies de contenido público reconocen los hosts HTTPS exactos nuevos.

`/.well-known/assetlinks.json` contiene la huella SHA-256 real del firmante del AAB local 9.1.40 / 180, obtenida con `keytool -printcert -jarfile`. No incluye claves ni certificados de depuración. La huella de Play App Signing sigue pendiente de contraste porque puede diferir de la clave local/de subida. Un archivo publicado no prueba por sí solo la asociación de una APK instalada.

Los enlaces compartidos conservan los controles de acceso existentes. Cambiar de dominio no hace público contenido privado ni sustituye las comprobaciones de audiencia o sesión. La transferencia multimedia mantiene la infraestructura propia y el contrato R2 ya activo; no se redirigió a ciegas todo el tráfico móvil a un proxy con límites diferentes.

## Correo profesional

El propietario proporcionó `administracion@novvinapp.com` como contacto oficial. Se incorporó en el pie público, privacidad y datos estructurados. GoDaddy conserva sus MX, SPF, los dos selectores DKIM y DMARC en cuarentena. No se activaron productos de pago adicionales ni se cambiaron las credenciales del proveedor.

Ubuntu quedó configurado para recuperación de cuentas desde ese correo mediante `smtpout.secureserver.net:465` y TLS, usando la última credencial facilitada por el propietario. La contraseña se introdujo por una entrada oculta y se guardó únicamente en la configuración privada del servidor; no está en código, documentos, evidencias ni argumentos de comandos. Los respaldos privados anteriores se conservaron.

La autenticación SMTP funcionó inicialmente con la primera credencial. Tras el cambio del propietario, las comprobaciones de la nueva conexión terminan en `ETIMEDOUT/CONN` o cierre del servidor antes de validar autenticación. Se conserva la última credencial correcta proporcionada; **el envío con esa credencial no se presenta como aprobado**. No se enviaron correos de prueba ni se solicitaron recuperaciones de cuentas reales. La configuración de envío previa está respaldada en `/home/alvaro/novvin-candidates/review119-mail-20261112`; la actualización posterior está en `/home/alvaro/novvin-candidates/review119-mail-current-20261112`.

La foto y la firma del buzón siguen pendientes de completar en la interfaz de Titan. La foto del perfil del proveedor no garantiza que todos los destinatarios muestren el logo junto al remitente; no se promete una asociación BIMI ni se inventa un certificado de marca.

## Servidor conservado con respaldo

Antes de autorizar los orígenes nuevos se respaldaron `.env`, ambos archivos Compose, configuración efectiva e inventario de imágenes en `/home/alvaro/novvin-candidates/review119-domain-20261112-9221650d/backup`. Solo se ampliaron `CORS_ORIGIN` y `SIGNALING_ALLOWED_ORIGINS`, conservando la dirección anterior. Se recrearon API y señalización y se validó/recargó Nginx.

Se conservaron los identificadores de imagen de API `sha256:d8f0347244bcb6fa02a5f299b3964e040218dd88a236e92e7f6012fa44820b97` y señalización `sha256:543dd35388d8ac727985fd90eb79242acb489a54b350251c64ad884a90c37808`. El digest de código público continúa siendo `68fd14dde110aa8323513dcc8bc8f2df732b2722d7ebf6ef8699265ffaa02aab` de la operación 116. Las comparaciones de configuración conservan las funciones activas y todas las opciones ajenas al cambio. No se solicitó una migración de base de datos.

Pasaron ocho verificaciones OPTIONS públicas: sesiones web y señalización para Vercel, dominio principal y `www`, más rechazo de un host impostor en ambas rutas. Son comprobaciones de política de origen; no representan una sesión autenticada ensayada en un dispositivo real.

## Recuperación de conversaciones entrantes

Se corrigieron dos defectos reproducidos con datos ficticios:

- El listener podía guardar un mensaje sin la conversación correspondiente en Room. La bandeja exige pertenencia verificada, por lo que el mensaje solo no generaba una entrada. Un push duplicado también podía retornar antes del descubrimiento del chat.
- La ocultación optimista conservaba el identificador del chat durante toda la vida del modelo. Un mensaje posterior podía seguir excluido de la lista y de sus contadores.

La recuperación obtiene metadatos autorizados cuando falta la conversación, comprueba identificador y participantes y confirma bajo la guardia de cuenta y una transacción Room. No inventa participantes a partir del remitente del aviso. Se solicita también para push duplicado, fuera del breve plazo del callback FCM. El consumidor se conecta antes de iniciar el listener. Si falla la lectura de metadatos se conserva el mensaje para reintentar mediante sincronización.

La ocultación tiene fecha de corte: rechaza instantáneas antiguas y admite mensajes posteriores. Respeta el vaciado local y descarta visitas de cuenta vencidas. Se comprobaron esas causas en código y pruebas aisladas; no se accedió a la conversación personal de la captura ni se atribuye con certeza la causa histórica de ese dispositivo.

La actualización de bandeja web agrupa las ráfagas sin retrasar indefinidamente la primera consulta ni solapar solicitudes de su propia cola; abandonar una cuenta o pestaña cancela sus reintentos pendientes.

## Validación

- Antes de activar la dirección canónica: 76 pruebas JVM en diez clases, sin fallos, errores ni omisiones. Cubren recuperación de pertenencia, duplicados, ráfagas, metadatos ajenos, cambios de visita, corte de eliminación y reintento; también las regresiones existentes de bandeja/listener/ocultación.
- Después de activar el dominio: 16 pruebas JVM de enlaces en cinco clases, sin fallos. No se suman a las anteriores como casos únicos porque se solapan.
- Web con el dominio activo en el código: 1.188 pruebas Jest en 186 suites y 121 controles Node aprobados. Incluyen relay WebSocket y migración de páginas públicas sin redirigir rutas API. TypeScript, Vite y el chequeo de 21 páginas públicas pasan; se mantienen avisos de tamaño de fragmento privado. ESLint anterior terminó con cero errores y siete avisos; se contrasta el resultado final después de la migración.

La revisión integral continúa abierta. No se generó una APK nueva ni se actualizó el teléfono de la captura. Los paquetes históricos no contienen automáticamente estos cambios. Permanecen pendientes el ensayo de la nueva entrega instalada, huella Play App Signing y los módulos aún no revisados. Los contadores de filtros y los iconos adicionales solicitados se continúan después de publicar esta migración del dominio.
