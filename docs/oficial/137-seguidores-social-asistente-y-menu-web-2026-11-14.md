# 137 — Seguidores, Social, asistente y menú web

14 de noviembre de 2026. Continuación de [136](136-integracion-api-compartidos-y-mandato-word-2026-11-14.md). El propietario priorizó estos ajustes web y repartió después la revisión Android con otro asistente por el chat de escritorio. No se crean subagentes ni se utiliza el proyecto como buzón. Las fuentes y huellas del bloque están en [137-resultados.json](../quality/evidence/137-resultados.json).

## Correcciones

- La pantalla propia de seguidores no renderizaba avatares. Ahora presenta foto o respaldo de SafeAvatar, nombre público, usuario, identidad canónica y una acción de seguimiento por persona. Acepta los campos de Ubuntu y evita duplicados. Las listas de otras personas también reconocen displayName y el tipo de avatar animado o de video.
- Cada cuenta, perfil y pestaña tiene su visita de carga. Las peticiones canceladas no sustituyen una vista nueva. Se impiden mutaciones duplicadas por persona y se confirma el estado con el servidor. El contrato heredado consulta también el seguimiento saliente para permitir dejar de seguir desde Seguidores.
- Las listas se suscriben al canal autorizado de recuentos del perfil y recuperan al volver al primer plano o reconectar el tiempo real, con agrupación de eventos y respaldo periódico de treinta segundos. La lista visible se conserva al refrescar; un error de lectura inicial no se presenta como una comunidad vacía. Esto no certifica actualización instantánea de todas las cuentas ni el rendimiento de listas masivas.
- El menú del chat muestra seis entradas: información, buscar, seleccionar, notificaciones, Más opciones y cerrar. El segundo nivel conserva apodo, programación, traducción, encuesta, fijado, favoritos, archivo y herramientas. Volver, Escape, flechas y Tab conservan navegación y foco; se mantienen las acciones existentes.
- Los stickers de conversación pasan de 160 a 136 píxeles, conservando proporción y animación. La biblioteca mantiene desplazamiento sin barra visible. La cabecera y el compositor siguen en 58 y 52 píxeles en el ensayo; este bloque no los reduce más.
- Social conserva Social/Momentos/Rondas arriba y utiliza el espacio de escritorio para publicaciones y columna de Momentos. Se corrigió también la especificidad de la antigua restricción de ancho. En móvil queda una columna. Los carruseles contienen las fotos completas en su celda en vez de recortarlas; siguen disponibles los visores y las acciones.
- El asistente Novvin conserva conversación centrada, compositor inferior y color azul. Presenta encabezados, listas, énfasis y código como texto seguro; no interpreta HTML ni ejecuta enlaces o código. Añade una acción visible de nueva conversación y mantiene borrador, historial por cuenta, cancelación, reintento y copia. No cambia el proveedor ni afirma capacidades nuevas del modelo.

## Verificación

La suite web completa aprueba **1.249 pruebas en 196 suites**, sin fallos ni omisiones. Incluye carreras de cuenta y pestaña, carga cancelada, seguimiento duplicado, refresco agrupado, contenido HTML inerte del asistente y pruebas de interacción del chat. Se actualizaron los recorridos de esas pruebas para entrar al segundo nivel del menú, manteniendo sus aserciones de permisos y aislamiento.

TypeScript, Vite y los controles de **21 páginas públicas** pasan. ESLint sobre siete archivos de implementación termina con cero errores y cero avisos. Vite mantiene el aviso conocido de un paquete grande; este bloque no resuelve todo el presupuesto de JavaScript.

Playwright con Chromium 158 ejecuta bandeja, chat, seguidores, asistente y Social en **1440, 768 y 375 píxeles**. Se comprueban avatares cargados, búsqueda, menú, respuesta estructurada, fotografías completas, columnas de escritorio, desplazamiento funcional y ausencia de desbordamiento. No aparecen errores de página ni consola ni solicitudes externas bloqueadas. Axe 4.14.0 registra cero infracciones en los recorridos ensayados. Se corrigieron dos diferencias entre texto visible y nombre accesible antes del resultado final.

Los datos, imágenes, API y WebSockets de esos recorridos son sintéticos. No son una sesión personal ni prueban toda la red productiva, lector de pantalla manual, permisos o medios físicos. Se revisaron las capturas; **la comparación visual automatizada es inconclusa** porque no hay referencia versionada equivalente. Las capturas de esta entrega son evidencia del aspecto observado, no una certificación universal.

Capturas: [seguidores escritorio](../quality/evidence/137-followers-1440.png), [Social escritorio](../quality/evidence/137-social-1440.png), [asistente escritorio](../quality/evidence/137-assistant-1440.png), [seguidores móvil web](../quality/evidence/137-followers-375.png), [asistente móvil web](../quality/evidence/137-assistant-375.png).

## Compartidos e investigación

La [guía de fuentes y criterios](../product/COMPARTIDOS_PUBLICOS_REFERENCIAS_2026-11-14.md) contrasta Open Graph, Meta, Mastodon y documentación fuente de Vercel/Next.js. Define imagen y destino por perfil, publicación, video y publicidad, junto con privacidad y caché. Se identifican versiones fijas en las URLs de las tarjetas: cambiar avatar o contenido todavía puede conservar la misma URL externa. La corrección y la prueba de actualización se mantienen pendientes, al igual que el recorrido específico de publicidad y el ensayo dentro de WhatsApp.

## Publicación y límites

El bloque se prepara desde el árbol común de 136 para publicar únicamente estas correcciones en main y feature/live-gifts. El despliegue de 137 se comprobará por separado; las pruebas anteriores son del paquete local. La API 136 sigue siendo la integración activa documentada. Los cuatro cambios locales ajenos de web, los documentos ajenos de main y el trabajo Android del otro asistente no se incorporan a este bloque.

El otro asistente asumió CallTonePlayer.kt y sus pruebas de inicio/parada en el worktree de main. Su trabajo no está incluido ni validado por estas cifras. Se coordina cualquier cambio de índice o publicación por el panel antes de integrar; correciones y el plan experimental 105 permanecen separados.

Continúan abiertos los historiales vacíos y el límite global de mensajes importados, escalabilidad de seguidores, carga de grupos y comunidades, paridad completa del menú con Android, archivos, tonos y medios físicos, propiedad global del motor/servicio LIVE y los demás recorridos móviles. No se genera una APK nueva ni se declara preparación integral para producción o marketing masivo. Las lecturas parciales de archivos grandes se conservan como parciales en la cobertura; compilar o buscar texto no las convierte en revisión completa.
