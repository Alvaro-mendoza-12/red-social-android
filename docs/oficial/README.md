# Novvin — Documentación oficial del proyecto

Estado documental al 13 de noviembre de 2026. Esta es la entrada para desarrollo, operación y traspaso técnico.

## Estado vigente

La [continuación 133](133-formularios-de-herramientas-live-2026-11-13.md) separa los borradores y las acciones de juego y puntos por visita de cuenta, protege la lectura del saldo y conserva la recompensa hasta confirmar su creación. Identifica las pruebas de interfaz y los límites del bloque. La propiedad global del motor y del servicio LIVE, otros formularios, las pruebas físicas y la APK final siguen pendientes; no cierra la revisión integral.

La [continuación 132](132-cuentas-juego-y-canjes-live-2026-11-13.md) corrige las visitas de cuenta del juego y de puntos LIVE, conserva los canjes ante confirmaciones perdidas y rechaza reintentos de otra cuenta o recompensa. Documenta 2817 pruebas JVM aprobadas, las transacciones PostgreSQL y los resultados instrumentales del bloque. Sustituye el pendiente local 128; no representa una APK final ni un despliegue productivo y mantiene abierta la revisión integral.

Las continuaciones [130](130-web-compacta-asistente-y-portada-2026-11-13.md) y [131](131-compartidos-publicos-por-contenido-2026-11-13.md) compactan la navegación y el chat, renombran el asistente a Novvin, simplifican la portada con capturas reales y corrigen las vistas previas públicas por objeto. Identifican sus ensayos locales, la paridad parcial del menú y los despliegues pendientes. El bloque móvil 128 sigue sin publicar; no se entrega una APK nueva ni se cierra la revisión integral.

La [continuación 129](129-web-menu-plegable-e-invitaciones-de-llamada-2026-11-13.md) incorpora el menú lateral web plegable y la invitación autorizada de una tercera persona durante una llamada, sin reiniciar los medios existentes. La prioridad web concreta fue indicada por el propietario; el bloque móvil 128 sigue local, sin publicar ni validar. Identifica los ensayos locales y mantiene pendientes la integración física, el despliegue de API y la revisión integral.

La [continuación 127](127-android-participacion-pausa-y-trazabilidad-2026-11-13.md) corrige la pausa y los borradores de participación LIVE en Android. Añade sesenta líneas omitidas a la trazabilidad, que conserva 357 entradas, y un control de correspondencia de fuentes en CI. Identifica sus pruebas y límites; la revisión móvil integral, los recorridos físicos y la APK final siguen pendientes.

La [continuación 125](125-live-participacion-y-recuperacion-2026-11-13.md) incorpora encuestas y preguntas de LIVE en Android, web y API, corrige el acceso de invitados privados y la recuperación de suscripciones Android y confirma inicio/salida en la web. Supera 2 777 pruebas JVM y doce controles de emulador. Son cambios y ensayos locales, con integración física y despliegue pendientes. La [trazabilidad de requisitos](../product/REQUIREMENT_TRACEABILITY.csv) conserva también las fases todavía abiertas.

La [revisión nocturna 124](../../Novvin_Auditoria_Ecosistema/INFORME_AVANCE_CODEX.md) corrige mensajería colectiva en tiempo real, documentos, stickers, llamadas y el vencimiento de rondas. El [estado de salida](../operations/PRODUCTION_READINESS.md) corresponde al candidato local de las dos ramas estables. Los ensayos completos de red física, R2 externo y recuperación operativa siguen abiertos.

La [revisión 123](123-rediseno-web-navegacion-y-controles-2026-11-12.md) documenta la portada, la navegación web y las acciones de conversación, publicación y perfil. `administracion@novvinapp.com` es el contacto directo. Los códigos de verificación y recuperación conservan su remitente habitual por decisión del propietario.

La [revisión 122](122-stickers-monedero-imagenes-y-dominio-2026-11-12.md) documenta las correcciones de stickers y monedero, las imágenes de Momentos y los enlaces y códigos QR de perfil con el dominio novvinapp.com. El sitio y la API de Ubuntu ya sirven las invitaciones públicas. El contacto oficial es administracion@novvinapp.com. El proveedor rechazó su autenticación SMTP y el remitente activo anterior autenticó por TLS. La revisión integral continúa abierta.

La [continuación 121](121-dominio-live-y-prompt-auditoria-2026-11-12.md) añade enlaces LIVE del dominio oficial y su lectura Android, corrige el compartido web del directo minimizado y conserva los enlaces propios en moderación. Incluye el encargo detallado de auditoría independiente para Gemini. Separa el código de una nueva APK y de la actualización de índices de buscadores.

La [continuación 120](120-contadores-bandeja-e-iconos-2026-11-12.md) conecta los contadores de los filtros privados en Android y web y corrige iconos de la bandeja. Distingue pruebas automatizadas, publicación y entrega instalada; no representa una APK nueva ni el cierre integral.

La [continuación 119](119-dominio-y-recuperacion-bandeja-2026-11-12.md) corrige la recuperación de conversaciones entrantes y su ocultación después de mensajes nuevos. Vincula `novvinapp.com` a Vercel, prepara Android y autoriza sus orígenes en Ubuntu con respaldo. DNS y HTTPS están activos; la migración de enlaces y la indexación se documentan con sus comprobaciones. El SMTP corporativo está pendiente por rechazo del proveedor; se restauró el remitente anterior con autenticación TLS verificada. No representa una APK nueva ni el cierre de la revisión integral.

La [matriz 118](118-veinte-criterios-web-y-android-2026-11-12.md) contrasta los veinte criterios solicitados con web y Android, añade privacidad y cookies públicas, reduce sin pérdida las imágenes de las guías y mantiene una acción principal por página. Separa los datos legales y la analítica aún no configurados de las comprobaciones técnicas aprobadas.

La [continuación 117](117-cambio-de-cuenta-y-web-publica-2026-11-12.md) corrige el seguimiento de ActivityScenario del ensayo de cambio de cuenta, refuerza A → B → A y verifica el alojamiento público web con 90 controles HTTP y navegador. Distingue el fallo del ensayo de la lógica de cuentas de producción.

La [operación 116](116-ubuntu-tiempo-real-y-diseno-web-2026-11-12.md) instala en Ubuntu las correcciones de tiempo real y amistades con respaldo y restauración comprobados, conserva las funciones ya activas y corrige iconos y controles de la web. Separa las pruebas con datos ficticios del despliegue productivo y mantiene abierta la revisión integral.

La [continuación 115](115-tiempo-real-y-cancelacion-amistades-2026-11-12.md) corrige los bloques de tiempo real y cancelación de amistades diferidos por 114, con pruebas de recepción y concurrencia PostgreSQL. Distingue el código publicado del servidor Ubuntu activo y mantiene abierta la revisión integral.

La [selección de correcciones 114](114-comparacion-correcciones-dot-2026-11-12.md) compara `correcciones-dot` con las dos ramas estables e identifica las mejoras incorporadas, sus pruebas y los bloques diferidos. No cierra la revisión integral ni representa una APK nueva o un despliegue del servidor.

La [revisión de arranque de Videos 113](113-arranque-y-desplazamiento-de-videos.md) separa la medición de rangos de R2/Ubuntu de la experiencia en pantalla y registra los ajustes de reproducción y precarga de Android.

La [revisión móvil 101](101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md) mantiene el estado actual, las correcciones comprobadas y sus límites. **La revisión integral de Android sigue abierta.** Primero se completa el móvil y después se revisa la web. Una batería de pruebas aprobada no certifica todos los recorridos ni sustituye la cobertura de código pendiente.

La [matriz de cobertura por archivo](review-coverage.json) registra las lecturas a una huella concreta de todos los archivos versionados. Incluye código, pruebas, recursos y documentos; los respaldos y temporales ignorados quedan fuera. `code-read` significa lectura del archivo, no certificación de producción. Los cambios posteriores invalidan automáticamente esa lectura.

La [revisión de rendimiento 103](103-rendimiento-social-y-videos.md) comprobó la resolución acotada del dominio R2, la carga progresiva de Social y los ajustes de Videos. La versión con presupuesto TCP multimedia de cinco segundos aprobó **2.673 pruebas JVM en 506 clases**, **15 pruebas de emulador** y la comparación de lectura y permisos R2/Ubuntu. El mismo video público dio medianas de primer cuadro de **1.581 ms con Wi-Fi** y **1.520 ms con datos móviles sin limitación artificial**, desde R2. La referencia Wi-Fi daba 11.711 ms. La simulación limitada sigue fallando y se conserva como pendiente, junto con el recorrido completo del feed autenticado y las medidas físicas de fluidez. La compilación local posterior sí se completó. Los fallos de preparación de CI siguen descritos en 103. Las correcciones posteriores de proporción de fotos, cámara y reacciones tienen su evidencia en la revisión 104.

La [revisión de cámara y reacciones 104](104-camara-y-reacciones-android.md) registra el achatamiento de fotos por orientación EXIF y las reacciones guardadas que algunas lecturas no reconocían. Su corrección de servidor se instaló inicialmente en `novvin-api:likes104-20261103_84ae4912`, con controles de salud y disponibilidad aprobados. El AAB firmado **9.1.39 / 179** está entregado en ambos proyectos. La tanda Android aprobó 2.689 pruebas JVM, 22 pruebas de interfaz y una lectura nativa R2/Ubuntu. El documento identifica por separado sus commits y el de la entrega firmada. Lint final terminó con cero errores y 318 avisos.

La [revisión 110](110-contadores-social-y-avatares.md) corrige la omisión de likes y comentarios en las recomendaciones y la repetición de avatares de video. Su corrección de API se instaló en `novvin-api:engagement110-20261104_a0dbcc2d_p2`; la [operación 112](112-pruebas-android-studio-y-api-experimental.md) registra la imagen activa posterior, el respaldo y la comprobación de que conserva esos contadores. Pasaron nueve pruebas HTTP/PostgreSQL aisladas, 388 pruebas JVM seleccionadas y 23 pruebas de emulador, además de la comparación nativa R2/Ubuntu.

El AAB firmado más reciente es **9.1.40 / 180**, descrito en la [revisión 110](110-contadores-social-y-avatares.md). Incluye las correcciones estables y pasó firma, R8, alineación de 16 KiB y sus cuatro presupuestos de tamaño. Las dos copias entregadas tienen la misma huella. Lint K1 terminó con cero errores y 318 avisos. La APK histórica entregada sigue siendo **9.1.35 / 175**, descrita en la [entrega 100](100-compartidos-completos-documentos-y-candidato-175-2026-10-31.md). La validación física y la subida del AAB a Google Play están a cargo del propietario. `main` y `feature/live-gifts` deben contener las mismas correcciones. La rama `correciones` queda para el otro chat.

El backend activo es el servidor propio Ubuntu. Empieza por [arquitectura](01-arquitectura.md), [backend](03-backend-mini-pc.md) y [operación](06-operaciones-runbook.md); contrasta cualquier cambio de infraestructura con su estado real. Los nombres de contratos o tablas de importación no son evidencia de que otro servicio esté en producción.

La [operación R2 102](102-r2-replica-y-entrega-multimedia.md) registra la réplica activa desde Ubuntu a un bucket privado, la entrega mediante Worker, la preparación de avatares y la reducción medida de la consulta previa web. Distingue despliegues, pruebas automatizadas, recuperación del JPEG antiguo y entregas Android. El chat de voz usa contenido temporal con vencimiento de una hora.

La revisión web de compartidos está en la [entrega 100](100-compartidos-completos-documentos-y-candidato-175-2026-10-31.md); el historial de personas y grupos y el envío de documentos se revalidarán después del móvil. La operación 102 registra las correcciones y los ensayos posteriores de multimedia. El proveedor de retiros sigue pendiente; las preferencias de compra están en [91](91-presupuesto-30m-correo-y-opcion-dlocal-2026-10-29.md).

## Cómo interpretar el historial

Los informes numerados y fechados registran lo que se comprobó en esa versión. Una mención de «cierre», una cifra de pruebas, un paquete o un despliegue antiguo no constituye el estado actual. Para continuar el trabajo usa el apartado **Estado vigente** de la revisión 101, y después el documento operativo correspondiente. La limpieza local de carpetas de [93](93-limpieza-local-y-git-2026-10-30.md) es evidencia fechada, no una autorización para borrar nuevos respaldos o temporales ignorados.

## Índice recomendado de lectura

1. [Contexto y estado actual](00-contexto-y-estado.md)
2. [Arquitectura general](01-arquitectura.md)
3. [Aplicación Android](02-android.md)
4. [Backend Ubuntu / servidor mini PC](03-backend-mini-pc.md)
5. [WebRTC: llamadas, salas de voz, cámara y pantalla](04-webrtc-llamadas-salas.md)
6. [Datos, storage y backups](05-datos-storage-backups.md)
7. [Operación en producción / runbook](06-operaciones-runbook.md)
8. [Seguridad y legal](07-seguridad-legal.md)
9. [Pruebas, release y handoff](08-pruebas-release-handoff.md)
10. [IA propia de Conectate (Novvin)](09-ia-conectate.md)
11. [Experiencia Novvin LIVE](10-live-experience-reference.md)
12. [Auditoría y validación de Novvin LIVE](11-live-validation-audit.md)
13. [Auditoría de preparación para producción](12-production-readiness-audit.md)
14. [Música comunitaria, audio de Momentos y batallas Cross-LIVE](13-musica-comunitaria-y-batallas-cross-live.md)
15. [Sistema musical legal para Momentos](14-sistema-musical-legal.md)
16. [Auditoría extrema de producción 2026-08-24](15-auditoria-extrema-2026-08-24.md)
17. [Auditoría técnica y plan priorizado 2026-08-25](../AUDITORIA_TECNICA_2026-08-25.md)
18. [Runbook: Cloudflare Tunnel nombrado](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md)
19. [Modelo operativo de NoWi por áreas](../empresa/README.md)
20. [Roles y capacidades administrativas](../empresa/06-roles-y-capacidades-administrativas.md)
21. [Recuperación de túnel, feed y mezcla de momentos — 2026-09-09](16-recuperacion-tunel-y-feed-2026-09-09.md)
22. [Borrado durable por cuenta — 2026-09-09](17-borrado-durable-por-cuenta-2026-09-09.md)
23. [Timeout de mensajes programados y aislamiento del chat — 2026-09-09](18-mensajes-programados-timeout-2026-09-09.md)
24. [Visibilidad de la bandeja por cuenta — 2026-09-10](19-visibilidad-bandeja-2026-09-10.md)
25. [Integridad de los reintentos de mensajes — 2026-09-10](20-integridad-reintentos-mensajes-2026-09-10.md)
26. [Videos, Social y conectividad pública — 2026-09-10](21-videos-social-conectividad-2026-09-10.md)
27. [Social, LIVE, regalos y validación de servicios — 2026-09-12](22-live-regalos-y-validacion-2026-09-12.md)
28. [Multimedia sin conexión y restauración aislada — 2026-09-13](23-multimedia-offline-y-restauracion-2026-09-13.md)
29. [Música, permisos de multimedia y plan Gamer Hub — 2026-09-13](24-musica-privacidad-y-plan-gamer-hub-2026-09-13.md)
30. [Prueba física, cabecera de Momentos y filtros PlayMatch — 2026-09-14](25-prueba-fisica-momentos-y-playmatch-2026-09-14.md)
31. [Firma AAB desde Android Studio — 2026-09-14](26-firma-aab-android-studio-2026-09-14.md)
32. [Música completa, portadas y aviso de Windows — 2026-09-14](27-musica-completa-y-watchdog-2026-09-14.md)
33. [Fragmentos musicales en video — 2026-09-16](28-fragmentos-musicales-en-video-2026-09-16.md)
34. [Catálogo musical: concurrencia y recuperación — 2026-09-16](29-catalogo-musical-concurrencia-2026-09-16.md)
35. [Identidades sin conexión y retención por cuenta — 2026-09-17](30-identidades-offline-por-cuenta-2026-09-17.md)
36. [Destacados del perfil y plan de diferenciales — 2026-09-17](31-perfil-destacados-y-diferenciales-2026-09-17.md)
37. [Permisos de stickers y cierre de destacados — 2026-09-18](32-permisos-stickers-y-cierre-destacados-2026-09-18.md)
38. [Fluidez y auditoría por módulos — 2026-09-30](33-fluidez-y-auditoria-modulos-2026-09-30.md)
39. [Cadencia de LIVE, salas, llamadas y PK — 2026-10-01](34-cadencia-live-salas-2026-10-01.md)
40. [Arranque, invitaciones LIVE y recuperación por correo — 2026-10-01](35-arranque-invitaciones-y-recuperacion-correo-2026-10-01.md)
41. [Mensajería, recomendaciones y avisos — 2026-10-01](36-mensajeria-recomendaciones-avisos-2026-10-01.md)
42. [SSD autorizado, llamadas y multimedia — 2026-10-01](37-ssd-llamadas-multimedia-2026-10-01.md)
43. [Web publicada: autenticación y avisos — 2026-10-01](38-web-autenticacion-avisos-2026-10-01.md)
44. [Móvil: cancelación de llamadas y adjuntos simultáneos — 2026-10-01](39-movil-llamadas-y-adjuntos-2026-10-01.md)
45. [Móvil: mensajes físicos, notas de voz y galería — 2026-10-01](40-movil-pruebas-fisicas-mensajes-2026-10-01.md)
46. [Móvil: lectura inmediata y archivos entre redes — 2026-10-02](41-movil-confirmaciones-y-archivos-2026-10-02.md)
47. [Móvil: citas y reintentos de documentos — 2026-10-02](42-movil-citas-y-reintentos-2026-10-02.md)
48. [Móvil: negociación de llamadas y documentos internos — 2026-10-02](43-movil-arranque-llamadas-y-documentos-2026-10-02.md)
49. [Móvil: preparación de red y llamada con la tablet bloqueada — 2026-10-02](44-movil-preparacion-red-y-llamada-bloqueada-2026-10-02.md)
50. [Móvil: señalización durante el timbre y cancelación física — 2026-10-02](45-movil-senalizacion-durante-timbre-2026-10-02.md)
51. [Móvil: capacidad compartida y fallback de llamadas — 2026-10-02](46-movil-capacidad-fallback-llamadas-2026-10-02.md)
52. [Móvil: TURN por sesión y tiempos nativos de llamadas — 2026-10-02](47-movil-turn-sesion-y-tiempos-2026-10-02.md)
53. [Emulador: stickers y recuperación de la cola — 2026-10-02](48-emulador-stickers-y-cola-2026-10-02.md)
54. [Emulador: respuestas a multimedia y sincronización — 2026-10-02](49-emulador-mensajes-respuestas-y-sync-2026-10-02.md)
55. [Emulador: envío fuera del hilo de la pantalla y documentos — 2026-10-02](50-emulador-envio-background-y-documentos-2026-10-02.md)
56. [Creación de grupos y comunidades: propiedad y reintentos — 2026-10-02](51-creacion-grupos-propiedad-2026-10-02.md)
57. [Enlaces de chat — 2026-10-02](52-enlaces-chat-2026-10-02.md)
58. [Transporte de llamadas, salas y LIVE — 2026-10-02](53-transporte-llamadas-salas-live-2026-10-02.md)
59. [Permisos al contestar llamadas — 2026-10-02](54-permisos-al-contestar-llamadas-2026-10-02.md)
60. [Salas privadas y media entre emuladores — 2026-10-03](55-salas-privadas-y-media-emuladores-2026-10-03.md)
61. [Entrada a salas sin peticiones repetidas — 2026-10-03](56-admision-salas-sin-peticiones-repetidas-2026-10-03.md)
62. [LIVE: controles e invitados — 2026-10-03](57-live-controles-e-invitados-2026-10-03.md)
63. [Encuestas: transacción y actualizaciones — 2026-10-03](58-encuestas-transaccion-y-actualizaciones-2026-10-03.md)
64. [Registro de visitas autenticadas — 2026-10-03](59-registro-de-visitas-autenticadas-2026-10-03.md)
65. [Encuestas, audio, Momentos, avisos y perfil — 2026-10-03](60-encuestas-audio-momentos-avisos-y-perfil-2026-10-03.md)
66. [Perfil, arranque y consultas visibles — 2026-10-03](61-perfil-arranque-y-consultas-visibles-2026-10-03.md)
67. [Social, Momentos y avisos — 2026-10-03](63-social-momentos-y-avisos-2026-10-03.md)
68. [Validación Moto G04 — 2026-10-03](64-validacion-moto-g04-2026-10-03.md)
69. [Rechazo de llamadas y arranque — 2026-10-04](65-rechazo-de-llamadas-y-arranque-2026-10-04.md)
70. [Validación móvil e729365 — 2026-10-04](66-validacion-movil-e729365-2026-10-04.md)
71. [Inicialización de novedades Home — 2026-10-04](67-inicializacion-de-novedades-home-2026-10-04.md)
72. [Editor móvil: música, letras y duración — 2026-10-04](68-editor-movil-musica-letras-y-duracion-2026-10-04.md)
73. [Encuestas: editor móvil y publicación — 2026-10-04](69-encuestas-editor-movil-y-publicacion-2026-10-04.md)
74. [Medios, arranque y avisos — 2026-10-04](70-medios-arranque-y-avisos-validacion-2026-10-04.md)
75. [Captura, edición y entrega — 2026-10-04](71-auditoria-captura-edicion-y-entrega-2026-10-04.md)
76. [Medios, Social y LIVE entre redes — 2026-10-05](72-medios-social-y-live-redes-distintas-2026-10-05.md)
77. [Botella al mar: recepción sin filtros — 2026-10-07](73-botella-al-mar-recepcion-sin-filtros-2026-10-07.md)
78. [Auditoría del release: acceso, sesión, invitado, vídeo y perfiles — 2026-10-24](74-auditoria-release-acceso-y-medios-2026-10-24.md)
79. [Continuidad móvil: acceso, emojis, avatares, vídeo y música de perfil — 2026-10-25](75-cierre-movil-167-2026-10-25.md)
80. [Servidor: limpieza, runtimes, dependencias y restauración — 2026-10-26](76-auditoria-servidor-2026-10-26.md)
81. [Prompt de continuidad: producción, auditoría y crecimiento](77-prompt-continuidad-produccion-y-escalado.md)
82. [Host actualizado, capacidad y acceso público web — 2026-10-27](78-capacidad-host-y-acceso-web-2026-10-27.md)
83. [Indexación pública e imágenes — 2026-10-27](79-indexacion-publica-2026-10-27.md)
84. [Auditoría SEO y candidato local, pendiente de autorización — 2026-10-27](80-auditoria-seo-y-candidato-local-2026-10-27.md)
85. [SEO, imágenes, chats y candidato Android 168](81-continuidad-seo-chats-y-release-168-2026-10-28.md)
86. [Carga multimedia, emojis y candidato 169](82-carga-multimedia-emojis-y-candidato-169-2026-10-28.md)
87. [Correcciones de las nuevas capturas de chat y voz](83-capturas-chat-y-recuperacion-voz-2026-10-28.md)
88. [Auditoría móvil integral y candidato 170](84-auditoria-integral-movil-2026-10-29.md)
89. [Conectividad, cancelación y carga del cliente móvil](85-conectividad-cancelacion-y-carga-movil-2026-10-29.md)
90. [Salas compartidas, recuperación de voz y avatares](86-salas-compartidas-voz-y-avatares-2026-10-29.md)
91. [Web: insignias, chat adaptable y estados comprensibles](87-web-insignias-chat-adaptable-y-estados-2026-10-29.md)
92. [Reintento de video e identidades completas](88-reintento-video-e-identidades-completas-2026-10-29.md)
93. [Colas por cuenta y recuperación del visor](89-colas-por-cuenta-y-recuperacion-visor-2026-10-29.md)
94. [Cierre de auditoría, red y presupuesto](90-cierre-produccion-red-y-presupuesto-2026-10-29.md)
95. [Cable de 30 m, dominio/correo y opción dLocal](91-presupuesto-30m-correo-y-opcion-dlocal-2026-10-29.md)
96. [Miniaturas, portada y contadores inmediatos](92-miniaturas-portada-y-contadores-inmediatos-2026-10-29.md)
97. [Limpieza local de las dos carpetas y de Git](93-limpieza-local-y-git-2026-10-30.md)
98. [Reacciones visibles y auditoría de navegación web](94-reacciones-y-recorrido-web-2026-10-30.md)
99. [Auditoría móvil: notificaciones, actualización y reconexión](95-auditoria-apk-notificaciones-y-reconexion-2026-10-30.md)
100. [Segundo plano, subidas, video y perfiles](96-segundo-plano-subidas-y-perfiles-2026-10-30.md)
101. [Segunda revisión: cancelación y continuidad de la cola push](97-segunda-revision-cancelacion-y-cola-push-2026-10-30.md)
102. [Colas web y carpetas de chats por cuenta](98-colas-web-y-carpetas-por-cuenta-2026-10-30.md)
103. [Contraste de auditoría móvil y candidato 174](99-contraste-auditoria-movil-y-candidato-174-2026-10-30.md)
104. [Compartidos completos, documentos y candidato 175](100-compartidos-completos-documentos-y-candidato-175-2026-10-31.md)

## Reglas para quien trabaje en este proyecto

- No asumir que “compila” significa “funciona en producción”.
- No declarar listas las llamadas, salas de voz o pantalla compartida sin prueba en Android real, con al menos dos dispositivos y, cuando aplique, en redes diferentes.
- No volver a depender de Supabase como backend principal. Supabase queda como legado histórico/migración, salvo que el dueño del proyecto lo autorice explícitamente.
- No publicar secretos, tokens, llaves Firebase, keystores, `.env`, `secrets.properties` ni credenciales de túneles/TURN.
- Antes de tocar backend de producción, crear backup verificable.
- Antes de subir a Play Store, subir `versionCode`, generar AAB release y probar instalación limpia.
- Para cualquier cambio grande, documentar:
  - qué se cambió;
  - qué se probó;
  - qué queda pendiente;
  - cómo revertir.

## Definiciones rápidas

- **Conéctate**: red social móvil con mensajes, grupos, comunidades, videos, social/feed, perfiles, llamadas, salas de voz y notificaciones.
- **APK/App Android**: cliente principal de producción.
- **Backend Ubuntu**: backend propio desplegado en la mini PC con Ubuntu Server.
- **Mini PC / servidor**: máquina física que aloja Docker, PostgreSQL, API, realtime, signaling, nginx edge, backups y servicios 24/7.
- **Ingreso público actual**: HTTPS mediante Tailscale Funnel en `https://conectate.tail9b267c.ts.net`, comprobado desde IPv4 público sin usar la LAN ni la IP privada de Tailscale. Los documentos de Cloudflare describen el despliegue anterior y una alternativa operativa; no prueban la configuración vigente.
- **DuckDNS TXT discovery**: mecanismo firmado y temporal de transición/recuperación; no sustituye el hostname API estable.
- **TURN/STUN**: infraestructura para que WebRTC pueda conectar audio/video/pantalla entre redes distintas.
- **CGNAT**: restricción del proveedor de internet que impide exponer puertos públicos directamente desde el router.
