# Social, LIVE, regalos y validación de servicios — 2026-09-12

## Dictamen

**No se certifica todavía la preparación integral para producción.** Se corrigieron y comprobaron fallos concretos del cliente y de la API. Que los endpoints respondan, la aplicación compile y las pruebas pasen no demuestra entrega multimedia entre teléfonos, calidad WebRTC, capacidad de carga ni disponibilidad de una infraestructura productiva.

Rama de trabajo: `feature/live-gifts`, repositorio `D:\red social - live-gifts`. Se conservaron los cambios anteriores de la rama; no se publicó todo el repositorio ni se modificaron conversaciones o saldos de usuarios para probar.

## Correcciones implementadas

### Social: contenido estable y carga inicial

- El feed podía recibir la caché dos veces y volver a ordenarse cuando llegaban el perfil y los amigos. `SocialViewModel` ahora utiliza el disco sólo para la primera visualización, solicita después un único snapshot remoto y reserva una nueva selección para el refresco explícito.
- `SocialFeedSnapshot` conserva el orden de los IDs visibles durante actualizaciones de fondo, pero actualiza sus campos y respeta eliminaciones. Una respuesta vacía confirmada limpia el snapshot; un fallo de conexión conserva el contenido disponible.
- `PostRepository.getGlobalPosts` permite desactivar las emisiones intermedias de caché. También persiste un resultado vacío confirmado, para no resucitarlo al reiniciar.
- Cambiar Descubrir/Seguidos limpia el contenido del modo anterior. Cargar perfiles/amigos no reorganiza lo que ya se está leyendo.
- Se separó el indicador de recarga manual del estado de carga inicial: no se muestran simultáneamente el círculo flotante y el cargador de contenido al entrar.
- Las claves de filas eran `post:<id>`, pero el registro de impresiones buscaba `<id>` sin quitar el prefijo. Se corrigió su identificación.
- Se mantienen la recuperación acotada, el restablecimiento del desplazamiento con feed vacío y la hidratación no bloqueante de autores.

### LIVE y regalos

Las pantallas que abre la navegación eran `FigmaLiveHostScreen` y `FigmaLiveViewerScreen`, conectadas a `LiveExperienceScaffold`. El motor de celebraciones existía en otras pantallas, pero este recorrido mostraba principalmente un aviso y miniaturas.

- Anfitrión y espectador ahora montan `GiftCelebrationOverlay`, con el motor de escenas animadas existente: atlas de poses, movimiento, partículas y sonido. No se sustituyen por una imagen estática ni se copiaron recursos de TikTok.
- `LiveGiftSheet` reutiliza el selector común: categorías, vista previa, confirmación, límites, privacidad, selección de receptor y recarga mediante Google Play. Se eliminó el selector duplicado que enviaba directamente al tocar una miniatura.
- `GiftSendCoordinator` guarda el identificador de operación antes de enviar. Si se pierde la respuesta o se cancela la pantalla, comprobar el mismo envío reutiliza la clave. Los toques simultáneos no lanzan compras concurrentes.
- El cliente requiere un comprobante con ID de transacción y saldo confirmado. El saldo visual sólo se actualiza para la cuenta que originó el envío. Un error de red no se presenta como prueba de que no hubo descuento.
- `LiveGiftEventStore` separa historial y presentación: conserva hasta 256 eventos, deduplica recibo local/socket/recuperación y rechaza eventos de otra sesión. `GiftEventQueue` mantiene una cola acotada de animaciones; terminar la animación no borra el historial.
- Al pasar a segundo plano se detienen los sonidos y se descartan celebraciones pendientes. No se reproducen indiscriminadamente regalos históricos al entrar a un LIVE.
- La nueva lectura autenticada `GET /v1/data/live/:liveId/gifts` recupera transacciones confirmadas. Filtra por LIVE, acceso, estado y privacidad antes del límite SQL; no expone saldos ni ingresos del ledger. También se endureció el acceso a supporters.
- Se retiró del resumen del anfitrión una gráfica con valores prefijados y se usa el contador real de reacciones en lugar de contar sólo las partículas visibles.

Socket.IO es la vía rápida, no un ledger durable: su entrega por defecto no garantiza recuperar eventos perdidos durante una desconexión. Por eso se conserva el recibo del servidor y se añade una lectura de recuperación. [Garantías de entrega oficiales](https://socket.io/docs/v4/delivery-guarantees/).

**Límites pendientes de LIVE:** el historial local está acotado y no debe usarse como contabilidad total de una sesión larga. Faltan pruebas visuales en Android real, ráfagas sostenidas, cambio Wi-Fi/datos, anfitrión/invitados, batallas y regalos recibidos en dispositivos separados. No hubo LIVE activo durante los sondeos. No se realizaron compras reales.

### Mensajería y Videos: cambios previos realmente desplegados

La imagen que estaba ejecutándose seguía usando `data-routes.js` con digest `355e425c…`. El archivo del directorio de despliegue era diferente y dependía de `message-insert-store.js`, que no estaba allí. Esto explica por qué no bastaba con comprobar el archivo copiado o su sintaxis.

Se revisó el diff completo y se incorporó exclusivamente el payload pendiente:

- Los reintentos de mensajes directos, grupos y comunidades usan inserción única y devuelven el estado existente. No sobrescriben ediciones, recibos, borrados ni un mensaje de otro autor/conversación/canal. Los visitantes sin pertenencia no pueden publicar en una sala.
- El endpoint de Videos ya aplica `followingOnly`, incluido el mapeo de identidades importadas/locales y las restricciones de visibilidad.
- Las pruebas anteriores de caché multimedia, ACK, avatares, reproducción y recuperación siguen incluidas en la suite Android. No se afirma haber observado su funcionamiento en el teléfono del usuario en esta intervención.

## Despliegue y recuperación

Servidor: `alvaro@192.168.18.22`; sólo se recreó el servicio API. No se reinició Ubuntu ni se restauraron/eliminaron tablas, volúmenes, conversaciones o saldos.

El primer rebuild del directorio remoto falló al arrancar por el módulo ausente. Se restauró inmediatamente la imagen anterior y se verificó `/ready`. Después se prepararon imágenes candidatas sobre la imagen comprobada, con cambios acotados, dependencias completas y pruebas antes de reemplazar la API.

Imagen final: `conectate-api-services:20260912`, manifest list SHA-256 `fbe9be529d538b9f1fc3345c41ea20fb25b9edca6dc85d38c71a26365f65bd68`. La etiqueta usada por Compose es `conectate-local-fallback-api:latest`.

Digests comprobados **dentro del contenedor activo**:

| Archivo | SHA-256 |
|---|---|
| `src/data-routes.js` | `d46d337a69e8cd147d709ea0da584c694e90427d732562268e8604f94bd9a264` |
| `src/message-insert-store.js` | `ea36cebc9f3f23c1c596d331fda487c4e98cb22bcada5f19623fa15fe60482f2` |
| `src/live-routes.js` | `9c6278f308161a7829455864bd0ac1aeb1abdc88251b9cdaa95c9e4027c9dd30` |
| `src/live-gift-events.js` | `96d7f5bffd47360d0dfee950d83213ac4844d8bf900b43d4d9553f3fd73830cd` |

Payload, Dockerfiles y pruebas candidatas: `/home/alvaro/conectate-releases/live-gift-recovery-20260912/`. Respaldo previo: `conectate-api-before-gift-recovery:20260912`. La imagen intermedia `conectate-api-security:20260912` conserva las correcciones de dependencias y LIVE sin los cambios pendientes de mensajería/Videos.

Para revertir exclusivamente el último bloque, etiquetar la imagen intermedia como `conectate-local-fallback-api:latest`, recrear únicamente `api` con `--no-deps` y comprobar `/ready` y las lecturas públicas. Tampoco confiar sólo en el campo histórico `release` de `/ready`; los digests anteriores identifican este parche.

El 13 de septiembre se compararon todos los archivos de `src` y `scripts` del host con los de la imagen. Las únicas diferencias funcionales eran cuatro archivos ausentes en el host: `message-insert-store.js`, `verify-message-insert-store.js`, `verify-service-read-paths.js` y `verify-source-dependencies.js`. Se reconciliaron esos archivos, el Dockerfile y los manifiestos npm, con respaldo `host-api-config-before-reconcile.tar.gz` en la carpeta de release. Los paquetes del host ahora coinciden con el contenedor y la validación de imports pasó montando la fuente del host. Se conservaron los archivos históricos de respaldo; no fue necesario reiniciar ningún servicio. Sigue pendiente unificar los metadatos y el proceso de release para futuras publicaciones.

Se añadió `scripts/verify-source-dependencies.js` al Dockerfile mantenido: resuelve imports CommonJS literales sin ejecutar la aplicación ni conectar a la base, y bloquea módulos faltantes antes de publicar una imagen. No es un analizador completo de imports dinámicos.

## Evidencia de conectividad posterior al último despliegue

Origen observado: `https://worldwide-quotations-reflect-kingdom.trycloudflare.com`.
Son muestras desde el servidor **a través de la dirección pública**, no mediciones de latencia desde LTE del usuario ni una prueba de carga. Los scripts finales miden hasta recibir el cuerpo completo y separan el tiempo hasta cabeceras (`ttfbMs`). Se corrigió también una copia antigua del diagnóstico remoto que sólo contabilizaba cabeceras.

| Lectura | Resultado | Tiempo total de esta muestra |
|---|---|---|
| Social | HTTP 200, 37 publicaciones | 467 ms |
| Videos | HTTP 200, 12 elementos | 935 ms |
| Perfiles | HTTP 200, 20 resultados | 829 ms |
| Imágenes del feed | HTTP 200, WebP | 351–500 ms |
| Video: primer MiB / segundo MiB | HTTP 206, rangos consecutivos coherentes | 1233 / 443 ms |
| Bandeja chats / grupos / comunidades | HTTP 200; 7 / 7 / 3 elementos | 498 / 603 / 576 ms |
| Historial de chat | HTTP 200, 188 mensajes | 522 ms |
| Mensajes programados | HTTP 200, lista vacía | 246 ms |
| Momentos | HTTP 200, lista vacía | 753 ms |
| Salas de voz | HTTP 200, 2 salas | 740 ms |
| Retos propios | HTTP 200, catálogo vacío | 414 ms |
| Juegos | HTTP 200, 30 elementos | 496 ms |
| Capacidades / directorio LIVE | HTTP 200; sin directo activo | 395 / 391 ms |
| Catálogo de regalos | HTTP 200, 24 regalos | 671 ms |
| Regalos sin autenticación / ID inválido | HTTP 401 / 400 esperados | 575 / 545 ms |

Una lista vacía sólo verifica respuesta/autorización, no publicación, reproducción o ciclo completo. No se creó automáticamente un reto ni se enviaron mensajes públicos para llenar los resultados.

## Seguridad y pruebas

Se detectaron cuatro avisos de dependencias de producción de la API: dos altos y dos moderados. Se actualizaron `multer` a 2.3.0, `sharp` a 0.35.4, `csv-parse` a 7.0.2 y la dependencia transitiva `qs` a 6.16.0. Las versiones se comprobaron en el contenedor activo. La auditoría posterior no reportó vulnerabilidades conocidas en ese conjunto; no equivale a ausencia de vulnerabilidades desconocidas.

Referencias primarias: [Multer](https://github.com/expressjs/multer/security/advisories/GHSA-wc9g-mqfw-jrwm), [Sharp/libheif](https://github.com/lovell/sharp/security/advisories/GHSA-rgj7-g3m4-5g8c), [CSV Parse](https://github.com/adaltas/node-csv/security/advisories/GHSA-8cw4-87c7-c6xx).

| Comprobación | Resultado registrado |
|---|---|
| Android, suite unitaria completa | 623 pruebas, cero fallos/errores/omitidas |
| API local después de actualizar dependencias | 122 pruebas: 121 pasan, 1 omitida, cero fallos |
| Imagen Linux final, selección de pruebas de los módulos cambiados | 25 pasan, cero fallos |
| Inserción de mensajes contra PostgreSQL del servidor | 25 comprobaciones en tablas TEMP y ROLLBACK; sin datos reales |
| Consulta SQL de recuperación de regalos | HTTP lógico 200 y lista vacía sobre el esquema real, transacción READ ONLY, identidad/stream sintéticos; no equivale a prueba audiovisual ni de autorización E2E |
| Señalización | 27 pruebas: 26 pasan, 1 omitida, cero fallos |
| Web | 17 suites, 87 pruebas pasan; build TypeScript/Vite correcto con URL configurada |
| Herramientas y contratos de mantenimiento | 60 pruebas pasan |
| Auditoría SQL del manifiesto de release | Cero hallazgos/nuevas infracciones |
| Auditoría estática runtime | Cero hallazgos |
| Auditorías npm de producción API / Web / señalización | Cero avisos después de las correcciones |

La ejecución de **toda** la suite dentro de una imagen runtime mínima no fue una prueba válida del repositorio completo: faltaban los fixtures SQL, archivos Android, Bash y configuración de infraestructura que varios tests leen. Se registró ese intento (91 pasan / 29 fallan), se corrigió la versión antigua del diagnóstico de tiempos que reveló y luego pasó la selección de 25 pruebas pertinente a la imagen. No se presenta aquel intento como una suite completa exitosa.

La auditoría SQL sin manifiesto señalaba definiciones históricas/archivadas; el resultado de release anterior corresponde al comando de CI con `--manifest sql/release-manifest.txt`. No afirma haber comparado todas las políticas efectivamente instaladas en bases legadas.

## APK y validación física

Se generó el APK actualizado `app/build/outputs/apk/debug/app-debug.apk`, variante de prueba `3.3.4-debug`, `versionCode=66`, paquete `conectate.com`, target SDK 36. SHA-256: `3B43455053AE98C9D95B6D6F4CB825750B7C406B811CD1CE5C63E49B5CFF8CA7`. `apksigner verify --verbose` confirmó firma APK v2 válida. No es un AAB de publicación ni una certificación de producción.

La comprobación del 13 de septiembre compiló, generó ese APK y repitió las 623 pruebas sin fallos. **Lint terminó aprobado a las 08:29:53, sin errores y con 195 advertencias de mantenimiento** (181 UseKtx, 12 recursos sin uso y 2 duplicaciones de icono), en `tmp/android-validation-final-20260913.log`. Los tres `SuspiciousIndentation` de la ejecución anterior no se reprodujeron al analizar la fuente estable; no se desactivaron reglas ni se creó baseline. Los cambios posteriores de persistencia multimedia llevan una validación separada en el [informe del 13 de septiembre](23-multimedia-offline-y-restauracion-2026-09-13.md); el hash de este apartado identifica el APK anterior a ese nuevo bloque.

La primera ejecución falló por salidas incrementales DEX inconsistentes; se preservaron sólo los directorios generados afectados en `tmp/generated-dex-backup-20260912` y se reconstruyeron. No se borró código ni se recrearon emuladores. No desinstalar una aplicación existente para forzar una instalación de prueba con otra firma: se perderían sus datos locales.

No había teléfonos en `adb devices -l`. La siguiente puerta requiere dos Android reales, cuentas de prueba y redes distintas:

1. Fotos/videos/audio de ida y vuelta en chat directo, grupo y comunidad; volver a abrir sin red; reintentar después de perder el ACK; respetar borrado y vista única.
2. Social con arranque frío/caliente, cambiar de pestaña, deslizar y refrescar manualmente; sin movimientos de contenido causados por perfiles/amigos.
3. Videos: primer cuadro, reproducción sostenida, precarga, entrada renovada, gesto de refresco superior, pausa manual y recuperación Wi-Fi/LTE.
4. Momentos: foto/video, música del dispositivo/comunidad, mezcla de volumen y publicación visible para el otro usuario; retos y participación; juegos multijugador.
5. Llamadas y salas: audio/video bidireccional, permisos, reconexión, Bluetooth, segundo plano, NAT/TURN y cierre limpio.
6. LIVE: anfitrión/espectador/invitado/batalla, regalos animados recibidos en ambos extremos, reconexión, privacidad y confirmación idempotente con saldo de prueba. Compras sólo en entorno de prueba autorizado.

## Bloqueos de producción restantes

- **Hostname estable:** Quick Tunnels están destinados a desarrollo/pruebas, sin SLA y con un límite de 200 solicitudes simultáneas en tránsito. Hace falta un dominio y un túnel nombrado configurado con acceso del propietario. No son equivalentes a una infraestructura productiva aunque ahora respondan. [Documentación oficial Cloudflare](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/). Seguir el [runbook existente](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md).
- **Pruebas físicas y de carga:** falta demostrar calidad multimedia y funcionamiento de cada opción. No se prometen tiempos instantáneos ni capacidad comparable a plataformas globales con estas muestras.
- **Releases reproducibles:** fuente, scripts y paquetes del host ya fueron reconciliados con la imagen; falta unificar los metadatos y configurar un despliegue único con validación previa y rollback. La discrepancia encontrada no debe repetirse.
- **Seguridad operativa:** falta una revisión independiente y validación completa de firewall. El 13 de septiembre pasó la integridad del respaldo y la restauración aislada de 202 tablas, documentadas en el siguiente informe; todavía falta un simulacro completo que también reconstruya archivos, secretos operativos y aplicación. El usuario SSH disponible no pudo ejecutar `sudo -n ufw status`; no se pidió ni expuso contraseña.
- **Infraestructura:** los ocho contenedores observados estaban saludables. Había aproximadamente 13 GiB de RAM disponible y 158 GiB libres en disco del servidor; estas cifras no prueban escalabilidad ni alta disponibilidad de la mini PC.
- **Publicación Android:** Lint de este bloque pasó; repetirlo para los cambios posteriores, probar el artefacto final, release firmada/AAB, instalación limpia y actualización sobre la versión distribuida antes de Play Store.

Este informe sustituye cualquier inferencia de “está copiado, luego está activo”. Sólo se da por desplegado lo identificado dentro del contenedor y comprobado después de su arranque.
