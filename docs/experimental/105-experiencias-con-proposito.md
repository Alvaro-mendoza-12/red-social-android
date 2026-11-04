# Experiencias con propósito en Novvin

Investigación y decisión inicial del 3 de noviembre de 2026. El propietario autorizó una rama nueva a partir de las correcciones actuales. La implementación se encuentra en `codex/experiencias-con-proposito`, creada desde `1a1a54d1`, después de publicar la entrega estable 179 en `main` y `feature/live-gifts`. Se utiliza el worktree existente de validación. La rama `correciones` conserva su propietario. La revisión integral móvil continúa abierta según el estado oficial 101.

## Criterio de producto

El 4 de noviembre el propietario confirmó que todo el plan de innovación y sus rediseños pertenece exclusivamente a `codex/experiencias-con-proposito`. Las ramas estables contienen las correcciones anteriores documentadas en 104. Se verificó con los árboles de Git la ausencia de los archivos de Rutas en `main` y `feature/live-gifts`.

La hipótesis común consiste en permitir que una persona declare para qué entra, recorra una cantidad comprensible de contenido o acciones, produzca un resultado y pueda retomarlo. La utilidad se medirá por tarea resuelta, control y satisfacción declarada. La duración de la visita por sí sola será insuficiente para aceptar una propuesta.

Cada módulo tendrá una ventaja que se pueda probar por separado. La combinación de módulos servirá para conservar el contexto cuando el usuario decida pasar de uno a otro. La investigación documental permite identificar antecedentes y oportunidades. La preferencia real del público requiere ensayos de producto.

## Antecedentes consultados

- Engelbart planteó en 1962 estudiar sistemas que ayuden a comprender situaciones y resolver problemas. Se toma como orientación para convertir actividad en resultados verificables. [Informe original](https://www.dougengelbart.org/content/view/138).
- Pirolli y Card estudiaron en CHI 1995 el valor de la información obtenida frente al coste de encontrarla. Esto inspira medir resultados útiles por sesión. Su trabajo sobre acceso a documentos requiere adaptación y validación para video social. [Artículo CHI](https://chi1995.acm.org/proceedings/papers/ppp_bdy.htm).
- Babble exploró en 1999 conversación persistente y señales sociales en grupos pequeños. Ofrece antecedentes para conservar el contexto de una conversación. Sus observaciones no se extrapolan directamente a una red pública masiva. [IBM Research](https://research.ibm.com/publications/socially-translucent-systems-social-proxies-persistent-conversation-and-the-design-of-babble).
- Whittaker y Sidner estudiaron en 1996 el uso del correo para comunicación, archivo y seguimiento de tareas. Sus veinte participantes pertenecían a Lotus. Ese alcance laboral explica por qué se toma como antecedente de problemas de contexto y se exige investigar las necesidades del público actual de Novvin. [CHI 1996](https://chi1996.acm.org/proceedings/papers/Whittaker/sw_txt.html).
- IBM publicó en 2003 un prototipo de colaboración alrededor de objetos compartidos y en 2006 su evolución a Activity Explorer. Conservar origen, actividad y participantes tiene antecedentes concretos. La propuesta de acuerdos debe demostrar que ese contexto reduce malentendidos en conversaciones móviles. [GROUP 2003](https://research.ibm.com/publications/supporting-activity-centric-collaboration-through-peer-to-peer-shared-objects), [IBM Systems Journal 2006](https://research.ibm.com/publications/activity-explorer-activity-centric-collaboration-from-research-to-product).
- TikTok ya documenta preferencias por temas, «No me interesa», filtros y reinicio del feed. Novvin ya tiene Mi algoritmo y controles explícitos. Un nuevo selector de temas duplicaría mecanismos existentes. [TikTok](https://support.tiktok.com/en/account-and-privacy/account-privacy-settings/manage-topics).
- Un estudio de 2026 con cuentas controladas examina diferencias entre señales explícitas e implícitas del feed. Apoya investigar el control del usuario, con límites propios de ese método y de una versión concreta de la plataforma. [Kaplan y colaboradores](https://arxiv.org/abs/2605.10690).
- Un ensayo de siete días con 104 participantes encontró respuestas diferentes a intervenciones para interrumpir el desplazamiento. La aplicación de esos resultados exige opciones voluntarias y validación local. Novvin ya incluye un presupuesto de minutos y pausa de sesión. [Meinhardt y colaboradores](https://arxiv.org/abs/2607.15818).
- WhatsApp documenta listas, eventos y recordatorios. Meta describe un sistema específico para procesar mensajes privados con IA. Se descartan esas etiquetas como prueba de novedad y se evitan propuestas que envíen conversaciones a un procesador nuevo por defecto. [Listas](https://blog.whatsapp.com/focus-on-what-matters-with-custom-lists), [recordatorios](https://about.fb.com/news/2026/01/whatsapp-group-chats-member-tags-text-stickers-event-reminders/), [procesamiento privado](https://engineering.fb.com/2025/04/29/security/whatsapp-private-processing-ai-tools/).
- Instagram Blend ya combina recomendaciones entre miembros de un chat mediante invitación. Un feed compartido, por sí solo, tiene ese antecedente comercial. [Meta Japón](https://about.fb.com/ja/news/2025/04/instagram-reels-blend-launch/amp/).
- Discord ya personaliza roles y canales al entrar en una comunidad. La oportunidad a comprobar es ayudar a completar una primera contribución útil después del acceso. [Discord](https://support.discord.com/hc/en-us/articles/11074987197975-Community-Onboarding-FAQ).
- GamerLink ya ofrece búsqueda de compañeros por juegos y plataformas. PlayMatch también posee catálogo, disponibilidad y preferencias. La investigación de gaming se centrará en llegar a una partida acordada y comprobar su resultado. [GamerLink](https://gamerlinkapp.com/faq).
- La publicación EP3664461A1 describe control de reproducción y listas a partir de intención y entidades. Es un antecedente documental para descartar una afirmación de originalidad sobre «intención más playlist». El estudio de producto no constituye un análisis jurídico de las reivindicaciones ni de libertad de operación. [Publicación de patente](https://patents.google.com/patent/EP3664461A1/en).

Las fuentes incluyen publicaciones antiguas, investigación reciente y funciones comerciales documentadas. Se consultaron documentos y resúmenes disponibles públicamente. Las propuestas siguientes son hipótesis de producto propias que requieren prototipos y comparación.

## Videos. Rutas con una pregunta y un cierre

**Problema y situación actual.** Una persona que entra para encontrar algo concreto puede perder el objetivo entre recomendaciones sucesivas. Novvin ya tiene búsqueda, Mi algoritmo, historial, colecciones, guardados y pausa por minutos. Se aprovecharán esas piezas.

**Propuesta y diferencia a probar.** El usuario elige una pregunta o tema y una cantidad pequeña de videos. La aplicación prepara un recorrido finito, indica su tamaño real, favorece autores distintos cuando el catálogo lo permite y conserva los hallazgos elegidos. Al terminar presenta un cierre y permite retomar un resultado concreto. La hipótesis de preferencia es llegar a algo útil con un esfuerzo previsible.

**Uso.** Entrar en Rutas, elegir tema y tamaño, revisar la selección, reproducir, guardar lo que sirve y terminar o pausar. El sistema comunica catálogo insuficiente y errores de lectura. Una interrupción conserva la selección y la posición de la cuenta.

**Viabilidad e implementación.** Primera prioridad. Empezar en Android con búsqueda autorizada existente, reproducción Media3/R2, estado acotado por cuenta y componentes separados del feed. Las rutas guardarán referencias y resultados voluntarios. La adaptación web utilizará el mismo contrato de selección. La sincronización entre dispositivos necesitará almacenamiento autorizado del backend en una etapa posterior.

**Validación y riesgos.** Comparar una tarea de búsqueda con el flujo actual usando orden alternado, misma red y catálogo. Medir resultado encontrado, esfuerzo, satisfacción y consumo de datos. Comprobar duplicados, catálogo vacío, errores, cuenta cambiada, proceso recreado y video retirado. La variedad de autores no acredita veracidad ni diversidad de opiniones. La superioridad frente a TikTok sigue como hipótesis a evaluar.

## Mensajería. Acuerdos que conservan su origen

**Problema y situación actual.** Un chat mezcla propuestas, respuestas y cambios. Eventos, listas, mensajes fijados y resúmenes ya tienen equivalentes comerciales y algunos existen en Novvin. El resultado acordado puede seguir requiriendo reconstruir la conversación.

**Propuesta y diferencia a probar.** Un participante propone una decisión vinculada al mensaje original. Las personas afectadas pueden aceptar, pedir cambios o retirarse. La decisión muestra su revisión y quién confirmó cada parte. Cada modificación invalida las aceptaciones que correspondan. La ventaja propuesta es reconocer qué se decidió y qué falta confirmar.

**Uso.** Desde un mensaje, proponer acuerdo, elegir participantes y resolverlo dentro del chat. Se conserva el enlace al contexto y se presenta un estado pendiente cuando falta confirmación. La IA opcional podría redactar un borrador únicamente con contenido elegido por el usuario.

**Viabilidad e implementación.** Segunda prioridad, con cambios Android, web y API. Reutilizar identidad de conversación, permisos de participantes, referencias de mensaje y eventos de tiempo real. Se requiere un módulo separado con revisión esperada, idempotencia y confirmaciones explícitas. El contrato de privacidad real de la mensajería debe verificarse antes de añadir procesamiento de contenido.

**Validación y riesgos.** Ensayar dos participantes, cambios simultáneos, retirada de un miembro, pérdida del acuse y borrado del mensaje de origen. Medir tiempo para identificar el acuerdo y errores de interpretación. La presión social y las falsas atribuciones de consentimiento son riesgos centrales. Se mantienen las opciones de rechazar y abandonar.

## Social. Publicaciones que muestran cómo terminó una petición

**Problema y situación actual.** Una pregunta o petición puede recibir reacciones sin que quien llega después sepa si encontró solución. Las preguntas y los estados resueltos tienen antecedentes en foros y comunidades.

**Propuesta y diferencia a probar.** Un recorrido voluntario que enlaza necesidad, aportaciones y resultado. El autor puede publicar una conclusión con referencias a las contribuciones y revisarla si aparece información nueva. La razón propuesta para preferirlo es seguir un problema hasta un resultado reutilizable.

**Uso.** Publicar una petición, recibir aportaciones, indicar qué ayudó y compartir cómo terminó. Las reacciones siguen disponibles. El resultado conserva fecha, revisión y autoría.

**Viabilidad e implementación.** Tercera prioridad. Reutilizar publicaciones, respuestas, preguntas comunitarias y referencias existentes. Android y web comparten las representaciones. La API autoriza la edición y mantiene el vínculo con el origen. Hace falta revisar primero las capacidades ya implementadas para evitar otra representación paralela.

**Validación y riesgos.** Medir peticiones resueltas y facilidad para encontrar la conclusión. Probar retractación, aportación eliminada, bloqueo de usuarios y privacidad del origen. Resolver una petición expresa la valoración de su autor. El sistema debe evitar presentar esa marca como certificación universal.

## Comunidades. Primera contribución acompañada

**Problema y situación actual.** Una persona puede entrar y seguir sin saber cómo participar. Discord ya ofrece incorporación por roles y canales. Novvin dispone de canales, preguntas y eventos.

**Propuesta y diferencia a probar.** El nuevo miembro elige qué desea aportar o conseguir y recibe una acción pequeña que la comunidad mantiene disponible. Puede ayudar en una pregunta abierta, participar en un encuentro o pedir orientación a alguien que haya aceptado acompañar. El resultado propuesto es una primera interacción útil.

**Uso.** Elegir un propósito opcional, ver una acción compatible con permisos y disponibilidad y participar. Poder omitir o salir en cualquier momento.

**Viabilidad e implementación.** Tercera prioridad, junto con Social. Reutilizar preguntas y eventos. Añadir propuestas administrables y reglas de elegibilidad con permiso del servidor. Android y web comparten el mismo estado. Depende de que haya actividades y acompañantes reales.

**Validación y riesgos.** Medir primera contribución voluntaria, satisfacción y retorno, junto con abandonos y reportes. Evitar mensajes automáticos, presión por completar tareas y exposición del estado de presencia. La disponibilidad de ayuda se representa con aceptación y vencimiento.

## Gaming. Llegar a una partida compatible

**Problema y situación actual.** Encontrar un perfil compatible puede terminar sin una partida. Los catálogos, rangos, plataformas y horarios ya existen en PlayMatch y otras aplicaciones.

**Propuesta y diferencia a probar.** Un acuerdo de partida con objetivo, duración prevista, compatibilidad confirmada y aceptación de las personas invitadas. Reconfirmar antes del inicio y permitir replanificar cuando alguien no pueda asistir. La hipótesis de valor es aumentar las partidas que llegan a realizarse con expectativas compartidas.

**Uso.** Buscar compañero, proponer una partida con los datos del juego, confirmar y volver al punto acordado. La valoración posterior es voluntaria y permite señalar problemas.

**Viabilidad e implementación.** Cuarta prioridad. Revisar las sesiones y confirmaciones actuales de PlayMatch antes de extenderlas. Se necesitan estados de sesión consistentes, vencimiento y autorización de cada participante. Conservar el acceso adulto que actualmente declara PlayMatch y sus límites de privacidad. Reutilizar disponibilidad y preferencias del catálogo.

**Validación y riesgos.** Medir partidas realizadas y compatibilidad declarada. Ensayar zonas horarias, reconfirmación vencida, cancelación, bloqueos y pocas personas disponibles. Evitar clasificaciones públicas punitivas y promesas de disponibilidad cuando faltan jugadores.

## Coherencia e implementación inicial

El patrón compartido tendrá propósito, alcance, progreso, resultado y referencias al origen. Cada módulo conservará sus permisos y su modelo de interacción. Las referencias deberán volver a comprobar acceso, eliminación y vencimiento. Los resultados privados estarán separados por cuenta. Los estados compartidos se implementarán con confirmación del servidor.

El primer prototipo será la ruta finita de Videos. Se prioriza por apoyarse en búsqueda y reproducción existentes, necesitar pocas consultas y permitir una prueba concreta en el servidor actual. El alcance inicial incluye entrada visible, preparación real, reproducción, pausa y regreso, cierre, errores y aislamiento de cuenta. Se medirán funcionamiento y coste antes de ampliar la propuesta.

La rama experimental tendrá documentación y pruebas propias. La entrega estable 179 conservará su identificación y evidencias. La incorporación posterior a las dos ramas de producción dependerá de validar el recorrido y aceptar el valor del experimento.

## Plan del primer prototipo Android

Se añadirá una pantalla Rutas accesible desde el menú de Videos. Una búsqueda por palabras y una elección entre tres y cinco elementos prepararán una selección finita de los resultados autorizados del catálogo. El orden alternará autores cuando haya suficientes. Se mostrará el tamaño obtenido y se conservará la ruta anterior si falla la preparación de otra.

Cada elemento abrirá el reproductor y las acciones comunes de Videos en un destino acotado a ese video. El regreso devolverá a la selección. La marca «Abierto» acredita que se solicitó abrirlo. El usuario decidirá cuáles le sirvieron y podrá escribir una conclusión privada. Terminar la ruta será voluntario y permitirá revisar sus resultados o crear otra.

Se conservará una ruta por cuenta en almacenamiento privado cifrado del dispositivo. Contendrá referencias, etiquetas breves, aperturas, utilidad elegida y conclusión. El acceso al video se comprobará de nuevo al abrirlo. Se podrá eliminar toda la ruta desde la pantalla. La selección y los cambios locales usarán la guardia de sesión existente. Los errores de lectura o persistencia se mostrarán con acciones de reintento.

El primer prototipo requerirá una búsqueda del catálogo y las lecturas de los videos que la persona abra. Se reutilizarán Media3, la caché multimedia y R2. Se comprobará que el reproductor acotado no solicita páginas de recomendaciones ni introduce contenido fuera de la selección. Los futuros contratos web y sincronización se diseñarán después de medir la utilidad del recorrido móvil.

### Diseño de la pantalla

Se conservarán tipografía y colores de MaterialTheme de Novvin, para respetar el tema de cada cuenta. El acento principal marcará la selección y las acciones. El fondo y la superficie existentes darán continuidad con Videos. El color de error se reservará a acciones fallidas. El texto secundario tendrá el contraste de `onSurfaceVariant`.

La referencia visual del tema Neutro usa texto `#202124`, secundario `#5F6368`, fondo `#FFFFFF`, superficie `#F7F7F8`, fondo oscuro `#0B0B0D` y superficie oscura `#151519`. La implementación obtiene los colores del tema activo y las fuentes de la cuenta. La tipografía de título indica el tema y la de cuerpo conserva legibilidad con el tamaño elegido por el usuario.

```text
Volver       Rutas de video

¿Qué quieres explorar?
[ tema del catálogo              ]
[ 3 videos ] [ 5 videos ]
[ Preparar ruta ]

Tema elegido          2 de 3 abiertos
1  Título y autor
   [ Abrir video ] [ Me sirvió ]
2  Título y autor
   [ Abrir video ] [ Me sirvió ]

Lo que me llevo
[ Mi conclusión                  ]
[ Guardar conclusión ] [ Terminar ruta ]
```

El elemento distintivo será una secuencia visible que termina. Las posiciones numeradas representarán ese orden real. Una sola columna con ancho máximo mantendrá la lectura en teléfonos y pantallas mayores. Las tarjetas tendrán título, autor, acción de reproducción y elección de utilidad. La conclusión será un resultado editable, con guardado explícito.

Se evitarán reproducción automática en la lista, descargas anticipadas y animaciones decorativas. La pantalla tendrá estados de preparación, catálogo vacío, almacenamiento fallido y sesión ausente. Los ensayos incluirán pantalla estrecha, texto ampliado, regreso desde el video y recuperación de la selección guardada.

La revisión del plan mantuvo el tema visual existente y centró la diferencia en la secuencia y el resultado privado. Se dejó una única columna adaptable y acciones que se ajustan al ancho disponible. El reproductor del recorrido tiene su propio destino con `singleVideoOnly`, conserva las acciones comunes y limita el contenido visible a la referencia elegida. Las lecturas de recomendaciones y páginas adicionales quedan desactivadas en ese destino.

## Estado de implementación

El prototipo Android está implementado en la rama experimental. Se accede desde Videos, botón de creación y biblioteca, opción «Rutas con propósito». La búsqueda conserva hasta ochenta candidatos y selecciona tres o cinco videos públicos, con variedad de autores cuando existe. El registro local cifrado contiene una ruta por cuenta. La conclusión se guarda por acción explícita y el regreso avisa de cambios sin guardar. Borrar la ruta exige confirmación.

El [primer recorrido experimental de Acuerdos en mensajes](107-acuerdos-con-origen.md) continúa la segunda prioridad en chats privados entre dos personas. Su documento identifica implementación, comprobaciones y límites pendientes. Social, comunidades, gaming y la adaptación web conservan las prioridades y dependencias descritas. Las comprobaciones de Rutas se registran al final de este documento con sus identidades de código.

## Razones de elección que deben comprobarse

| Apartado | Beneficio concreto que se quiere probar | Evidencia necesaria |
| --- | --- | --- |
| Videos | Encontrar material útil para un tema dentro de una selección cuyo final se conoce y recuperar lo elegido | Resolver la misma tarea con menos esfuerzo declarado y conservar los hallazgos al volver |
| Mensajes | Identificar el acuerdo vigente y las personas que lo aceptaron, incluyendo cambios posteriores | Reconstruir decisiones con menos errores, sin atribuir una aceptación a quien todavía no respondió |
| Social | Encontrar cómo terminó una petición y qué contribuciones ayudaron | Recuperar la conclusión y sus referencias sin reconstruir todas las respuestas |
| Comunidades | Completar una primera contribución que el grupo considere útil | Aumentar las primeras contribuciones aceptadas y reducir el tiempo que lleva encontrar dónde participar |
| Gaming | Llegar a una partida con horario, expectativas y compatibilidad confirmados | Aumentar las partidas realizadas y la satisfacción con los compañeros elegidos |

La experiencia transversal propuesta conserva la intención, las referencias y el resultado cuando la persona decide pasar a otro apartado. Su valor dependerá de que cada recorrido resuelva su propia tarea y de que el cambio de contexto resulte comprensible. Los futuros traspasos entre módulos exigirán una acción explícita del usuario y los permisos del destino.

Los prototipos de Videos y Acuerdos permiten comprobar sus recorridos implementados. La preferencia frente a productos consolidados requiere evaluación con personas y un catálogo suficiente. Las ventajas de Social, comunidades y gaming continúan como propuestas con sus condiciones técnicas y de validación.

## Plan de aceptación de Rutas

Primero se comprueban selección finita, referencias válidas, guardado, errores, cuenta cambiada, regreso y accesibilidad. Después se propone un ensayo de tarea con un grupo pequeño de usuarios, alternando el orden entre el flujo actual y Rutas. Se utilizarán las mismas palabras de búsqueda y catálogo, y una tarea elegida por cada participante.

El ensayo de producto recogerá si encontró algo útil, esfuerzo percibido, facilidad para volver al hallazgo, consumo de datos y preferencia razonada. Se registrarán también rutas abandonadas y consultas sin catálogo. Las conclusiones privadas se conservarán en cada dispositivo. La participación en el ensayo y cualquier envío de métricas deberán ser voluntarios.

La hipótesis se reconsiderará si la selección resulta insuficiente, el esfuerzo de preparar una ruta supera su beneficio o las personas prefieren el flujo actual para esa tarea. El prototipo aún no añade sincronización de conclusiones ni un sistema nuevo de recomendación semántica. Esas ampliaciones requieren justificar el valor de la primera experiencia.

## Comprobaciones del 4 de noviembre

La batería completa del commit `08c9d1a17e49` aprobó **2.716 pruebas JVM en 510 clases**, con cero fallos, errores y omisiones. Las 27 regresiones nuevas cubren selección finita, autores, duplicados, referencias sin URLs de reproducción, restauración, revisiones simultáneas, error de guardado, tiempo de espera, doble pulsación, salida y regreso a una cuenta y respuestas atrasadas. Una ejecución anterior se interrumpió al finalizar el proceso de pruebas. Se repitió con el límite ya utilizado de un proceso, 768 MiB y renovación cada 75 clases.

El primer ensayo de emulador aprobó **28 pruebas de interfaz en Android 15**, incluidas cinco de Rutas y una del almacén de claves de Android. Se comprobó que el XML guardado no contuviera el tema ni la conclusión legibles, su recuperación con una instancia nueva y el borrado separado por cuenta. Se revisaron capturas normales y de 280 puntos de ancho con escala de texto 1,6. Después se corrigió el singular cuando hay un solo video.

La primera lectura conectada de este bloque comparó un fragmento de 4 KiB del mismo objeto por Media3 desde R2 y Ubuntu. Coincidieron los bytes y R2 fue la fuente efectiva. Ambas rutas devolvieron 401 con sesión inválida. El ensayo utilizó acceso de invitado a contenido público.

Se añadieron dos comprobaciones sobre el grafo registrado para verificar el destino acotado, los identificadores codificados, el regreso y los argumentos del reproductor habitual. La primera ejecución señaló una aserción incorrecta del ensayo. `NavBackStackEntry.savedStateHandle` conserva resultados entre destinos. El modelo recibe los argumentos mediante sus extras de creación y `createSavedStateHandle()`. Se contrastó la fuente local de Navigation 2.7.5 y se corrigió la prueba para usar la creación de un modelo con esos extras. [Navegación programática](https://developer.android.com/guide/navigation/use-graph/programmatic), [alcance de ViewModel](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-apis).

La compilación posterior y Lint K1 finalizaron correctamente. Lint informó **cero errores, cero errores fatales y 321 avisos**, con sus reglas vigentes. Los paquetes de emulador son de depuración y conservan la numeración 9.1.39 / 179. El código principal y su identificador incorporado corresponden a `5030b962a5d2`. La prueba corregida de navegación está en `c04cbfc9`. Su paquete de instrumentación se vuelve a compilar fijando el identificador del código principal con la variable existente `NOVVIN_BUILD_COMMIT`. Las fuentes principales se comparan con `5030b962` antes de hacerlo.

La última repetición aprobó **30 pruebas de interfaz y navegación en 51,858 segundos**. Incluyó los dos ensayos corregidos sobre los destinos registrados y las comprobaciones anteriores de cámara, Social, ciclo de reproducción y compartidos. La lectura nativa R2/Ubuntu volvió a aprobar en una ejecución posterior separada. Se conservaron capturas de pantalla y huellas de las fuentes y de ambos APK de depuración. La aserción incorrecta anterior y los ensayos aprobados se conservaron como evidencias distintas.

El AAB firmado estable está identificado en la revisión oficial 104. Este bloque conserva abiertos el ensayo completo con una cuenta autenticada a través de la interfaz, las pruebas físicas, la evaluación de preferencia y la sincronización entre dispositivos. La prueba del grafo, las acciones de la pantalla, el almacenamiento y la lectura conectada tienen evidencias independientes.

## Rediseño visual solicitado el 4 de noviembre

El propietario señaló que la primera pantalla parecía un formulario y carecía de atractivo visual. El nuevo diseño presenta descubrimiento por temas, portadas y progreso. La conclusión se abre mediante una acción dentro del recorrido. La interfaz conserva el tamaño de texto y el tema elegidos en la aplicación.

Se revisaron los ejemplos oficiales de Android. Jetsnack muestra colecciones y filtros dentro del contenido. Jetcaster prioriza las portadas y las categorías. Reply adapta la composición al espacio disponible. Son referencias de organización y jerarquía. El código del rediseño se escribe sobre los componentes y los contratos existentes de Novvin. [Jetsnack](https://github.com/android/compose-samples/tree/main/Jetsnack), [Jetcaster](https://github.com/android/compose-samples/tree/main/Jetcaster), [Reply](https://github.com/android/compose-samples/tree/main/Reply), [adaptación de tamaños](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes).

La paleta combina índigo `#535BC1`, menta `#B7E5DF`, melocotón `#F8D1BB`, azul claro `#D2DFFC` y tinta `#263149`. Las superficies y el texto general proceden del tema activo. El índigo identifica el recorrido y las acciones principales. Los colores suaves distinguen sugerencias de temas. Se utiliza la tipografía configurada por la cuenta, con un título destacado, subtítulos de sección y metadatos secundarios.

```text
Volver              Rutas              Opciones

Tu próximo descubrimiento      [recorrido visual]
Un tema. Algo que te llevas.
[ Buscar un tema                             ]
[ Música       ] [ Cocina                    ]
[ Juegos       ] [ Ciencia                   ]
[ 3 videos ] [ 5 videos ]
                         [ Preparar ruta      ]

Guitarra                         En curso
[ 1 ]────[ 2 ]────[ 3 ]           1 de 3 abiertos
[ Continuar ruta                              ]
[ portada ] Título del video
            Autor          [ Me sirvió ]
[ portada ] Título del siguiente video

Lo que me llevo                [ Escribir ]
                         [ Terminar ruta      ]
```

La revisión del plan sustituyó las cajas de texto permanentes y la fila de botones por contenido visual y acciones de mayor jerarquía. La numeración representa los elementos reales de la selección. Las tarjetas de temas son sugerencias de búsqueda y el catálogo decide qué videos hay disponibles. Las portadas se cargarán con el cliente y la autorización multimedia existentes. Los registros anteriores conservarán su lectura y mostrarán una portada gráfica cuando falte la referencia de imagen.

### Revisión de las primeras capturas

El commit `4cba90bdf3b9` compiló los dos APK de depuración y aprobó 31 pruebas JVM de Rutas, 33 pruebas nativas de interfaz, una lectura pública de portadas, un ensayo de pantalla ancha y la lectura autorizada R2/Ubuntu. Las capturas del catálogo muestran portadas reales en las superficies clara y oscura. El ensayo de portadas utiliza el acceso autorizado de invitado y un estado visual de prueba. No acredita una búsqueda completa con una cuenta autenticada a través de la interfaz.

La inspección visual de 280 puntos con texto a escala 1,6 identificó una mejora que esas 33 pruebas funcionales no detectaban. «Volver a abrir», «Me sirvió» y «Escribir» podían partirse excesivamente. `ec17dbf1` lleva las acciones debajo del contenido cuando necesitan más ancho, separa la acción de la conclusión, apila los indicadores y calcula las columnas de temas y videos considerando el tamaño de texto. Se añadió una comprobación de preparación con tema y cinco videos en esa configuración. El ensayo de portadas cuenta referencias distintas y espera el dibujo del tema antes de capturar la superficie oscura.

La segunda composición, del commit `b873130350b1`, aprobó 31 pruebas JVM, 34 pruebas de interfaz, portadas públicas, pantalla ancha y lectura R2/Ubuntu. Se abrieron sus nuevas capturas estrechas y se comprobó la lectura completa de esas acciones. Las evidencias de cada composición se conservan por separado.

La revisión de los gestos identificó además una interacción ausente en el nuevo diseño. El símbolo de reproducción de la portada necesitaba abrir el video igual que el botón textual. `7d9a5a86` conecta la portada al mismo callback protegido y añade una etiqueta accesible. La prueba comprueba ambos accesos y la desactivación mientras se procesa la apertura. La fuente de los nuevos paquetes se fija a `b5923575e3a1`, que incorpora también las capturas completas de pantalla mediante la [API UiAutomation de Android](https://developer.android.com/reference/android/app/UiAutomation#takeScreenshot()). Se conservan las capturas anteriores de composición como evidencia independiente.

La ejecución conjunta generó ambos APK y terminó las pruebas JVM. El proceso de Lint con 2 GiB de heap avisó de memoria insuficiente y alcanzó un uso superior al 99 %. Se detuvieron únicamente los procesos propios identificados de Gradle y Kotlin. El informe de esa ejecución queda inconcluso. Se repitió únicamente Lint con 4 GiB, manteniendo el código y sus reglas. Finalizó correctamente en 12 minutos y 18 segundos, con cero errores, cero errores fatales y 321 avisos. Estos avisos no se presentan como corregidos.

### Comprobación del rediseño con apertura desde portadas

Los dos paquetes de depuración y sus identificadores de compilación corresponden a `b5923575e3a1`. Incluyen la corrección principal de `7d9a5a86`. Conservan la numeración de depuración 9.1.39 / 179. El AAB firmado estable de la revisión 104 mantiene su código anterior y no incorpora este experimento.

| Comprobación | Resultado de esta fuente |
| --- | --- |
| Modelo, diario y modelo de pantalla | 31 pruebas JVM, 3 clases, cero fallos, errores u omisiones |
| Interfaz y navegación Android 15 | 34 pruebas aprobadas, 59,863 segundos |
| Portadas del catálogo público | 1 prueba aprobada, 7,657 segundos |
| Pantalla ancha de 1920 × 1600 píxeles, densidad 320 | 1 repetición aprobada, 6,074 segundos |
| Lectura Media3 del mismo fragmento R2/Ubuntu y rechazo de sesión inválida | 1 prueba aprobada, 6,554 segundos |
| Lint K1 con 4 GiB | Completado, cero errores, cero errores fatales y 321 avisos |

Se examinaron las nueve capturas de pantalla completa del emulador. Incluyen descubrimiento, selección, catálogo con portadas reales, tema claro, tema oscuro, disposición ancha y un contenedor de 280 puntos con texto a escala 1,6. En este último ensayo, el ancho adicional visible pertenece al contenedor de la prueba. La pantalla completa confirmó el dibujo de los iconos del catálogo en oscuro que faltaban en una captura de composición anterior. El ensayo de gestos comprobó tanto el botón textual como la portada y su desactivación mientras la apertura está pendiente.

Las huellas SHA-256 de los paquetes de esa ejecución son las siguientes.

```text
app-debug.apk              C3BFCC5F35E52A1D573BEBDA4E2B738D681FB810CD2FD8046F1593FD08090B1C
app-debug-androidTest.apk   F65CDEAE88C15B103852B9E3805FA11432BCBADEF20A7E06CDD81BBC9E8FB077
```

Los registros `routes106-native-ui.log`, `routes106-native-public-covers.log`, `routes106-native-wide.log`, `routes106-native-r2.log` y `routes106-build-evidence.json` se conservan en el directorio local de evidencias. Las composiciones anteriores están separadas en `routes106-first-pass-4cba90bd` y `routes106-adaptive-pass-b8731303`. Se conservan sus resultados y sus límites.

Estas comprobaciones cubren el prototipo descrito. Siguen pendientes el recorrido completo de búsqueda con una cuenta autenticada desde la interfaz, la evaluación con personas y la sincronización de rutas entre dispositivos. La revisión móvil integral y los otros cuatro módulos del plan continúan abiertos.

## Siguiente módulo. Preparación del contrato de acuerdos

La lectura inicial de `ChatWorkspaceModels`, `ChatWorkspaceStore`, `ChatWorkspaceViewModel` y `ChatWorkspaceSheet` confirma que las tareas actuales son privadas del dispositivo. La programación de una fecha está conectada desde `ChatScreen`. No contienen aceptaciones compartidas. La preparación del módulo de acuerdos continúa con estas condiciones extraídas de sus consumidores actuales.

- La lectura de una sala pública puede admitir una vista previa. Un acuerdo requiere comprobar además la membresía activa y la identidad canónica de cada participante. Las operaciones de un chat directo deben respetar bloqueos y el estado de solicitud de mensajes. El permiso de leer marcadores no sustituye esos requisitos.
- La referencia al mensaje original se resuelve bajo los permisos de cada lector. El contrato actual de marcadores excluye mensajes borrados y de una sola vista, aplica el historial permitido y acota su fragmento. El acuerdo volverá a comprobar esas condiciones al abrirse y evitará duplicar automáticamente contenido del origen.
- Proponer atribuye la autoría de una propuesta. La aceptación requiere una acción expresa de cada persona. Un administrador tampoco podrá aceptar por otros. Cambiar el texto o los participantes inicia una revisión nueva y deja pendientes las confirmaciones de esa revisión. Pedir cambios, retirarse y cancelar tendrán estados visibles.
- Cada modificación enviará la revisión esperada y una clave de operación ligada a actor, acuerdo y contenido. La API resolverá concurrencia y repetición dentro de una transacción. La pérdida del acuse podrá recuperarse con la misma clave sin generar dos propuestas ni dos decisiones.
- Se conservará el contexto de cuenta y conversación durante las peticiones. Una respuesta antigua no modificará otro chat ni otra sesión. Los avisos de tiempo real transportarán referencias y provocarán una lectura autorizada del estado vigente.
- El almacenamiento cifrado de `ChatWorkspaceStore` protege material local. No demuestra por sí solo cifrado de extremo a extremo de los acuerdos compartidos. El recorrido actual de envío de texto llama a la API Ubuntu con su contenido. No se añadirá procesamiento por IA ni se copiará una conversación completa al nuevo módulo por defecto.

Esa preparación dio paso al [primer prototipo Android y API de Acuerdos](107-acuerdos-con-origen.md). Su alcance inicial es un chat privado de dos personas y conserva pendientes propios antes de promoverlo. Los grupos, las comunidades y la contraparte web continúan pendientes. La implementación experimental y sus ensayos aislados no equivalen a activar el contrato en el servidor de producción.
