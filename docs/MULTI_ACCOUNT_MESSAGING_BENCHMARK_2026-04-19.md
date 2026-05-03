# Benchmark de mensajeria y multi-cuenta 2026-04-19

## Objetivo

Tomar el problema real reportado en Conectate:

- notificaciones que llegan a una cuenta distinta de la activa;
- apertura de la app sin contexto de cuenta;
- posibilidad de terminar en pantallas vacias o chats ambiguos;
- poca visibilidad del estado multi-cuenta dentro del hub de mensajes;

y compararlo contra patrones fuertes de producto ya probados en apps grandes.

## Hallazgos principales del benchmark

### 1. Multi-cuenta visible, no invisible

Patron observado:

- WhatsApp formalizo el soporte multi-cuenta y lo presenta como una forma de evitar escribir desde la cuenta equivocada.
- En marzo de 2026 Meta reforzo esto mostrando la foto de perfil en la barra inferior para que siempre sepas en que cuenta estas.
- Discord documenta explicitamente su account switcher y lo trata como una funcion central para usuarios con varias identidades.

Implicacion para Conectate:

- no basta con soportar cuentas guardadas;
- la cuenta activa debe verse en el hub de mensajes;
- cuando una notificacion llega a otra cuenta, la app debe explicarlo antes de navegar.

Fuentes:

- WhatsApp, "Multiple Accounts Coming to WhatsApp" (2023-10-19): https://about.fb.com/news/2023/10/multiple-accounts-on-whatsapp/
- WhatsApp, "WhatsApp Adds New Features to Simplify Storage, Switch Accounts, and More" (2026-03-26): https://about.fb.com/news/2026/03/whatsapp-new-features-simplify-storage-switch-accounts/
- Discord, "Discord Account Switcher FAQ" (actualizado 2024-06-29): https://support.discord.com/hc/en-us/articles/4412732047127-Discord-Account-Switcher-FAQ

### 2. El inbox central reduce friccion

Patron observado:

- Discord usa Inbox como centro de mensajes no leidos y menciones.
- El propio feedback de Discord pide consultar notificaciones sin abrir toda la app, lo que confirma que los usuarios valoran una bandeja resumida y accionable.
- WhatsApp tambien empujo filtros de chat para llegar mas rapido a lo pendiente.

Implicacion para Conectate:

- el icono multi-cuenta del hub de mensajes no debe ser solo un badge;
- debe resumir cuentas, pendientes y destino probable;
- el usuario debe poder saltar al pendiente principal con un solo gesto.

Fuentes:

- Discord, "Inbox FAQ" (actualizado 2025-09-12): https://support.discord.com/hc/en-us/articles/360045027712-Inbox-FAQ
- Discord feedback, "Clicking the system tray icon should open the inbox in a popup" (2021-10-07): https://support.discord.com/hc/en-us/community/posts/4410221123351-Clicking-the-system-tray-icon-should-open-the-inbox-in-a-popup
- WhatsApp, "Find Messages Faster with WhatsApp Chat Filters" (2024-04-16): https://about.fb.com/news/2024/04/whatsapp-chat-filters/

### 3. Los usuarios odian los badges fantasma y el contexto incompleto

Patron observado:

- En Reddit hay quejas claras de Instagram sobre recibir solo un punto rojo en otra cuenta sin detalle suficiente para actuar.
- En Reddit hay tambien reportes de Discord donde los badges dejan de reflejar bien el estado al cambiar de cuenta.

Implicacion para Conectate:

- el badge por si solo no sirve;
- hay que guardar el ultimo destino pendiente por cuenta;
- al tocar una notificacion cruzada no se debe caer en una ruta ambigua ni abrir una pantalla en blanco.

Fuentes:

- Reddit, Instagram: https://www.reddit.com/r/Instagram/comments/17u9144/problem_with_notifications_while_on_two_accounts/
- Reddit, Discord: https://www.reddit.com/r/discordapp/comments/1162eog/unread_badge_message_not_working_only_for_one/

### 4. Las apps grandes mezclan chat privado, comunidad y descubrimiento

Patron observado:

- X Communities separa el espacio de comunidad con reglas y moderacion propias.
- Snapchat Topic Chats lleva conversaciones publicas a accesos rapidos, busqueda, Stories y video.
- TikTok conecta feed, Stories y mensajes directos para compartir contenido hacia conversaciones.
- Telegram usa programacion de mensajes como una capacidad de mensajeria madura, no como un extra raro.

Implicacion para Conectate:

- el modelo multi-cuenta debe soportar no solo chat directo, sino tambien grupos y comunidades;
- los destinos pendientes deben guardar tipo de conversacion y no solo un contador;
- la navegacion debe ser resistente y explicita.

Fuentes:

- X Help, "Communities on X": https://help.x.com/en/using-x/communities
- Snapchat Newsroom, "Introducing Topic Chats" (2025-11-17): https://newsroom.snap.com/snapchat-topic-chats
- TikTok Newsroom, "TikTok launches 'Share to TikTok' feature" (2024-11-07): https://newsroom.tiktok.com/tiktok-launches-share-to-tiktok?lang=en
- Telegram Blog, "Scheduled Messages, Reminders, Custom Cloud Themes and More Privacy": https://translated.telegram.org/blog/scheduled-reminders-themes

### 5. El inbox moderno ayuda a priorizar antes de abrir cada chat

Patron observado:

- WhatsApp formalizo filtros simples y rapidos arriba del inbox para llegar primero a lo no leido.
- Instagram llevo Notes a la parte superior del inbox para convertir Mensajes en un espacio mas vivo y no solo una lista plana.
- Messenger mantiene el cambio de cuentas como un gesto de primer nivel dentro de la experiencia de mensajeria.

Implicacion para Conectate:

- la pestaña de chats necesita una capa de foco rapido, no solo carpetas y busqueda;
- el usuario debe poder aislar no leidos y fijados sin entrar a ajustes ni desplazarse demasiado;
- la priorizacion debe convivir con multi-cuenta, no competir contra ella.

Fuentes:

- WhatsApp, "Find Messages Faster with WhatsApp Chat Filters" (2024-04-16): https://about.fb.com/news/2024/04/whatsapp-chat-filters/
- Instagram, "New Sharing Features on Instagram: Notes, Group Profiles and More" (2022-12-13): https://about.fb.com/news/2022/12/sharing-features-on-instagram-notes-group-profiles-and-more/
- Messenger Help Center, "Add or switch accounts on Messenger": https://www.facebook.com/help/messenger-app/1033919196669390/

## Debilidad detectada en Conectate antes de esta mejora

- La notificacion podia abrir una ruta de chat sin asegurar el contexto de cuenta correcto.
- En chat directo la ruta podia depender solo de `userId` o terminar cayendo en `chatId` como si fuera usuario, lo que favorecia estados placeholder.
- El centro multi-cuenta contaba pendientes, pero no conservaba el ultimo destino real para abrirlo despues del cambio.
- Faltaba una capa de explicacion al usuario cuando el contenido pertenecia a otra cuenta guardada o no guardada.

## Cambios aplicados en esta ronda

### 1. Puente de cuenta para notificaciones cruzadas

Se agrego una pantalla puente que ahora explica:

- en que cuenta estas;
- a que cuenta llego el contenido;
- que contenido se va a abrir;
- si existe o no una sesion guardada para continuar.

Resultado:

- se evita la navegacion muda;
- se elimina el salto "abre y no pasa nada";
- el usuario puede continuar con la cuenta correcta o quedarse en la actual con total claridad.

### 2. Hub multi-cuenta con destino pendiente real

Ahora el sistema guarda por cuenta:

- contador de conversaciones pendientes;
- ultimo destino pendiente;
- tipo de conversacion (`chat`, `group`, `community`);
- preview y foco de mensaje cuando existe.

Resultado:

- el centro multi-cuenta deja de ser solo informativo;
- al cambiar de cuenta puede abrir directamente el pendiente mas reciente.

### 3. Navegacion directa robusta

La ruta de chat directo ahora puede cargar:

- `userId`;
- `chatId`;
- `focusMessageId`.

Resultado:

- la app ya no depende de adivinar si el identificador recibido era un usuario o un chat;
- baja mucho el riesgo de pantalla vacia al abrir un mensaje desde push.

### 4. Banner visible dentro del hub de mensajes

Cuando otra cuenta tiene actividad pendiente, el usuario ahora ve un banner accionable dentro de Mensajes con:

- cuenta activa;
- cuenta con pendientes;
- preview del ultimo pendiente;
- accion directa para abrirlo o ver todas las cuentas.

### 5. Bandeja rapida para priorizar mensajes directos

Ahora la pestaña privada suma una capa nueva de foco rapido con:

- filtros `Todas`, `No leidos` y `Fijados`;
- conteos visibles por filtro dentro del contexto actual;
- compatibilidad con carpetas y busqueda sin perder el estado;
- estados vacios mas claros cuando la bandeja o la carpeta no tienen coincidencias.

Resultado:

- la pestaña de chats deja de sentirse como una lista plana;
- el usuario puede revisar primero lo urgente o lo importante con un toque;
- la logica queda separada y testeable para seguir ampliando el inbox sin degradarlo.

## Criterio de producto que queda establecido

Para cualquier mejora futura de Conectate en mensajeria y multi-cuenta:

- siempre mostrar contexto de cuenta;
- nunca navegar a ciegas cuando la cuenta destino no coincide;
- preferir resumen accionable sobre badge aislado;
- persistir destino pendiente, no solo cantidad;
- usar rutas explicitas y resistentes para evitar estados vacios.

## Siguiente backlog recomendado

1. Extender el conteo remoto por cuenta a grupos y comunidades, no solo chats directos.
2. Agregar filtros por cuenta y por tipo de pendiente dentro del hub multi-cuenta.
3. Permitir historial de pendientes por cuenta, no solo el mas reciente.
4. Añadir instrumented tests ejecutables en CI para el flujo completo: push -> puente de cuenta -> switch -> apertura del destino.
5. Llevar el mismo patron de contexto multi-cuenta a llamadas, notificaciones sociales y moderacion.
6. Extender la bandeja rapida a filtros configurables por prioridad, silenciados y mensajes respondidos.
