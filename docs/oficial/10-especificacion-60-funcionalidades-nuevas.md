# Especificación de requisitos de producto: 60 funcionalidades nuevas

**Producto:** Conéctate / Novvin  
**Versión del documento:** 1.0  
**Fecha de elaboración:** 24 de septiembre de 2026  
**Estado:** especificación de requisitos en implementación incremental; las 60 funciones no están completas. Ver el [registro de auditoría e implementación](../audit-2026-09-23/revision-especificacion-60-2026-09-24.md) para el avance comprobado al 25 de septiembre de 2026.  
**Alcance:** Android como cliente principal; web como plataforma de paridad planificada  
**Base de auditoría:** inspección estática del árbol de trabajo disponible. El árbol ya contenía cambios locales en múltiples archivos; esos cambios no fueron realizados ni modificados para este documento.

## 1. Propósito y alcance

Este documento convierte oportunidades observadas en requisitos funcionales medibles para cinco áreas solicitadas: **Mensajes, Videos, Social, Juegos y Perfil**. Define cómo se espera que funcionen, qué valor cotidiano aportan, criterios que permiten aceptarlos, requisitos de calidad y un orden de implementación sugerido. Contiene **60 funcionalidades nuevas: 12 por área**.

“Nuevo” significa que la capacidad descrita no se encontró como flujo completo al revisar el código local, o que extiende de manera explícita una capacidad parcial existente. No implica que todos los clientes, el servidor de producción o la versión publicada tengan idéntico estado. La evaluación es de código y documentación; no es una prueba de usuario, de producción ni una certificación de seguridad.

Fuera de alcance: implementar código, alterar datos, modificar el servidor, publicar una versión o afirmar fechas/costos de entrega. Las estimaciones de tamaño requieren refinamiento técnico por el equipo que implemente.

**Última revisión de línea base:** 25 de septiembre de 2026. La descripción inicial de implementación se actualizó con los cambios de Android y servidor ya comprobados; sigue siendo un documento de requisitos, no una certificación de producción.

## 2. Resumen ejecutivo

La navegación inferior de Android en `HomeScreen.kt` presenta Mensajes, Videos, Social, Juegos y Perfil. `GamesHomeScreen.kt`, los perfiles gamer y el catálogo de PlayMatch ya implementan parte del área Juegos; las trivias y minijuegos adicionales de FR-JUE-02 a 12 siguen siendo otro alcance.

Mensajes ya incluye chats, grupos, comunidades, reacciones, respuestas y reenvíos; la lista de conversaciones admite fijar conversaciones. La revisión del menú contextual de mensajes no encontró la operación de fijar un mensaje o guardarlo como destacado personal. Se documentan ambas capacidades, con permisos y trazabilidad.

La selección de un documento antes del envío abre un diálogo con nombre, tipo MIME y tamaño, y ahora ofrece una vista interna para formatos compatibles. PDF conserva páginas; DOCX muestra estilos y elementos comunes sin reproducir exactamente el diseño de Word, y permite editar una copia de párrafos simples; XLSX y PPTX ofrecen vistas básicas de lectura. FR-MSG-04, 05 y 06 siguen parciales: faltan fidelidad documental completa, edición compleja y exportación multiformato validada. El sistema ya transmite documentos como adjuntos y el repositorio menciona un límite de carga de 100 MB; los límites propuestos en este documento deben conservar o reducir ese techo tras validación de rendimiento.

## 3. Línea base observada y hallazgos de auditoría

| Área | Evidencia inspeccionada | Estado observado | Oportunidad |
|---|---|---|---|
| Navegación | `app/src/main/java/conectate/com/presentation/home/HomeScreen.kt`, `GamesHomeScreen.kt` y `PlayMatchScreens.kt` | Cinco destinos de primer nivel; Juegos ya ofrece el hub y funciones de PlayMatch. | Extender juegos sociales, reglas y seguridad de acuerdo con FR-JUE-02 a 12; medir usabilidad del acceso. |
| Mensajes y comunidades | `ChatScreen.kt`, `CommunityChatScreen.kt`, `ConversationComponents.kt` | Acciones contextuales incluyen reaccionar, reenviar y responder. La bandeja permite fijar conversaciones; no se encontró acción contextual para fijar/destacar mensajes. | Fijados compartidos por conversación y destacados privados por usuario; navegación directa al original. |
| Previsualización de adjunto | `app/src/main/java/conectate/com/presentation/components/AttachmentPreviewDialogs.kt` y `presentation/documents/` | Diálogo con metadatos y visor interno para PDF, DOCX, XLSX y PPTX; la edición Word genera una copia y solo habilita párrafos estructuralmente simples. | Completar fidelidad, edición compleja y exportación con corpus real; verificar límites, accesibilidad y envío desde chat, grupo y comunidad. |
| Videos | `app/src/main/java/conectate/com/presentation/video/VideoFeedScreen.kt` y `presentation/videos/` | Hay feed, subida y edición de videos. | Añadir controles de consumo, accesibilidad, organización, continuidad e interacción. |
| Social | `app/src/main/java/conectate/com/presentation/social/` | Hay feed social, publicaciones y perfil social. | Mejorar publicación inclusiva, descubrimiento controlable, eventos, privacidad y transparencia. |
| Juegos | `linked_games` en perfil; `game_name` en directos | Metadatos/intereses de videojuegos, sin sección de partidas identificada en presentación Android. | Crear un catálogo pequeño de juegos sociales, sesiones, reglas y clasificación segura. |
| Perfil | `ProfileScreen.kt`, ajustes y modelo de usuario | Perfil editable y campos de intereses/juegos existentes. | Dar control explícito de privacidad, datos, sesiones, descubribilidad y preferencias. |

Las referencias de código describen el árbol de trabajo local a la fecha indicada. La ausencia de una coincidencia en búsqueda estática se trata como **“no localizada en el flujo revisado”**, no como prueba de ausencia en todos los entornos.

## 4. Convenciones y medida de aceptación

- Cada requisito tiene identificador estable `FR-[área]-NN`, prioridad inicial, comportamiento esperado, valor para el usuario y criterio de aceptación cuantificable.
- **MUST / P0:** necesario para un primer incremento usable o para proteger datos/permisos. **SHOULD / P1:** mejora de alto valor posterior al núcleo. **COULD / P2:** expansión, condicionada a telemetría y capacidad.
- Los tiempos se miden desde interacción visible hasta resultado confirmado, en dispositivo Android de gama media acordado por QA y conexión de prueba estable. Las operaciones de red informan progreso y no bloquean el hilo visual.
- Los porcentajes de éxito se calculan sobre sesiones elegibles instrumentadas, excluyendo cancelaciones voluntarias y fallas externas documentadas; la instrumentación debe respetar consentimiento y minimización de datos.
- Cada funcionalidad se acepta cuando todos sus criterios de aceptación pasan en pruebas automatizadas pertinentes y un flujo manual en dos cuentas reales de prueba. El plan de prueba debe cubrir permisos, estados vacíos, error, reintento, pérdida de red y accesibilidad aplicables.

## 5. Requisitos funcionales: Mensajes (12)

### FR-MSG-01 — Fijar mensaje en chat, grupo o comunidad · P0
**Funcionamiento:** menú de pulsación prolongada > “Fijar”; el sistema valida rol, muestra confirmación visible y coloca el mensaje en un panel “Fijados”. En grupos y comunidades, se aplicará la política de administradores definida por espacio; la audiencia ve quién fijó y cuándo. Desfijar deja evento y conserva el mensaje original.  
**Ayuda:** evita repetir instrucciones, direcciones y acuerdos importantes.  
**Aceptación:** fijar/desfijar un mensaje de texto y uno con adjunto se refleja para todos los miembros autorizados en ≤2 s con red estable; cada conversación admite hasta 10 fijados activos; usuario sin permiso recibe explicación accionable; el estado persiste tras cerrar sesión e iniciar sesión.

### FR-MSG-02 — Destacados privados del usuario · P0
**Funcionamiento:** “Destacar” guarda una referencia personal al mensaje, sin cambiar lo que ven los demás; sección “Mensajes guardados” con etiquetas opcionales.  
**Ayuda:** permite volver a citas, tareas o datos sin enviárselos a uno mismo.  
**Aceptación:** guardar/quitar desde mensaje o adjunto en ≤2 acciones; cambios visibles solo para esa cuenta en 100% de pruebas con dos usuarios; colección permite filtrar por conversación, fecha y tipo; borrar original presenta “mensaje ya no disponible” sin revelar contenido eliminado.

### FR-MSG-03 — Buscar y saltar a mensajes fijados o destacados · P1
**Funcionamiento:** panel de fijados y lista privada ofrecen búsqueda por texto/remitente/fecha y “Ir al mensaje”.  
**Ayuda:** reduce el tiempo de volver a localizar información antigua.  
**Aceptación:** desde resultados elegibles el chat llega al mensaje correcto en ≤3 s para historial de 10 000 mensajes; 100% de resultados muestran conversación y fecha; mensaje eliminado o sin permiso no abre contenido.

### FR-MSG-04 — Visor interno de documentos antes del envío · P0
**Funcionamiento:** seleccionar un archivo abre un visor de Conéctate con páginas, zoom, miniaturas y nombre/tamaño. Primera entrega soporta PDF y DOCX; DOCX se renderiza en páginas con texto, imágenes y estilos comunes, y PDF conserva sus páginas. “Enviar” devuelve al compositor sin abrir otra aplicación.  
**Ayuda:** permite comprobar que se eligió la versión correcta y evitar enviar páginas en blanco o imágenes equivocadas.  
**Aceptación:** el flujo no lanza `ACTION_VIEW` ni cambia a otra aplicación; para archivos de hasta 50 MB o 200 páginas muestra la primera página en ≤3 s en dispositivo objetivo; texto e imágenes de un corpus acordado de 30 documentos se ven en 100% de casos admitidos; formato no compatible declara el motivo y ofrece cancelar o adjuntar como archivo.

### FR-MSG-05 — Editar documento dentro de la app · P0
**Funcionamiento:** desde el visor, “Editar” habilita edición de texto y estilos comunes DOCX; cambios se guardan como borrador versionado, sin alterar el adjunto original. PDF e imágenes se abren en solo lectura en MVP; anotación PDF se considera extensión tras validación.  
**Ayuda:** permite corregir un documento breve desde el teléfono sin instalar una suite aparte.  
**Aceptación:** editar título, texto y negrita/itálica, guardar, cerrar y reabrir mantiene el contenido en 100% de pruebas de aceptación; se avisa si el archivo supera 50 MB o contiene elementos no editables; nunca se sobrescribe el original sin confirmación explícita.

### FR-MSG-06 — Exportar documento eligiendo formato dentro de Conéctate · P0
**Funcionamiento:** acción “Exportar” ofrece PDF, DOCX y texto plano cuando sea posible; se genera una copia en almacenamiento privado con nombre y formato elegidos. La pantalla muestra “Compartir”, “Enviar a chat” y “Guardar en la biblioteca de la app”; no abre el diálogo del sistema para elegir ruta.  
**Ayuda:** reduce pasos al convertir y compartir desde el mismo flujo.  
**Aceptación:** exportación de documento de hasta 50 MB termina en ≤10 s en dispositivo objetivo, presenta el tipo de salida antes de ejecutarla, abre/adjunta el resultado correcto; 100% de las exportaciones conservan el texto; PDF/DOCX conserva imágenes y estilos incluidos en el corpus de referencia; fallo deja el documento fuente intacto y muestra reintento.

### FR-MSG-07 — Confirmación visual del documento en el compositor · P1
**Funcionamiento:** antes de enviar, se ve primera página/miniatura, destinatario, nombre, tamaño, formato, texto opcional y acción de quitar/cambiar archivo.  
**Ayuda:** previene errores de destinatario o archivo.  
**Aceptación:** toda carga documental requiere una revisión explícita; cambiar el destinatario invalida la confirmación anterior; cancelar no envía ni borra el archivo original; pruebas de doble toque no producen duplicados.

### FR-MSG-08 — Búsqueda avanzada de adjuntos · P1
**Funcionamiento:** buscar en una conversación o en “Archivos” por nombre, tipo, remitente y rango de fecha.  
**Ayuda:** encuentra contratos, tareas o imágenes compartidas sin recorrer todo el chat.  
**Aceptación:** filtro combinado devuelve resultados correctos para corpus de 5 000 adjuntos en ≤2 s; paginación de 50; resultados respetan permiso y membresía en el momento de consulta.

### FR-MSG-09 — Reanudación de cargas interrumpidas · P1
**Funcionamiento:** cargas grandes guardan progreso reanudable, pausa/cancelación explícita y estado de entrega; deduplicación por identificador evita duplicados.  
**Ayuda:** ahorra volver a seleccionar archivos ante una señal móvil inestable.  
**Aceptación:** tras desconexión inducida, carga se reanuda desde el último bloque confirmado; 95% de cargas de prueba recuperables terminan sin duplicado; al cancelar se elimina el temporal en ≤24 h.

### FR-MSG-10 — Bandeja de pendientes y borradores · P1
**Funcionamiento:** separa mensajes no enviados, borradores de texto y adjuntos aún cargando; restauración al reabrir el chat.  
**Ayuda:** evita perder respuestas extensas o archivos preparados.  
**Aceptación:** 100% de borradores locales sobreviven cierre forzado y reinicio del dispositivo; eliminar borrador pide confirmación si contiene texto o adjunto; borradores privados no se sincronizan a otro usuario.

### FR-MSG-11 — Resumen de mensajes pendientes · P1
**Funcionamiento:** botón “Resumir no leídos” genera un resumen con enlaces a los mensajes fuente; opción de no usar IA y acceso a política de datos.  
**Ayuda:** permite ponerse al día tras una ausencia sin leer cientos de mensajes.  
**Aceptación:** resumen solo incluye mensajes visibles para el usuario, muestra enlaces a cada afirmación recuperable, se produce en ≤10 s para 500 mensajes; no se envía contenido a servicio externo sin consentimiento explícito.

### FR-MSG-12 — Modo de tarea en conversaciones · P2
**Funcionamiento:** convertir un mensaje en una tarea con responsable, vencimiento y estado; disponible en chat personal/grupo/comunidad según rol.  
**Ayuda:** transforma acuerdos cotidianos en próximos pasos visibles.  
**Aceptación:** tarea puede asignarse, marcarse completada y enlazarse al mensaje original; notificación se envía solo a responsables; toda tarea modificada incluye autor y hora; filtro de vencidas responde en ≤2 s para 1 000 tareas.

## 6. Requisitos funcionales: Videos (12)

### FR-VID-01 — Lista “Ver después” · P0
**Funcionamiento:** guardar video desde menú y abrir cola privada ordenable.  
**Ayuda:** separa lo que interesa de lo que se puede ver en el momento.  
**Aceptación:** añadir/quitar en ≤2 acciones; cola se sincroniza entre sesiones en ≤3 s; 100% de elementos guardados se abren o indican eliminación.

### FR-VID-02 — Historial con continuidad de reproducción · P0
**Funcionamiento:** pestaña de vistos con posición, opción “Continuar” y borrar elemento/historial.  
**Ayuda:** evita buscar otra vez el video o recordar dónde quedó.  
**Aceptación:** retoma a ±5 s de la posición guardada cuando el video sigue disponible; historial paginado de 50; borrar se propaga al perfil en ≤5 s.

### FR-VID-03 — Colecciones personales de videos · P1
**Funcionamiento:** crear colecciones privadas, añadir videos y cambiar orden.  
**Ayuda:** organiza recetas, tutoriales y entretenimiento por uso.  
**Aceptación:** crear/renombrar/eliminar; hasta 100 colecciones y 500 elementos por colección; búsqueda en una colección en ≤2 s; no publica la colección sin elección explícita.

### FR-VID-04 — Control de velocidad y pausa precisa · P0
**Funcionamiento:** 0.5×, 0.75×, 1×, 1.25×, 1.5× y 2×; bloqueo opcional de desplazamiento mientras se lee.  
**Ayuda:** ajusta la reproducción al tiempo disponible y facilita seguir instrucciones.  
**Aceptación:** controles accesibles por TalkBack, velocidad aplicada en ≤500 ms; el valor persiste por sesión; botones no ocultan subtítulos.

### FR-VID-05 — Subtítulos automáticos revisables · P1
**Funcionamiento:** generar borrador de subtítulos al publicar y permitir corrección manual, idioma y sincronización.  
**Ayuda:** mejora acceso en silencio y para personas sordas o con dificultades auditivas.  
**Aceptación:** subtítulos activables/desactivables, editables antes y después de publicar; sincronía objetivo ±300 ms en 95% de segmentos de validación; idioma siempre etiquetado.

### FR-VID-06 — Preferencias de consumo de datos · P0
**Funcionamiento:** automático/ahorro/alta calidad y desactivar reproducción automática con red móvil.  
**Ayuda:** protege planes de datos y batería.  
**Aceptación:** modo ahorro reduce en ≥30% los bytes reproducidos frente a alta calidad en corpus controlado de 10 videos, sin descargar el siguiente video automáticamente; aviso visible si la calidad cambia.

### FR-VID-07 — Marcadores dentro de video · P1
**Funcionamiento:** creador agrega capítulos con título y tiempo; reproductor permite saltar y compartir enlace a capítulo.  
**Ayuda:** lleva directo a la explicación que se busca.  
**Aceptación:** hasta 50 capítulos por video, orden cronológico validado; salto con precisión ±1 s; ningún marcador apunta fuera de la duración.

### FR-VID-08 — Borradores de subida recuperables · P0
**Funcionamiento:** guardar título, descripción, audiencia, portada y progreso de carga para terminar luego.  
**Ayuda:** evita repetir una publicación interrumpida por llamada o mala conexión.  
**Aceptación:** borrador sobrevive cierre forzado y se restaura en ≤3 s; reanuda carga sin duplicar publicación; borrador se elimina a elección del usuario.

### FR-VID-09 — Controles “ver menos de esto” · P0
**Funcionamiento:** ocultar autor, tema o etiqueta desde el reproductor, con reversión en preferencias.  
**Ayuda:** reduce contenido irrelevante y mantiene el feed bajo control.  
**Aceptación:** preferencia afecta las siguientes 20 recomendaciones en ≤1 actualización de feed; opción deshacer disponible por 10 s; no bloquea reportar abuso.

### FR-VID-10 — Filtro de comentarios · P1
**Funcionamiento:** ordenar por recientes, relevantes o respuestas al creador y filtrar comentarios del usuario/autor.  
**Ayuda:** facilita encontrar una respuesta útil sin recorrer toda la conversación.  
**Aceptación:** filtros devuelven orden estable y 50 elementos por página; comentarios bloqueados/reportados no reaparecen por caché tras refrescar.

### FR-VID-11 — Compartir fragmento con marca de tiempo · P1
**Funcionamiento:** seleccionar inicio/fin hasta 60 s y compartir como enlace a la fuente, sujeto a permisos del autor.  
**Ayuda:** comparte el momento relevante sin pedir que otra persona vea todo el video.  
**Aceptación:** duración entre 5 y 60 s, recorte dentro del original; enlace abre el punto correcto en ±2 s; no genera una copia descargable sin autorización.

### FR-VID-12 — Estadísticas de creador comprensibles · P2
**Funcionamiento:** panel de vistas únicas, finalización, tiempo visto, guardados y comentarios por rango de fechas.  
**Ayuda:** permite entender qué publicaciones ayudan o interesan a la comunidad.  
**Aceptación:** cada métrica muestra definición y periodo; reconciliación con eventos de prueba ≥99%; usuarios no creadores no ven datos privados.

## 7. Requisitos funcionales: Social (12)

### FR-SOC-01 — Borradores de publicaciones · P0
**Funcionamiento:** guardar texto, medios, audiencia y texto alternativo para terminar luego.  
**Ayuda:** permite escribir con calma y evita perder una publicación.  
**Aceptación:** borrador restaura tras reinicio, es privado y se puede eliminar; previsualización coincide con lo que verá la audiencia seleccionada.

### FR-SOC-02 — Texto alternativo accesible para imágenes · P0
**Funcionamiento:** añadir descripción manual y sugerencia editable; publicación avisa si una imagen no tiene alternativa.  
**Ayuda:** hace publicaciones entendibles con lector de pantalla.  
**Aceptación:** cada imagen permite alt text de 1–500 caracteres; TalkBack lee descripción; publicación puede continuar sin él solo tras aviso explícito.

### FR-SOC-03 — Publicaciones con encuesta · P1
**Funcionamiento:** pregunta y 2–6 opciones, cierre programado, resultados según configuración.  
**Ayuda:** recoge decisiones rápidas sin contar respuestas manualmente.  
**Aceptación:** un voto activo por cuenta, edición solo antes del primer voto, cierre automático con resultado; administración auditada.

### FR-SOC-04 — Publicación de evento · P1
**Funcionamiento:** fecha, zona horaria, lugar/enlace, aforo opcional y estados “Asistiré/Me interesa”.  
**Ayuda:** coordina reuniones y actividades en un mismo lugar.  
**Aceptación:** hora se muestra en zona local y en zona del organizador; recordatorio configurable; cambios notifican solo a asistentes interesados.

### FR-SOC-05 — Feed con selección transparente · P0
**Funcionamiento:** pestañas “Siguiendo” y “Para ti”, control de temas y explicación breve de recomendaciones.  
**Ayuda:** da acceso cronológico y permite entender/controlar lo sugerido.  
**Aceptación:** “Siguiendo” presenta orden temporal; “Para ti” explica al menos una señal por tarjeta; preferencia de tema se aplica en ≤1 refresco.

### FR-SOC-06 — Guardar publicaciones · P0
**Funcionamiento:** colección privada de publicaciones, con etiquetas y salto al origen.  
**Ayuda:** recupera ideas o información local sin llenar el feed de mensajes propios.  
**Aceptación:** guardar/quitar en ≤2 acciones; colecciones de hasta 100 publicaciones; contenido borrado no se reproduce desde caché.

### FR-SOC-07 — Coautoría con consentimiento · P1
**Funcionamiento:** invitar a otra cuenta a aparecer como coautora; acepta/rechaza antes de publicación.  
**Ayuda:** atribuye correctamente actividades y proyectos compartidos.  
**Aceptación:** publicación no aparece en perfil invitado hasta aceptación; invitación expira en 7 días; ambas cuentas pueden retirar asociación según política visible.

### FR-SOC-08 — Control granular de audiencia por publicación · P0
**Funcionamiento:** elegir público, seguidores, lista cercana o personas seleccionadas; previsualizar audiencia y editar antes de publicar.  
**Ayuda:** comparte vida personal con el grupo apropiado y evita exposiciones accidentales.  
**Aceptación:** selector visible antes de publicar; servidor aplica la misma regla a feed, búsqueda, enlace y notificación; pruebas negativas de cuenta excluida muestran cero contenido.

### FR-SOC-09 — Silenciar una conversación de comentarios · P1
**Funcionamiento:** dejar de recibir notificaciones de un hilo sin dejar de seguir al autor; reactivar en menú.  
**Ayuda:** reduce interrupciones en publicaciones activas.  
**Aceptación:** silenciar detiene notificaciones en ≤60 s; insignias no cuentan el hilo; reactivación surte efecto en el siguiente evento.

### FR-SOC-10 — Compartir publicación con contexto · P1
**Funcionamiento:** reenviar dentro de Conéctate conservando autor/origen, comentario opcional y controles de audiencia originales.  
**Ayuda:** evita perder contexto y atribución.  
**Aceptación:** el enlace siempre señala la fuente; contenido privado no se hace público por reenviar; autor original y permiso se muestran antes de confirmar.

### FR-SOC-11 — Centro de reportes con seguimiento · P1
**Funcionamiento:** reportar publicación/comentario, recibir número de caso y ver estado y decisión comunicable.  
**Ayuda:** da claridad sobre la gestión de contenido dañino.  
**Aceptación:** acuse en ≤5 s; el denunciante ve fecha/estado, no datos sensibles de terceros; moderación deja motivo y registro de actor.

### FR-SOC-12 — Controles para reducir tiempo de uso · P2
**Funcionamiento:** resumen diario de minutos, recordatorio configurable y pausa opcional del feed.  
**Ayuda:** ayuda a equilibrar la red social con responsabilidades y descanso.  
**Aceptación:** minutos se calculan localmente por foreground activo con tolerancia ±5%; límite se puede editar/desactivar; nunca bloquea chats de seguridad o salida de una llamada.

## 8. Requisitos funcionales: Juegos (12)

### FR-JUE-01 — Acceso y catálogo de Juegos · P0
**Funcionamiento:** crear sección de primer nivel o acceso claramente etiquetado; catálogo con reglas, duración, privacidad y número de participantes.  
**Ayuda:** permite descubrir juegos sociales sin instalar otra app.  
**Aceptación:** sección accesible desde navegación en ≤2 acciones; toda ficha declara reglas, edad sugerida, tiempo promedio y si guarda clasificación; catálogo carga en ≤2 s para 100 fichas.

### FR-JUE-02 — Trivias rápidas de conocimiento general · P0
**Funcionamiento:** partida de 10 preguntas, temporizador opcional y explicación al responder.  
**Ayuda:** ofrece ocio breve y aprendizaje entre actividades diarias.  
**Aceptación:** partida completa dura ≤5 min; respuesta correcta solo se puntúa una vez; 100% de preguntas del catálogo tiene fuente/versión interna revisable y explicación.

### FR-JUE-03 — Duelo asincrónico por invitación · P0
**Funcionamiento:** invitar un contacto; ambos responden el mismo conjunto; rival no ve respuestas antes de terminar.  
**Ayuda:** facilita jugar aunque las personas estén conectadas en horarios distintos.  
**Aceptación:** invitación expira en 48 h; un intento por participante; empate explícito; push solo si el usuario acepta notificaciones de juego.

### FR-JUE-04 — Juego de palabras diario · P1
**Funcionamiento:** reto diario en idioma elegido, intentos limitados y resultado compartible sin revelar solución.  
**Ayuda:** crea una actividad corta compartida con amistades.  
**Aceptación:** una partida por cuenta/fecha; fecha se deriva del huso del servidor; compartir oculta respuesta hasta cerrar reto.

### FR-JUE-05 — Partida privada de sala · P1
**Funcionamiento:** crear sala por enlace/código para 2–8 miembros de chat o comunidad, con anfitrión que inicia y cierra.  
**Ayuda:** organiza una pausa grupal sin publicar la actividad en el perfil.  
**Aceptación:** código aleatorio no predecible, expira en 30 min si está vacía; solo miembros invitados entran; desconexión permite reingreso durante 2 min.

### FR-JUE-06 — Juegos dentro de comunidades · P1
**Funcionamiento:** administrador habilita/deshabilita catálogo permitido, crea evento y define cupos.  
**Ayuda:** fortalece actividades de grupos con intereses comunes.  
**Aceptación:** solo miembros pueden participar; cambios de rol invalidan acceso en ≤60 s; juego deshabilitado no acepta nuevas partidas.

### FR-JUE-07 — Marcador con reglas publicadas · P0
**Funcionamiento:** puntos, desempate, abandono y reconexión siguen reglas visibles antes de jugar.  
**Ayuda:** reduce discusiones sobre resultados.  
**Aceptación:** 100% de partidas completadas guardan regla/versión, puntaje y resultado; misma secuencia de respuestas produce resultado idéntico en servidor.

### FR-JUE-08 — Clasificación privada y opt-in · P1
**Funcionamiento:** el usuario decide si aparece con nombre, alias o no aparece en tablas semanales.  
**Ayuda:** permite competir sin forzar exposición de identidad o rendimiento.  
**Aceptación:** por defecto participación visible es opt-in; cambio de privacidad se aplica en ≤5 min; API no devuelve identidad real para modo alias.

### FR-JUE-09 — Logros no monetarios · P2
**Funcionamiento:** insignias por participación/aprendizaje, sin compra ni ventaja de juego.  
**Ayuda:** reconoce constancia sin convertir el ocio en gasto obligatorio.  
**Aceptación:** cada insignia tiene regla verificable; no es vendible ni altera puntuación; usuario puede ocultarla del perfil.

### FR-JUE-10 — Moderación, bloqueo y salida de partidas · P0
**Funcionamiento:** reportar jugador, bloquear invitaciones, silenciar chat de juego y salir con confirmación.  
**Ayuda:** mantiene el juego voluntario y seguro.  
**Aceptación:** bloqueado no invita ni ve salas privadas del usuario; reporte crea caso en ≤5 s; salida siempre disponible sin penalidad oculta.

### FR-JUE-11 — Registro de partida limitado · P1
**Funcionamiento:** historial con juego, fecha y resultado; retención configurable.  
**Ayuda:** recuerda una partida o resultado sin crear vigilancia extensa.  
**Aceptación:** historial privado por defecto; política propone retención de 90 días; borrado elimina o anonimiza datos personales en ≤30 días y queda confirmación visible.

### FR-JUE-12 — Accesibilidad y modos sin temporizador · P0
**Funcionamiento:** controles grandes, TalkBack, contraste, texto claro y alternativa sin límite de tiempo cuando reglas lo permitan.  
**Ayuda:** permite jugar a personas con distintas capacidades y ritmos.  
**Aceptación:** todas las acciones principales se completan por lector de pantalla; objetivos táctiles ≥48 dp; cada juego declara compatibilidad y alternativa antes de iniciar.

## 9. Requisitos funcionales: Perfil (12)

### FR-PER-01 — Panel de privacidad por tipo de dato · P0
**Funcionamiento:** un panel muestra audiencia de publicaciones, seguidores, actividad, juegos e información de contacto; editar sin recorrer varios menús.  
**Ayuda:** facilita decidir qué información se comparte.  
**Aceptación:** estado efectivo se muestra en una pantalla; cambio se propaga a API/feed/búsqueda en ≤60 s; pruebas de cuentas excluidas no obtienen datos protegidos.

### FR-PER-02 — Vista previa de perfil como otra audiencia · P0
**Funcionamiento:** alternar vista pública, seguidor y solo yo antes de guardar.  
**Ayuda:** comprueba exposición real sin pedir otra cuenta.  
**Aceptación:** vista previa usa el mismo selector de campos que API; cero campos ocultos aparecen en muestra automatizada de cada audiencia.

### FR-PER-03 — Sesiones y dispositivos conectados · P0
**Funcionamiento:** listar dispositivo, actividad aproximada, fecha y opción de cerrar otras sesiones.  
**Ayuda:** detecta accesos desconocidos y recupera control de la cuenta.  
**Aceptación:** revocación invalida token en ≤60 s; el dispositivo actual pide confirmación antes de cerrarse a sí mismo; no expone dirección IP completa.

### FR-PER-04 — Descarga de datos personales · P0
**Funcionamiento:** solicitar exportación de perfil, publicaciones, mensajes/adjuntos propios según permisos, configuración y juegos; entregar dentro de la app y avisar al completarse.  
**Ayuda:** conserva recuerdos o facilita cambiar de servicio.  
**Aceptación:** solicitud requiere reautenticación; archivo incluye inventario y formato legible JSON/HTML; enlace de descarga expira en 24 h; generación completa ≤24 h para cuenta de prueba de 10 GB.

### FR-PER-05 — Borrado de cuenta con vista de consecuencias · P0
**Funcionamiento:** explica datos que se borrarán/anonimizarán, retención legal/operativa y pasos de recuperación durante periodo de gracia.  
**Ayuda:** permite cerrar cuenta de forma clara y segura.  
**Aceptación:** reautenticación obligatoria; cancelación durante 14 días; eliminación/anonimización ejecutada en ≤30 días salvo retención legal declarada; confirmación al usuario.

### FR-PER-06 — Controles de descubrimiento y contacto · P1
**Funcionamiento:** habilitar/deshabilitar búsqueda por teléfono, email, enlace y sugerencias de contactos.  
**Ayuda:** reduce contactos no deseados.  
**Aceptación:** configuración se aplica a búsquedas nuevas en ≤60 s; se puede deshacer; endpoint no confirma si una cuenta existe ante solicitante no autorizado.

### FR-PER-07 — Estado de disponibilidad y horario tranquilo · P1
**Funcionamiento:** definir disponible/ocupado/desconectado y horario en que se silencian notificaciones sociales.  
**Ayuda:** protege concentración y descanso sin dejar de recibir alertas críticas configuradas.  
**Aceptación:** estado puede expirar automáticamente; zona horaria configurable; excepciones visibles; ninguna llamada activa se interrumpe por el cambio.

### FR-PER-08 — Centro de preferencias de notificación · P0
**Funcionamiento:** resumen por mensajes, menciones, videos, social, juegos y seguridad; canales y frecuencia por categoría.  
**Ayuda:** disminuye interrupciones repetitivas y mantiene avisos importantes.  
**Aceptación:** opción por categoría disponible en ≤3 pasos; preferencias efectivas en ≤60 s; notificación crítica de seguridad respeta configuración definida por el usuario y política legal.

### FR-PER-09 — Indicador de finalización del perfil con control · P2
**Funcionamiento:** mostrar sugerencias opcionales (foto, bio, intereses), explicar su uso y permitir ocultar el indicador.  
**Ayuda:** mejora presentación sin obligar a publicar más datos.  
**Aceptación:** cada sugerencia explica beneficio; completar no exige dato sensible; porcentaje se calcula en cliente/API con regla documentada.

### FR-PER-10 — Intereses y juegos gestionables por privacidad · P1
**Funcionamiento:** ordenar, editar, ocultar y asociar intereses/juegos a recomendaciones o perfil según consentimiento.  
**Ayuda:** personaliza descubrimiento manteniendo control sobre aficiones visibles.  
**Aceptación:** hasta 20 intereses; ocultar elimina del perfil público en ≤60 s; desactivar personalización detiene su uso en recomendaciones futuras.

### FR-PER-11 — Bloqueados y silenciados desde el perfil · P0
**Funcionamiento:** buscar, desbloquear y cambiar restricciones de cuentas; explicar efecto sobre chat, menciones y juegos.  
**Ayuda:** resuelve límites de contacto sin buscar la conversación original.  
**Aceptación:** bloqueo corta mensajes/invitaciones nuevas en ≤60 s; desbloqueo no restaura vínculos automáticamente; lista solo accesible al dueño de la cuenta.

### FR-PER-12 — Registro de cambios importantes · P1
**Funcionamiento:** historial de cambios de email, teléfono, privacidad, contraseña, sesiones y estado de solicitudes de datos.  
**Ayuda:** facilita detectar cambios inesperados y entender acciones recientes.  
**Aceptación:** cada evento contiene hora, tipo y dispositivo aproximado; eventos de seguridad se conservan 12 meses; secretos y contenido de mensajes no aparecen en el historial.

## 10. Requisitos no funcionales

Los siguientes son objetivos propuestos para diseño y verificación. Deben medirse en el entorno de despliegue real y ajustarse con capacidad/telemetría; no constituyen una afirmación sobre el rendimiento actual.

| ID | Requisito | Métrica de aceptación |
|---|---|---|
| NFR-01 Rendimiento | Navegación, listas y acciones comunes responden con fluidez. | p95 ≤1 s para acciones locales; p95 ≤2 s para lectura API bajo carga objetivo acordada; primer render útil ≤2.5 s en Android de referencia. |
| NFR-02 Visor | Abrir documento mediano sin bloquear interfaz. | Primera página ≤3 s para PDF/DOCX de hasta 50 MB/200 páginas; memoria adicional ≤300 MB en dispositivo de referencia; cierre libera recursos en ≤5 s. |
| NFR-03 Disponibilidad | Operaciones sociales y mensajería accesibles. | SLO inicial de API 99.5% mensual, excluyendo ventana publicada; errores de servicio p95 <1% por operación crítica. Validar capacidad de mini PC antes de adoptar SLO. |
| NFR-04 Seguridad en tránsito y reposo | Proteger sesión, documentos y datos privados. | TLS vigente; tokens con caducidad/revocación; almacenamiento local sensible cifrado; revisión automatizada de dependencias en cada release. |
| NFR-05 Autorización | Servidor aplica permisos de sala y cuenta, incluso si el cliente manipula la interfaz. | 100% de pruebas de acceso negativo bloqueadas en API para cuenta ajena, miembro expulsado y usuario bloqueado. |
| NFR-06 Privacidad | Recoger solo telemetría necesaria y consentida. | Eventos no contienen cuerpo de mensaje/documento; consentimiento revocable; política de retención para cada dato documentada antes de producción. |
| NFR-07 Accesibilidad | Compatibilidad con lector de pantalla, tamaño y contraste. | WCAG 2.2 AA como objetivo para vistas web y principios equivalentes en Android; objetivos táctiles ≥48 dp; flujos críticos operables con TalkBack. |
| NFR-08 Compatibilidad | Soportar Android actualmente declarado por el proyecto. | Pruebas de smoke en minSdk 24 y versión Android estable más reciente; formato corrupto/no soportado falla con recuperación, no crash. |
| NFR-09 Resiliencia | Recuperar de señal móvil intermitente. | 95% de operaciones idempotentes de envío reanudables en pruebas de desconexión; reintento no crea duplicados; modo offline distingue pendiente de enviado. |
| NFR-10 Escalabilidad | Crecer sin rediseñar contrato público por feature. | Prueba de carga a 5× pico observado y 2× usuarios activos objetivo de siguiente release; p95 dentro de NFR-01; límites de paginación y cuotas en toda colección. |
| NFR-11 Integridad | Evitar resultados dobles o discordantes. | 100% de operaciones de voto, fijado y marcador tienen clave idempotente; consistencia visible tras reintento en ≤5 s. |
| NFR-12 Observabilidad | Diagnosticar fallos sin contenido privado. | Cada error tiene ID correlacionable, área, versión y código no sensible; ≥95% de fallos de flujos P0 aparecen en tablero agregado en ≤5 min. |
| NFR-13 Mantenibilidad | Añadir formatos/juegos sin copiar lógica de permisos en pantallas. | API versionada; adaptadores de formato/juego independientes; documentación de contrato y migración revisada por PR; cobertura de contratos críticos ≥90%. |
| NFR-14 Portabilidad | Mantener paridad definida entre Android y web. | Cada requisito indica clientes soportados; para funcionalidades declaradas multiplataforma, ≥95% de escenarios de contrato pasan en ambos clientes antes de marcarlas completas. |
| NFR-15 Uso de datos y batería | Respetar elección de ahorro. | Feed de video no precarga más de 1 elemento adicional en modo ahorro; consumo medido en sesión de 30 min ≤ presupuesto acordado tras baseline; carga cesa en background no autorizado. |
| NFR-16 Lenguaje y localización | Evitar depender de textos codificados. | Español completo en primera entrega; formatos de fecha/número localizados; 100% de errores orientados a usuario ofrecen causa y siguiente paso. |
| NFR-17 Retención y borrado | Ejecutar retención definida para contenido temporal y solicitudes. | Temporales abandonados eliminados ≤24 h; solicitud de borrado terminada ≤30 días salvo excepción declarada y comunicada; informe auditable de resultado. |
| NFR-18 Recuperación ante desastre | Preservar datos sociales y metadatos. | Backups cifrados con prueba de restauración trimestral; RPO ≤24 h y RTO ≤8 h como objetivo inicial sujeto a la infraestructura. |

## 11. Usabilidad: evaluación heurística informada por código

Se usa la lista de diez principios de Nielsen como guía de inspección, no como sustituto de pruebas con usuarios. La referencia base es [10 Usability Heuristics for User Interface Design, Nielsen Norman Group](https://www.nngroup.com/articles/ten-usability-heuristics/) (consulta 24-09-2026). Severidad preliminar: 0 cosmético, 1 menor, 2 importante, 3 grave, 4 bloqueante. Puntuación basada en alcance potencial y flujo inspeccionado; confirmar con observación de usuarios.

| Heurística | Hallazgo de código | Severidad | Requisito que lo aborda |
|---|---|---:|---|
| 1. Visibilidad del estado | La previsualización documental detectada comunica metadatos; el código inspeccionado no muestra progreso/estado de renderizado interno. | 2 | FR-MSG-04, 06, 09 |
| 2. Correspondencia con el mundo real | Un adjunto llamado documento se previsualiza con una acción cuya disponibilidad depende de aplicaciones externas, interrumpiendo el flujo de chat. | 3 | FR-MSG-04 a 07 |
| 3. Control y libertad | No se localizó fijado/desfijado de mensaje en el menú contextual revisado; los controles de documento se limitan a cancelar o enviar. | 2 | FR-MSG-01, 02, 07 |
| 4. Consistencia y estándares | Se fijan conversaciones, pero el patrón no se extiende visualmente a mensajes; Juegos carece de destino principal en navegación. | 2 | FR-MSG-01, FR-JUE-01 |
| 5. Prevención de errores | Se muestran nombre/tipo/tamaño, pero no una representación completa del contenido antes de enviar. | 3 | FR-MSG-04, 07 |
| 6. Reconocimiento antes que recuerdo | Mensajes importantes requieren recordar conversación/fecha si no hay colección de fijados y destacados. | 2 | FR-MSG-02, 03 |
| 7. Flexibilidad y eficiencia | No se halló salto desde una lista global de mensajes destacados ni filtros avanzados de adjuntos. | 2 | FR-MSG-03, 08 |
| 8. Diseño minimalista | Cinco áreas principales serán visibles; hay que decidir cómo introducir Juegos sin congestionar navegación. | 1 | FR-JUE-01; validar arquitectura de información |
| 9. Diagnóstico y recuperación de errores | Si no existe app externa para el MIME, se informa que no hay aplicación disponible; no se ofrece alternativa de visualización integrada. | 3 | FR-MSG-04, 05, 06 |
| 10. Ayuda y documentación | Juegos nuevos requieren reglas breves y comprensibles dentro del flujo. | 1 | FR-JUE-01, 02, 07 |

### Validación heurística pendiente

Antes de priorizar diseño final, ejecutar tareas moderadas con 5–8 usuarios diversos: fijar y recuperar un anuncio en comunidad, editar/exportar/enviar un DOCX, retomar un video, crear una publicación con audiencia limitada, iniciar un juego privado y cambiar visibilidad de perfil. Registrar éxito sin ayuda, tiempo, errores y puntuación de facilidad posterior; corregir tareas P0 con menos de 90% de éxito sin ayuda. Esta sesión no se realizó para elaborar este documento.

## 12. Beneficio diario e indicadores de resultado

Los beneficios siguientes son hipótesis de producto; se deben medir con una línea base de al menos 2 semanas antes de activar funciones a todos los usuarios.

| Necesidad diaria | Funciones asociadas | Indicador y objetivo inicial |
|---|---|---|
| Encontrar un acuerdo sin releer chats largos | Mensajes fijados, destacados, búsqueda y resúmenes | Mediana de tiempo para recuperar un mensaje marcado: ≤15 s y mejora ≥30% respecto a la línea base. |
| Revisar/compartir un documento desde el teléfono | Visor interno, edición, exportación y confirmación | Mediana de pasos de selección a envío: ≤5; reducir abandono del flujo documental ≥20% frente a línea base. |
| Ver contenido cuando hay tiempo o datos limitados | Ver después, historial, velocidad, ahorro | Reanudación exitosa ≥95%; reducir bytes por 30 min ≥30% en ahorro respecto a alta calidad. |
| Coordinar planes y compartir intereses | Encuestas, eventos, coautoría, juegos privados | ≥90% de creación de evento/encuesta completada sin ayuda en pruebas; medir participación voluntaria, no solo volumen. |
| Descansar, cuidar privacidad y seguridad | Pausa social, audiencia granular, sesiones, exportación/borrado | Cambios de privacidad completados correctamente ≥95%; cierre remoto de sesión p95 ≤60 s. |

No se presentan ahorros de tiempo actuales como hechos. Producto/analítica debe definir telemetría mínima y publicar resultados agregados tras piloto.

## 13. Propuesta de implementación y escalabilidad

### 13.1 Orden sugerido por dependencias

1. **Fundación de permisos y contratos:** definir IDs canónicos de usuario, mensaje, conversación, comunidad, documento, juego, audiencia y eventos; esquema de permisos en el backend Ubuntu; paginación, idempotencia y versiones de API. No usar una bandera local como control de acceso.
2. **Primer incremento (P0):** mensajes fijados/destacados, visor documental interno DOCX/PDF, edición básica y exportación; verificación de amenazas de archivos; configuración de privacidad y sesiones; catálogo inicial/juego trivial; herramientas de control de consumo en videos.
3. **Segundo incremento (P1):** búsqueda de adjuntos, reintentos reanudables, herramientas de feed/eventos, salas de juego y controles de perfil; instrumentación de resultados y experimento con grupo pequeño.
4. **Tercer incremento (P2):** resúmenes asistidos, estadísticas de creador, logros y controles de bienestar tras comprobar demanda, costo y aceptación de privacidad.
5. **Paridad web:** publicar tabla por requisito con Android/web/no aplica y contratos compartidos; no declarar paridad por compartir backend sin probar interfaz.

### 13.2 Componentes técnicos que deben diseñarse

- **Android:** componentes Compose por capacidad, ViewModels y casos de uso; estado explícito (`loading`, `ready`, `pending`, `error`, `unsupported`); persistencia local cifrada para borradores; accesibilidad incorporada.
- **Visor/editor:** interfaz `DocumentRenderer` con adaptadores aislados por formato; sandbox de lectura, límite de memoria, análisis de MIME por contenido además de extensión; preservar original y escribir revisiones derivadas. Confirmar licencia/costo de biblioteca DOCX/PDF antes de seleccionar dependencia.
- **Backend y datos:** PostgreSQL como autoridad para permisos, fijados, destacados, partidas y estado de solicitudes; endpoints paginados y transaccionales; almacenamiento existente para binarios; migraciones SQL compatibles hacia atrás y rollback documentado.
- **Tiempo real:** eventos mínimos (`message_pinned`, `message_unpinned`, `game_invite`, `game_result`) autenticados, autorizados, versionados e idempotentes; reconciliación al volver online.
- **Juegos:** reglas y scoring calculados en servidor; catálogos de contenido versionados; temporizador y cliente solo como presentación; protección ante repetición, doble envío y reloj manipulado.
- **Observabilidad:** IDs de correlación, contadores de latencia/fallo y eventos de producto agregados; nunca capturar el texto de documentos o conversaciones en logs.
- **Cliente web:** reutilizar contratos y reglas; renderer web independiente; cifrado/descarga y edición deben revisarse por separado antes de prometer paridad.

### 13.3 Diseño de implementación por área

| Área | Interfaz y cliente | Servicios, datos y sincronización | Decisión de escalabilidad |
|---|---|---|---|
| Mensajes | Componentes Compose reutilizables para acciones contextuales, panel de fijados/guardados, visor/editor por página y exportación en una sola ruta compartida por chat, grupo y comunidad. Borrador local con estado explícito y restauración. | API autenticada para fijados por conversación, guardados por usuario, referencias de adjuntos y exportaciones. Persistir el binario en storage y metadatos/versiones en PostgreSQL. Validar rol de comunidad en cada mutación. Cargas por bloques e idempotencia. | Adaptadores DOCX/PDF aislados para añadir formatos sin copiar pantallas; límites de tamaño/páginas, paginación y limpieza de temporales. Evaluar biblioteca, licencia y memoria en dispositivo antes de elegir renderer/editor.
| Videos | Preferencias accesibles en reproductor, listas paginadas, historial y colecciones; reproductor guarda posición con debounce para reducir escrituras. | Tablas por usuario para historial, colecciones y capítulos; endpoints de preferencia de calidad/captions; procesamiento asíncrono de subtítulos/miniaturas; eventos de analítica agregados. | Cache limitada por cuota, precarga gobernada por ahorro y cursores estables; procesar trabajos multimedia fuera de peticiones síncronas.
| Social | Compositor de publicación extensible por tipo (texto, medio, encuesta, evento); selector de audiencia reutilizado para vista previa y edición; lector de pantalla integrado. | Audiencias aplicadas en consultas de feed, perfil, búsqueda, enlaces y notificaciones; tablas transaccionales para votos/eventos; colas para recordatorios; auditoría de moderación con acceso restringido. | Separar orden de feed de permisos; índices guiados por consultas; límite de notificaciones por usuario; usar feature flags para cambios de recomendación.
| Juegos | Catálogo y salas como destinos propios; máquina de estados visible (invitación, esperando, en curso, resultado, cancelado); compatibilidad accesible incluida en cada ficha. | Servidor autoritativo para rondas, relojes y puntuación; versiones inmutables de reglas/preguntas; claves idempotentes por intento; invitaciones/eventos realtime solo para participantes autorizados; clasificación usa alias opcional. | Iniciar con trivias de baja carga y partidas asincrónicas; establecer cuota/rate limit; separar paquetes de contenido de lógica del cliente para añadir juegos sin actualizar reglas de scoring en pantalla.
| Perfil | Panel único de privacidad, preview con selector de audiencia real, gestión de sesiones y estado de exportación; explicación antes de operaciones irreversibles. | Preferencias como fuente central del backend; comprobarlas en cada endpoint que expone datos. Revocar tokens en servidor; proceso asíncrono para exportar/borrar y job de retención con auditoría de finalización. | Exportación en cola con cuotas y enlaces de corta duración; borrado idempotente y reanudable; evitar listas de seguidores o sesiones sin paginar.

Las tablas de esta sección asignan una ruta técnica a las 60 funciones en conjunto. Antes de desarrollar, cada ticket debe enlazar su requisito y criterio, detallar el contrato/API afectado, migración, permisos, estados de error y plan de rollback. No se debe marcar un requisito como terminado solo por su pantalla Compose.

### 13.4 Entidades iniciales para diseño de datos

`conversation_pins(conversation_id, message_id, pinned_by, pinned_at, expires_at)`; `user_saved_messages(user_id, message_id, label, saved_at)`; `document_revisions(document_id, owner_id, source_hash, format, storage_key, created_at)`; `video_history(user_id, video_id, position_ms, updated_at)`; `video_collections`/`video_collection_items`; `social_events`, `polls`, `poll_votes`; `game_sessions`, `game_participants`, `game_rounds`, `game_results`; `user_privacy_preferences`; `security_sessions`; `data_export_requests`. Son nombres conceptuales sujetos al esquema real; aplicar claves foráneas, índices por consultas observadas, retención y borrado en cascada/anónimo según obligación legal.

### 13.5 Protección y abuso

- Verificar cada lectura/escritura de recurso en el servidor con el usuario autenticado y membresía actual.
- Limitar tasa de invitaciones, votos, exportaciones, búsqueda, generación de archivos y partidas; cuotas configurables por cuenta/IP sin revelar existencia de cuenta.
- Validar MIME real, tamaño, extensión, malware, documentos comprimidos anómalos y archivos cifrados; presentar decisión antes de abrir y aislar parsing.
- Tratar resultados de IA y contenido generado como no confiables; resumen siempre enlaza a fuente y no altera acciones del usuario.
- Definir aviso/consentimiento, solicitud y eliminación de datos con el equipo legal antes de producción; jurisdicción aplicable no se infiere en esta auditoría.

## 14. Métricas globales, piloto y definición de terminado

### Métricas de producto

- **Éxito de tarea:** completar cada flujo P0 sin ayuda en ≥90% de participantes de prueba.
- **Accesibilidad:** 100% de flujos P0 completables con TalkBack; 0 defectos críticos de navegación por teclado/TalkBack abiertos al release.
- **Errores:** duplicados de envío o voto <0.1% en piloto; 0 accesos no autorizados en suite negativa.
- **Confianza documental:** ≥95% de documentos del corpus de formatos admitidos se abren; edición/exportación preserva elementos soportados definidos; rechazos explican cómo seguir.
- **Escalabilidad:** alcanzar NFR-10 y latencias NFR-01 con fallo en backend simulado y datos de volumen creciente.
- **Impacto:** comparar medianas antes/después por cohorte para tiempo de búsqueda, pasos de exportación, abandono, bytes de video y configuración de privacidad.

### Definición de terminado para cada requisito

1. Diseño de interacción revisado con usuarios y estado vacío/carga/error.
2. Contrato API, esquema/migración y autorización revisados cuando aplica.
3. Criterios funcionales y negativos automatizados; smoke manual en dispositivos objetivo.
4. Accesibilidad, localización, métricas y registro sin datos personales revisados.
5. Política de retención, límites de carga y notas de migración publicadas.
6. Piloto reversible con feature flag y plan de rollback; comparar métricas con línea base antes de ampliar.

## 15. Preguntas de producto para resolver en refinamiento

1. ¿Juegos será pestaña permanente o entrada desde Mensajes? Esta decisión cambia navegación, orden y disponibilidad para menores.
2. ¿El fijado de mensajes será permitido a todos, solo a administradores o configurable por conversación? Propuesta inicial: todos en chat 1:1; administradores en grupos/comunidades, con límite 10.
3. ¿La edición de documentos admite solo DOCX en primera entrega? Propuesta inicial: DOCX editable y PDF en solo lectura; otros formatos declaran compatibilidad parcial.
4. ¿Qué audiencia y periodo de retención se aplican a historiales de juegos, analítica y exportaciones? Propuesta inicial: historial privado por 90 días y exportaciones con enlace de 24 h.
5. ¿Se requiere que visor/editor y Juegos también estén disponibles en web desde la primera entrega o se prioriza Android?

## 16. Fuente metodológica

- Nielsen Norman Group, [10 Usability Heuristics for User Interface Design](https://www.nngroup.com/articles/ten-usability-heuristics/), Jakob Nielsen; página revisada el 30-01-2024; consultada el 24-09-2026. Los principios sirven para inspección; no son requisitos funcionales por sí solos.
