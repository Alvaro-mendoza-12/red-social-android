# 180 · Salas de voz desde grupos

Implementación solicitada expresamente por el propietario el 17 de noviembre. Continúa después de [retiros 179](179-retiros-sesion-y-reintentos-duraderos-2026-11-17.md), sobre `main 6d2a4c52` y `feature/live-gifts 3028fc56`. La revisión integral de la APK sigue abierta; este bloque no certifica los módulos pendientes de la matriz 178.

## Comportamiento

Android y web incorporan **Crear sala de voz**, con micrófono, en las opciones del grupo. El diálogo permite incluir a todos o elegir integrantes con avatar, nombre, casilla, selección completa y contador. Excluye al creador y sus identidades históricas. El servidor carga la membresía completa, incluidos integrantes importados que todavía no iniciaron sesión local; una caché de chat no autoriza el envío a todos. Si cambia el conjunto antes de confirmar, solicita revisar la selección.

El servidor crea la sala privada, el registro de intención, las invitaciones y sus notificaciones en una transacción. `group_voice_room_invite` es un aviso normal administrado por el servidor. Conserva creador, grupo y acceso a la sala, sin insertar llamadas ni presencia de los destinatarios. Ignorar el aviso no conecta a nadie. La notificación Android y el centro de actividad web abren la sala correspondiente cuando la persona los pulsa.

Las aplicaciones conservan un identificador duradero por intención antes de enviar. Un fallo de almacenamiento impide HTTP; una confirmación perdida o la reconstrucción del cliente reintentan la misma intención, con los mismos destinatarios. La API deduplica también solicitudes concurrentes y no reactiva una sala terminada. La cola duradera conserva el identificador de cada aviso al reintentar sus transportes; Android publica con la identidad estable y sin pantalla de llamada. La recepción depende de los permisos y preferencias de notificaciones existentes.

Se reutilizan las pantallas y el motor WebRTC reales. Hay participantes, estados de conexión, micrófono, salida e indicadores de voz. El ingreso de grupo comienza silenciado. En navegador ni siquiera se captura micrófono hasta pulsar su control; un permiso denegado permite seguir escuchando y reintentar. Android conserva su autorización de micrófono al ingresar voluntariamente y crea la pista inicialmente desactivada. No se altera el namespace de llamadas ni su flujo de invitaciones.

La lectura y admisión exigen seguir en el grupo y ser creador o destinatario autorizado. El enlace no concede permiso. Estas salas quedan fuera del directorio público y de las lecturas genéricas de compatibilidad de salas/presencia. Los ajustes genéricos no pueden volverlas públicas. La señalización revalida acceso y desconecta una sesión de voz rechazada. La sala permanece con participantes cuando sale el creador; conserva su identidad. Un recolector retira salas vacías con creación de más de cinco minutos y sin actividad/presencia reciente durante dos minutos. La admisión y el recolector comparten exclusión de base de datos. El límite actual del motor reutilizado es de 50 participantes conectados.

## Defectos encontrados durante el recorrido

- El detalle de una sala privada ocultaba sus participantes a un invitado autorizado. La sala de grupo usa su autorización propia para mostrar la presencia.
- Una actualización de micrófono sin socket podía reemplazar el socket persistido e impedir que una salida posterior retirara esa presencia. Las actualizaciones de grupo conservan el socket existente cuando no se envía uno nuevo.
- El primer selector consideraba únicamente usuarios locales y omitía integrantes importados. Ahora conserva sus destinatarios y avisos, con perfiles importados o de compatibilidad.
- Una limpieza de efecto de React podía publicar un error de carga antiguo después de cargar correctamente. Cada ejecución tiene vigencia propia; el ensayo con StrictMode conserva el control.
- El promedio de todas las bandas de frecuencia no detectaba cierto audio audible aunque WebRTC recibiera energía. Se usa energía temporal, un breve sostenimiento del indicador y un temporizador independiente del repintado. Se verificó su sincronización entre los dos clientes.

## Evidencia separada

| Comprobación | Resultado y alcance |
| --- | --- |
| Android, selección directa inicial | 17 casos del repositorio y ViewModel reales: visitas de cuenta, respuesta tardía, cancelación, reintentos duraderos, doble pulsación, plazos y selección. |
| Gradle Audit general | 3.243 casos en 564 clases; dos fallos del nuevo caso de notificación por leer antes de drenar su cola. Compilación Kotlin, Java e inyección Hilt aprobadas. No se presenta ese intento como una batería en verde. |
| Gradle Audit final | 35 casos en cuatro clases, cero fallos/errores/omisiones, y `assembleAudit` correcto. El único delta Android posterior al intento general añade la espera de la cola a la prueba; el código de producción Android es el mismo. Incluye navegación real y avisos Robolectric en API 25/34. Entradas y HEAD estables. |
| API y PostgreSQL aislados | 45 comprobaciones sobre la candidata derivada de la versión instalada: todos/algunos, seis solicitudes concurrentes, notificaciones exactas, silencio inicial, acceso voluntario, ajenos/expulsados/no invitados, directorios, salida, caducidad, reintentos de cola e integrantes importados. |
| Node focalizado | 37 casos de selección, membresía, identidad de presencia, directorio y ajustes de salas. |
| Web | 31 Jest de diálogo, reintentos, cancelación, cuenta, StrictMode, plazos, enlaces e indicador de audio; TypeScript y ESLint focalizado correctos. El empaquetado se registra por separado en `web-build.log`. |
| Chrome con viewport móvil | Interfaz real, API, PostgreSQL y señalización reales. Creación desde tres puntos, selector a 320/390 px, aviso y acceso, cero capturas al recibir/entrar, permiso denegado y recuperación, RTP bidireccional con energía recibida, indicadores de hablantes en ambos clientes, mute, ignorar y salida del creador/último participante. Audio sintético; no es un micrófono físico. |

Registros y huellas en `D:/Novvin-Entregas/evidencias/2026-11-17-group-voice/`. Los fallos de preparación Robolectric directa sin recursos completos se conservaron y se sustituyeron por Gradle real. `isolated-v1`, `before-imported-member-fix`, `production-candidate-http-v2` y `browser-v2` conservan los fallos previos a sus ajustes; `production-candidate-http-v3`, `browser-v3` y `gradle-voice-final` contienen los resultados posteriores.

## Ubuntu y entrega Android

El contenedor activo difería de Git y conservaba funciones de servidor adicionales. El despliegue aplicó exclusivamente el delta de voz sobre su fuente real, sin trasladar esas funciones a las ramas estables ni sustituir contratos de importación. Se conservaron las fuentes pendientes del host. Las imágenes se construyeron desde las imágenes anteriores y el override de Compose cambió únicamente sus dos referencias. El manifiesto de la entrega conserva las huellas exactas; reconstruir después desde las fuentes pendientes del host requiere conciliar ese overlay.

El primer intento se retiró al detectar permisos de lectura incorrectos; se comprobó la restauración de imágenes y salud anteriores. La segunda preparación usa archivos legibles y valida JavaScript como los usuarios de ejecución antes de reemplazar servicios. Se conservaron respaldo de Compose y archivo PostgreSQL verificado, y se comprobó ausencia de sesiones multimedia activas antes del reemplazo. La API y señalización finales están saludables, `/ready` público devuelve 200 y la ruta nueva sin credenciales devuelve 401.

Entrega instalada: `group-voice-1fe2dab42225-p2`, huella de fuentes `1fe2dab422256513b4251c9dbdd0a338baa81b9bb2c61bf53f993aca4fa7f2e0`. Recibos y respaldo permanecen en `/home/alvaro/conectate-releases/group-voice-1fe2dab42225-p2/`. El marcador histórico general del servidor no se usa como prueba de este overlay.

APK nueva de laboratorio: **9.1.43-qa / 183**, paquete `conectate.com.qa`, `D:/Novvin-Entregas/Novvin-salas-grupo-9.1.43-qa-183-2026-11-17.apk`. SHA-256 `3e2702c03fa3d07775b82360847138c2f57505c9c0ce82ff8417590526b3ce38`. Incluye las salas de grupo Android y los retiros 179. Incluye también el cambio local ajeno de CommentItem que estaba presente al compilar; ese cambio y su prueba siguen fuera de esta selección Git. No contiene la galería experimental de errores ni constituye publicación de Play. La coincidencia del número QA con una entrega anterior no implica igualdad del paquete: se identifica por este nombre y huella.

## Pendientes explícitos

Faltan el recorrido entre teléfonos físicos Android/navegador, micrófonos y redes móviles reales, recepción FCM en un dispositivo y cambios de red en hardware. La configuración FCM del servidor se comprobó activa; eso no demuestra recepción física de esta invitación. Las pruebas de grupo no cierran la auditoría general de voz, LIVE, llamadas ni otros módulos.

La promoción de Errores con personalidad sigue coordinada con el otro asistente y no se incluye en este commit. Su vista compilada puede probarse localmente en `http://127.0.0.1:4190/ayuda/errores` y `?codigo=503`; el acceso es desde este ordenador y depende del servidor local. El Preview de Vercel requiere autenticación y la ruta pública estable de la galería todavía no se presenta como disponible.
