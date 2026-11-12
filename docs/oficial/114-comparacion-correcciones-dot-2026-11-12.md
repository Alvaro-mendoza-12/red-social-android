# Selección de correcciones de `correcciones-dot`

Fecha: 12 de noviembre de 2026. Se comparó la rama solicitada y se seleccionaron correcciones de Android, web y contratos de API. **La revisión integral del proyecto continúa abierta.** Esta selección responde a la petición expresa del propietario de comparar e incorporar mejoras de ambas aplicaciones; no cierra las fases pendientes de la revisión 101 ni incorpora el plan experimental 105.

## Referencias y alcance real

- Proyecto de trabajo: `D:/red social - live-gifts`, rama `feature/live-gifts`.
- Copia existente de `main`: `D:/red social - copia`.
- La carpeta `D:/red social - correciones` existe, pero contiene la rama distinta `correciones`. No se modificó ni se tomó su único commit propio como sustituto de la rama solicitada.
- Rama correcta encontrada al actualizar referencias remotas: `origin/correcciones-dot`, instantánea **`a9b67814e1fd123ea66b86cc57264eb235e5ff5d`**. No se creó ni modificó un worktree de esa rama.
- Bases de comparación: `main` **`455d26999111116f59ae8bd76dae56a5e137aee3`** y `feature/live-gifts` **`4a62184a34361ba6b22a6d607d13ae6f689fe5a1`**. Sus historiales difieren, pero sus árboles versionados iniciales son idénticos: `92765caedd1818897feb28096e9320c3c26c9d9b`.
- La candidata añade **51 commits** sobre `main`: **195 archivos**, 10.555 inserciones y 2.033 eliminaciones. Se incorporan cambios de **123 archivos**, algunos desde instantáneas anteriores de la misma rama. Otros **72 archivos** quedan fuera. En los archivos seleccionados también se excluyen expresamente bloques posteriores descritos abajo.

El [inventario de decisiones](correcciones-dot-seleccion-2026-11-12.json) enumera los 195 archivos, la decisión y su origen. Es un inventario del diferencial: no certifica lectura completa de esos archivos. En los archivos extensos se examinaron las secciones afectadas y sus consumidores relevantes. La [cobertura versionada](review-coverage.json) distingue lectura parcial, lectura completa y contenido pendiente; ejecutar una prueba no marca su fuente como leída.

No se fusionó toda la rama. Se conservaron los archivos ajenos no versionados de ambos worktrees, el material `anuncio/`, el ZIP existente, los respaldos y los temporales ignorados. No se modificaron `correciones` ni `codex/experiencias-con-proposito`.

## Evaluación del riesgo

El diferencial se considera de **riesgo alto por las áreas afectadas**: autenticación, propiedad de datos, borrado y transporte. La recomendación es **incorporar la selección comprobada y diferir los bloques indicados**, con despliegue del servidor y validación física separados. Confianza alta en los resultados automatizados locales; confianza parcial en integración operativa, concurrencia PostgreSQL real y recorridos de dispositivo.

| Riesgos concretos que motivan exclusiones | Crítico | Alto | Medio | Bajo |
| --- | --- | --- | --- | --- |
| Bloques no incorporados, sin reproducción en producción | 0 | 0 | 2 | 0 |

Esta tabla clasifica dos riesgos de integración explicados abajo; no cuenta como vulnerabilidades confirmadas los defectos históricos, los avisos de herramientas o los escenarios que las correcciones pretenden evitar. No se afirma ausencia de otros defectos.

Secuencia relevante: `455d2699` → seguridad `13593bb3` (9 de noviembre) → Android/web `3a4dc8ef` → correcciones posteriores hasta `a9b67814` (11 de noviembre). El inventario completo contiene 195 decisiones; la revisión de implementación se concentra en los cambios seleccionados y sus dependencias inmediatas, con exclusiones justificadas por contrato o alcance. No se da un porcentaje ficticio de cobertura de recorridos.

## Correcciones incorporadas y valor

### Android: 38 archivos

Se toma la instantánea `a9b67814`, excepto el bloque de tiempo real indicado después.

| Área | Comportamiento corregido | Límite relevante |
| --- | --- | --- |
| Ocultar y vaciar conversaciones | La operación remota y el cambio local pertenecen a la sesión inicial; el borrado local se confirma con una transacción Room y comprobaciones de vigencia. La cancelación revierte la presentación optimista de su propietario. | No constituye una validación física del cambio de cuenta en todos los recorridos de mensajería. |
| Fondos de conversaciones | Claves y archivos de fondo por cuenta y conversación; una preparación de imagen atrasada no escribe la clave de otra cuenta. Se actualizan chat, grupo y comunidad. | Los fondos antiguos sin propietario no se asignan automáticamente a una cuenta. Se conservan y se muestra el fondo predeterminado hasta seleccionar uno otra vez. |
| Amistades y perfil abierto | El borrado bidireccional exige éxito de ambas respuestas. Los resultados de acciones de amistad dejan de modificar una vista abandonada o perteneciente a otra cuenta. | El ticket de perfil comprueba cuenta y revisión de vista; no es por sí solo una garantía completa de sesión A→B→A. |
| Editar perfil | Las secciones envían únicamente sus campos, evitando sobrescribir cambios de otras secciones. Se conservan borradores y adjuntos pendientes; se avisa antes de descartar y se evita fecha de nacimiento futura. | Requiere revisión visual posterior en dispositivo. |
| Crear publicación | Borrador asociado a la cuenta, conservación de datos de adjuntos y aviso de descarte; desplazamiento y teclado del editor. | No se promete persistencia de permisos de archivo después de que Android los revoque. |
| Accesibilidad e idioma | Etiquetas de controles y navegación; nueve recursos de idioma con las mismas 49 claves. | No equivale a una auditoría completa de accesibilidad ni a revisión lingüística por hablantes de cada idioma. |
| Monetización | Explica que los retiros aún no están disponibles; evita presentar un proveedor inexistente como activo. | No habilita retiros ni pagos. |
| Configuración de release | Rechaza orígenes con credenciales, rutas, parámetros, fragmentos, punto final o puertos no autorizados; conserva el puerto TLS previsto. La fixture CI explícita evita cargas Crashlytics. | Sin cambio de versión, nueva APK/AAB ni publicación en Play. |

Fuentes principales: `OwnedChatHide.kt`, `MessageRepository.kt`, `AccountChatBackgroundStore.kt`, `ChatSettingsRepository.kt`, `FriendMutationPolicy.kt`, `ProfileScopedAction.kt`, `ProfileSocialDraft.kt`, pantallas y modelos asociados. Las cinco clases de pruebas añadidas cubren las políticas pequeñas; la suite Android completa se ejecutó con la selección.

### Web: correcciones funcionales sin el rediseño de la candidata

- Se vinculan lecturas y mutaciones a su identidad inicial: transporte, preferencias y borrado de chat, reacciones, encuestas, programados, apodos, cola local, reportes y formularios de cuenta. La cola IndexedDB vuelve a comprobar identidad después de abrir la base y antes de escribir. Los resultados atrasados no actualizan la nueva vista o cuenta.
- Se conserva la contraseña exacta, incluidos espacios, en acceso, restablecimiento y ajustes. El rechazo de origen web muestra una explicación concreta y un enlace fijo a la web oficial; no incluye correo, códigos ni tokens en ese enlace.
- El arranque invitado espera el descubrimiento del backend autorizado y evita inicializaciones duplicadas. Los recorridos públicos de invitado y multimedia permiten credenciales de despliegue únicamente en el gateway oficial del mismo origen; el middleware elimina las cookies antes de reenviar al backend. Las peticiones fuera de ese origen omiten cookies.
- El borrador de Momentos se conserva en memoria durante navegación SPA, con preparación de audio cancelable y publicación vinculada a identidad. Se invalida al cerrar sesión o cambiar de cuenta/proveedor/backend. No se guarda en disco ni publica por navegar.
- El catálogo de juegos utiliza el endpoint existente con paginación, deduplicación, reintento y protección frente a respuestas de búsquedas antiguas.
- Los diálogos de compartir reutilizan el control de foco existente. Se corrigen enlaces visibles de comentarios, títulos de página, límites de coincidencia de rutas, contraste de controles y posición de flechas de video.

Se usan los cambios funcionales de `a9b67814` con dos selecciones anteriores: `FriendRequestsPage.tsx` y su prueba proceden de **`11902007b225cb7b0f1b024e966680136fc63b87`**, con protección de vista y acciones, sin nuevo botón de cancelación; `webParity.css` procede de **`3a4dc8efdd1fd1e52b41c27a1ca89389bf4d0967`**, solo con la corrección de flechas. Se retira también el nuevo helper `cancelFallbackFriendRequest` y su prueba, al diferir esa funcionalidad.

No se toman los diseños posteriores de portada, navegación y Social, sus pruebas visuales, metadatos SEO generados ni cambios del lockfile. No se trasladan funciones del plan 105 a las ramas estables.

### Backend propio: únicamente el bloque de seguridad `13593bb3`

Se incorporan ocho archivos desde **`13593bb315ba8bb5e96c7f45af94d4098d4363f7`**, conservando los contratos de importación existentes:

1. Lecturas de compatibilidad sujetas a privacidad, audiencia, borrado y vencimiento antes de paginar y contar. El seguimiento no permite leer una publicación privada. Una audiencia desconocida de Momentos se rechaza de forma conservadora.
2. Inserciones de compatibilidad con comprobación de colisiones nativas, importadas y de compatibilidad; bloqueo transaccional por recurso y condición de propiedad al actualizar. No se permite cambiar propietario o ámbito inmutable mediante una colisión de identificador.
3. Respuesta de acceso uniforme `401 / INVALID_CREDENTIALS` para cuenta inexistente o contraseña incorrecta, también en migración; comparación bcrypt ficticia para el caso sin usuario.

El cambio de `package.json` solo añade `test:audit-security`; no cambia dependencias ni versión mínima de Node. No se incluye una migración de esquema. **El servidor Ubuntu activo no se ha actualizado en esta tarea**: el código corregido queda preparado en Git y requiere su despliegue con respaldo y comprobaciones operativas. El beneficio de privacidad del servidor no se presenta como ya activo en producción.

Los ensayos de API y web se ejecutaron localmente en Windows con Node 26.13.0; el Dockerfile de producción conserva Node 22.23.4. Esta ejecución local no se presenta como una prueba de la imagen productiva. En los archivos de API y herramientas copiados se normalizan únicamente blancos sobrantes al final del archivo respecto a su instantánea de origen.

Se contrastaron tres escenarios concretos: un usuario autenticado solicita contenido privado ajeno mediante compatibilidad (`postgrest-compat-routes.js:1134`); intenta insertar el identificador de una fila de otro propietario (`postgrest-compat-routes.js:1508` y bloqueo en `1534`); y un cliente anónimo compara respuestas de cuentas inexistentes y contraseñas erróneas en `POST /v1/auth/login` (`server.js:2402`). Las pruebas de privacidad, respuesta y propiedad comprueban rechazo o indistinguibilidad de respuesta según el escenario; no se hizo un ataque contra producción ni se midió una garantía de tiempo constante de la red.

### Verificación de firma Android

Desde `13593bb3`, `tools/verify_android_release.py` rechaza un archivo que contenga entradas sin firmar aunque `jarsigner` también escriba `jar verified.`. Conserva la aceptación de la advertencia normal de certificado de carga autofirmado. Se actualizan sus pruebas.

## Cambios diferidos y riesgos concretos

### Tiempo real Android: no incorporar tal como está

Riesgo medio; cobertura parcial. En `a9b67814`, `UbuntuRealtimeClient.kt:86`, `replaceUserSubscription(userId)` incrementa siempre `userSubscriptionGeneration` y reconecta, incluso si se repite la misma cuenta (`87` y `99`). A su vez, `events`, `eventsOnSubscription` y `publishAcceptedEnvelope` filtran por igualdad con esa generación (`66`). `git blame` atribuye el incremento y la reconexión a `3a4dc8ef`, introducidos para aislar visitas de cuenta.

No es una operación exclusiva del cambio de cuenta: la invocan `CommunityRepository`, `GroupRepository`, `MessageRealtimeSync`, `ConversationMarkersViewModel`, `GlobalSavedMessagesViewModel`, `VideoCollectionsViewModel`, `VideoHistoryViewModel` y `VideoWatchLaterViewModel`. Abrir o refrescar otro consumidor de la misma cuenta puede invalidar eventos admitidos pendientes de consumir. **Es un riesgo deducido del código y sus consumidores; no una pérdida reproducida en dispositivo en esta tarea.**

Se dejan fuera `UbuntuRealtimeClient.kt`, `LiveRepository.kt`, `ConversationReadReceiver.kt`, `MessageRealtimeSync.kt` y las dos pruebas nuevas de ese bloque. Antes de incorporarlo hace falta separar la visita de sesión de una resuscripción idempotente y probar sus consumidores concurrentes. Esto deja pendiente también el beneficio de aislamiento de eventos de ese bloque; no se afirma que el código estable previo ya lo resuelva.

### Estado de amistades y cancelación: primero validar y desplegar el contrato

Riesgo medio; depende de concurrencia y mezcla de versiones. La parte posterior de la candidata cambia estados, migración y concurrencia de solicitudes de amistad, rutas nativas, compatibilidad y moderación. Su nuevo botón web de cancelar depende de ese comportamiento. Contra el servidor anterior, una solicitud que acaba de aceptarse puede coincidir con un `DELETE /v1/data/friends/:requestId` que elimine la amistad (`data-routes.js:14090`, contrato conservado). Se conserva la interfaz previa y se difiere el bloque nuevo completo, incluidas sus dependencias y pruebas específicas. Requiere PostgreSQL aislado con varias conexiones y ensayo del contrato antes de exponer la acción.

### CI, dependencias y diseño

Los cambios de preparación CI, emulador, KVM, diagnósticos y requisitos de runtime se dejan para validación de su plataforma. No se toman cambios de dependencias sin probar su matriz operativa. Los informes históricos y la cobertura de la otra rama no sustituyen evidencia local. Que un archivo esté diferido no significa que carezca de valor; significa que no se incorpora ni se certifica en esta selección.

## Validación de esta selección

| Comprobación | Resultado y alcance |
| --- | --- |
| Referencia Android previa de navegación/video | 156 pruebas, 35 clases, cero fallos y errores. |
| Android `:app:testDebugUnitTest`, JDK 21 | **2.728 pruebas, 513 clases; cero fallos, errores y omisiones.** Incluye la compilación de la aplicación. |
| Android Lint K1 y compilación de pruebas instrumentadas | **Aprobados** en una ejecución de 20 min 35 s. XML de Lint: cero errores, cero fatales y **321 avisos**. Compilar las pruebas no equivale a ejecutarlas en dispositivo. Hay una advertencia de API Compose de pruebas obsoleta. |
| Web Jest, selección final | **1.162 pruebas aprobadas en 183 suites**, cero fallos. Ejecutada después de retirar el helper y sus dos pruebas de cancelación diferida. |
| Web Node | **113 pruebas aprobadas**, cero fallos y omisiones. |
| Web ESLint | Cero errores; siete avisos de hooks. Incluyen archivos previos y referencias de generación en limpiezas; no se desactivaron reglas. |
| Web compilación y SEO | Compilación TypeScript/Vite y prerender aprobados con origen ficticio de pruebas, también después de retirar el helper. SEO: 19 páginas públicas, cero bytes de JavaScript público. Hay aviso de un chunk superior a 1.000 kB. |
| Navegador aislado | Acceso a 360×800 y 1280×800 con datos ficticios y red externa bloqueada. Sin desbordamiento horizontal, foco visible; capturas inspeccionadas. Es un ensayo del acceso, no de todos los flujos web. |
| API `npm test` | **1.058 aprobadas de 1.077, cero fallos; 19 omitidas** por requisitos de entorno, PostgreSQL externo o plataforma. Las omitidas no cuentan como aprobadas. |
| API `npm run check` | Aprobado. |
| SQL de colisiones/propiedad | Se ejecutó explícitamente `verifyCompatInsertDatabase` con PostgreSQL embebido PGlite 0.5.16 en memoria: colisiones nativas/importadas/compat y conservación de datos propios aprobadas. No sustituye PostgreSQL real con conexiones concurrentes. No se usaron datos reales. |
| Verificador de release | **Ocho pruebas Python aprobadas.** |
| Recursos Android | Nueve XML válidos, con las mismas 49 claves. No sustituye revisión visual o lingüística. |
| Integridad de cambios | `git diff --check` aprobado. No se cambian versiones ni se añaden credenciales o archivos locales a Git. |

Una primera invocación de Gradle, con una propiedad sin proteger en PowerShell, falló durante la configuración del plugin Crashlytics. La suite JVM posterior sin esa opción completó correctamente; para Lint se pasó la propiedad K1 como un argumento único entre comillas. No se aisló por separado la causa de aquel fallo de invocación ni se modificó el plugin para ocultarlo. La prueba de contraste web inicialmente falló al leer CRLF de Windows; se normalizan únicamente los finales de línea del texto de entrada y se conservan las aserciones de contraste.

Los logs locales están en `tmp/correcciones-dot-*`, las capturas de acceso en `output/playwright/correcciones-dot/`; permanecen ignorados y no contienen evidencia de despliegue. La dependencia SQL temporal está bajo `tmp/`, fuera de las dependencias productivas.

## Publicación y continuación

El destino de esta selección es `main` y `feature/live-gifts` con árboles versionados iguales, usando los worktrees existentes y sin incluir archivos ajenos. Los identificadores se consultan en el historial de ambas ramas; la igualdad relevante es la de los árboles, no la de sus commits, porque conservan historiales distintos. La confirmación de la publicación remota se entrega al propietario después de subir ambas referencias y verificarlas.

No hay nueva APK/AAB, ejecución en dispositivo o emulador, publicación Play, despliegue Ubuntu ni certificación de todos los recorridos web. Los despliegues automáticos que pueda iniciar Git deben verificarse por separado. La reversión de este bloque debe hacerse mediante un commit de reversión en ambas ramas estables, conservando sus árboles iguales; no mediante un reinicio destructivo de ramas o del servidor.

Pendientes: reescritura idempotente del bloque de tiempo real; contrato nuevo de amistades con PostgreSQL concurrente; despliegue respaldado del bloque API; pruebas físicas de las interfaces móviles y recorridos web afectados; continuación de la revisión integral según 101 y su cobertura por huella.
