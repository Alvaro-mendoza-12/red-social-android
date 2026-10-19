# Análisis de integración: historial, invitaciones y creación

## Orden y criterio de cierre

Cerrar publicación 9.1.10. Después: continuidad del historial (prioridad alta), invitaciones/QR y notificaciones; nubes accesibles; creación/edición de videos y personalización. Conservar permisos, URLs existentes, aislamiento por cuenta y los contratos de la API. No se considera corregido un flujo solo porque exista un botón.

## Investigación consultada

- [Android: aplicaciones que funcionan sin conexión](https://developer.android.com/topic/architecture/data-layer/offline-first): usar almacenamiento local como fuente de lectura y sincronizar con la red. Aplicación: respuestas vacías o errores de red no deben equivaler a ordenar un borrado del historial.
- [WhatsApp: invitación por enlace o QR](https://faq.whatsapp.com/3242937609289432/): flujo de compartir invitación desde la información del grupo. Aplicación: un mismo enlace debe servir para copiar, enviar, mostrar QR y leer una imagen; el servidor sigue comprobando acceso y revocación.
- [WhatsApp: menciones en grupos](https://faq.whatsapp.com/521835819370205/): una mención directa puede superar el silencio del grupo. Aplicación: excepción limitada al destinatario mencionado; no omitir bloqueos, baja de la sala ni permisos del sistema.
- [Diez heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/): estado visible; lenguaje familiar; control/salida; consistencia; prevención; reconocimiento; eficiencia; minimalismo; recuperación; ayuda contextual. Son criterios de revisión, no una certificación.
- [W3C: pausar movimiento](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide) y [movimiento reducido](https://www.w3.org/WAI/WCAG22/Techniques/css/C39): animación decorativa con pausa explícita y respeto a preferencias del sistema.
- [TikTok: edición y publicación](https://support.tiktok.com/en/using-tiktok/creating-videos/editing-posting-and-deleting): vista previa, texto, efectos, portada, borrador y eliminación. Referencia de flujo; no se atribuye acceso a tecnología propietaria.
- [CapCut: recortar/dividir](https://www.capcut.com/tools/online-video-trimmer): selección temporal y herramientas agrupadas; [Filmora: edición](https://filmora.wondershare.com/guide/editing-video.html): recorte, color, velocidad y restablecer. Solo añadir controles que lleguen realmente a la exportación.
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker): selección de solo videos y permisos limitados. Un catálogo propio debe conservar la alternativa del sistema y admitir acceso parcial, sin exigir toda la galería.
- [OWASP MASVS](https://mas.owasp.org/MASVS/): almacenamiento y autorización comprobados por separado. Esta revisión no constituye certificación ISO ni auditoría externa.

## Contratos funcionales y de integración

| Área | Resultado requerido | Impactos a controlar |
|---|---|---|
| Historial | Entrar, recibir, cambiar rol o editar la sala no cambia la fecha original de pertenencia. El vacío de red no borra caché. | Reingreso real conserva las restricciones del periodo de ausencia; no ampliar acceso a exintegrantes. |
| QR | Cualquier integrante puede encontrar Compartir; enlace legible con token de invitación cuando corresponde; QR, copia y envío. | Reusar rutas actuales y tokens revocables. Nunca incluir claves de acceso del usuario. Solo generar invitaciones a salas autorizadas. |
| Lector | Cámara, linterna y lectura de imagen elegida; previsualizar la sala antes de unirse. | Validar dominio, tipo e identificador; imagen grande acotada; liberar cámara y bitmap. No abrir URLs arbitrarias. |
| Menciones | Nombre azul pulsable hacia el perfil; aviso específico al mencionado aunque el grupo esté silenciado. | Resolver pertenencia/alias en servidor; no notificar texto que imita una mención fuera del contrato; deduplicar. |
| Reacciones | Aviso al autor de un mensaje al recibir reacción de otra persona; abrir el mensaje correcto. | Evitar autoavisos, notificaciones al retirar, duplicados y mensajes borrados. Aplicar bloqueos y preferencias. |
| Nubes | Hasta tres respuestas blancas, texto oscuro, movimientos suaves alrededor de la pregunta, sin ocultarla. | Misma API e IDs; límites de ancho y fuentes grandes; lista completa accesible; pausa; no anunciar cada cambio decorativo por lector. |
| Videos | Elegir solo videos, previsualizar, editar, deshacer/restablecer, publicar con progreso/reintento. | MIME/tamaño/duración en cliente y servidor; borrador por cuenta; filtros/volumen/recorte consistentes con exportación; cancelación libera recursos. |
| Perfil | Secciones claras y vista previa de portada/efecto; animación acotada y legibilidad estable. | Misma configuración persistida, contraste del contenido, movimiento reducido y visibilidad del perfil ajeno; no tapar botones. |
| Visitas/directos | Actualización de visitas y aviso al seguir a un creador que empieza un directo. | Consentimiento/privacidad, deduplicación, exclusión de autoavisos y de usuarios bloqueados. |

## Requisitos no funcionales

- No borrar ni recrear bases locales como respuesta a una sincronización vacía.
- Operaciones de pertenencia y límites bajo transacción; reintentos idempotentes.
- Lectura accesible con fuentes grandes; blancos y oscuros con contraste; objetivos táctiles de al menos 48 dp en Android.
- Animación por transformaciones de render, sin alterar medidas del chat continuamente; detener fuera de primer plano y permitir pausa.
- Ningún cambio visual requiere otra URL, API key o cambio de autenticación.
- Publicación con compilación, verificaciones dirigidas y reversión; no añadir actividad artificial a cuentas reales para probar notificaciones.

## Hallazgos de análisis inicial (corregidos en 9.1.11)

1. Compartir grupo existe dentro de un menú condicionado a administradores; no es visible a los demás miembros. Comunidad no ofrece el mismo flujo en su detalle. Web tampoco presenta esa acción en CollectionInfo.
2. El cambio de información de una sala importada crea una pertenencia local con NOW() cuando todavía no hay fila local: puede desplazar su periodo de historial. El cambio de rol de grupo también reemplaza joined_at en caché con la hora actual.
3. La sincronización Android elimina físicamente filas que la política recibida considera fuera del periodo. Una fecha desplazada puede convertir un problema de permiso/representación en pérdida de la copia local. Debe corregirse la fecha y separar ocultación de contenido y borrado explícito.

## Ampliación: controles, comentarios y contadores

Referencias primarias revisadas: [W3C, agrupación de controles](https://www.w3.org/WAI/ARIA/apg/patterns/toolbar/), [MDN, audio y controles nativos](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/audio), [ISO/IEC 25010:2023, modelo de calidad](https://www.iso.org/standard/78176.html), [ISO/IEC/IEEE 29148:2018, ingeniería de requisitos](https://standards.ieee.org/ieee/29148/6937/). Se consultaron las descripciones públicas; no se afirma certificación ni conformidad integral con los textos completos.

| Requisito verificable | Implementación y alcance | Control de regresión |
|---|---|---|
| V-01: una acción para abrir reacciones | Un botón, selector de ocho reacciones, cierre al seleccionar, Escape o pulsación exterior. | Objetivos de 44 px, etiquetas accesibles y foco devuelto. No cambia la señalización de reacciones. |
| V-02: ocultar y volver a mostrar chat en cine | Visibilidad del panel ligada al estado del chat; cine conserva espacio para el chat solicitado. | Vista a 390 y 1182 px sin desbordamiento; no recrea la sesión WebRTC. |
| V-03: enlaces activos y fotos en sala | Enlaces HTTP(S) pulsables sin cargar contenido externo automáticamente; fotos con vista previa explícita y codificación JPEG de hasta 720 px / 132000 caracteres. | Servidor verifica pertenencia, identidad, tipo, cabecera y dimensiones. No crea una URL pública. Solo para miembros conectados; contenido de sesión, máximo 100 entradas y diez fotos retenidas por cliente. |
| C-01: escuchar comentarios de audio | Se conservan media_url/media_type de Android y formato histórico [Audio] URL; reproductor existente StreamingAudio con controles y precarga de metadatos. | MIME/URL seguras; texto sin audio sigue como texto; sin UUID presentado como nombre de usuario. |
| C-02: compositor compacto | Campo flexible y botón de ancho intrínseco; nombres y fechas se ajustan al ancho. | Etiqueta de campo y botón habilitado solo con contenido. |
| M-01: mensajes propios no crean novedades | Lectura usa fecha original de la instantánea del servidor, antes de superponer vista previa optimista. FCM de llamadas/invitaciones no crea avisos pendientes de mensajes. | Prueba de política de notificaciones y batería del contador; no elimina mensajes recibidos sin leer. |
| Q-01: acceso a vincular web | Icono de escáner en cabecera de bandeja Android que abre el flujo existente de vinculación. | Reutiliza ruta y autorización existentes; separado del lector de invitaciones. |

Los controles no usan un rol ARIA toolbar porque mantienen el recorrido estándar por Tab; se evita prometer navegación por flechas no implementada. El selector está marcado como grupo de botones. Las nuevas fotos no modifican chat privado, almacenamiento de documentos, credenciales, claves API ni el contrato de medios de llamadas/directos.


## Nueva revisión visual y de paridad — capturas recibidas al cerrar 9.1.12

Orden: cerrar APK 9.1.12 y su verificación; después implementar este bloque sin modificar esa entrega cerrada.

1. Búsqueda web: filas compactas, avatar/identidad/bio/acción principal alineados; formulario de reportar oculto hasta solicitarlo; sin botones partidos ni desbordamientos en 320–1920 px.
2. Bandeja de Voz: ocupación visible con participantes actuales; entrada/salida actualiza conteo por eventos, sin importar canales de comunidades ni datos históricos de participantes desconectados.
3. Ctrl+V confirmado por el usuario: vista previa, Enviar, Cancelar. Menú de tres puntos para eliminar mensajes propios para todos, autorizado por servidor. Decisión de diseño dentro del rango solicitado: chat temporal de 90 minutos para texto y fotos, sin introducir archivos permanentes en el servidor. Actualmente el socket reenvía las fotos en datos JPEG y no escribe archivos; agregar caducidad coordinada y borrado explícito, confirmar/reintentar envío sin duplicados, límites de memoria y compatibilidad Android/web.
4. Comentarios de Social y Videos: audio, stickers, reacciones, menciones, respuestas agrupadas bajo el original con contador y expandir/ocultar hilo; paridad de permisos de editar/eliminar y avisos de mención. Reducir medios y compositor para que no dominen la pantalla.
5. Videos Recomendados/Amigos: comprobar que consultan fuentes distintas y que cambia el contenido según la relación de amistad; evitar solo cambiar la etiqueta.
6. Fechas Android: se encontró CommentSheet.kt usando created_at.take(10), que muestra el día UTC. Mostrar el instante en la zona del dispositivo sin cambiar los datos UTC guardados.
7. Comunidades: confirmar admins escriben por defecto, propietarios gestionan roles, cupos semanales configurables, preguntas respondibles por integrantes y reacciones permitidas; compositor sustituido por explicación cuando no puede escribir, distintivo de administrador.
8. Grupos: modo solo administradores con el mismo bloqueo real de escritura y reacciones permitidas; expulsados conservan historial permitido, sin escribir y sin eliminar el grupo o su caché.
9. Nubes: movimiento visible y suave, tamaño adaptable al contenido sin tapar la pregunta; mantener opción de pausar y preferencias de movimiento reducido. Revisar Android y web.
10. Revisar márgenes, tamaño de tarjetas, paneles de comentarios y controles en resoluciones de móvil y escritorio, con evidencia visual y regresiones de permisos/otros módulos.

Fuentes iniciales: [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder), [confirmación de eventos Socket.IO](https://socket.io/docs/v4/emitting-events/), [fechas y zonas con Intl.DateTimeFormat](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/DateTimeFormat). Reutilizar los mecanismos existentes de medios autenticados, identidad y permisos.


## Ampliación posterior al cierre del bloque anterior — indicador, lectura y módulos

- Mantener la lectura más reciente al mezclar identidades importadas y canónicas. Guardar en el navegador hasta 500 marcas confirmadas durante 30 días, sin contenido de mensajes. Un fallo de red no se confirma como lectura guardada; los mensajes nuevos siguen siendo visibles mientras se reintenta.
- Indicador web de voz: arrastrar con ratón/táctil, mover con flechas o seleccionar una esquina; conservar posición por cuenta, ajustar a cambios de tamaño; el icono de audífonos controla el mismo estado de audio de la sala y muestra el silencio.
- La URL de PublicProfilePage de la captura devolvía HTTP 404. Aislar errores por ruta para mantener navegación y sesiones, recuperación limitada de módulos, no-cache en HTML y explicación legible. No recargar automáticamente con una sesión multimedia activa o un formulario con contenido.
- [Vite, errores de carga tras publicaciones](https://vite.dev/guide/build.html#load-error-handling), [W3C, alternativas a arrastrar](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html). Se aplica la funcionalidad descrita; no se declara certificación integral.

## Decisión operativa aprobada: firmas antivirus

El 19 de octubre el usuario aprobó ampliar a **48 horas** la antigüedad máxima de firmas. API, daemon y actualizador deben aplicar el mismo límite inclusivo; rechazar antigüedad superior, fechas futuras y respuestas de protocolo inválidas. FreshClam conserva las comprobaciones cada dos horas y la validación de las bases. No ampliar automáticamente si vuelve a vencer el plazo ni omitir el escaneo. Verificar la disponibilidad con `/ready`, la integridad de la política y los resultados reales de archivo inocuo/EICAR. Implementado y comprobado en 9.1.13; ver AUDIT.md y `artifacts/release-9.1.13/antivirus/`.
