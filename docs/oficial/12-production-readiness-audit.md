# Auditoría de preparación para producción

Fecha inicial: 2026-08-12  
Última actualización: 2026-08-15  
Rama auditada: `feature/live-gifts`  
Rama estable `main`: no modificada  
Alcance: Novvin LIVE, Videos, Social, Momentos, mensajes, PlayMatch, perfil, llamadas entrantes, salas de voz y retiros de creadores.

> **Vigencia:** este informe es histórico (última actualización: 2026-08-15).
> Sus referencias a releases desplegados y a validaciones físicas sólo describen
> esa evidencia temporal. No deben usarse para certificar el estado actual ni para
> afirmar que la rama está desplegada. Consulta la [auditoría extrema vigente](15-auditoria-extrema-2026-08-24.md),
> actualizada el 2026-08-26, antes de cualquier release.

## 1. Dictamen ejecutivo

La rama compila, genera APK, pasa las pruebas automáticas y no presenta errores lint. Los recorridos locales de LIVE creador, Videos y Social Descubrir fueron comprobados en una instalación Android autenticada. En las rutas ejercitadas no se detectaron cierres, botones fantasma ni errores P0/P1.

Todavía no debe declararse lista para producción pública. Quedan validaciones externas que una sola instancia local no puede demostrar: llamada en frío entre dos teléfonos, audio/TURN en redes distintas, LIVE real host-espectador, colaboración/regalos, configuración SFU de producción y un retiro PayPal real o sandbox con credenciales del propietario.

## 2. Cambios incluidos

### Novvin LIVE

- Las superficies de preparación, práctica, Centro LIVE, recompensas, música, ajustes, Servicio+, juegos móviles, LIVE Studio, host y espectador están conectadas a navegación y estado real.
- Acciones de compartir y selección de audio usan intents Android reales.
- Mejorar aplica brillo, contraste, saturación, calidez y suavizado al pipeline de cámara.
- Efectos filtra y aplica presets; controles de cámara, micrófono, fuente y calidad llegan al ViewModel/repositorio.
- Se sustituyó la marca TikTok por Novvin en la experiencia propia.
- Se redujeron paddings visuales sin reducir por debajo de 48 dp los objetivos táctiles relevantes.

### Videos, Social y Momentos

- Videos no muestra el estado vacío durante la primera consulta y centra correctamente medios pequeños u horizontales dentro de un lienzo negro.
- Social Descubrir adopta la arquitectura de la pantalla principal móvil de Facebook: compositor, historias y feed, con identidad Novvin y acciones reales.
- Social Siguiendo se mantuvo funcional y sin el rediseño solicitado para Descubrir.
- Los estados vacíos aparecen únicamente después de una respuesta final, no durante la carga transitoria.
- Las historias se presentan como **Momentos**, comparten el mismo origen de datos y pueden destacarse en el perfil con su visor a pantalla completa.
- Se eliminó el cierre reproducible del visor de Momentos (`VerifyError`) dividiendo la pantalla en unidades verificables por ART y protegiendo la subida contra doble envío y excepciones.
- Cada Momento admite audiencia `todos`, `seguidores`, `seguidos`, `chats`, `seleccionados`, `todos excepto seleccionados` o `solo yo`; el servidor aplica la relación elegida antes de devolver o notificar contenido.
- Las reacciones, el contador y las respuestas están conectados; `allowReplies` bloquea la respuesta tanto visual como lógicamente.
- Crear Momento usa una tarjeta compacta rectangular y Mis Momentos muestra la audiencia real, no una etiqueta fija.

### Temas y widgets

- Una instalación nueva inicia en modo oscuro neutro mediante `Sin tema`; el antiguo rojo no se aplica implícitamente.
- Un tema personalizado sustituye por completo el lienzo anterior con colores opacos; no interpola negro con rojo/verde/azul.
- La preferencia de color/fondo del chat queda limitada a conversaciones y no vuelve a colorear ajustes, navegación o superficies globales.
- Android registra widgets separados de Mensajes, Videos, Momentos y Accesos rápidos, con destinos profundos funcionales.

### Mensajes, PlayMatch, Ajustes y Perfil

- El color global del chat modifica realmente las burbujas enviadas (Rojo, Blanco, Azul y Grafito) y conserva contraste legible.
- El fondo global de conversación aplica Negro, Textura, Noche o Limpio; un fondo configurado expresamente para una conversación conserva prioridad.
- PlayMatch permite cancelar la búsqueda o volver inmediatamente, detiene el sondeo local y solicita la cancelación idempotente al backend.
- Las fotos de perfiles gamer abren el visor nativo a pantalla completa, con zoom, paneo y navegación.
- Ajustes usa tarjetas con espaciado interno consistente; Perfil separa `Inicio` de `Presentación` y fue comprobado también a 360 dp.

### Llamadas y salas de voz

- Contestar desde la notificación abre directamente `IncomingCallActivity`; se eliminó el salto intermedio por `BroadcastReceiver` en Android moderno.
- La pantalla entrante se renderiza antes de construir el grafo pesado de WebRTC y repositorios.
- Aceptar inicia la conexión inmediatamente; la consulta del perfil continúa en segundo plano.
- Se protegió la aceptación duplicada, se conservaron extras de video y se aceleró la navegación posterior al permiso.
- Salas de voz evita solicitudes duplicadas de micrófono y ofrece reintento ante fallo de ingreso en vez de un loader infinito.

### Retiros de creadores

- El flujo valida KYC/MFA, saldo disponible, mínimo, moneda e idempotencia.
- El correo PayPal se almacena cifrado con AES-256-GCM.
- El backend usa ledger, bloqueo de concurrencia, worker, reconciliación y webhook firmado con deduplicación.
- El modo sandbox/deshabilitado mostrado por la UI es intencional hasta cargar credenciales y activar el feature flag.

## 3. Evidencia automática

| Validación | Resultado |
|---|---|
| `:app:testDebugUnitTest` | 96 suites, 362 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:app:assembleDebug` | APK generado correctamente |
| `:app:lintDebug` | PASS; 0 errores y 42 advertencias no bloqueantes |
| `npm run test:creator-payouts` | PASS |
| `npm run check` en backend local-fallback | PASS |
| `test:moments`, `test:creator-analytics`, `test:playmatch`, `test:socialization` | PASS |
| `test:live-collaboration`, `test:live-moderation` | PASS |
| Revisión de referencias TikTok en código LIVE | Sin marca TikTok visible pendiente |
| Revisión de callbacks vacíos en acciones LIVE | Sin acciones visibles deliberadamente vacías detectadas |

## 4. Evidencia manual local

- Entrada autenticada a Videos y Social sin parpadeo de estado vacío.
- Video de marco pequeño/horizontal centrado y con letterbox.
- Social Descubrir carga compositor e historias antes del feed y abre el compositor real.
- Social Siguiendo conserva el flujo previo.
- LIVE preparación abre desde Crear directo.
- Mejorar cambia el estado del filtro; Efectos filtra presets.
- Centro LIVE, recompensas, ajustes, Servicio+ y práctica abren y cierran correctamente.
- Sin cierres observados en `logcat` durante estos recorridos.
- Personalización Azul + Noche comprobada en una conversación real: las burbujas y el fondo cambian sin perder contraste.
- Perfil reorganizado comprobado en 1080 × 2400 y en un viewport compacto equivalente a 360 dp.
- Publicación real de un Momento `Solo yo`, actualización del feed, apertura del visor y persistencia `only_me/private`: PASS; el registro temporal fue eliminado después de comprobarlo.
- Selector de audiencia con siete opciones, respuesta, reacción, visor y tema Jade/Neutro comprobados en la APK instalada.
- Android Package Manager detectó los cuatro providers de widget esperados.

El APK debug final auditado pesa 306.093.014 bytes y su SHA-256 es `5510F725E821BA8C68A654BC71CF9679416DD47886C29F50711B7378C5D13292`.

## 4.1 Medición de arranque

Después de diferir la construcción de telemetría, sincronización y del grafo de llamada hasta que realmente se necesitan, se reinstaló el APK y se midieron tres lanzamientos con `am start -W`: 6.967 ms COLD, 4.813 ms WARM y 7.839 ms COLD; mediana 6.967 ms. El emulador usa renderizado por software y mostró variación elevada, por lo que estos números demuestran ausencia de regresión determinista, no un SLA de producción.

`IncomingCallActivity` no está exportada y no puede lanzarse directamente desde `adb shell`. El tiempo toque-en-Contestar → primer audio debe medirse desde una notificación real entre dos dispositivos.

## 5. Puertas obligatorias antes de producción

### A. Llamadas y salas de voz — bloqueante

Probar en dos teléfonos físicos y dos redes diferentes:

1. Aplicación abierta, en segundo plano, proceso terminado y teléfono bloqueado.
2. Contestar y rechazar desde notificación y pantalla completa.
3. Audio bidireccional, video, cambio de altavoz/auricular/Bluetooth y recuperación tras perder red.
4. Permiso de notificaciones denegado/aceptado y políticas de batería de al menos un fabricante restrictivo.
5. Verificar STUN/TURN y medir tiempo desde toque en Contestar hasta primer audio.

Criterio: ninguna llamada debe quedar conectando indefinidamente; el fallo debe mostrar reintento o cierre recuperable.

### B. LIVE real — bloqueante

1. Host y espectador en dos dispositivos.
2. App del espectador en foreground/background y reconexión de red.
3. Solicitud de invitado, aceptar/rechazar, máximo de participantes y salida inesperada.
4. Batalla, regalo, club, moderación, comentarios y finalización del host.
5. Validar `LIVE_GUESTS_BATTLES_ENABLED`, `LIVE_SFU_ENABLED`, credenciales de LiveKit/SFU, signaling y TURN de producción.
6. Durante una batalla/invitación, alternar cámara frontal, trasera y pantalla en ambos dispositivos; comprobar que no haya flash negro, pista huérfana ni desincronización de animaciones.

### C. Retiros — bloqueante

Configurar exclusivamente en el servidor seguro:

- `CREATOR_PAYOUTS_ENABLED`
- `PAYPAL_MODE`
- `PAYPAL_CLIENT_ID`
- `PAYPAL_CLIENT_SECRET`
- `PAYPAL_API_BASE_URL`
- `PAYPAL_WEBHOOK_ID`
- `PAYOUT_ACCOUNT_ENCRYPTION_KEY` de 32 caracteres o más
- `CREATOR_PAYOUT_MINOR_PER_COIN`
- `CREATOR_PAYOUT_MINIMUM_MINOR`
- `CREATOR_EARNINGS_HOLD_DAYS`
- `CREATOR_PAYOUT_CURRENCY`

Después, ejecutar un retiro sandbox completo, webhook, reintento idempotente, fallo de proveedor y reconciliación. El paso de tarjeta o cuenta real queda, como indicó el propietario, para su validación manual.

### D. Distribución — bloqueante para Play Store

- Incrementar `versionCode`/`versionName`.
- Generar AAB release con la clave real del propietario.
- Ejecutar instalación limpia y actualización desde la versión productiva.
- Revisar permisos, Data Safety, política de contenido generado por usuarios y reporte pre-launch de Google Play.

## 6. Riesgos no bloqueantes observados

- El proyecto mantiene advertencias de APIs obsoletas y recursos sin uso; no rompen el build, pero deben entrar en deuda técnica con responsable y fecha.
- Una medida de arranque en emulador con renderizado por software no sustituye Macrobenchmark en un teléfono físico de gama media/baja.
- “Cero bugs” no puede certificarse mediante análisis estático o una sola sesión; el criterio empresarial es no tener defectos críticos conocidos, telemetría activa, degradación controlada y rollback probado.

## 7. Decisión de salida

Estado actual: **CONDICIONAL / NO PROMOVER TODAVÍA A `main`**.

Promover únicamente cuando A, B, C y D estén documentadas como PASS. La rama queda preparada para esas pruebas cerradas; `main` permanece como la versión estable de producción.

## 8. Cierre visual del 12 de agosto de 2026

Se reinstaló el APK final de `feature/live-gifts` y se verificaron, en modo oscuro, Bandeja, Social, Perfil, Crear Momento, Perfil ajeno, destacado existente y PlayMatch. La evidencia está en `.codex-tmp/audit-20260812-dark/`.

Correcciones confirmadas:

- pestañas de Mensajes compactas y legibles;
- cabeceras e iconos de Perfil visibles en oscuro;
- audiencia y publicar visibles en Crear Momento;
- editor de texto/música y zoom funcional;
- nombre/avatar actuales en el carrusel de Momentos;
- destacados desacoplados del vencimiento del Momento mediante instantánea y recuperación por portada;
- perfiles con timeout y reintento en vez de spinner infinito;
- logo Novvin en PlayMatch y eliminación de `GRATIS`.

El APK auditado pesa 304.532.546 bytes y su SHA-256 es `324397FD1FE51F6F9456613932FBB2B25477E06DDAEAD516F4EE64954DB0CFE3`.

La decisión de salida no cambia: el APK está preparado para **prueba cerrada**, pero no debe promoverse a `main` hasta completar las puertas A–D con dispositivos y credenciales reales.

## 9. Cierre de música, PlayMatch y regalos del 15 de agosto de 2026

### Música de Momentos y LIVE

- El selector final muestra **Mi música** y **Comunidad**. La primera pestaña lee MediaStore del dispositivo; la segunda consulta exclusivamente pistas comunitarias aprobadas y reutilizables.
- La respuesta vacía de Comunidad ya no se presenta como `NOT_FOUND`: la ruta autenticada responde correctamente y la UI distingue entre carga, error recuperable y catálogo aprobado aún vacío.
- El mismo flujo de selección, preescucha, fragmento y volumen se reutiliza en la preparación y en la sesión LIVE; no existe un segundo reproductor divergente.
- La música de un Momento se prepara antes de iniciar el reloj visual. Una imagen conserva como mínimo seis segundos y nunca termina antes del fragmento musical seleccionado.
- La publicación local usa el bucket `community-music`, no `moment-media`, con lo que `audio/mpeg` deja de producir `Unsupported content type`.
- La comprobación manual autenticada confirmó Mi música, cambio a Comunidad, búsqueda/categorías y estado vacío legítimo sin `NOT_FOUND`, `INTERNAL_ERROR` ni excepción musical.

### PlayMatch

- Se corrigió la causa de `INTERNAL_ERROR`: producción no tenía la columna `fallback_users.avatar_media_type` que ya esperaba el cliente.
- Las migraciones `018_user_avatar_media_type.sql` y `019_playmatch_avatar_reconciliation.sql` agregan el tipo multimedia, validan `image/gif/video`, recuperan la identidad gamer y descartan solamente URL gamer rotas para heredar el avatar social vigente.
- Ya no hay bloqueo de «prueba cerrada» para un perfil autenticado. La pantalla principal, Free Fire, jugadores cercanos y el perfil gamer cargaron manualmente en el emulador.
- El avatar gamer de video se reconcilió con el perfil social y vuelve a reproducirse en bucle en lugar de quedar genérico o cargando indefinidamente.

### Catálogo y animaciones de regalos

- Se conservaron y conectaron **24 regalos originales Novvin**, desde Semilla Estelar (5 diamantes) hasta Génesis Aurora (5.000 diamantes), sin copiar nombres ni arte de TikTok.
- El selector ya no representa todos los regalos con el mismo icono: usa la ilustración propia de cada regalo y un fondo cósmico original de marca.
- Cada regalo tiene perfil de movimiento, efecto de escena, articulación, partículas, capas y plan sonoro propios. La interpolación produce **64 estados visuales efectivos por regalo**, dentro del objetivo de 50 a 100 estados, incluso cuando el atlas fuente contiene menos poses.
- Los niveles económicos usan celebraciones compactas; Dragón Celestial y los siete niveles superiores usan escenas de pantalla completa. La duración y complejidad aumentan con el precio.
- Los 24 sonidos son sintetizados localmente y distintos por perfil; los premium duran entre 2,6 y 4,8 segundos. El motor conserva foco/volumen seguro, libera `AudioTrack` con el ciclo de vida y degrada a silencio sin cerrar la app si el dispositivo rechaza audio.
- La celebración y el sonido se ejecutan solo después del evento confirmado por el servidor. Hay deduplicación, cola acotada y calidad adaptativa para evitar repetición, bloqueo o consumo excesivo en teléfonos modestos.
- Las pruebas de catálogo, límites, transacción, saldo insuficiente, autorregalo, concurrencia, idempotencia, reembolso, fallo de earning y rollback de base de datos pasan para los 24 regalos.

### Backend y despliegue de prueba cerrada

- Se aplicaron las migraciones 018 y 019 en la infraestructura de prueba, con copia de seguridad previa y servicios de API, base de datos, realtime, signaling y TURN saludables tras el despliegue.
- `npm run check`, `test:music`, `test:playmatch`, `test:live-collaboration`, `test:live-moderation`, `test:gift-limits` y `test:gift-flow`: PASS.
- La API pública responde `200` en `/ready`; música y PlayMatch exigen autenticación y responden `401` sin sesión, como corresponde.

### Artefacto final de esta revisión

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Tamaño: 308.208.881 bytes.
- SHA-256: `4AFFB0AEE7224A4E9AC5F55D7138DCF1B43B37FC43311BD3E1B443D4B280AEA8`.
- Gradle `testDebugUnitTest + lintDebug + assembleDebug`: **BUILD SUCCESSFUL**.

### Dictamen actualizado

No quedan defectos conocidos en los recorridos locales corregidos de Música, PlayMatch o presentación/transacción de regalos. La rama es candidata para **prueba cerrada**, no para promoción directa a `main`. Antes de producción pública siguen siendo obligatorias las pruebas de las secciones A–D, en especial un LIVE y una llamada entre dos teléfonos físicos en redes distintas, compra real o sandbox de un regalo y validación de las animaciones simultáneamente en host y espectadores.

## 10. Cierre de Momentos, reembolsos y resiliencia del 20 de agosto de 2026

### Duración y audio de Momentos

- Una imagen de Momento usa ahora una política única y autoritativa de **6.000 ms** en cliente, repositorio y servidor. El backend normaliza metadatos antiguos o inválidos y ya no permite que un teléfono termine la imagen a los tres segundos.
- El reloj visual no comienza hasta que el contenido y la pista musical estén preparados. El reproductor respeta el fragmento elegido, vuelve a intentarlo ante un fallo transitorio, no duplica audio al cambiar rápidamente de Momento y se libera al abandonar la pantalla.
- Las pruebas unitarias cubren metadatos heredados de tres segundos, imagen sin música, imagen con música y video con duración propia.

### Publicación de música comunitaria

- La publicación muestra progreso real por etapas desde la transferencia hasta la validación (`0–100 %`), impide cierres accidentales mientras sube y presenta confirmación final.
- Solo después de validar el archivo real con `ffprobe`, duración, metadatos, permisos y términos vigentes, el servidor marca la pista como pública/aprobada, emite el evento realtime y envía la notificación `community_music_published`.
- Una pista aprobada entra en el catálogo consultado por los demás usuarios; una subida incompleta o inválida nunca aparece como publicada.

### Reembolsos de Google Play y prevención de fraude

- La reconciliación de compras anuladas está activada en servidor cada cinco minutos y usa una ventana compatible de 29 días para la Voided Purchases API. Se mantiene idempotente ante eventos repetidos.
- Al recibir un reembolso se retiran primero las monedas restantes. Lo ya gastado queda como deuda visible en rojo, bloquea nuevos regalos y se amortiza antes de acreditar una futura compra verificada.
- Los regalos financiados con la compra anulada y los ingresos correspondientes del creador se revierten mediante trazabilidad por lote; comprador y creadores afectados reciben eventos/notificaciones. Los retiros siguen deshabilitados y la UI informa **Retiros próximamente** sin presentar una conversión monetaria ficticia.
- Se verificó el caso real reportado: de una compra de 70, las 25 monedas remanentes fueron retiradas, se registró deuda de 45 y se revirtieron seis asignaciones de regalos por 45, junto con sus seis ingresos de creador.
- Referencia oficial de operación: Google Play Billing recomienda Real-time Developer Notifications y Voided Purchases API para detectar y recuperar compras anuladas. RTDN queda activo como vía inmediata y la reconciliación periódica se conserva como respaldo.

### Offline-first comprobado

- Room sigue siendo la fuente local de verdad para usuarios, conversaciones, mensajes, grupos y comunidades. Los envíos pendientes se conservan y WorkManager los reintenta con restricción de conectividad y backoff exponencial, incluso después de cerrar el proceso o reiniciar el dispositivo.
- La descarga automática duradera ya no depende únicamente de abrir el historial de un chat directo: un mensaje multimedia entrante activa la precarga al guardarse en Room, y los flujos de grupos y comunidades aplican la misma política. La selección tiene pruebas unitarias para límite reciente, deduplicación, videos grandes y exclusión absoluta de `view_once_*`; la caché mantiene pruebas de aislamiento por cuenta, cuota, tipos permitidos y borrado selectivo.
- Prueba manual en `Novvin_QA_2`: se activó modo avión, se cerró y abrió Novvin y continuaron visibles la bandeja, avatares, conversación, imágenes y audios ya descargados. Un audio cacheado avanzó de `0:00` a `0:04` sin red; no hubo `FATAL EXCEPTION` ni ANR de Novvin. El contenido remoto nunca descargado, por definición, no puede abrirse sin conexión.

### Seguridad, compilación y servidor

- `npm audit` informa **0 vulnerabilidades conocidas** en API, web, signaling y realtime. Se actualizaron React Router, Socket.IO parser y dependencias transitivas vulnerables; el build web de producción terminó correctamente.
- API: validación sintáctica completa y 62 pruebas de backend aprobadas, incluidas RTDN, publicación musical, sincronización de Momentos, regalos, concurrencia, deuda y reembolso.
- Android: pruebas unitarias, lint, APK debug y APK release minificado terminan correctamente. La compilación release completa finalizó con `BUILD SUCCESSFUL`.
- Lint Android: `0 errores`, `30 advertencias` no bloqueantes. APK debug: 306.898.001 bytes, SHA-256 `1F587F5C241D204680F48F7E69C7902232CE51F68663373862CEBB936B9DD188`. APK release sin firma: 279.827.629 bytes, SHA-256 `B405262BED5428B4588D26E000DEE36DB0D00AF7E9D495A91D3CA9016044417E`.
- Despliegue en la mini-PC: respaldo `/home/alvaro/conectate-backups/20260820T233318Z`, release `conectate-mini-pc-20260820T233311Z`; base, API, realtime, signaling, TURN, edge y chatbot quedaron saludables. LIVE valida credenciales/tokens de SFU y Billing valida ocho paquetes y cuenta de servicio. El endpoint público `/ready` responde correctamente.

### Dictamen de esta revisión

La rama `feature/live-gifts` queda como **candidata para prueba cerrada**; `main` no fue modificado. No es correcto afirmar certificación ISO/IEEE/TIA solo por revisión de código: esas certificaciones requieren alcance, evidencias, proceso formal y auditor independiente.

Antes de producción pública siguen siendo bloqueantes:

1. LIVE, batalla, invitado, regalos y llamadas entre al menos dos teléfonos físicos y dos operadores/redes diferentes, incluyendo pérdida y recuperación de red.
2. Dominio público estable y certificado, en sustitución del túnel temporal `trycloudflare.com`.
3. AAB firmado por el propietario, reporte pre-launch de Play Console, Data Safety y pruebas de compra/reembolso desde el canal cerrado.
4. Definir y aprobar una ventana de retención/liquidación antes de habilitar retiros; hasta entonces el saldo del creador es informativo y no retirable.
5. Monitoreo, alertas, rollback y prueba de restauración observados durante el periodo cerrado.

## 11. RTDN de Google Play activado el 20 de agosto de 2026

- Proyecto Google Cloud: `nueva-red-social-c9d04` (número `524338870666`).
- Tópico: `projects/nueva-red-social-c9d04/topics/novvin-google-play-rtdn`.
- Suscripción push: `novvin-google-play-rtdn-push`, sin expiración, `ackDeadline` de 30 segundos y reintentos entre 10 y 600 segundos.
- Identidad OIDC exclusiva: `novvin-rtdn-push@nueva-red-social-c9d04.iam.gserviceaccount.com`; no se crearon claves descargables.
- El agente administrado de Pub/Sub tiene `Service Account Token Creator` solamente sobre esa identidad. La cuenta del sistema de Google Play tiene `Pub/Sub Publisher` solamente sobre el tópico RTDN.
- Play Console tiene habilitadas notificaciones de suscripciones, compras anuladas y todos los productos únicos.
- El webhook público valida audiencia, emisor, correo verificado y cuenta de servicio. Una petición sin OIDC responde `401`; no se usa secreto en URL.
- La notificación oficial de prueba de Play Console llegó a PostgreSQL como `notification_kind=test`, `status=processed`, `attempts=1` y resultado `test_notification_processed`.
- El backend conserva deduplicación persistente, lease de procesamiento, reintento seguro, hashes de identificadores y no persiste el token de compra ni el payload completo.
- Los productos únicos se acreditan una sola vez; compras anuladas y reembolsos revierten saldo, deuda y asignaciones de regalos de forma idempotente. Las revisiones pendientes quedan en modo `NEUTRAL` con evidencia de uso hasta una decisión operativa.

RTDN deja de ser un bloqueo para prueba cerrada. Antes de producción pública sigue siendo obligatorio sustituir el túnel temporal por un dominio HTTPS estable y actualizar de forma conjunta el endpoint push y su audiencia OIDC.

## 12. Incidente crítico de Momentos, botellas y música del 21 de agosto de 2026

### Clasificación y correcciones

| Prioridad | Incidente | Causa confirmada | Estado |
|---|---|---|---|
| P0 | Producción no podía arrancar de forma segura después de actualizar el stack | Faltaban secretos obligatorios, había comodines CORS/signaling y una referencia incorrecta a la función de firma de objetos | Corregido, desplegado y verificado |
| P1 | `Lanzar botella` mostraba `Not Found` | El APK conocía rutas de botellas que aún no estaban desplegadas en la API pública | Corregido; capacidades publicadas y E2E autenticado aprobado |
| P1 | La imagen del Momento parecía durar dos o tres segundos y la música no sonaba | El reloj comenzaba antes de que Coil terminara de mostrar la imagen y antes de que Media3 preparara la pista | Corregido; reloj y audio arrancan desde la misma transición de disponibilidad |
| P1 | Cámara → foto → `Done` regresaba a Nuevo Momento | La URI temporal no sobrevivía con seguridad la recreación producida por la actividad externa de cámara | Corregido con estado guardable, copia privada estable y entrada al recortador |
| P1 | `Usar la canción completa` y `Elegir para publicar` parecían no hacer nada | El selector hijo confirmaba la pista y luego cerraba también el diálogo padre | Corregido; ambas acciones conservan la selección y avanzan a `Publicar en Comunidad` |
| P2 | Un backend antiguo podía volver a aceptarse silenciosamente | El descubrimiento comprobaba `/ready`, pero no las funciones disponibles | Corregido con capacidades obligatorias y rechazo de endpoints incompatibles |
| P2 | El visor generaba un log por cada recomposición visible | Quedó instrumentación de depuración dentro del cuerpo del composable | Eliminado para evitar ruido y trabajo innecesario |

### Producción desplegada

- Respaldo: `/home/alvaro/conectate-backups/20260822T003705Z`.
- Release: `conectate-mini-pc-20260822T003705Z`.
- La respuesta pública `/ready` expone `anonymous_bottles_v1`, `community_music_v1`, `moment_music_v1` e `image_moments_exact_6s_v1`.
- Base, API, realtime, signaling, edge, chatbot y TURN quedaron saludables. El control de despliegue exige esas capacidades antes de declarar éxito.
- Las rutas reales de botella y música responden `401` sin sesión, no `404`; esto prueba que están montadas y protegidas.

### Evidencia automática

- Backend API: 49 archivos de pruebas aprobados y `npm run check` aprobado.
- Botellas públicas: prueba autenticada con tres cuentas temporales aprobada para lanzar, ofrecer, rechazar, reenrutar automáticamente, aceptar y abrir chat anónimo. Usuarios y registros temporales eliminados al finalizar.
- Android: 97 suites, 365 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Android Lint: `BUILD SUCCESSFUL`, 0 errores y 31 advertencias no bloqueantes.
- Web: 10 suites y 63 pruebas aprobadas, ESLint aprobado y build de producción aprobado.
- Realtime, signaling y chatbot: validaciones y pruebas aprobadas.
- Auditoría de dependencias de API, realtime, signaling y web: 0 vulnerabilidades conocidas de producción.
- Auditoría estática de seguridad de runtime: 0 hallazgos y 0 infracciones nuevas.

### Evidencia manual en `Novvin_QA_2`

- APK instalado y arranque en frío completado; `MainActivity` quedó activa sin `FATAL EXCEPTION` ni ANR.
- Cámara → Tomar foto → obturador → `Done` abrió `Recortar momento`; `Usar` abrió el editor.
- Música comunitaria: `Usar la canción completa` y `Elegir para publicar` avanzaron al formulario `Publicar en Comunidad`, conservando pista y duración. Se cerró sin publicar.
- Botella: un mensaje temporal pasó a `Tu botella está viajando` sin `Not Found`; `Retirar botella` lo eliminó inmediatamente.
- Momento temporal: se seleccionó una pista de 26,667 ms, la UI produjo el fragmento `0:00–0:06`, publicó la imagen, abrió el propio Momento desde `Tú` y regresó automáticamente al completar el progreso.
- AudioFlinger registró la pista de `conectate.com` activa durante el visor. `AudioTrack` se detuvo tras entregar 267,264 cuadros a 44.1 kHz, aproximadamente 6.06 s incluido el búfer del emulador; Media3 no reportó error. El Momento y los audios temporales se eliminaron después.

### Artefacto

- APK debug: `app/build/outputs/apk/debug/app-debug.apk`.
- Tamaño final: 305,916,559 bytes.
- SHA-256 final: `F30159755EF5E14D43CAB2AA3FCABFF6CC4BE02D581748D6B453F39089D82E54`.
- `testDebugUnitTest + assembleDebug`: `BUILD SUCCESSFUL`; 97 suites y 365 pruebas sin fallos, errores ni omisiones.

### Dictamen

Los P0/P1 descritos en esta sección están corregidos y el backend actualizado está desplegado. Esto no equivale a certificar ausencia absoluta de errores ni compatibilidad con todos los teléfonos. La rama sigue siendo candidata para prueba cerrada: aún son obligatorias las puertas físicas A–D, especialmente audio real de Momentos, llamadas y LIVE en al menos dos dispositivos y redes distintas, dominio estable y AAB firmado.

## 13. Videos, sonidos y personalización por cuenta del 22 de agosto de 2026

### Clasificación y resolución

| Prioridad | Problema confirmado | Resolución | Estado |
|---|---|---|---|
| P1 | Un video creado con `Usar sonido` podía publicarse sin conservar una pista de audio reproducible | El sonido seleccionado obliga a pasar por el editor; el exportador verifica pistas de video y audio y la publicación vuelve a validar el contenedor. Si no hay audio físico, la publicación falla de forma visible | Corregido y probado con publicación real |
| P1 | El audio reutilizado perdía identidad y no permitía reunir todos los videos que lo usan | Se añadió identidad canónica (`sound_id`, video fuente, título, artista y URL), validada en servidor y propagada por Android, PostgREST compatible y algoritmo | Corregido, migrado y desplegado |
| P1 | El feed podía conservar caché y orden de otra cuenta; la popularidad dominaba el orden | Caché, estado, trabajos y cursor quedan ligados a la cuenta activa. El ranking usa afinidad e historial por usuario, penaliza vistos, limita popularidad y añade exploración/descubrimiento con orden estable por cuenta | Corregido con pruebas por cuenta |
| P1 | Borrar un video quitaba sus metadatos pero dejaba el MP4 y la miniatura físicamente accesibles | El borrado autorizado elimina interacciones, clasificación, filas de storage, MP4, miniatura y variantes. El E2E exige que la URL pública responda `404` | Corregido y probado en producción |
| P2 | El usuario propietario no tenía una acción inequívoca `Eliminar video` en todos los menús | Los tres puntos del feed y la vista de detalle muestran `Eliminar video`, con confirmación y validación de propiedad en servidor | Corregido |
| P2 | La vista de sonido no reunía ni abría los videos relacionados | La hoja de sonido muestra título, artista, reproducción, contador y galería de todos los videos con el mismo identificador; cada tarjeta abre el video | Corregido |
| P3 | Un cambio normal de pantalla registraba una cancelación de corrutina como error | `CancellationException` se propaga sin registrarse como fallo funcional | Corregido |

### Evidencia funcional de extremo a extremo

- Se tomó `Sonido original — Angel`, se eligió `Usar sonido`, se seleccionó un video y el editor abrió automáticamente con la pista `Música añadida`.
- La exportación terminó con audio codificado; el video `codex_reuse_qa_20260822` se publicó en producción y PostgreSQL conservó el mismo `sound_id`, video fuente, título, artista y URL del sonido de Angel.
- El reproductor del video publicado abrió el decodificador AAC (`audio/mp4a-latm`) y `AudioTrack` entregó audio sin error de Media3.
- La hoja pasó a `Videos con este sonido (2)` y mostró juntos el original y el video reutilizado.
- La eliminación desde la aplicación quitó el video del servidor. Durante esa comprobación se detectó el archivo huérfano descrito arriba; después de corregirlo, el E2E público verificó también eliminación física y `404` de la URL.
- Los videos y archivos de las cuentas temporales del E2E fueron eliminados al finalizar. Los dos objetos temporales que precedieron a la corrección también fueron purgados de forma explícita.

### Evidencia automática

- Backend: `npm run check` aprobado; 49 archivos y 65 pruebas TAP aprobadas, 0 fallos.
- E2E público de Videos: favoritos independientes, comentarios realtime, reacciones, edición, reportes, stickers, privacidad, orden estable, preferencias de perfil, identidad de sonido original/reutilizado, consulta por sonido, propiedad de borrado y purga física aprobados.
- Android: 98 suites, 367 pruebas, 0 fallos, 0 errores y 0 omitidas. `compileDebugKotlin`, `testDebugUnitTest` y `assembleDebug` finalizaron con `BUILD SUCCESSFUL`.
- Android Lint: `BUILD SUCCESSFUL`, 0 errores y 31 advertencias no bloqueantes.
- APK final: `app/build/outputs/apk/debug/app-debug.apk`, 306.970.199 bytes, SHA-256 `DE51BE04FD5DA73C2C272795084D2F3325D14ED7CA5348E4EBD14E9467EB6A76`.

### Producción desplegada

- Respaldo previo: `/home/alvaro/conectate-backups/20260822T042857Z`.
- Release: `conectate-mini-pc-20260822T042855Z`.
- La migración `021_video_sound_identity.sql` está aplicada y `/ready` publica `video_sound_identity_v1`.
- Base, API, realtime, signaling, edge, chatbot y TURN quedaron saludables; la validación se ejecutó contra `https://mandate-sarah-compare-fit.trycloudflare.com`.

### Alcance real de la garantía de seis segundos

La política de Momentos de imagen fija exactamente `6000 ms` en cliente, repositorio y servidor; el reloj no comienza hasta que imagen y audio están disponibles y ambos se detienen juntos. Las pruebas automáticas impiden avanzar antes de seis segundos y el emulador entregó aproximadamente 6,06 s incluyendo su búfer. Esto demuestra el contrato y la ejecución observada, pero no permite prometer honestamente compatibilidad física del 100 % con todos los modelos, códecs y fabricantes sin una matriz de teléfonos reales. Esa puerta física continúa siendo obligatoria antes de producción pública.

## 14. Cierre reforzado de Momentos y feed en vivo del 22 de agosto de 2026

### Clasificación de los incidentes confirmados

| Prioridad | Problema confirmado | Causa raíz | Resolución y estado |
|---|---|---|---|
| P0 | Un video eliminado podía seguir visible en otros teléfonos hasta recargar | La API borraba el recurso, pero no publicaba una invalidación del feed y Android no estaba suscrito a una fuente de verdad en vivo | Creación y borrado publican eventos autenticados en `feed:live`; Android inserta el alta sin desplazar el video actual y purga inmediatamente el borrado de todas las listas y cachés activas. Corregido, desplegado y probado en producción |
| P1 | En algunos teléfonos una imagen de Momento terminaba cerca de un segundo | El progreso usaba `Animatable/tween`, que respeta la escala de duración de animaciones de Android; una escala `0` podía convertir los seis segundos funcionales en una transición instantánea | El tiempo funcional usa `SystemClock.elapsedRealtime()`, independiente de las opciones de animación. Corregido y probado con las tres escalas del emulador en `0` |
| P1 | La canción aparecía en la cabecera, pero podía no oírse | `STATE_READY` se trataba como reproducción iniciada aunque Media3 todavía no estuviera entregando audio | Preparación y reproducción efectiva son estados separados; la ventana de seis segundos comienza solo con `onIsPlayingChanged(true)` y el audio se detiene en el mismo límite visual. Corregido |
| P1 | Una pista válida en el teléfono publicador podía ser incompatible con el decodificador de otro fabricante | El servidor conservaba formatos de dispositivo como WAV, OGG/Opus o FLAC y delegaba toda la compatibilidad al teléfono receptor | Toda música de imagen se renderiza en el servidor a M4A AAC-LC, 44,1 kHz, estéreo, con duración autoritativa de 6 s y `faststart`. Corregido, desplegado y probado mediante decodificación real |
| P1 | El feed repetía el mismo orden al reabrir y podía coincidir entre cuentas | La semilla heredada dependía de cuenta y día; la sesión de apertura no llegaba al ranking | Cada apertura, reanudación y actualización obtiene un `feedSessionId` nuevo; cuenta, historial, preferencias y exploración participan en el ranking, sin volver a ordenar únicamente por popularidad. Corregido |
| P1 | Los videos nuevos no aparecían mientras otros usuarios recorrían el feed | Existía el canal realtime, pero las rutas de creación/borrado no publicaban eventos de feed | Las mutaciones publican el evento y el repositorio Android mantiene una suscripción por cuenta. Corregido y probado en producción |

### Evidencia real de extremo a extremo

- Momento musical publicado mediante API real: duración de imagen `6000 ms`, duración musical `6000 ms`, códec `aac-lc`, 44.100 Hz, dos canales y volumen máximo decodificado de `-11,5 dB`; la pista no fue silenciosa.
- La cuenta espectadora recibió la URL canónica; al borrar el Momento, esa URL respondió `404` y no quedaron filas `moment-aac-compatibility-render` en almacenamiento.
- En Android se recorrió galería → recorte 4:5 → editor → música local → corte `0:00–0:06` → publicación → apertura desde la tarjeta `Tú`. El toque abrió el visor y no volvió al selector de galería.
- Con `window_animation_scale`, `transition_animation_scale` y `animator_duration_scale` en `0`, el visor continuó avanzando con reloj monotónico. `AudioFlinger` mostró una pista activa del proceso `conectate.com`, AAC decodificado a 44,1 kHz y estéreo; el Momento temporal se eliminó al finalizar la prueba.
- El E2E público de Videos confirmó `feedCreateRealtime=true` y `feedDeleteRealtime=true`, además de identidad de sonido original/reutilizado, consulta de todos los videos por sonido, privacidad, propiedad de borrado y purga física del archivo.

### Evidencia automática y artefacto

- Android: 98 suites, 369 pruebas, 0 fallos, 0 errores y 0 omitidas. `testDebugUnitTest + assembleDebug + lintDebug`: **BUILD SUCCESSFUL**.
- Android Lint: 0 errores y 31 advertencias no bloqueantes.
- API: `npm run check` aprobado y los 34 scripts `test:*` aprobados; las pruebas de Momentos incluyen política de rutas y renderizador de compatibilidad.
- Realtime gateway: validación y pruebas aprobadas, incluida suscripción autorizada, rechazo no autorizado y entrega interna real de `feed:live`.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`, 304.499.092 bytes, SHA-256 `EACEABF0AF945D62AF02B0CCC8B6CF2AF6C371C030AC5B1B386F11C7B02C9215`.

### Producción desplegada

- Respaldo final previo: `/home/alvaro/conectate-backups/20260822T062434Z`.
- Release: `conectate-mini-pc-20260822T062433Z`.
- `/ready` publica `moment_music_aac_lc_v1`, `video_feed_session_v1` y `video_feed_live_v1` junto con `image_moments_exact_6s_v1`.
- API, base, realtime, signaling, edge, chatbot y TURN quedaron saludables; no aparecieron errores, excepciones fatales ni rechazos no controlados en los registros posteriores a las pruebas.

### Dictamen actualizado

Los incidentes P0/P1 de esta revisión están corregidos, probados y desplegados en `feature/live-gifts`. La evidencia cubre contrato de servidor, decodificación audible, ejecución Android con animaciones desactivadas y propagación realtime real. No se declara ausencia absoluta de defectos ni compatibilidad física universal: antes de producción pública todavía debe ejecutarse la matriz de teléfonos reales, fabricantes, versiones Android, salidas de audio y condiciones de red indicada en las puertas A–D.
