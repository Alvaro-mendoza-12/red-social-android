# Ubuntu: tiempo real, amistades y controles de la web

Fecha: 12 de noviembre de 2026. Continúa la [corrección 115](115-tiempo-real-y-cancelacion-amistades-2026-11-12.md). La revisión integral de Android y web sigue abierta. Este bloque acredita los recorridos indicados, no el cierre de todos los módulos.

## API instalada y respaldo

Se desplegó únicamente la API del servidor Ubuntu propio. El marcador público es `novvin-review116-realtime-friends-20261112`, con commit de correcciones estables `5eab22a081631110e136316a49b248d38a34de87` y huella del artefacto `68fd14dde110aa8323513dcc8bc8f2df732b2722d7ebf6ef8699265ffaa02aab`.

Imagen activa: `novvin-api:review116-20261112_5eab22a0`, identificador Docker `sha256:d8f0347244bcb6fa02a5f299b3964e040218dd88a236e92e7f6012fa44820b97`. Su base es la imagen activa anterior `novvin-api:purpose111-20261104_4c4350e0`, identificador `sha256:4b39ad0ca02627aaa1189e9743ea046d51887f80f317f824a6fa70fc120c4100`.

La imagen anterior ya contenía funciones experimentales habilitadas. Se conservaron al integrar las correcciones estables mediante combinación de tres versiones: ancestro `455d2699`, fuente real desplegada equivalente a `4c4350e0` tras normalizar finales de línea y correcciones `5eab22a0`. No hubo conflictos después de esa normalización. El artefacto contiene siete archivos combinados; el resto y las dependencias de producción proceden de la imagen anterior. El manifiesto de fuentes está en `evidencias/116-api-overlay.json` y dentro de la imagen como `/usr/src/app/review116-overlay-manifest.json`.

Esto **no incorpora los experimentos en las ramas estables**. La API instalada es una superposición documentada sobre el servidor existente; su fuente completa no se atribuye exclusivamente al commit estable ni al último commit de la rama experimental. No se sustituyó el árbol de fuentes del host con una copia de `main`.

Respaldo privado previo, conservado en Ubuntu:

`/home/alvaro/novvin-candidates/review116-20261112-ccbe97b8de/backup`

Incluye configuración, marcador anterior, dump PostgreSQL, esquema, roles sin contraseñas, imagen anterior y archivo de almacenamiento. Se comprobaron SHA-256, integridad gzip/tar y catálogo del dump. La restauración en un PostgreSQL aislado y sin red produjo **261 tablas**, sin errores. El dump es una copia lógica consistente; no se afirma una instantánea atómica conjunta de base de datos y archivos con la aplicación detenida. Los datos del respaldo y la configuración privada permanecen exclusivamente en el servidor.

La activación usó el bloqueo Compose, comprobó que la configuración y la imagen no habían cambiado desde el respaldo y preparó reversión a imagen, override y marcador anteriores. Se cambió solamente la referencia de imagen de la API y su marcador. Se recargó Nginx; los identificadores de los otros contenedores y el archivo de entorno quedaron iguales. Se verificaron las siete huellas dentro del contenedor activo y la conservación de los dos indicadores experimentales.

Se comprobaron `/ready` en API, edge y HTTPS público, con la nueva identidad y huella. WSS público negocia TLS y rechaza suscripciones anónimas; el listado de amistades sin autenticación devuelve 401. Los recorridos que escriben solicitudes y mensajes se ejecutaron en una base aislada con identidades ficticias y **la misma imagen de API y el mismo gateway de producción**. No se usaron cuentas de personas ni se enviaron mensajes a usuarios reales.

## Correcciones de interfaz

Se mantiene el diseño y los componentes existentes, sin trasladar el plan experimental 105.

| Control | Corrección |
| --- | --- |
| Asistente | Robot, también en la cabecera de Mensajes. |
| Llamadas | Teléfono, en lugar de cámara de video genérica. |
| Crear espacio | Símbolo de crear, en lugar de micrófono. |
| En vivo | Emisión, distinguible de Videos. |
| Personas cerca | Ubicación, en lugar de globo de Social. |
| Mis reportes / Ayuda | Bandera / ayuda, en lugar de notificaciones / ajustes. |
| Ranking / solicitudes | Trofeo / persona con invitación. |
| Opciones del chat | Diferencia fijar/desfijar, archivar/desarchivar y añadir/quitar favorito. Cerrar chat usa una cruz. |
| Fila de conversación | Botón nativo para abrir y botón hermano para opciones, sin controles interactivos anidados. |
| Opciones de cada contacto | Nombre accesible con el contacto, semántica de menú y superficie de 44 × 44 px. |

Abrir las opciones no abre la conversación. Escape devuelve el foco a su activador; Enter permite abrir el chat desde el teclado. Se conservan las acciones, preferencias y protecciones por cuenta existentes.

## Evidencia de pruebas

| Comprobación | Resultado |
| --- | --- |
| API, Node 22 en Ubuntu y PostgreSQL aislado | **1.110 aprobadas, cero fallos, 19 omitidas**, de 1.129 pruebas. Las omisiones no se cuentan como aprobadas. Incluye las suites estables y las pruebas de las funciones que ya estaban desplegadas. |
| API y gateway por red | Capacidad de cancelación anunciada; cancelación pendiente; aceptación seguida de cancelación rechazada con 409; amistad aceptada conservada; suscripción y entrega real de mensaje por WebSocket. |
| Web, Jest | **1.177 aprobadas en 185 suites**. Después del ajuste final del icono y del test, selección de 16 pruebas aprobadas. |
| Web, tipos y lint de fuentes modificadas | TypeScript aprobado. ESLint sin errores en los tres archivos de producción modificados. Los tests están excluidos de ESLint por la configuración existente. |
| Web conectada al entorno aislado | Login desde formulario; cancelación desde Enviadas y confirmación persistida por API; apertura del chat; evento real recibido y mensaje visible sin recargar en **317 ms**. Esta latencia es una observación del ensayo local, no una promesa del producto. |
| Web visual y teclado | 375, 768 y 1.440 px, temas claro y oscuro: sin desbordamiento horizontal de bandeja y menús; foco restaurado al cerrar; opciones separadas de abrir conversación. |
| Navegación secundaria | Apertura y renderizado de Llamadas, Crear espacio, Personas cerca, Mis reportes, Ayuda y Ajustes a 375 px con datos ficticios. No implica validar todos sus formularios o servicios. |
| Accesibilidad automatizada de la bandeja | Axe 4.10.3, seis combinaciones: cero infracciones detectadas. Hay comprobaciones incompletas de ARIA y contraste; no se presenta como certificación completa. |
| Android, compilación QA | APK e instrumentación `audit` aprobadas; versión **9.1.40-qa / 180**, paquete separado `conectate.com.qa`. Compilación en 25 min 14 s. |
| Android, contrato nativo por red | **Una prueba aprobada** en Android 15 / API 35: cancelación pendiente, protección 409 de la aceptada y dos mensajes reales entregados a un consumidor detenido, conservados tras ocho suscripciones y renovación real del token. Usa `UbuntuRealtimeClient`, el guardián de sesión y OkHttp con confianza limitada al certificado del fixture. |
| Android, selección de interfaz y navegación | **Seis casos aprobados**: cinco de rutas y uno de búsqueda estrecha con texto grande. **El caso de cambio de cuenta no está aprobado**: registra esperas de interfaz y fallo al cerrar la Activity. |

Los primeros intentos del contenedor de pruebas fallaron por contexto de repositorio ausente, falta de Bash/dependencias del servicio de señalización, permisos del material auxiliar y un fixture de borrado que desconocía los módulos ya desplegados. Se corrigió el entorno de pruebas, manteniendo las aserciones de las correcciones estables; no se alteró la API candidata para ocultar esos fallos. El fixture de administración declara explícitamente la ausencia de las tablas experimentales y las suites experimentales cubren sus contratos propios. La ejecución final completa es la reflejada en la tabla.

En el primer ensayo WebSocket, el emisor de prueba usó `clientMessageId` donde este contrato requiere `id`; se corrigió el emisor y se repitió con entrega y comparación del identificador real. El arranque del entorno vacío necesitó el esquema inicial versionado; su ausencia inicial tampoco se atribuye a una regresión de producción. Las notificaciones Firebase no forman parte de este entorno aislado.

El primer arranque instrumentado sufrió un ANR antes de ejecutar el contrato nativo, con presión de memoria en el equipo. Después de terminar los procesos de compilación propios y preparar DEX, el contrato pasó en 0,836 s. Los ensayos de interfaz también encontraron un ANR de System UI. Se repitieron con arranque limpio y recuperación de System UI; el caso `MessagesMultiAccountSwitchingTest` sigue sin poder darse por aprobado. La traza del proceso QA muestra el hilo principal ocupado en `NodeChain.has` y el recorrido de semántica de Compose durante la espera posterior al cambio de cuenta, además del timeout de cierre de `ActivityScenario`. Runtime e instrumentación usan Compose UI 1.11.3. Esto no demuestra todavía una causa de producto o de fixture. Se conserva como pendiente concreto, sin atribuirlo a tiempo real ni ocultarlo en el recuento de aprobadas.

El contrato nativo requiere `-e review116Fixture true`, emulador y los túneles de bucle local 43118/43119; se omite explícitamente sin ese argumento. Su controlador está versionado en `tools/audit/fixtures/review116-stage-controller.js`. Antes de insertar datos comprueba que la base se llama exactamente `novvin_review116_stage`. Requiere esquema inicial, `pg`, `bcryptjs`, `ws`, API y gateway en la misma red aislada, secretos aleatorios ajenos a producción y certificado temporal con SAN `10.0.2.2`. El puerto de bootstrap solo se publica en el bucle local del host. Las credenciales sintéticas se consumen en memoria y no se imprimen ni se incluyen en capturas. El ensayo resuelve una amistad; para repetirlo se provisiona una base ficticia nueva. Nunca se apunta este controlador a producción.

APK QA local: `app/build/outputs/apk/audit/app-audit.apk`, SHA-256 `8ad2431b492c48c752d9a2653969fcde9456cf4b6866ffd5ad92ee1494728352`. Incluye el código Android estable de 115; los cambios de iconos son de la web. Este paquete de diagnóstico no sustituye la entrega firmada histórica.

Evidencia local ignorada: `tmp/review116-*.log` y `output/playwright/review116/`. Incluye vistas anteriores/posteriores, seis combinaciones de bandeja/menú, pantallas de navegación y cancelación y mensaje por red. Los cinco errores de consola del navegador visual inicial proceden de una extensión externa bloqueada; sus intentos de WebSocket con sesión simulada se distinguen del segundo navegador, conectado realmente al entorno aislado. No hay una referencia visual histórica versionada que permita afirmar una regresión visual global aprobada.

## Límites y continuidad

No se generó una nueva entrega Android de producción ni se publicó en Google Play. La aceptación física, muerte real de proceso, avisos en segundo plano, llamadas/audio/video y los demás módulos pendientes no se deducen de estas pruebas. La web se corrige y publica como código en las ramas estables; este bloque no acredita un despliegue verificado del alojamiento público web.

Para una reversión operativa se conservan la imagen anterior y la configuración privada de `backup/`, además del procedimiento concreto de activación/reversión en `activate.sh` dentro de la carpeta del candidato en Ubuntu. Debe hacerse bajo el mismo bloqueo Compose, restableciendo override y marcador, recreando solo API y verificando API/edge/TLS antes de darla por terminada. No se restaura automáticamente un dump antiguo sobre actividad nueva.
