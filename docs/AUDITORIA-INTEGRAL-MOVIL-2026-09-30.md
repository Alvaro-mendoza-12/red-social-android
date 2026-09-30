# Auditoría de Novvin móvil — 30 de septiembre de 2026

Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Esta entrega continúa el trabajo de LIVE, salas, llamadas y los seis puntos del
[informe anterior](MEJORAS-SEIS-PUNTOS-2026-09-30.md).

## 1. Resultado y alcance

Se corrigieron las tres observaciones de las capturas, se habilitó la solicitud
de 60 FPS en LIVE cuando el formato de cámara lo permite, se corrigieron rutas
de conexión y se desplegaron mejoras del servidor. Se verificaron recorridos
reales de autenticación, salas, video, audio RTP, invitado y pantalla compartida.

**No se certifica ausencia de errores en toda la aplicación.** La revisión
combina inventario de todos los archivos principales, análisis estático,
compilación, pruebas automatizadas y revisión manual de los recorridos descritos.
No equivale a leer manualmente cada una de las 283.815 líneas ni a ejecutar cada
pantalla en todos los teléfonos. La matriz de cobertura distingue esas pruebas.

Inventario reproducible: **774 archivos Android y 91 archivos del backend**;
**397 declaraciones HTTP** detectadas. Son conteos léxicos: no incluyen como
rutas HTTP los eventos Socket.IO ni sustituyen una revisión de permisos por ruta.
Los recursos y el manifiesto se incluyen en Lint, además de la revisión específica
de notificaciones, enlaces, copias de seguridad y permisos.

- [Inventario por archivo](AUDITORIA-INVENTARIO-2026-09-30.csv).
- [Inventario por módulo y rutas](AUDITORIA-INVENTARIO-2026-09-30.json).
- Generador: `infrastructure/local-fallback/scripts/generate-application-audit-inventory.py`.

El Motorola no está conectado en esta entrega. Las pruebas instrumentadas usan
`emulator-5554`. Dos extremos WebRTC en ese emulador comprueban señalización y
medios reales, pero no dos teléfonos en Wi-Fi y datos móviles independientes.

## 2. Correcciones entregadas

### Salas de voz

- El avatar conserva su recorte circular; el indicador de micrófono queda fuera
  del contenedor que lo recortaba. Tiene borde contrastante y descripción accesible.
- Las salas nuevas reciben un **código numérico único de seis o siete dígitos**
  asignado por PostgreSQL. El UUID interno sigue identificando registros y navegación.
- La pantalla de éxito muestra y copia el código corto. Siete dígitos caben en
  un ancho de 280 dp con texto al doble de tamaño, sin truncar el código.
- Las salas locales anteriores se migran preservando sus alias; la migración
  es transaccional, idempotente y usa una restricción única. Dos registros de la
  importación histórica siguen pendientes de incorporarse al registro local.
- El selector de reacciones abre una hoja inferior con cierre visible, botones
  accesibles y desplazamiento horizontal. Las reacciones flotantes bajan dentro
  del área disponible sobre los controles.
- Se reutiliza la sala ya cargada al entrar, evitando consultas repetidas. El
  reinicio nativo se espera por su finalización, en lugar de pausas fijas.
- El rechazo del servidor al entrar detiene el recorrido antes de abrir el socket.
- **Seguridad:** crear una sala con el UUID de otra cuenta ya no permite cambiar
  su propietario. Se comprobó HTTP 403 y conservación de propietario y contenido.
- El acceso a salas privadas sigue exigiendo contraseña; el código corto no
  reemplaza ese control y las respuestas no exponen el hash.

### LIVE y conexión

- Preview, invitado y perfiles de colaboración solicitan **60 FPS**. Se consultan
  los formatos y límites reales de Camera2; un formato incompatible usa una
  cadencia admitida y mantiene el presupuesto de resolución del dispositivo.
- Esto es una solicitud de captura, no una garantía de 60 FPS entregados. La
  cámara, la iluminación, el codificador, el dispositivo receptor y la red influyen.
  Android documenta que el rango solicitado depende de las capacidades y de la
  duración mínima del formato. [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics),
  [CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult).
- El DNS público consulta primero su caché válido y, al renovar, compite con el
  sistema y los proveedores HTTPS en paralelo. El primer resultado público
  válido gana; hay un plazo conjunto y cancelación de tareas restantes. La ruta
  anterior podía sumar tres consultas de cinco segundos, además del DNS del sistema.
- La validación TLS conserva el hostname original. Se mantiene el rechazo de
  direcciones privadas, locales y CGNAT para el endpoint público de Tailscale.
- El backend usa bcrypt nativo asíncrono, con coste 12 y compatibilidad con los
  hashes anteriores. La comprobación de hashes antiguos pasó con ASCII y Unicode.
- Se rechazan **nuevas** claves que superan los 72 bytes UTF-8 en registro,
  cambio, recuperación, migración, administración y salas privadas. Se evita
  el truncado silencioso documentado por [bcrypt](https://github.com/kelektiv/node.bcrypt.js/).
  El inicio de sesión de credenciales antiguas permanece compatible; una política
  de migración de claves históricas largas sigue siendo una mejora pendiente.
- La app muestra en español el error de contraseña demasiado larga.
- La supervisión comprueba el origen canónico de la API y prioriza usuarios reales
  sobre fixtures recién usados. Antes consultaba un túnel antiguo y una cuenta
  temporal sin conversaciones: generaba falsas alarmas de mensajería.

### Interfaz, privacidad y calidad

- Se retiraron fallbacks del correo como nombre público en los recorridos revisados
  de perfil, mención, compartir y participantes de salas.
- Se corrigió la comprobación de permisos de llamadas para Android 7: los canales
  y su importancia solo se consultan desde Android 8. Los ajustes tienen una
  alternativa compatible para dispositivos anteriores.
- Se corrigieron los errores de recursos de Compose en acceso/recuperación y
  traducciones faltantes de avisos de amistad y del navegador de salas.
- El primer Lint detectó **16 errores y 273 advertencias**. La repetición pasó
  con **cero errores y 272 advertencias**. No se desactivaron detectores para aprobar.

## 3. Pruebas y mediciones

### Compilación y suites

| Verificación | Resultado | Límite de la evidencia |
|---|---|---|
| APK debug y APK de pruebas | Compilan | No es firma ni publicación de producción |
| Android unitarias | 981; cero fallos, errores u omisiones | Incluyen Robolectric y contratos; no reemplazan hardware físico |
| Backend Node | 312 casos: 310 aprobados, dos omitidos en Windows | Los omitidos requieren fixtures Bash/Linux |
| Interfaz instrumentada | Diez casos aprobados | Componentes y estados descritos, no todas las pantallas |
| Medios instrumentados | LIVE, sala, TURN, invitado, pantalla y videos aprobados | Dos extremos en un emulador; escucha humana no comprobada |
| Cámara local | Un caso con frontal/trasera y solicitudes de 30/60 FPS aprobado | Cámara virtual |
| Migración PostgreSQL | Cinco salas de esquema aislado; unicidad, alias e idempotencia aprobados | La prueba revierte su esquema |
| API de salas desplegada | Creación, reintento, propiedad, código, entrada y contraseña aprobados | Dos cuentas desechables |
| Acceso y recuperación desplegados | 21 comprobaciones aprobadas | Código emitido por el almacén del servidor; transporte SMTP separado |
| SMTP Gmail | TLS y autenticación aceptados | Esta prueba no certifica recepción en la bandeja |
| Entrada pública | Seis comprobaciones TLS/HTTP pasaron forzando las IPv4 públicas | No representa dos operadores móviles |

La repetición final sobre la APK de `1e05021` aprobó **17 pruebas instrumentadas
distintas, cero fallos y cero omisiones**, en 215,269 segundos. Incluye las diez
de interfaz, cámara y los seis recorridos de medios. La compilación, las 981
unitarias y Lint también finalizaron sobre ese código. El resumen conserva la
huella de la APK para vincular el artefacto con las pruebas.

En la primera ejecución de la suite de medios, pantalla compartida quedó omitida
por faltar su opción de activación. Se repitió con esa opción y pasó junto con las
diez pruebas de interfaz: **11 pruebas, cero fallos**. No se cuenta una omisión
como una prueba de pantalla aprobada.

### Autenticación y recuperación

Se comprobaron login correcto e incorrecto, cambio de contraseña, rechazo de
clave actual incorrecta, rechazo de claves nuevas excesivamente largas, revocación
de sesiones anteriores, verificación de seis dígitos, rechazo de código incorrecto,
rechazo de reutilización del código y del permiso de reset, guardado de nueva clave
y acceso posterior a los datos de la cuenta.

Rechazar una nueva clave demasiado larga **no consumió el permiso de recuperación**:
la siguiente clave válida funcionó. El servidor no devolvió el código en la
respuesta pública a una cuenta inexistente. Gmail autenticó por SMTP con TLS.
El código de esta prueba se emitió directamente en el almacén del servidor para
evitar enviar correo a las direcciones `.invalid`; la recepción real debe probarse
con una bandeja controlada antes de certificar entrega de correo completa.

### Tiempos observados

| Recorrido | Medida | Condición |
|---|---:|---|
| Operaciones HTTP de salas | 9–316 ms | API desde el servidor, sin todo el trayecto de Internet |
| Login correcto | 306 ms | API desde el servidor; muestra individual |
| Cambio de contraseña | 458 ms | Verificación de clave, hash nuevo y sesión |
| Verificación de código | 19 ms | API desde el servidor |
| Guardar contraseña recuperada | 240 ms | API desde el servidor |
| Diez lecturas de servicios | 186–334 ms | URL canónica; no se fuerza ruta pública en esta prueba |
| Seis conexiones públicas nuevas | 1.120–1.250 ms | TLS con IP pública forzada, sin atajo de MagicDNS |
| Entrar al LIVE como espectador/invitado | 942 ms | Retorno de `joinLiveAsViewer`; no primer cuadro |
| Solicitud y aprobación de invitado | 1.445 ms | Incluye recorrido de solicitud y aceptación automatizada |
| Primer cuadro del invitado | 1.734 ms | Desde activar transmisión, después de aceptación |
| Primer cuadro LIVE directo nativo | 1.648 ms | Prueba sobre emulador durante análisis de compilación |
| Primer cuadro de sala | 2.667 ms | Mismas condiciones de emulador |
| Primer cuadro de llamada TURN | 3.348 ms | Relay obligatorio; RTT observado 268 ms |
| Primer cuadro de pantalla compartida | 4.828 ms | Incluye recorrido de permiso/captura del fixture |
| Primer cuadro de dos videos | 1.406 / 658 ms | Reproducción, avance y reanudación; posible influencia del caché |

Son muestras, no percentiles ni garantías globales. Un tiempo de API, un handshake
TLS y el primer cuadro de video son medidas distintas. **El objetivo universal
de un segundo todavía no está cumplido.** Acortar timeouts para ocultar una demora
no sería una mejora de entrega.

### Cadencia y audio

- Cámara virtual trasera: 8,37 FPS, solicitando tanto 30 como 60.
- Cámara virtual frontal: 11,25 FPS, solicitando tanto 30 como 60.
- Recepción de LIVE/sala/llamada: aproximadamente 8,47–8,71 FPS en la última suite.
- Invitado: captura 11,25 FPS; recepción 11,12 FPS, 640×360.
- Pasaron encendido/apagado y giro de cámara; la promoción de invitado conservó
  la conexión y salir del asiento no volvió a abrir cámara/micrófono por callbacks tardíos.
- Audio RTP recibido en ambas direcciones. Se silencia la reproducción en ese
  fixture para evitar acople entre dos extremos del mismo emulador.
- Pantalla compartida entregó cuadros con luminancias diferentes, no solo una
  imagen estática. Su permiso y arranque siguen necesitando medida física.

No se convierte 11 FPS medidos en una afirmación de 60 FPS. La prueba de aceptación
de 60 debe medir captura y recepción en teléfonos con formatos compatibles.

## 4. Matriz de cobertura de la aplicación

Todos estos módulos entraron en inventario, compilación y análisis estático.
“Parcial” identifica la cobertura funcional, no una aprobación de cada opción.

| Módulo | Evidencia actual | Pendiente de ejecución representativa |
|---|---|---|
| Registro, identidad y username | Contratos de unicidad/migración; suite de backend; fallbacks públicos revisados | Registro completo en varios tamaños, idiomas y redes |
| Acceso, cambio y recuperación | Ciclo HTTP con 21 comprobaciones; interfaz código/clave; SMTP autenticado | Recepción en bandeja, cambio de app y recuperación con 2FA en dispositivo físico |
| Administración y evidencia manual | Suites de moderación y adjuntos; revisión de permisos | Recorrido visual de todos los estados administrativos y entrega humana |
| Invitado | Prueba de invitado LIVE; contratos de contenido público | Galerías múltiples, navegación prolongada y rotación en teléfono |
| Mensajes, grupos y comunidades | Lecturas públicas del monitor; contratos de mensajes, marcadores, privacidad y jobs | Conversaciones entre teléfonos, grandes adjuntos y red intermitente |
| Stickers | Contratos de favoritos, deduplicación y política de archivos | Alternancia real de cuentas, teclado, favoritos y reenvío en varios fabricantes |
| Social, amistades y avisos | Feed público; pruebas de payloads y notificaciones; traducciones | Push real con app cerrada, solicitudes y borrado de indicadores en hardware |
| Videos y edición | Dos videos reproducidos/avanzados/reanudados; contratos de publicación, historia y colecciones | Edición/exportación larga, HDR, códecs, permisos y memoria física |
| LIVE, invitados y batallas | Medios nativos, invitado y contratos de colaboración/puntuación | SFU, batallas y coanfitriones bajo carga en equipos independientes |
| Salas de voz | API completa de los escenarios descritos; layout; cámara/audio/pantalla | Muchos participantes, escucha humana, Bluetooth y reproducción compartida física |
| Llamadas | TURN obligatorio, video/audio y acciones de notificación; compatibilidad Android 7 | Push remoto, bloqueo, ahorro de batería y llamada real entre fabricantes |
| Perfiles, búsqueda y enlaces | Contratos de tarjetas públicas, búsqueda y protección de contenido | Previews de redes externas y todos los enlaces en teléfonos sin la app |
| Documentos | Implementaciones y evidencia anterior de PDF y Office | Archivos grandes, cifrados, fuentes especiales y diseños complejos |
| Juegos, retos, rondas y regalos | Catálogo leído; suites de puntuación, límites y transacciones; layout de rondas | Interacción concurrente representativa; compras externas no realizadas |
| Ajustes, widgets y accesibilidad | Manifiesto, backup, recursos, permisos y texto ampliado | TalkBack completo, multiwindow, tablets, RTL y escalas extremas |
| Infraestructura y almacenamiento | Nueve servicios sanos; lecturas públicas; backup nuevo; ensayo anterior de restore | Carga mixta sostenida, recuperación de desastre y capacidad de subida real |

Los retiros económicos permanecen fuera del alcance acordado de las pruebas.
No se hicieron compras ni se certificaron pagos externos mediante transacciones reales.

## 5. Qué falta: prioridades de mayor a menor gravedad

### P1 — Bloqueos antes de una entrega pública amplia

1. **Medios físicos y arranque.** Todavía hay primeros cuadros por encima de un
   segundo y no existe medida física de 60 FPS. Ejecutar dos teléfonos en operadores
   distintos, recoger tiempos de DNS/TLS/API/ICE/captura/primer cuadro por separado,
   comprobar escucha, bloqueo, Bluetooth y cambios de red. Aceptación: imagen y
   audio bidireccionales, recuperación sin pantalla negra y percentiles acordados
   bajo redes definidas. La falta de dispositivo impide cerrar esa certificación.
2. **Entrega release firmada y tamaño.** No está configurada la clave original de
   publicación. El debug universal ocupa 265,16 MiB; no representa la descarga de un
   AAB por dispositivo. Generar AAB release con R8 y firma original, ejecutar el
   control de presupuestos y probar actualización conservando datos. No se debe
   publicar la APK debug como entrega final de tienda.
3. **Disponibilidad y latencia fuera de la mini PC.** API, señalización, archivos
   y TURN dependen de un solo equipo y su conexión. Backups/monitores ayudan a
   recuperar, pero no eliminan un corte de energía o subida. Definir capacidad,
   objetivo de disponibilidad y contingencia; medir carga mixta y percentiles de
   usuarios. Hay pruebas públicas de más de un segundo incluso con API sana.

### P2 — Seguridad, rendimiento y cobertura

4. **Entrega de correo durable.** El request espera al proveedor SMTP. Una caída,
   cuota o demora puede retrasar el proceso aunque la API esté sana; también deja
   una diferencia de tiempos entre una cuenta existente y una inexistente.
   Implementar outbox transaccional, reintentos, métricas, límites y respuesta
   uniforme. Verificar recepción y expiración en bandejas controladas.
5. **Credenciales históricas extensas.** La nueva política evita crear claves
   truncadas, pero no transforma hashes antiguos. Planificar restablecimiento o
   migración versionada de las cuentas afectadas, sin bloquear su login de golpe.
6. **Escalabilidad de salas y colaboración.** La prueba usa dos extremos; no mide
   el coste de múltiples peers, cámara, pantalla y voz simultáneas. Ensayar capacidad,
   batería, temperatura y degradación adaptativa antes de elevar límites de participantes.
7. **Cobertura visual y de dispositivos.** Quedan tres advertencias de tamaños
   basados en `Configuration.screenHeightDp`, en invitado y panel de stickers.
   Revisar tamaño real de ventana, teclado/multiwindow, TalkBack y pantallas extensas.
8. **Registro histórico de salas.** Incorporar los dos registros importados al
   registro de códigos cortos preservando alias y permisos, si también se quiere
   uniformar la presentación de todas las salas antiguas.
9. **Archivos, edición y uso prolongado.** Sesiones físicas de 30–60 minutos,
   exportaciones grandes, documentos complejos y pérdida de red. La prueba sostenida
   previa de cuatro minutos y el restore aislado de 216 tablas siguen siendo
   evidencia acotada, no sustituyen estas verificaciones.

### P3 — Mantenimiento

10. **Advertencias estáticas.** 245 sugerencias KTX, 16 recursos posiblemente
    sin uso, tres tamaños de pantalla, dos duplicidades XML/PNG de iconos, dos
    candidatos a plurales, dos traducciones de formatos no traducibles, un orden
    de parámetro Modifier y una revisión de JavaScript en WebView. El WebView
    revisado restringe esquemas y desactiva acceso a archivos/contenido y contenido
    mixto; el aviso no demuestra una vulnerabilidad por sí solo.
11. **Código heredado.** El módulo TOTP local antiguo genera secretos de 80 bits y
    rechaza el entero cero, aunque `000000` puede ser un TOTP válido. Los ajustes
    actuales usan el factor del servidor. Armonizar o retirar ese recorrido local
    y normalizar el comentario con codificación inválida de `AndroidTotp.kt`.
12. **Identidad estable de supervisión.** Se corrigió la prioridad de fixtures y
    el origen. Configurar una identidad explícita con contenido de diagnóstico
    representativo evitaría depender del usuario real más recientemente activo.
    La ausencia legítima de conversaciones no debe confundirse con una caída.

No se ha demostrado un fallo P0 en los recorridos verificados; eso no demuestra
que no exista uno en recorridos todavía no probados.

## 6. Diez heurísticas aplicadas

Se usan como criterios concretos, siguiendo las [heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/).

| Heurística | Aplicación comprobada | Cobertura restante |
|---|---|---|
| Estado visible | Micrófono completo, estados de entrada y error | Medir todos los estados lentos en teléfono |
| Lenguaje familiar | Código de sala corto, avisos en español | Revisar textos de todas las pantallas |
| Control y salida | Cerrar reacciones/enlace y salir del asiento | Back y restauración en todos los módulos |
| Consistencia | Controles y mensajes de recuperación separados | Uniformar recorridos heredados |
| Prevención de errores | Permisos, propiedad, contraseña y código únicos | Más concurrencia y abuso de entradas |
| Reconocimiento | Descripciones accesibles, código visible/copiable | TalkBack y ayuda contextual general |
| Eficiencia | Sala ya cargada, DNS paralelo, caché y bcrypt nativo | Percentiles bajo carga real |
| Información necesaria | Hoja inferior compacta y código sin UUID visible | Administración y pantallas densas restantes |
| Recuperarse de errores | Reintentar, cerrar y conservar permiso tras clave inválida | Red intermitente y entrega SMTP |
| Ayuda | Pasos de código/nueva clave y ajustes de llamada | Ayuda de primeros usos y modos avanzados |

## 7. Operación, evidencias y cierre

- Nueve servicios Docker comprobados sanos después del despliegue.
- Supervisión pública recuperada: origen `conectate.tail9b267c.ts.net`, social y
  mensajería aprobados, `status=ok`, cero fallos consecutivos.
- Backup verificado: `/home/alvaro/conectate-backups/20260930T222300Z`;
  PostgreSQL 3.468.790 bytes, storage 2.182.336.262 bytes y estado AI 4.695.842 bytes.
  Se comprobaron estructura y hashes; el restore aislado completo corresponde al
  ensayo anterior documentado, no a una restauración de producción en esta entrega.
- Despliegue con comparación de fuentes anteriores y copia de respaldo privada;
  se mantuvieron datos y claves del servidor.
- Commits: `5ab949f` (salas/cámara/privacidad), `48c18df` (hash nativo/código
  accesible), `ff6ec45` (DNS/compatibilidad/traducciones), `1e05021`
  (límite de claves/supervisión/feedback).

### Artefacto final

- **Novvin 9.0.20-debug, código 117**, compilado desde `1e05021`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 278.041.602 bytes.
- SHA-256: `db18ee2858823ce0a00567dedac0719a6d59c94a6db3c720c699c10b4f10391c`.
- Firma APK v2 comprobada con `apksigner`; es la firma debug, no la de publicación.
- Instalado y probado en el emulador conservando la sesión original. No se instaló
  en el Motorola ausente ni se modificó su cuenta.
- Se borraron las **dos cuentas sintéticas** tras terminar las 17 pruebas finales.
  Sus sesiones devuelven 401 y la consulta posterior encontró cero cuentas
  temporales de ese lote. Se retiraron sus credenciales privadas del PC y emulador.
- Comprobación final del servidor a las 23:47 UTC: nueve servicios sanos; monitor
  público correcto, cero fallos, siete lecturas social y diez de mensajería aprobadas.

### Evidencias guardadas

- [Compilación, suites, Lint y huella de APK](evidencias-auditoria-2026-09-30/cierre-pruebas.json).
- [Estado final del servidor y limpieza](evidencias-auditoria-2026-09-30/estado-servidor-final.json).
- [Acceso y recuperación: 21 comprobaciones](evidencias-auditoria-2026-09-30/voice-audit-auth-cycle.json).
- [Migración de códigos](evidencias-auditoria-2026-09-30/voice-room-code-migration-result.json)
  y [API de salas](evidencias-auditoria-2026-09-30/voice-room-e2e-result.json).
- [Lecturas HTTP de servicios](evidencias-auditoria-2026-09-30/voice-audit-service-reads.json)
  y [conexión forzada por IPv4 públicas](evidencias-auditoria-2026-09-30/voice-audit-public-ingress-final.json).
- [LIVE](evidencias-auditoria-2026-09-30/voice-audit-media-live-final.json),
  [salas](evidencias-auditoria-2026-09-30/voice-audit-media-voice-final.json),
  [llamada por TURN](evidencias-auditoria-2026-09-30/voice-audit-media-call-final.json),
  [invitado](evidencias-auditoria-2026-09-30/voice-audit-native-guest-final.json),
  [pantalla](evidencias-auditoria-2026-09-30/voice-audit-screen-share-final.json),
  [videos](evidencias-auditoria-2026-09-30/voice-audit-video-playback-final.json)
  y [cámara virtual a 30/60 solicitados](evidencias-auditoria-2026-09-30/voice-audit-camera-cadence.json).
- [Eliminación de cuentas temporales y rechazo de sesiones](evidencias-auditoria-2026-09-30/voice-audit-fixture-cleanup.json).
- Capturas revisadas: [micrófono](evidencias-auditoria-2026-09-30/voice-muted-badge.png),
  [reacciones abajo](evidencias-auditoria-2026-09-30/voice-reactions-bottom.png),
  [reacciones con texto grande](evidencias-auditoria-2026-09-30/voice-reactions-large-text.png),
  [código de siete dígitos con texto al doble](evidencias-auditoria-2026-09-30/voice-short-code-double-text.png).

Las capturas son fixtures de componentes, no pantallas de cuentas reales.
Los JSON publicados solo contienen estados, tiempos y contadores; se excluyen
credenciales, tokens, códigos de recuperación y contenido privado de conversaciones.
