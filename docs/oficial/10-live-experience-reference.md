# Novvin LIVE — contrato oficial de experiencia Android

Fecha base: 2026-08-11  
Rama autorizada: `feature/live-gifts`  
Estado: especificación canónica del rediseño y lista de verificación de implementación.

## 1. Objetivo

Reemplazar la interfaz LIVE experimental por una experiencia móvil de pantalla completa inspirada en el flujo actual de TikTok LIVE, conservando la marca Novvin y conectando cada control con las capacidades reales del cliente Android y del backend Ubuntu.

La referencia visual principal son las nueve capturas entregadas por el propietario el 11 de agosto de 2026. Las referencias funcionales son documentación pública actual de TikTok y TikTok LIVE Studio. No se deben copiar logotipos, nombres, ilustraciones ni recursos propietarios de TikTok; se replica la jerarquía, densidad, interacción y comportamiento con identidad Novvin.

## 2. Evidencia funcional vigente

- El inicio móvil parte de `+ > LIVE`, permite título, herramientas y ajustes, y mantiene herramientas y seguridad accesibles durante la emisión: [TikTok — What is TikTok LIVE?](https://support.tiktok.com/en/live-gifts-wallet/tiktok-live/what-is-tiktok-live).
- El modo práctica es una sesión privada para probar audio, vídeo y herramientas antes de emitir públicamente: [Google Play — How to go LIVE on TikTok](https://play.google.com/store/apps/editorial?id=mc_apps_editorial_global_useredu_tiktok_feature_deep_dive_fcp&hl=en_US) y [TikTok Shop Academy — New Seller Guide](https://p16-oec-university-file-ttp-sign.tiktokcdn-us.com/tos-useast5-i-hes5c89316-tx/07377a8365194aafa412fc2f3306481c.pdf).
- Multiinvitado admite invitación del creador o solicitud del espectador, previsualización de cámara/micrófono y entrada después de aceptar; la documentación indica una transición objetivo de cinco segundos: [TikTok — Multi-guest LIVE](https://support.tiktok.com/id/live-gifts-wallet/tiktok-live/tiktok-live-multi-guest?lang=fr).
- Las partidas LIVE parten de una emisión conjunta, pueden ser individuales o por equipos y puntúan con Me gusta, todos los Regalos o un Regalo específico: [TikTok — Partida LIVE](https://support.tiktok.com/es/live-gifts-wallet/tiktok-live/live-match).
- Antes y durante el LIVE deben existir moderadores, audiencia 18+, comentarios, palabras bloqueadas, cuentas silenciadas y bloqueadas: [TikTok — Moderación LIVE](https://support.tiktok.com/es/live-gifts-wallet/tiktok-live/moderating-on-tiktok-live).
- Los espectadores participan viendo, comentando, siguiendo, compartiendo, suscribiéndose y enviando Regalos; los Regalos consumen Monedas y están limitados por edad/región: [TikTok — Regalos](https://support.tiktok.com/es/live-gifts-wallet/gifts/gifts) y [TikTok — LIVE](https://support.tiktok.com/es/live-gifts-wallet/tiktok-live/).
- Centro LIVE consolida rendimiento histórico: espectadores, interacción, recompensas, actividad, demografía y análisis por vídeo: [TikTok LIVE Studio — Analiza tu rendimiento](https://www.tiktok.com/live/studio/help/article/Track-your-performance-and-grow/Analyze-your-performance-and-understand-viewers?lang=es).
- LIVE Studio organiza escenas, fuentes, widgets, audio, vista previa móvil, estadísticas y chat. Los diseños admiten vertical, horizontal y dual: [LIVE Studio — Conceptos básicos](https://www.tiktok.com/live/studio/help/article/Get-started-with-your-first-LIVE/Learn-the-basics-of-LIVE?lang=en) y [LIVE Studio — Dual layout](https://www.tiktok.com/live/studio/help/article/Enhance-visuals/Use-dual-layout-to-go-LIVE-in-portrait-and-landscape-simultaneously?lang=en).
- El co-host de juego documenta hasta tres invitados además del anfitrión y layouts de escena actual o pantalla compartida: [LIVE Studio — Gaming co-host](https://www.tiktok.com/live/studio/help/article/Gaming-Co-host/Gaming-Co-host).

## 3. Principios del rediseño

1. **La cámara es el lienzo.** En Cámara del dispositivo no existe una tarjeta de previsualización separada: el `SurfaceViewRenderer` ocupa toda la zona útil, detrás de los controles.
2. **Una sola acción dominante.** `Emitir LIVE` usa el color de acción `#FF2D55`, altura mínima 54 dp y esquinas de 28 dp.
3. **Controles ligeros sobre vídeo.** Iconos blancos de 24–28 dp, etiquetas de 12–13 sp y áreas táctiles invisibles de al menos 48 dp.
4. **Paneles de abajo hacia arriba.** Centro LIVE, Recompensas, Ajustes, Mejorar, Efectos y Servicio+ usan `ModalBottomSheet`, fondo `#111111`/`#1F1F1F`, esquinas superiores de 24 dp y scrim negro al 55%.
5. **Densidad adaptable.** En alturas menores de 700 dp se reduce separación y texto secundario, nunca el área táctil ni la acción principal. En tablet el contenido inferior se limita a 520 dp y permanece centrado.
6. **Estado real, no decoración.** Cada botón debe abrir un estado funcional, persistir su selección y reflejarla en cámara, signaling o backend cuando corresponda.
7. **Sin pantallas vacías transitorias.** Toda carga inicial muestra progreso/skeleton; el mensaje vacío solo aparece después de una respuesta final sin datos.

## 4. Flujo del creador

### C1 — Preparación con cámara

- Vista edge-to-edge con cámara frontal real y botón cerrar arriba a la izquierda.
- Arriba a la derecha: acceso a `Centro LIVE` o cápsulas de `Recompensas`, nivel y Centro.
- Mensaje contextual de práctica y botón `Practicar` cuando corresponda.
- Herramientas sobre el tercio inferior: `Girar`, `Mejorar`, `Efectos`, `Ajustes`, `Servicio+`.
- Tarjeta inferior translúcida: portada cuadrada, título editable y botón `Emitir LIVE`.
- Selector horizontal inferior de fuentes: `Cámara del dispositivo`, `Juegos para dispositivos móviles`, `LIVE Studio`.
- No se crea el stream hasta pulsar `Emitir LIVE`. La navegación posterior siempre ocurre en `Dispatchers.Main`.

### C2 — Juegos para dispositivos móviles

- Fondo oscuro/verde sin mostrar la cámara como fuente principal.
- Herramientas: Consejos, Club de fans, Paisaje, Cámara flotante, Compartir cámara, Compartir y Ajustes.
- Título, tema/juego y acción `Emitir LIVE`.
- Al iniciar se solicita `MediaProjection`; si el usuario cancela, se conserva la configuración y se muestra error legible.

### C3 — LIVE Studio

- Página informativa Novvin Studio con ilustración propia, descripción de escenas/fuentes y botón `Obtener acceso` o `Abrir guía`.
- No debe fingir conexión con TikTok LIVE Studio. Novvin expone su propia elegibilidad y configuración de signaling/RTMP cuando exista.

### C4 — Modo práctica

- Sesión local/privada, nunca visible en descubrimiento ni notificada a seguidores.
- Cabecera: avatar, nombre, `0 espectadores`, botón `Emitir LIVE` y salir.
- Mensajes de bienvenida y aviso de que los ajustes pueden cambiar al LIVE real.
- Controles inferiores para co-host, invitados, chat, compartir, efecto activo y más.
- La cámara y el micrófono se prueban, pero el stream persistente solo se activa al convertir la práctica en LIVE.

### C5 — Centro LIVE

- Bottom sheet grande con onboarding/carrusel inicial y CTA `Iniciar LIVE`.
- Secciones reales: campañas, resumen de últimos 7/30/60 días, análisis por directo y atajos a repeticiones.
- Métricas: visualizaciones, únicos, promedio/pico concurrente, duración media, seguidores, comentarios, likes, compartidos, regaladores y diamantes.

### C6 — Recompensas LIVE

- Pestañas `Este LIVE` y `Esta semana`.
- Tarjeta de ingresos/diamantes, progreso semanal, misiones y porcentaje.
- Los importes proceden del ledger del backend. Nunca se calcula saldo retirable solo en cliente.
- El panel informativo `Que el mundo te escuche` ofrece carga de audio únicamente cuando exista soporte de storage/licencias; de lo contrario explica disponibilidad sin CTA falso.

### C7 — Efectos y mejorar

- `Mejorar`: intensidad de suavizado/iluminación y filtros de color reales soportados por `LiveCameraFilter`.
- `Efectos`: visor de categorías, búsqueda, favoritos, recientes y catálogo descargable.
- La primera entrega debe incluir `Sin efecto`, cálido, frío, blanco y negro, cine, neón y los filtros ya soportados. Los efectos faciales AR solo se anuncian cuando exista seguimiento facial real; no se simulan miniaturas sin motor.

### C8 — Ajustes y Servicio+

- Fuente, cámara, micrófono, calidad, orientación, comentarios, regalos, solicitudes de invitados, moderadores, audiencia 18+, palabras bloqueadas, notificación de límite de regalos y modo práctica.
- `Servicio+` agrupa diagnóstico de red, estado de cámara/micrófono, guía de iluminación, prueba de audio y ayuda.

### C9 — Directo activo del creador

- Vídeo a pantalla completa; cabecera compacta con identidad, métricas y finalizar.
- Chat y eventos en el tercio inferior izquierdo; acciones en la fila inferior.
- Solicitudes de invitados aparecen inmediatamente, pero se aceptan solo tras confirmación autoritativa del backend.
- Co-host: panel, cuadrícula y fijo; máximo único compartido por UI, API y signaling.
- Batalla: invitación, selección solo/equipo, todos los regalos/regalo específico, cuenta atrás, marcador, resultado victoria/derrota/empate.
- Animaciones de regalo: cola secuencial, combinación por remitente/regalo, duración limitada, cancelación al salir y fallback accesible si el dispositivo es de gama baja.

## 5. Flujo del espectador

### V1 — Visualización

- Vídeo vertical full-bleed con `FIT` para pantalla compartida y `FILL` para cámara.
- Cabecera: avatar, nombre, seguir, espectadores y cerrar.
- Columna/área inferior: chat, campo de comentario, compartir, multiinvitado, regalos y Me gusta.
- Los overlays se superponen sin reservar un bloque negro central.

### V2 — Solicitar unirse

- El espectador elige audio o vídeo y previsualiza permisos antes de enviar.
- La solicitud permanece `enviando` hasta que la API la persiste; después se notifica al host por Socket.
- Tras aceptar, se obtiene token SFU y se entra en un objetivo máximo de cinco segundos. Error o timeout permite reintentar sin estados fantasma.

### V3 — Regalos, club y batallas

- Catálogo de regalos con saldo, recarga y destinatario cuando hay invitados.
- Confirmación autoritativa antes de descontar/animar; la animación se muestra a todos desde evento de servidor.
- Club de fans, misiones y regalos especiales dependen de elegibilidad y región.
- En batalla se muestra marcador y criterio de puntuación; el empate tiene estado propio.

## 6. Máquina de estados mínima

`PREP_CAMERA | PREP_GAME | PREP_STUDIO | PRACTICE | STARTING | LIVE_HOST | LIVE_VIEWER | JOIN_REQUESTED | GUEST_PREVIEW | GUEST_CONNECTED | ENDING | SUMMARY`

Paneles superpuestos: `NONE | LIVE_CENTER | REWARDS | SONG_PROMO | ENHANCE | EFFECTS | SETTINGS | SERVICE_PLUS | GUESTS | BATTLE | GIFTS | MODERATION`.

Los estados de preparación viven en ViewModel/SavedStateHandle; los paneles efímeros usan `rememberSaveable`. Ninguna navegación, Snackbar ni actualización de `NavController` puede ejecutarse fuera del hilo principal.

## 7. Contratos de servidor obligatorios

- API y signaling comparten `maxGuests`, `allowGuestRequests`, elegibilidad, estado activo y permisos.
- `requestGuestAccess`: primero persiste, luego emite evento; si falla, no queda `PENDING` local.
- `respondToGuestRequest`: backend autoritativo primero; el Socket solo difunde el resultado confirmado.
- El modo práctica nunca marca `is_active=true` ni dispara notificaciones.
- Batallas y regalos son idempotentes y usan IDs/nonce para evitar doble conteo.
- Los eventos de regalos incluyen `eventId`, remitente, receptor, catálogo/asset, cantidad, combo, timestamp y variante de animación.

## 8. Criterios de aceptación

- Compila `assembleDebug` y pasan unit tests, lint viable y pruebas de backend LIVE/gifts.
- Pruebas Compose para cada estado C1–C9 y V1–V3, más capturas en 360×640, 360×800, 412×915 y tablet.
- De `Emitir LIVE` a sala del host sin excepción de hilo principal.
- Ninguna solicitud de invitado atraviesa solicitudes cerradas o supera el máximo.
- Regalo visible durante tiempo finito; las animaciones se encadenan sin tapar controles esenciales.
- Rotación, cambio de tamaño y recreación de proceso conservan preparación y resumen.
- `main` permanece sin cambios; todo se valida y versiona en `feature/live-gifts`.

## 9. Fuera de alcance de una réplica legítima

- No usar marca, logotipo, textos comerciales ni recursos gráficos propietarios de TikTok.
- No afirmar compatibilidad con TikTok LIVE Studio ni acceso a sus servidores.
- No ofrecer efectos faciales, música o programas de recompensas que carezcan de motor, licencias o backend real.
- La equivalencia buscada es de patrón, jerarquía, comportamiento y calidad; la identidad y la infraestructura son Novvin.

