# Auditoría de medios, Descubrir y LIVE entre redes distintas

Fecha: 5 de octubre de 2026. Rama: `feature/live-gifts`. Base: `f772bef`.

## 1. Alcance y decisión de entrega

Este informe continúa el cierre de los once puntos, la cámara de mensajes y las mejoras posteriores solicitadas. Se trabaja con Motorola g04 Android14 mediante datos móviles y Samsung SM-X520 Android16 mediante Wi-Fi. Las pruebas utilizan cuentas, publicaciones, archivos y directos temporales propios. La aplicación principal de la tablet y sus datos se conservan; las pruebas se instalan en el paquete independiente `conectate.com.qa.cierre11`.

**La aplicación completa todavía no tiene certificación de producción.** Una comprobación aprobada demuestra el recorrido y el artefacto indicados. No demuestra todas las combinaciones de dispositivos, redes, cuentas, carga, accesibilidad o el AAB firmado de Play. Se mantienen los fallos iniciales y las repeticiones con su alcance.

## 2. Requisitos funcionales y criterios medibles

| ID | Función y comportamiento requerido | Criterio de aceptación y beneficio |
| --- | --- | --- |
| RF-M01 | Descargar una imagen ordinaria del chat directamente a la galería. | Una acción publica un archivo decodificable; no abre un selector de destino. Evita pasos para conservar una imagen. |
| RF-M02 | Compartir el archivo real con aplicaciones externas. | El intent entrega `EXTRA_STREAM`, MIME correcto y permiso temporal de lectura; la aplicación elegida recibe los bytes. Facilita reutilizar material recibido. |
| RF-M03 | Mantener la protección de mensajes de una sola visualización. | No exponer descargar/compartir en este tipo de mensaje. Evita que una mejora de exportación omita su restricción. |
| RF-M04 | Resolver medios descargados sin acceso a otra cuenta. | Se valida la raíz de caché de la cuenta activa y se rechazan rutas fuera de ella. La exportación se cancela al cambiar de cuenta. |
| RF-M05 | Publicar una descarga únicamente cuando termina. | Android29+ conserva `IS_PENDING` durante escritura y lo retira al completar; un fallo elimina el registro parcial. La galería no muestra archivos incompletos. |
| RF-M06 | Elegir fotos/videos mediante galería, cámara y editor internos. | El usuario confirma la selección y recibe un URI; puede recortar/rotar sin abandonar el aplicativo. Se reutiliza el compositor existente. |
| RF-M07 | Elegir música del dispositivo dentro de la aplicación. | Listado MediaStore con búsqueda y previsualización, confirmación «Usar audio» y pausa; sin navegación habitual por el administrador de archivos. |
| RF-M08 | Elegir documentos dentro del selector del aplicativo. | Navegación y filtro en la interfaz interna después de conceder el acceso Android requerido. La autorización inicial de carpetas/archivos continúa siendo un permiso del sistema. |
| RF-M09 | Pausar el audio del momento antes de enviarlo. | Pausa conserva posición y selección; la reproducción del video y música respeta la elección. No reiniciar por un bucle de sincronización. |
| RF-M10 | Detener multimedia ordinaria al salir de la aplicación. | `ON_PAUSE`/`ON_STOP` pausa; un `play()` tardío en segundo plano se rechaza; regresar no reanuda sin acción. Se aplica a los reproductores revisados, sin apagar por esta regla una llamada activa. |
| RF-M11 | Mostrar Tarjeta, Etiqueta, Solo música y Letras. | Las cuatro opciones son visibles. Letras requiere datos reales sincronizados; si no existen, se explica su disponibilidad. No inventar letras. |
| RF-M12 | Guardar imágenes ordinarias recibidas una vez descargadas. | Chats, grupos y comunidades registran imágenes de otro remitente; se reutiliza la copia completa privada y se publica en galería sin otro GET. Interruptor activado por defecto, exclusión de visualización única/deletadas e idempotencia por cuenta/origen. El acceso público permanece después de cerrar sesión. |
| RF-S01 | Continuar Descubrir después de la selección personalizada. | Al agotarse el cursor personalizado, continuar por páginas públicas con offset y deduplicación; conservar privacidad y cuenta activa. |
| RF-S02 | Intercalar recomendaciones entre publicaciones. | Primeras posiciones 2/5/8/11/16; conjuntos de videos sin solapamiento entre la primera y segunda tarjeta. No acumular tarjetas al final de una lista corta. |
| RF-S03 | Mantener un feed de videos personalizado. | Dos cuentas propias con la misma consulta reciben una selección diferente en la muestra. No se promete diferencia para cada par de usuarios ni se importa contenido de otras redes. |
| RF-L01 | Identificar a cada participante por su identidad estable. | Cámara, nombre y avatar se relacionan por identidad autenticada, sin asignar una cámara según nombre o posición. Evita mostrar la persona incorrecta. |
| RF-L02 | Mostrar más de cinco invitados en una cuadrícula legible. | Seis invitados más anfitrión: siete celdas; ocho más anfitrión: nueve en la prueba de diseño. Todas visibles, sin superposición. El límite efectivo del servidor actualmente es seis invitados. |
| RF-L03 | Mostrar a participantes de voz sin cámara. | La lista de participantes SFU conserva nombre/avatar aunque no haya pista de video o esté silenciada. No desaparece la identidad al apagar la cámara. |
| RF-L04 | Separar la cuadrícula de controles y marcador. | En colaboración, acciones en la fila inferior, chat compacto y espacio reservado para encabezado/batalla; pruebas de pantalla completa y viewport compacto. |
| RF-L05 | Reflejar los puntos reales de una batalla. | Marcador y ventaja cambian con el estado confirmado; finalizar muestra empate/ganador. La prueba visual no acredita compra de regalos ni puntuación financiera. |
| RF-L06 | Evitar abrir dos cámaras al iniciar una batalla. | La transmisión nativa existente se comparte con SFU; silenciar/reanudar y cambiar de cámara conserva captura y publicaciones. |
| RF-L07 | Adaptar la publicación de cámara a datos móviles. | Red medida: 640×360, 30 FPS, 450 kbit/s, sin simulcast. Mantener recepción y audio; no prometer 60 FPS reales en equipos que capturan a 30. |
| RF-L08 | Conectar un espectador mediante los repositorios reales. | Desde `joinLiveAsViewer` hasta primera imagen dibujada <5 s como objetivo provisional del cierre; medir HTTP, motor, socket, SDP y transporte por separado. |
| RF-L09 | Recuperar audio y video después de perder conectividad. | Tras restablecer datos, ocho muestras consecutivas con nuevos fotogramas y al menos dos incrementos de paquetes de audio en ≤10 s; la detección respeta los intervalos sin paquetes de Opus DTX; conservar sesión, permisos y controles. Registrar el corte intencional separado del funcionamiento normal. |
| RF-L10 | Autorizar participantes en el servidor. | Seis aceptaciones permitidas y séptima rechazada; un espectador no recibe autorización de publicación SFU. El cliente no decide por sí solo el aforo ni el rol. |

## 3. Implementación y límites técnicos

### Medios y ciclo de vida

`PublicMediaExport` centraliza la escritura pública y la limpieza transaccional. `ChatMediaDownloads` reutiliza descargas existentes cuando pertenecen a la cuenta activa y entrega una copia acotada mediante FileProvider para compartir. `InAppMediaPicker` reutiliza la cámara, galería y editor. `InAppDocumentPicker` usa el listado de audio del aplicativo y el selector documental existente.

`PauseMediaPlayerOnBackground` protege tanto la transición del lifecycle como una petición tardía de reproducción. Los reproductores del editor de momentos, selectores de audio, avatar, editor de video y replay revisados usan esta política o una equivalente. La comprobación de un componente no equivale a inspeccionar todos los reproductores del producto. `ReceivedChatImageGallery` reutiliza la descarga completa de `ChatMediaOfflineCache`. La precarga respeta Wi-Fi/datos móviles y el interruptor general; una copia ya disponible puede guardarse sin otro acceso a red. El interruptor «Imágenes recibidas en galería» se activa por defecto. Recibos acotados en archivos separados por cuenta evitan duplicados y respetan una copia pública borrada por el usuario. El registro excluye imágenes propias, eliminadas y de una sola visualización. La prueba nativa usa imágenes sintéticas propias; aún no acredita recepción real completa en los tres tipos de conversación.

La publicación atómica sigue el contrato de [MediaStore](https://developer.android.com/training/data-storage/shared/media), y el envío de archivos usa el contrato de [Android Sharesheet y FileProvider](https://developer.android.com/develop/ui/compose/sharing/send). La pausa de contenido ordinario sigue el [ciclo de vida de las actividades](https://developer.android.com/guide/components/activities/activity-lifecycle).

### Descubrir y videos

`SocialDiscoveryContinuation` decide entre el siguiente cursor personalizado y páginas públicas. `SocialViewModel` mantiene una lista creciente sin IDs duplicados y descarta respuestas de una cuenta anterior. `SocialScreen` calcula posiciones estables de recomendaciones. La importación real desde redes externas queda fuera de esta entrega: requeriría contratos, autorización, atribución y una fuente disponible.

### LIVE, participantes y batalla

`LiveParticipantLayoutPolicy` decide las columnas y compara identidades. `LiveKitRoomBridge` expone identidad estable del participante y una lista independiente de sus pistas de video; los eventos de silencio conservan su avatar. La cuadrícula admite hasta nueve personas en el diseño, mientras API y signaling comparten un límite de seis invitados en la configuración desplegada.

El perfil de publicación en red medida limita resolución y bitrate para reducir las pausas observadas. El perfil no fija un máximo de FPS de recepción garantizado: depende de captura, codificación, red y dispositivo. El candidato v12 gestiona dimensiones y habilitación por renderer visible, con `adaptiveStream=false`. La retirada de la última vista deshabilita esa suscripción; el cambio de pista o transporte renueva la vista. Este ajuste sigue pendiente de una repetición de corte aprobada y de la repetición física. La suscripción y el silencio siguen el contrato de [pistas y publicaciones de LiveKit](https://docs.livekit.io/transport/media/subscribe/); la recuperación se verifica con medios nuevos conforme al [ciclo de conexión de LiveKit](https://docs.livekit.io/intro/basics/connect/).

`MediaStartupTrace` conserva solamente tiempos de etapas, sin tokens ni identidades. La preparación del motor y lectura autorizada del directo se solapan; antes de iniciar signaling deben completarse ambas y conservarse la cuenta. La consulta TURN se inicia durante la preparación nativa; los pares siguen esperando credenciales utilizables antes de su primera oferta.

## 4. Evidencia y repeticiones

| Comprobación | Resultado y alcance |
| --- | --- |
| Android unitarias v16 | 1.358 pruebas, 293 suites, cero fallos/errores/omitidas. Incluye renovación de enlaces, ocho casos de elegibilidad del guardado automático y ocho de reparación limitada del visor. La regresión física v19 utiliza este mismo APK principal. |
| Regresión UI v8 | 73/73 en Motorola y 73/73 en Samsung. Incluye exportación, selector, cámara, enlaces, perfil, música, lifecycle, encuestas, cuadrícula y batalla. Evidencia histórica de ese artefacto. |
| Backend completo actual | API: 467 pruebas, 463 aprobadas, cuatro omitidas, cero fallos. Signaling: 30 pruebas, 29 aprobadas, una omitida, cero fallos. Las omitidas siguen fuera de la evidencia de aprobación. |
| Primer candidato de medios | 13/13 en cada equipo: cámara/editor, exportación de imágenes sintéticas y pausa del momento. |
| Candidato de cuadrícula v1 | Tablet 62/62; Motorola 61 aprobadas y un fallo del capturador de pantalla de encuestas. Los recorridos de cuadrícula, batalla y selector incluidos pasaron. |
| Regresión corta v2 | Tablet 19/20; Motorola 18/20. Un error de limpieza del test de lifecycle en ambos y un fallo de captura en Motorola. Se corrigieron los fixtures; falta contabilizar la repetición final. |
| LIVE con repositorios reales, primera muestra | Host primera imagen 1.888 ms; espectador 8.848 ms, 29,0 FPS recibidos durante 15 s. No alcanza el objetivo de entrada. |
| LIVE con trazado v3 | Host 1.903 ms; espectador 7.547 ms y 27,1 FPS durante 15 s. Motor listo 2.852 ms, socket 4.430 ms, oferta 5.280 ms, transporte 7.086 ms. Entrada todavía fuera del objetivo. |
| SFU/batalla v1, redes distintas | Audio RTP recibido en ambos. Motorola 26,5 FPS y pausa máxima 3.375 ms: fallo del presupuesto de pausas de 2 s. |
| SFU/batalla v2, redes distintas | Ambos aprobados durante 30 s. Tablet 29,4 FPS/pausa máxima 492 ms; Motorola 29,9 FPS/112 ms. Entrada del invitado 6.514 ms contando token y primera imagen: aún necesita mejora. |
| Cámara existente y controles de batalla | En la tablet v2 se conservó captura nativa, publicación SFU, silencio/reanudación y reemplazo por cámara trasera. No se probó con seis cámaras físicas simultáneas. |
| Backend dirigido | Cinco pruebas de colaboración, configuración y puntuación aprobadas. |
| Capacidad real HTTP final | Aprobada: seis invitados aceptados, séptimo rechazado con HTTP409 y token del espectador con `canPublish=false`. Las siete cuentas temporales y el LIVE quedaron eliminados. Se respetó el límite de registro tras intentos HTTP429 anteriores. |
| LIVE nativo v8 con invitado | Anfitrión recibe cámara del Motorola a 17,8 FPS; 3.064 ms desde aceptación a imagen. Espectador: primera imagen 5.442 ms y recepción 9,27 FPS al publicar cámara. Entrada y fluidez pendientes. |
| LIVE nativo v9 con invitado | Anfitrión recibe cámara a 21,8 FPS; 2.473 ms desde aceptación a imagen. Espectador: primera imagen 6.384 ms y recepción 9,60 FPS al publicar cámara. No cumple el presupuesto provisional de entrada. |
| LIVE nativo v10 físico | Ambos roles aprobados en una muestra: primera imagen del Motorola 4.742 ms. Recibía 10,07 FPS mientras publicaba cámara. Decodificación por hardware; publicación del Motorola VP8/libvpx. Una muestra no cierra latencia ni fluidez. |
| LIVE nativo v11 físico | Host aprobado; espectador 5.744 ms y 10,13 FPS: vuelve a exceder el presupuesto de entrada. |
| Regresión v11 física | 73 ejecutadas por equipo: un timeout del editor en cada uno y una prueba de servidor omitida por faltar opt-in. Repetición dirigida Motorola: 14/14; tablet ya desconectada. No sustituye la regresión v8 aprobada. |
| SFU v11, corte de datos físico | Controles aprobados antes del corte; audio fresco y contador de visor sin avance durante 25 s. No aprobado; la espera nativa no sincronizaba el reloj Compose, por lo que no basta para atribuir la congelación al producto. |
| Candidato v12, emulador + Motorola | No llegó al corte: emulador recibía 7,47 FPS, por debajo del mínimo del fixture. El recorrido de repositorios no inició en el emulador por ausencia de sesión previa. Se corrigió el fixture para restaurar una sesión vacía al finalizar. |
| Regresión v12 | Motorola: 77 ejecutadas, 76 aprobadas; el fallo fue doble eliminación de una imagen ya borrada dentro del fixture de galería. Emulador: además fallaron el permiso de galería y preparación de video. La corrección del fixture y concesión de permisos se repiten en v13. |
| Galería nativa v13, emulador | Cuatro pruebas aprobadas: copia decodificable/idempotencia/borrado voluntario, preferencias desactivadas, exclusión propia/visualización única y cambio de cuenta. |
| Regresión UI v13, Motorola | 77/77 aprobadas, cero omisiones. Incluye las cuatro pruebas de galería y publicación/respuesta reales de una encuesta propia. APK principal SHA-256 `1dbd5e447bbb5de2bdeaf1af9454d3bebc2aff0084197c563059b85d8ce56af0`. |
| Regresión UI v19, Motorola | 77/77 aprobadas, cero omisiones, 138,158 s. Incluye galería, encuestas contra servidor, cámara, música, navegación y renderizado. APK principal SHA-256 `c75b1654bfb5ad40adf61ccc5fb2c80d2fad154933d93de77d8f1980753c4adb`; APK de pruebas `457723e1ba3b3661141cc500fdb8f0357e0ca31397efbf8a08b20401e88d6c49`; log `95db3baaee0063e1a0df3dbc5e8d98abfdc2f406253b5fc99fd5fa9c510ffc14`. |
| Regresión UI v13, emulador | 77 ejecutadas, doce fallos: nueve de foco WebView y tres tiempos de espera de recorte, grabación y visor. Se detectó después un diálogo ANR de Bienestar Digital ocupando el foco del emulador. No se atribuye automáticamente cada fallo al diálogo. |
| Repetición UI v14, emulador | Tras cerrar el diálogo externo, 27 ejecutadas, 25 aprobadas y dos tiempos de espera: grabación de video e imagen inválida. Las nueve pruebas WebView y el recorte pasaron; esos dos casos también pasaron en Motorola. Sin omisiones; conserva el mismo APK principal de v13. |
| SFU v13, emulador + datos móviles | La prueba sin cámara compartida alcanzó el corte de ocho segundos. Audio nuevo en ambos; video congelado durante 25 s aunque había fotogramas decodificados, pista habilitada y visor adjunto. No aprobado. El mínimo de FPS virtual se redujo solo para aislar transporte: no acredita el rendimiento de un par físico. La cámara compartida falló antes del corte después de reemplazar la cámara. |
| Guardas de mantenimiento actuales | Doce pruebas aprobadas; no sustituyen lint ni la compilación optimizada actual. |
| SFU v17, prueba sincronizada, cámara SFU | Tras ocho segundos sin datos, ambos recibieron y dibujaron video nuevo. Motorola: ocho muestras en 9.208 ms; emulador: 11.249 ms. El Motorola cumple y el emulador supera el máximo de diez segundos. Audio nuevo en ambos. Sin cámara nativa compartida en esta muestra. |
| SFU v19, cámara compartida y corte | Captura nativa, silencio/reanudación y sustitución de cámara aprobados antes del corte. Tras ocho segundos sin datos, ambos recibieron y dibujaron imágenes nuevas: Motorola 12.107 ms y emulador 17.819 ms hasta ocho muestras estables y audio nuevo. Ambos exceden diez segundos. Cámara y video se recuperan; tiempo de recuperación abierto. |
| SFU v9, corte intencional de datos de 8 s | Los controles de silencio, reanudación y cambio de cámara pasaron antes del corte. Tras volver los datos reaparecieron paquetes de audio en ambos, pero el visor quedó congelado. Fallo retenido; `CONNECTED` no equivale a recuperación de medios. |

Las medidas de primera imagen y FPS provienen de cámara/pistas/reproductores reales. Las pruebas de siete/nueve celdas y cambio de marcador usan datos sintéticos. Ninguna de estas muestras aisladas es P95, ensayo de carga o revisión acústica durante el sueño del usuario.

## 5. Cambio operativo de capacidad

Se estableció `LIVE_MAX_GUESTS=6` en API y signaling de producción mediante override persistente. Antes del reinicio, los procesos reportaban cero llamadas, salas de voz y directos activos. Se guardó una copia recuperable del override y se verificaron configuración y salud. Las imágenes de contenedor y el release de código se conservaron; esto constituye un cambio de configuración, no despliegue de todos los cambios Android o del nuevo código backend local.

SHA-256 del override anterior: `ede8902e1bec4196e89880fe26787084ef99350985fcec23fdf4063db1ff8ea3`.

SHA-256 del override actualizado: `1a0356e326f89b6829f413cc2e965a29f7864f865e97b58651cb34e318f009d8`.

## 6. Requisitos no funcionales y puerta de producción

| ID | Requisito | Medición o condición de cierre |
| --- | --- | --- |
| RNF-01 | Rendimiento de entrada | Mantener separadas primera imagen de espectador, entrada de invitado y preparación de anfitrión. Repetir entre redes sin afirmar P95 a partir de pocas muestras. |
| RNF-02 | Recuperación | Audio RTP y video nuevos en ambos equipos después del cambio; documentar tiempo de recuperación y pausas. Restaurar el estado de radios al finalizar. |
| RNF-03 | Uso de recursos | Liberar cámara, micrófono, renderer, exportación parcial y reproductor al cerrar. Repetir entrada/salida sin duplicar publicación ni abrir una segunda cámara. |
| RNF-04 | Aislamiento y permisos | Guardas de cuenta/sesión en IO y signaling; autorización backend de participante/aforo; restricciones de archivos y visualización única. |
| RNF-05 | Accesibilidad y adaptación | Sin textos ni controles sobre las celdas en teléfonos/tablets; objetivos táctiles y variantes de texto/viewport necesitan regresión adicional. |
| RNF-06 | Integridad del artefacto | Registrar SHA-256 de APK principal/test y fuentes; no atribuir resultados antiguos a una compilación distinta. |
| RNF-07 | Escalabilidad | Paginación, deduplicación, perfiles de red, listas acotadas y políticas pequeñas. El aforo visual/API no acredita capacidad de infraestructura con seis cámaras simultáneas. |
| RNF-08 | Entrega | Compilación, regresión final, lint, artefacto optimizado y revisión del AAB/firma. Los paquetes QA no sustituyen la entrega de Play. |

## 7. Último candidato y pendientes

La regresión física completa del candidato vigente pasó en v19: 77/77, cero omisiones. Utiliza el APK principal de v16, que añade una reparación limitada del enlace nativo de video, conservando preparación paralela, controles, identidad y captura. El diagnóstico v15, con emulador acelerado por NVIDIA y Motorola celular, recibió y decodificó video después del corte mientras el contador del visor no avanzaba. Refrescar la habilitación produjo cero fotogramas nuevos; añadir el visor a la pista actual produjo 76 y 73 en tres segundos. La prueba original sigue fallida y tiene una limitación adicional: no sincronizaba Compose durante sus esperas. Esa observación no prueba que el SDK haya perdido un enlace correcto de producción.

`LiveRendererRecoveryPolicy` comprueba un enlace visible y vigente durante diez segundos después de adjuntarlo. Tras 1.500 ms sin fotogramas permite retirar/añadir el mismo sink, con máximo dos intentos y el mismo intervalo entre ellos. Los fotogramas actualizan un reloj atómico. Al retirar el visor o terminar la sala se cancelan comprobación y sink. Ocho unitarias pasaron. Los controles compartidos y la sustitución de cámara pasaron antes del corte en v19; el criterio de recuperación estable sigue abierto por superar diez segundos en ambos extremos y falta repetir con tablet física. APK SHA-256 `c75b1654bfb5ad40adf61ccc5fb2c80d2fad154933d93de77d8f1980753c4adb`.

El fixture v17 usa `waitForIdle()` durante las esperas de red para procesar sustituciones de pista y visor conforme a [la sincronización de Compose](https://developer.android.com/develop/ui/compose/testing/synchronization). También exige avance de la superficie dibujada. El fixture de controles posterior cuenta cinco fotogramas locales nuevos después de reemplazar la cámara; no suma los anteriores. Antes del corte exige cinco remotos nuevos en tres segundos y otra ventana de 1.500 ms con al menos cinco. Este asentamiento de controles se registra separado de la recuperación de red; el máximo de diez segundos tras restaurar datos permanece igual.

Continúan abiertos los criterios sin una muestra aprobada del artefacto correspondiente; no se cambia el presupuesto físico para convertir una demora observada en aprobación.

## 8. Disponibilidad de equipos y límites de esta repetición

La tablet dejó de estar disponible por ADB aproximadamente a las 05:58 (Bogotá). Al retomar a las 10:58 ya no aparecía ningún equipo físico; continuó el emulador Android35. El Motorola volvió a aparecer a las 11:18 y pasó la regresión completa v13. No se atribuye una prueba del emulador a la tablet ni a una red celular real; la prueba mixta utiliza datos móviles reales únicamente en Motorola. El AAB/firmado optimizado actual y lint siguen pendientes: se cancelaron compilaciones de candidatos sustituidos antes de completarlas. El mensaje de Android Studio «Generate Signed App Bundle or APK ... is not available while Studio is updating indices» describe una restricción temporal de indexación del editor, independiente de una aprobación de producción.
