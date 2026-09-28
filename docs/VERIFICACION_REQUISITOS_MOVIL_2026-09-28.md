# Verificación de requisitos móviles — 28/09/2026

Checkout: `D:\red social - live-gifts`. Rama: `feature/live-gifts`.

Este informe amplía `MODO_INVITADO_Y_USUARIO_UNICO_2026-09-27.md` y `CIERRE_INVITADO_USUARIO_2026-09-28.md`. La revisión separa implementación, pruebas del servidor, pruebas automatizadas Android y observación en pantalla. Ninguna de estas comprobaciones, por sí sola, certifica todas las funciones históricas de la aplicación.

## Entrega comprobada

- Android **3.3.22-debug**, código **84**, código de producto **f906b53f24c1**.
- APK: `build/deliverables/Novvin-3.3.22-debug-v84-f906b53.apk`.
- SHA-256: `4008ABE1699FEA6A9D51F53A63C2C44C68865D4AD1DA79A5205DE061E57B3E42`.
- `:app:assembleDebug :app:testDebugUnitTest`: **BUILD SUCCESSFUL**, 871 pruebas en 191 suites; cero fallos, errores u omisiones. Log: `build/codex-3.3.22-delivery-build.log`; resultados XML: `app/build/test-results/testDebugUnitTest`.
- Instalación con `adb install -r -t` en Motorola moto g04, Android 14, y emulador de QA. El hash del `base.apk` instalado en el Motorola coincide con el artefacto. No se borraron los datos ni se cambió la contraseña de una cuenta personal.
- Esta revisión usa la rama `feature/live-gifts` y el checkout indicado arriba. No se trabaja en `D:\red social - copia`.

## Novedades de las cinco secciones

| Sección | Fuente real | Cuándo deja de avisar |
| --- | --- | --- |
| Mensajes | Conversaciones directas, grupos y comunidades con mensajes pendientes. | Al leer las conversaciones; entrar en Mensajes no basta. |
| Videos | Videos de personas seguidas posteriores al último contenido cargado en Amigos. | Al cargar correctamente Amigos con la app visible. La fecha del contenido cargado evita borrar avisos de publicaciones posteriores. |
| Social | Publicaciones de seguidos y momentos vigentes sin ver de personas seguidas. | Publicaciones: carga correcta de Seguidos. Momentos: visualización del momento o vencimiento; entrar en Social no basta. |
| Juegos | Invitaciones entrantes pendientes, conexiones aceptadas y coincidencias vigentes. | Cuando se carga la lista con Juegos visible. Se registran las filas cargadas, no una lectura individual de cada tarjeta del viewport. Cambiar su estado genera otra revisión del aviso. |
| Perfil | Notificaciones personales sin leer. | Al leerlas; abrir Perfil no las marca automáticamente. |

Los indicadores no simulan actividad. Las preferencias se separan por ID de cuenta. Las consultas se cancelan al salir de la pantalla principal o cambiar de cuenta, tienen un máximo de 12 segundos por operación y se ejecutan independientemente. El refresco periódico es cada dos minutos mientras la pantalla principal está activa. No es una promesa de actualización instantánea de Videos/Social/Juegos. Perfil y Mensajes aprovechan además los flujos de notificaciones y conversaciones existentes.

La barra del invitado ahora conserva Mensajes, Videos, Social, Juegos y Perfil. Juegos explica que hace falta una cuenta; el invitado no consulta invitaciones ni notificaciones privadas. La búsqueda pública de personas continúa dentro de Mensajes. No se habilitan transacciones ni escrituras para invitados.

### Identificadores nuevos y cuentas anteriores

La unicidad y permanencia descritas para el registro se aplican al username elegido y guardado como identificador canónico. Para cuentas antiguas cuyo identificador se derivaba del correo, se oculta ese dato en las vistas públicas y se muestra una alternativa basada en el nombre. Esa alternativa visual heredada no queda certificada como identificador global único y permanente: una migración completa de esas cuentas sigue pendiente. Las operaciones siguen utilizando el UUID real de la cuenta. El ID de cinco dígitos mostrado en perfil es una referencia visual, no una clave única de base de datos.

El autor del video muestra el nombre visible sin añadirle un `@` ficticio. Sigue siendo pulsable para abrir el perfil; perfil, búsqueda y menciones emplean la identidad pública correspondiente. No se muestra un correo como autor público.

## Nombre visible y @usuario: trazabilidad de los 20 requisitos

| Requisito del texto adjunto | Implementación / evidencia |
| --- | --- |
| 1. Nombre visible separado | Paso de nombre en RegisterScreen; edición posterior de perfil. El servidor admite nombres repetidos. |
| 2. Normalización | UsernamePolicy y username-policy.js: NFKD, tildes, ñ, espacios, números y máximo de 24 caracteres ASCII. |
| 3. Disponibilidad real | API `/v1/auth/username/availability`, consulta parametrizada y comprobación al registrar. |
| 4. Sugerencia al entrar | Se deriva del nombre; si está ocupada, se toma una alternativa comprobada por el servidor. |
| 5. Edición antes de registrar | Campo editable; la disponibilidad anterior deja de autorizar otro texto. |
| 6. Validación mientras escribe | Debounce 400 ms, cancelación, validación local y plazo remoto de ocho segundos. |
| 7. Continuar y carrera final | Comparación con el username exacto comprobado; índice único y transacción. Prueba de registro concurrente. |
| 8. Recomendaciones | Botón que despliega alternativas filtradas; pruebas consultan su disponibilidad. |
| 9. Dos identidades distintas | Nombre visible puede repetirse; @usuario identifica de forma única sin exponer correo. |
| 10. Identificador permanente | API normal y compatibilidad rechazan modificación/remoción; trigger de base de datos. |
| 11. Formato | 3–24 caracteres: letras ASCII, números, punto, guion y guion bajo; unicidad sin distinguir mayúsculas. |
| 12. Reservados | Lista mantenida en el backend y filtro básico de términos bloqueados. No se presenta como moderación lingüística exhaustiva. |
| 13. Seguridad | Índice, trigger, consultas parametrizadas y límites de solicitudes. Pruebas mediante HTTP y SQL directo. |
| 14. Mensajes comprensibles | Disponibilidad, ocupado, formato y conexión se explican junto al campo. |
| 15. Estados | Vacío, longitud, caracteres, consulta, disponible, ocupado, fallo/reintento, alternativas y carrera final están tratados en RegisterScreen/AuthViewModel. |
| 16. Persistencia | rememberSaveable conserva los campos; disponibilidad verificada se vuelve a consultar al recrear. |
| 17. Accesibilidad | Labels, IME, región de error y revisión con texto ampliado. La navegación manual completa con TalkBack sigue pendiente. |
| 18. Auditoría previa | Documentos de arquitectura y matriz de rutas del 27/09; se reutiliza el registro existente. |
| 19. Pruebas | Unitarias Android, integración backend, carrera concurrente y recorridos de registro documentados. Las evidencias distinguen emulador y teléfono. |
| 20. Reporte y límites | Este informe y los informes enlazados contienen cambios, lógica, comprobaciones y pendientes. |

## Invitado: trazabilidad de los 27 requisitos

| Requisito del texto adjunto | Implementación / evidencia |
| --- | --- |
| 1. Explorar sin registrarse | Pantalla GuestScreen y API de lectura pública separada. |
| 2. Identidad automática | Botón Continuar como invitado; sin formulario de datos personales. |
| 3. ID impredecible | UUID aleatorio con prefijo de invitado; sin identificadores del dispositivo. |
| 4. Perfil limitado | Nombre, @usuario generado, avatar genérico e indicación de modo invitado. |
| 5. Secciones | Videos, Social, personas/Mensajes, vista limitada de Juegos y Perfil. |
| 6. Sin operaciones persistentes | Audiencia JWT distinta; rutas protegidas rechazan el token invitado. |
| 7. Personas y mensajes | Buscar/abrir perfil público; enviar mensaje exige registro. |
| 8. Aviso contextual | Explica la acción restringida y ofrece crear cuenta o seguir explorando. |
| 9. CTA directo | Usa el flujo de registro existente. |
| 10. Conversión | Tras registro se elimina la identidad temporal y se abre la sesión completa. |
| 11. Datos conservados | Idioma y tema; no migra mensajes, publicaciones ni estadísticas de invitado. |
| 12. Contexto anterior | Destino público validado de perfil, consumido una vez; no se ejecuta automáticamente una escritura. |
| 13. Backend | Audiencias registradas/invitadas separadas y autenticación central en rutas privadas. |
| 14. Permisos centralizados | RequireGuest, authRequired, guardia de navegación e inventario del backend. |
| 15. Abuso | Límites de altas y lecturas, paginación y vencimiento. |
| 16. Ciclo de sesión | Identidad guardada durante 30 días; cierre no la recrea; salida/conversión invalidan respuestas tardías. |
| 17. Rol explícito | Rol guest verificado al guardar identidad; no se deduce del correo ausente. |
| 18. Invitación en perfil | Crear mi cuenta visible y salida del modo invitado. |
| 19. Menús | La vista de invitado no abre seguridad avanzada, creador, estadísticas ni monetización de cuenta. |
| 20. Rutas e intents | Inventario de rutas, guardia de acceso, deep links nuevos y consumo del intent tras navegar. |
| 21. Errores | Restricción, carga, vacío y fallo de red con reintento; reproductor informa errores. |
| 22. Analítica condicional | No se configura un proveedor externo de analítica de uso. Las métricas de rendimiento existentes no se presentan como un embudo de conversión instalado. |
| 23. Accesibilidad | Modales desplazables, acciones separadas, región accesible y escalado. TalkBack manual continúa pendiente. |
| 24. Recorridos | Lecturas públicas, bloqueos, conservación/conversión y registro cubiertos por pruebas descritas en los informes. |
| 25. Todos los módulos | Matriz de permisos e inventario de 428 rutas del informe del 27/09. |
| 26. Arquitectura existente | Hilt, ViewModels, repositorios y navegación existentes; no se crea otro registro ni autorización paralela. |
| 27. Reporte | Hallazgos previos, cambios, módulos, pruebas y límites en estos tres informes. |

## Comprobación del servidor en esta continuidad

`verify-username-guest-e2e.js` ahora acepta explícitamente la URL por argumento, además de `E2E_API_URL`, y devuelve el origen probado. Esto evita confundir una prueba interna con una prueba por Internet.

- Ejecución HTTPS real: `https://conectate.tail9b267c.ts.net`.
- Resultado: **103 controles correctos**, login de fixture en **584 ms**; no garantiza ese tiempo en todas las redes.
- Incluye registro, username ocupado y permanente, privacidad de identidad, vistas públicas, 22 mutaciones rechazadas al invitado, ocho lecturas privadas rechazadas y autorizadas según rol, recuperación manual, sustitución/consumo de código y recuperación por pérdida del segundo factor.
- Para los indicadores: notificación propia visible, aislamiento entre cuentas, intento de marcar una notificación ajena sin efecto, lectura por el propietario, invitación entrante/saliente de Juegos y cambio de estado.
- Las filas sintéticas de notificaciones e invitaciones se borran en `finally`, igual que las cuentas y solicitudes de recuperación de la prueba. No se envían mensajes ni notificaciones a cuentas reales.
- `/ready`: base de datos accesible y backend `52f948e5c2e5`, release `conectate-mini-pc-20260928T105528Z`. Ocho servicios running/healthy.
- El backend de producción no necesitó cambios funcionales para estos indicadores: reutilizan sus API existentes. El script actualizado se ejecutó por entrada estándar porque el contenedor tiene sistema de archivos de solo lectura.
- Comprobación de cierre a las 17:46 UTC: `/ready` correcto en 247 ms desde esta PC, hora de PostgreSQL presente y el mismo release. Los ocho contenedores seguían `running/healthy`. Las latencias internas mostradas en el panel del Motorola tampoco constituyen una medición del audio o de toda la red del usuario.

Se eliminaron únicamente dos solicitudes antiguas de nuestras pruebas de interfaz, identificadas por UUID y datos sintéticos exactos: `301eb506-1aeb-4da8-b05c-7f5b6e465ecc` (rechazada) y `f3b5c73a-ccf5-4533-954e-5bdc84f7ef3d` (completada). En la segunda se comprobó además que no existiese su cuenta de prueba. Las solicitudes reales y los datos de cuentas reales permanecieron intactos. Las pruebas nuevas no dejaron cuentas, solicitudes, notificaciones o invitaciones sintéticas de ese recorrido.

## Mejoras encontradas e implementadas en esta continuidad

| Hallazgo | Cambio y archivos principales |
| --- | --- |
| Faltaban novedades reales en tres secciones y la barra invitada tenía otra organización. | `HomeNoveltyPolicy`, `HomeViewModel`, `HomeScreen`, `GuestScreen`, `SocialScreen`, `VideoFeedScreen` y `GamesScreen`: fuentes reales, reconocimiento al cargar y las mismas cinco secciones. |
| Un fallo del feed de seguidos podía esconderse tras contenido de caché; respuestas tardías podían cruzar cuentas. | `VideoRepository`, `VideoFeedViewModel`, `PostRepository` y `NotificationRepository`: errores y reintento, lista vacía autoritativa y captura/validación del propietario de la sesión. |
| Social desbordaba con texto grande; Crear se pegaba al botón de añadir una historia. | `SocialScreen`: título con ancho flexible, pestañas en una línea desplazable y altura del carrusel adaptada a la escala de fuente. |
| Los CTA de Juegos perdían contraste; el autor del video fingía un @usuario a partir del nombre visible. | `GamesScreen`: texto negro sobre sus CTA claros. `VideoFeedScreen`: nombre visible pulsable con ancho limitado y sonido en una línea. |
| Administración mostraba fallo antes de terminar la consulta y heredaba la posición vertical de otra pestaña. | `AdminPanelScreen`: estado de consulta explícito, pestaña/detalle conservados y lista independiente por pestaña. |
| Cambiar contraseña superponía líneas al 200 %; recuperación desde Añadir cuenta mostraba el título equivocado y podía abrir a mitad del formulario. | `ChangePasswordScreen` y `LoginScreen`: interlineado explícito, espacio del sistema, altura mínima de acción, encabezado por etapa y posición de desplazamiento independiente. |
| La estrategia temporal de compilación en proceso provocaba reconstrucciones completas. | `gradle.properties`: daemon de Kotlin e incremental restaurados; Gradle 2 GB y Kotlin 5 GB. `ReadDeadlineTest`: se actualizó la expectativa del mensaje DNS ya humanizado. |

Commits de estas últimas correcciones de interfaz/compilación: `8cd4bd7`, `dfc9ec5`, `726252e`, `59aa9b6`, `2639811`, `b9ea1ca` y `f906b53`. Los cambios de novedades, sesión y pruebas precedentes están en `f62a9bf`, `f38be26`, `c108384`, `93f9dc8` y `56bc818`.

El informe del compilador final confirma compilación incremental en daemon. Los tiempos observados antes y después correspondían a entradas distintas y no se presentan como benchmark. El bloqueo de compilación se administra mediante el BuildService cerrado automáticamente del commit `df84586`; no requiere borrar repetidamente el índice de Git.

## Revisión visual de Android

Se inspeccionaron capturas y jerarquías de Android de la APK instalada. Las capturas quedan en `build/`, fuera de Git, porque algunas contienen información privada de la cuenta administrativa. No se envió ningún mensaje real por WhatsApp o correo.

| Dispositivo y recorrido | Evidencia / alcance |
| --- | --- |
| Motorola, Social al 200 % | `motorola-final-social-font2.png`: cabecera y pestañas sin superposición; botón y etiqueta Crear separados. Se observó el indicador real de Juegos en la barra. |
| Motorola, cambio de contraseña al 200 % | `motorola-final-password-font2.png`: título, campos y acciones legibles y separados. Se revisó el formulario vacío; la prueba de cambio efectivo se hizo con fixtures en el servidor, no con la contraseña personal. |
| Motorola, Olvidé contraseña desde Añadir cuenta | `motorola-final-forgot.png`: título correcto, ayuda como acción principal y código guardado opcional con explicación. |
| Motorola, solicitud de ayuda al 200 % | `motorola-final-manual-font2.png` y `motorola-final-manual-bottom-font2.png`: tres secciones, campos, mínimo de relato, explicación de bloqueo y acciones accesibles mediante desplazamiento. El paso se abre desde arriba. No se creó una solicitud personal de prueba. |
| Motorola, panel administrativo | `motorola-final-admin-summary.xml` y `motorola-final-admin-tabs.xml`: estado de consulta seguido de servicios correctos. Tras desplazar Resumen y cambiar a Recuperaciones, se vio su título desde arriba. |
| Motorola, Recuperaciones al 200 % | `motorola-final-admin-recovery-font2.png`: conserva la pestaña tras recreación, tarjeta con estado, cuenta y contacto; abrir detalles funciona. El caso real completado se dejó sin cambios. |
| Motorola, Videos | `motorola-final-videos.png` y `motorola-final-videos-paused.xml` de la APK final: cabecera en una fila con icono LIVE, autor por nombre visible pulsable, cifras y sonido separados. |
| Motorola, Juegos | `motorola-3.3.22-games-font2.png` de la misma versión antes del ajuste final de recuperación: CTA legibles al 200 %. Ese componente no se cambió después. |
| Motorola, cierre completo y reapertura | `motorola-final-cold-messages.xml`, `motorola-final-cold-messages.png`: abre Mensajes y conserva la cuenta activa. El buffer de fallos del proceso actual quedó vacío; los registros históricos de otras instalaciones no se atribuyen a esta APK. |
| Emulador, registro en APK final | `guest-final-username-reserved.png`, `guest-final-recommendations.xml` y `guest-final-username-chosen.xml`: sugerencia desde «Usuario Prueba», error visible para `admin`, Continuar bloqueado, alternativas disponibles y selección de una recomendación. |
| Emulador, registro al 200 % | `guest-final-username-font2.png`: identificador, estado disponible y acción sin superposición. No se finalizó otra alta en este recorrido; el alta/conversión efectiva está documentada para el recorrido previo de 3.3.19 y en el E2E del servidor. |
| Emulador, invitado en APK final | Perfil temporal conservado al cerrar/abrir; cinco secciones. Aviso de Juegos al 200 % con acciones apiladas, separación y desplazamiento. El invitado no tiene indicadores privados inventados. |

La escala de fuente temporal se devolvió a 1,0 después de la revisión. Estas observaciones son de los recorridos listados y no de cada pantalla histórica, cada dispositivo o cada idioma.

## Las diez heurísticas en los recorridos revisados

Referencia: [Nielsen Norman Group](https://www.nngroup.com/articles/ten-usability-heuristics/). Son criterios de revisión; esta tabla no equivale a una certificación general de toda la app.

| Criterio | Aplicación revisada |
| --- | --- |
| 1. Estado visible | Carga, disponibilidad, reintento, conteos y avisos reales en las cinco secciones. |
| 2. Lenguaje familiar | Nombre frente a @usuario; explicación contextual al invitado. |
| 3. Control del usuario | Atrás, cerrar diálogos, seguir explorando y reintentar. |
| 4. Consistencia | Mismas cinco secciones en la barra registrada y de invitado. |
| 5. Prevención | Validación exacta, unicidad, exclusión de doble alta y escrituras prohibidas al invitado. |
| 6. Reconocimiento | Labels y recomendaciones; CTA disponible junto a la explicación. |
| 7. Eficiencia | Debounce, caché existente, consultas independientes y pausa de consultas fuera de pantalla. |
| 8. Claridad visual | Botones separados, contenido desplazable y etiquetas que no invaden otra sección. |
| 9. Recuperación del error | Mensajes concretos y reintento sin perder el formulario o reconocer contenido como visto. |
| 10. Ayuda contextual | Explicación de registro, identificador permanente y acceso limitado a Juegos. |

## Pendientes anteriores que se mantienen explícitos

La migración de identificadores de cuentas heredadas a usernames canónicos elegidos, la traducción completa a todos los idiomas de Android, el audio de salas con dos participantes reales, los casos avanzados del editor de video, la revisión manual completa con TalkBack, la entrega release firmada y el rediseño general de la web no se declaran completados por esta revisión de identidad/novedades. El retiro continúa suspendido.
