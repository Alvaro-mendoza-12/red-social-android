# Auditoría y correcciones de video móvil — 27 de septiembre de 2026

## Alcance y rama

Trabajo efectuado en `D:\red social - live-gifts`, rama `feature/live-gifts`. La prioridad es la APK Android y su backend de videos. Las pruebas de recuperación de cuenta están documentadas por separado en `IMPLEMENTACION_LIVE_AUTH_2026-09-26.md`.

## Hallazgos corregidos

| Área | Falla observada | Corrección |
| --- | --- | --- |
| Publicación | Un archivo reconocido por extensión podía subirse como MP4 aunque sus pistas no fueran H.264/AAC. | Inspección de cabecera y pistas; los formatos incompatibles pasan por el editor antes de publicar. |
| Publicación | La limpieza fallida de un borrador podía presentar como fallida una publicación ya aceptada. | Limpieza posterior independiente del resultado de publicación. |
| Editor | La selección de miniaturas ignoraba el inicio y duración del clip. El botón de guardar tenía texto blanco sobre fondo claro. | Miniaturas asociadas al recorte y color de texto según tema. |
| Editor | Se impedía importar un video fuente de más de 3 minutos aunque el resultado final permitido fuese de 45 segundos. | Importación de fuentes de hasta 12 horas; el límite de exportación continúa en 45 segundos. |
| Reproducción | El contador local de vistas podía mostrar un valor antiguo tras la respuesta del servidor. | Se utiliza el total confirmado por la API y se conserva el mayor valor durante la sesión. |
| Audio del feed | La vista de sonido controlaba el reproductor del video activo. | Reproductor independiente para la vista previa del sonido. |
| Biblioteca de sonidos | La vista previa empezaba a preparar audio al abrir la hoja, una URL de audio fallida no intentaba el video fuente y una consulta tardía podía repoblar una hoja cerrada. | Preparación bajo demanda, fuente alternativa, error visible con reintento y cancelación de consultas obsoletas. |
| Biblioteca de sonidos | En el emulador, «Usar sonido» quedaba fuera de pantalla al abrir la hoja a media altura. | Hoja expandida al abrir y contenido desplazable para pantallas pequeñas. |
| Menciones | La descripción no sugería cuentas, no abría perfiles ni notificaba al usuario mencionado. | Sugerencias, texto pulsable y notificación del backend con destino al video; solo para publicación pública y cuentas válidas. |
| Comentarios | El teclado cubría la entrada. Las fallas de envío quedaban solo en el registro y podían repetirse toques mientras se enviaba. | Campo visible sobre el teclado, aviso de error, borrador preservado y control de envío en curso. |
| Menú de video | El detalle elegía la versión básica del menú de compartir y omitía la acción de reportar allí. | Uso de la versión completa para mostrar reporte en videos ajenos. |
| Audio en comentarios | El borrador grabado se descartaba incluso si fallaba el envío. | Se conserva para reintentar. |
| Compartir | Los enlaces `conectate.com/video/...` no abrían el video: el dominio redirige a `/lander`. | Enlaces a la ruta publicada `red-social-android.vercel.app/app/reels/:videoId`; se incluye `conectate://video/:videoId` para apertura directa en Android. |
| Compartir hacia la web | Un destinatario sin sesión era enviado al acceso y perdía la ruta del video. | La protección web conserva la ruta original en `next` para volver al video tras iniciar sesión; requiere despliegue web para afectar el sitio público. |
| Detalle | Se veía la fecha ISO del servidor. | Fecha legible según idioma del dispositivo. |
| Detalle histórico | Fechas PostgreSQL antiguas con espacio en vez de `T` aún aparecían como marca temporal completa. | Lectura de la fecha `yyyy-MM-dd` y formato legible también para registros históricos. |
| Encabezado del feed | LIVE, Amigos, Recomendado, búsqueda y crear estaban en tres alturas distintas; el botón de crear ocupaba el video. | Una barra superior compartida; «Para ti» sustituye «Recomendado» en pantallas estrechas para conservar todas las acciones en una fila. El acceso a directos usa solo el icono, sin texto ni caja. |

## Comprobaciones ejecutadas

- La compilación limpia `clean :app:assembleDebug` terminó correctamente. Las pruebas `:app:testDebugUnitTest --tests 'conectate.com.presentation.videos.*' --tests 'conectate.com.data.model.VideoClipPolicyTest'` terminaron correctamente después de las últimas correcciones.
- `node --check infrastructure/local-fallback/api/src/data-routes.js` y `node --test infrastructure/local-fallback/api/tests/data-routes.test.js` pasaron tras agregar las menciones.
- Se desplegó `data-routes.js` en la mini PC y `/ready` respondió HTTP 200.
- En emulador Android, dos cuentas sintéticas comprobaron publicación de MP4, entrega HTTP 206 por rangos, reproducción, vista incrementada, mención en descripción, notificación recibida y apertura del video/perfil desde ella.
- En el mismo emulador, el editor importó un video local de 20 segundos, mostró miniaturas de línea de tiempo y exportó un filtro blanco y negro.
- En el detalle, el teclado quedó por encima de la caja de comentario. El comentario se envió desde la APK; la API lo devolvió con el autor y contenido correctos, y el contador pasó de 0 a 1.
- La ruta web compartida respondió HTTP 200 y existe en `Conectate-web/src/App.tsx`. No se ha comprobado una sesión de destinatario externa ni un dispositivo físico.
- El APK final se instaló en el emulador y el cuadro de compartir de Android mostró la URL de Vercel y el enlace directo `conectate://` correctos.
- El proyecto web compiló con `VITE_FALLBACK_API_URL=https://conectate.tail9b267c.ts.net:8443` después del ajuste de retorno al video.
- La prueba web `loginReturnPath.test.ts` pasó y comprobó que el retorno conserva video, consulta y fragmento. La web volvió a compilar después de extraer esa lógica.
- El backend rechazó con HTTP 403 el intento de la cuenta B de eliminar el video de la cuenta A. La cuenta A lo eliminó con HTTP 200, se retiraron dos objetos de almacenamiento sin fallas y una nueva consulta devolvió 404. Se eliminaron después ambas cuentas sintéticas con la API de eliminación de cuenta (HTTP 200).
- En emulador se abrió el sonido de un video existente, se reprodujo la vista previa independiente, se abrió su video relacionado y la vista aumentó de 131 a 132. «Usar sonido» abrió la subida con autor/título conservados; al seleccionar un clip local de 20 segundos se abrió el editor con pista de música, se exportó y regresó a la pantalla de subida con el sonido seleccionado. Ese clip de prueba no se publicó.
- La compilación posterior a la corrección de hoja/fecha pasó `:app:testDebugUnitTest --tests 'conectate.com.presentation.video.*' :app:assembleDebug` con JDK 17. Se instaló el APK nuevo en el emulador; una cuenta sintética inició sesión contra el servidor, la hoja mostró «Usar sonido» sin arrastrarla y el detalle mostró una fecha histórica como «13 Mar 2026». Se eliminó esta cuenta sintética y se limpiaron los datos del emulador.
- La corrección de la barra superior compiló con `:app:compileDebugKotlin :app:testDebugUnitTest --tests 'conectate.com.presentation.auth.*' :app:assembleDebug`. Se instaló en un emulador y se comprobó la misma fila con LIVE, pestañas, búsqueda y crear a 1080 px. Una primera captura a 900 px reveló que «Recomendado» se partía; la versión final calcula el ancho disponible y usa «Para ti» en ese tamaño. La segunda compilación `:app:assembleDebug` y la nueva captura a 900 px confirmaron la fila sin cortes.
- Tras la petición de dejar LIVE como icono, `:app:assembleDebug` pasó otra vez. La APK nueva inició sesión con una cuenta sintética, cargó y reprodujo video, mostró el icono sin texto ni caja a 1080 y 900 px, y el icono abrió «Transmisiones en Vivo». Se eliminó la cuenta sintética y se limpiaron los datos del emulador.

## Límites y trabajo pendiente comprobable

- El editor conserva el límite de 45 segundos por diseño actual. Una fuente larga puede recortarse; publicar videos largos necesita una decisión de producto y cambios coordinados en APK, backend, almacenamiento y transcodificación.
- La prueba visual del editor cubrió un filtro y un clip; quedan por medir los otros filtros, composición de varios clips, música, pegatinas, texto, orientación, dispositivos lentos y videos dañados.
- La reutilización del sonido se comprobó mediante exportación local y validación de la pista de audio por el editor. No se publicó el clip que incluía audio de una cuenta real; falta comprobar publicación y reproducción de un video derivado usando únicamente cuentas y medios sintéticos propios.
- La web abre el video compartido, pero aún no interpreta `resumeMs`/`endMs` de enlaces a fragmentos. El enlace directo Android sí lleva esos parámetros.
- Las pruebas en emulador y el servidor privado no certifican conectividad en todas las redes móviles, permisos/cámaras de todos los modelos ni carga concurrente. No se declara verificación universal de extremo a extremo.
- El enlace público web depende del despliegue actual de Vercel. Si cambia el dominio, se debe actualizar la configuración de enlaces compartidos y comprobar la ruta pública.
- La corrección web de redirección tras el acceso está compilada localmente; el sitio público aún conserva su versión desplegada anterior. Un destinatario web sin sesión todavía puede perder la ruta del video hasta desplegar este cambio.
- No hay `keystore.properties` de producción en este checkout. El APK generado es de depuración, lleva `android:testOnly=true` y se instaló en el emulador con `adb install -t`; no sustituye directamente una instalación release firmada con otra clave.
- Se reservó `versionCode` 76 y `versionName` 3.3.14 para la siguiente actualización Android. La entrega release sigue pendiente de la clave de firma de producción.
- El APK de depuración más reciente quedó en `app/build/outputs/apk/debug/Novvin-3.3.14-debug.apk` (SHA-256 `9BA833C9D0DA9113CE63C9F1C1BDE74DC8779CF72EAF14221ABA29A6C5EF4B2E`). `aapt` confirmó el código 76 y nombre `3.3.14-debug` antes de esta recompilación.
- La APK 3.3.14 se instaló en el emulador con `adb install -r -t`, arrancó y mostró la pantalla inicial en español. Después se cerró el emulador de prueba.
- Git sigue bloqueado por `D:\red social - copia\.git\worktrees\red-social---live-gifts\index.lock` (archivo vacío y antiguo). La revisión automática impidió retirarlo aun con autorización del usuario; por ello no se ha podido crear el commit solicitado. La misma revisión impidió borrar el archivo local de credenciales sintéticas `.codex-remote-backup/video-qa-session.json` después de eliminar las cuentas. El directorio quedó excluido de Git.
