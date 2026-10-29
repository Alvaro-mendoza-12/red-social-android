# 83 — Correcciones de las nuevas capturas de chat y voz

Fecha: 28/10/2026, America/Bogota. Continúa la [tanda 169](82-carga-multimedia-emojis-y-candidato-169-2026-10-28.md).

## Causas y cambios

- **Respuesta convertida en columna.** La clase `text-bubble` se aplicaba cuando no estaba cargado el mensaje respondido, aunque existiera `replyToId`. El botón de cita ocupaba el ancho del contenedor flex horizontal y comprimía el texto. Privados, grupos y comunidades ahora excluyen cualquier respuesta de ese diseño. Se verifican los tres casos con la cita fuera de la página cargada.
- **Fotos que desplazan el historial.** La tarjeta de carga no reservaba el espacio final de la imagen. El marco se conserva durante carga, éxito y error; la imagen se ajusta sin recortar el original, disponible en el visor. Stickers y álbumes conservan sus tamaños específicos. Las fotos normales solicitan miniaturas de 640 px; el visor conserva el original. La precarga cubre contenido cercano y cancela lecturas de imágenes que abandonan esa zona para liberar la cola acotada. No se descarga todo un historial ilimitado al abrirlo.
- **Video con carga interminable.** Si fallaba el registro de la fuente protegida, nunca se asignaba `src` y por tanto el navegador no emitía un error nativo. StreamingVideo ahora comunica ese fallo tras sus reintentos acotados al feed, que presenta el estado de error y reintento existente. Esto no demuestra que todos los archivos reportados tengan esa misma causa.
- **Foco al volver a seleccionar el mismo chat.** La identidad de conversación no cambia en la vista dividida. Ahora una selección explícita incrementa una solicitud de foco, conservando la protección contra refrescos de fondo que roban el foco.
- **Petición de perfiles 404.** La prueba en la web publicada detectó lecturas de `profiles_public`, una tabla ausente en la capa de compatibilidad Ubuntu. Esas lecturas usan ahora la ruta existente `profiles` y mantienen la proyección pública y el vínculo con la cuenta; el modo Supabase conserva su tabla original.
- **Reconexión de voz.** El cliente agotaba sus intentos tras ocho fallos y reutilizaba el token inicial. Mientras la sala siga activa mantiene reintentos con espera creciente de hasta diez segundos, consulta credenciales actuales en cada handshake y descarta credenciales si la sala dejó de pertenecer a esa conexión. Salir sigue desconectando explícitamente. Los errores de transporte se explican en español. No se atribuye el `xhr poll error` de la captura a una causa única sin registros de esa sesión.

## Validación

- Web: **816 pruebas / 150 suites**, sin fallos. Lint: **0 errores / 7 advertencias**. Compilación y puerta SEO aprobadas; continúan 19 páginas públicas legibles sin JavaScript y exclusión de rutas privadas. Las 96 comprobaciones Node de la tanda anterior siguen siendo aplicables a sus fuentes sin cambios.
- Diseño real a 390 px, con componentes/CSS reales e imagen sintética servida con demora controlada: la coordenada vertical del mensaje siguiente fue **374,015625 px antes y después**. El texto de la respuesta midió 290,125 px de ancho y 21,0625 px de alto. Esta prueba mide estabilidad geométrica local; no representa velocidad de descarga en todas las redes.
- Con autorización del propietario se publicaron también estas correcciones posteriores a las nuevas capturas. Vercel declaró READY el despliegue `red-social-android-7n093eye8-alvaro-s-projects1.vercel.app`, accesible en https://red-social-android.vercel.app. La verificación pública final aprobó **90/90**. En una sesión sintética real de producción se confirmó foco al abrir y al reseleccionar el mismo chat, reutilización de la URL de avatar tras ir a Comunidades/Mensajes y cero fallos en solicitudes de perfiles.
- Voz: sonda autenticada con cuenta sintética, Origin oficial y sin entrar a salas: WebSocket de voz/llamadas/LIVE conectó; polling de voz permaneció **65 segundos** conectado, sin errores. No prueba audio bidireccional, la conexión particular del propietario ni llamadas de larga duración.
- Servidor: 14 contenedores en ejecución, saludables todos los que tienen healthcheck, cero unidades fallidas y sin reinicio pendiente. La imagen del aviso de like en momento deriva de la imagen previamente desplegada y modifica únicamente `moments-routes.js`. Aprobó 17 pruebas aisladas en el host y el preflight de imagen, entorno y restricciones. Con autorización explícita se activó `novvin-api:moment-alert169`, imagen `sha256:f2cb913ec18af7c8b7103dc3cc3aed449b7be03c4fc0e47e76eca7c76ebca8c0`: API respondió ready y se comprobó identidad inalterada del resto de contenedores. No hubo migraciones. Respaldo: `/home/alvaro/novvin-candidates/moment-alert169-20261028/backup-20261029T024054Z`.

## Android 169

La compilación normal de producción **9.1.29 / 169** finalizó. APK y AAB tienen la firma esperada; bundletool, R8 (17 componentes) y alineación de 16 KiB (14 bibliotecas de 64 bits) aprobados. No se subió a Play Store.

Las fuentes Android aprobaron **1.914 pruebas unitarias / 405 suites** y **16 pruebas nativas** en el paquete QA: recorrido de entrada, PDF de 300 páginas, copias y recuperación de documentos, restauración del share y emojis/borradores/portapapeles. El APK firmado de producción también aprobó un recorrido independiente de bienvenida, login, recuperación, primer paso de registro, entrada de invitado, perfil invitado y salida; no hubo un crash Novvin en el buffer observado. Arranque frío medido: 3.976 ms en el emulador API 35; no es una medición de un teléfono físico. El primer intento del script buscaba Perfil como descripción accesible, mientras el paquete firmado lo expone como texto; se corrigió el localizador y se completó el recorrido sin cambiar código de producción.

SHA-256 APK: `dcb2078643adb177fb8ce82afc4cb50ee839f9034a034bace2b6f6f0a5fd468c`.

SHA-256 AAB: `bf1bde874d5b79abd7ec11dd3a7b8bd22366ac1d95cbb87dcae107ad51572705`.

Fuente Android y sello del paquete: `4fd0b236e63e`. Correcciones web: `1de7ae17` en main y `3250990c` en feature/live-gifts. Las correcciones web posteriores no requieren recompilar el móvil. Evidencias privadas en `artifacts/production-continuity-20261028/`, fuera de Git.

Se corrigió también el sello operativo del servidor, que aún anunciaba una revisión anterior: `/ready` identifica ahora `novvin-moment-alert169-20261028`, imagen indicada arriba y digest `5fedbdc4d360c5b778cc5aed65b794b6c47855fcdc4c0c98a60584d31a8e356d` (SHA-256 del listado ordenado de hashes de `src` y `scripts` de la imagen). El sello anterior quedó respaldado y no se necesitó otro reinicio.

La inspección automatizada de la ventana Brave del propietario fue detenida por la revisión automática al no poder verificar su URL con suficiente confianza. No se realizaron acciones en esa ventana. La inspección de una sesión sintética independiente y los ensayos de código continuaron por sus herramientas habituales.

Persisten como verificaciones de campo: FPS en equipos físicos, el PDF exacto del reporte (límite interno de vista previa 32 MiB), entrega FCM al dispositivo destinatario, audio/LIVE entre redes y pruebas previas al lanzamiento de Play Console. Estas evidencias no certifican ausencia de cualquier error en toda la aplicación.
