export const publicInfoPages = {
  '/privacidad': {
    title: 'Política de privacidad de Novvin: datos y controles',
    description: 'Consulta qué datos trata Novvin para cuentas, mensajes, contenido, seguridad y recomendaciones, y cómo gestionar permisos y eliminar tu cuenta.',
    heading: 'Tu privacidad en Novvin.',
    sections: [
      { heading: 'Cuenta, perfil y contenido', body: 'Novvin trata el correo, las credenciales protegidas y los datos de perfil que facilitas, como nombre, fecha de nacimiento, avatar, biografía e intereses. También procesa mensajes, publicaciones, fotos, videos, audios, documentos, comentarios y reacciones para entregar y sincronizar las funciones que utilizas. Revisa la audiencia antes de publicar o compartir contenido.' },
      { heading: 'Permisos que controlas', body: 'La cámara, el micrófono, los archivos, los contactos y la ubicación se utilizan cuando eliges funciones que los necesitan. El navegador y Android solicitan los permisos correspondientes y permiten retirarlos desde sus ajustes. Compartir pantalla requiere la confirmación del sistema.' },
      { heading: 'Recomendaciones y seguridad', body: 'Los intereses, las interacciones, el contenido visto y las preferencias ayudan a ordenar las recomendaciones. Novvin registra sesiones, eventos técnicos y señales de abuso para prevenir fraude, resolver fallos y moderar contenido. Puedes ajustar las notificaciones, bloquear cuentas y reportar contenido desde la aplicación.' },
      { heading: 'Infraestructura y proveedores', body: 'La operación principal utiliza servidores propios, incluida infraestructura Ubuntu para API, base de datos y multimedia. Se utilizan también proveedores necesarios para notificaciones, conectividad, almacenamiento o seguridad. La política de Novvin establece que no se vende tu información personal.' },
      { heading: 'Conservación y eliminación', body: 'Los datos se conservan mientras la cuenta está activa o durante el tiempo necesario para seguridad, obligaciones legales y resolución de disputas. Puedes actualizar tu perfil y solicitar la eliminación de la cuenta y sus datos desde la aplicación. Solo se conserva lo necesario por obligaciones aplicables, seguridad o prevención de fraude.' },
      { heading: 'Edad y normas de la comunidad', body: 'La edad mínima indicada por Novvin es de 13 años. Debes declarar tu fecha real y cumplir cualquier requisito superior o autorización adicional que corresponda en tu país. No se permite explotación, amenazas, acoso grave, suplantación, fraude, contenido sexual no consentido ni vulneraciones de derechos. Las medidas de moderación pueden incluir retirada de contenido o suspensión de cuentas.' },
      { heading: 'Información y controles adicionales', body: 'Puedes consultar las políticas y normas desde la app, gestionar los permisos en tu dispositivo y revisar las sesiones y los ajustes de privacidad. En una computadora compartida, cierra la sesión al terminar. Para solicitudes de privacidad y soporte, escribe a administracion@novvinapp.com. Nunca envíes tu contraseña ni códigos de recuperación.' },
    ],
  },
  '/cookies': {
    title: 'Cookies y almacenamiento de Novvin Web',
    description: 'Información sobre la cookie de sesión, las preferencias y la caché de Novvin Web, y cómo gestionarlas desde tu navegador.',
    heading: 'Cookies y datos en tu navegador.',
    sections: [
      { heading: 'Mantener tu sesión', body: 'Al iniciar sesión, Novvin Web utiliza una cookie protegida para conservar el acceso y renovar la sesión. Se transmite mediante HTTPS y no está disponible para el JavaScript de la página. Su finalidad es autenticar las funciones que solicitas, no mostrar publicidad.' },
      { heading: 'Preferencias y caché', body: 'El navegador puede guardar preferencias de apariencia, borradores y archivos multimedia en caché para facilitar la conversación y evitar descargas repetidas. Estos datos permanecen en el dispositivo y pueden ocupar espacio aunque cierres la pestaña.' },
      { heading: 'Cómo gestionar estos datos', body: 'Cierra la sesión cuando termines en un dispositivo compartido. Desde los ajustes del navegador puedes bloquear o eliminar cookies y datos del sitio; al hacerlo puedes perder preferencias, borradores o archivos guardados para uso sin conexión y tener que iniciar sesión de nuevo.' },
      { heading: 'Medición y publicidad', body: 'Esta versión de la web no incorpora un rastreador publicitario ni una herramienta opcional de analítica de audiencia. Los registros técnicos del servidor se utilizan para operar el servicio y detectar fallos o abuso. Puedes consultar el tratamiento de datos y los controles disponibles en la política de privacidad.' },
    ],
  },
  '/que-es-novvin': {
    title: 'Qué es Novvin: red social para Android y navegador',
    description: 'Novvin es una red social con mensajes, videos, publicaciones, grupos, comunidades y salas de voz. Conoce su app para Android y el acceso con Novvin Web.',
    heading: 'Qué es Novvin y para qué sirve.',
    sections: [
      { heading: 'Una red social para conversar y compartir', body: 'Novvin reúne mensajes privados, grupos, comunidades, videos y publicaciones sociales en una misma cuenta. Puedes conversar con otras personas, compartir fotos y archivos, seguir creadores y participar en espacios relacionados con tus intereses.' },
      { heading: 'Novvin para Android y Novvin Web', body: 'La aplicación móvil está disponible en Google Play con el nombre Novvin y el identificador conectate.com. Novvin Web es el acceso desde el navegador de una computadora, tablet o teléfono. Usa la misma cuenta; para entrar a la web no necesitas instalar un programa de escritorio.' },
      { heading: 'Mensajes, grupos y comunidades', body: 'Mensajes permite conversar y compartir fotos, stickers, notas de voz y documentos. Los grupos y las comunidades organizan conversaciones entre varias personas; los permisos de cada espacio determinan quién puede publicar y administrar sus participantes.' },
      { heading: 'Videos, Social y Momentos', body: 'Videos reúne contenido en formato de video para descubrir y compartir. Social permite publicar y comentar, y los perfiles muestran la presentación y el contenido disponible de cada persona. Momentos sirve para compartir historias con fotos, videos y música.' },
      { heading: 'Salas de voz y perfiles de jugador', body: 'Las salas de voz permiten conversar con otras personas y compartir cámara o pantalla con los permisos necesarios del dispositivo. Juegos reúne perfiles de jugador e intereses para encontrar personas con las que coordinar partidas.' },
      { heading: 'Cómo empezar', body: 'Abre Crear cuenta si todavía no tienes una cuenta de Novvin. Si ya usas la app, entra a Novvin Web con tus datos de acceso o escanea su código QR desde Perfil, Vincular web, en el teléfono. La entrada como invitado permite explorar contenido público; inicia sesión para participar y conversar.' },
    ],
  },
  '/ayuda/preguntas-frecuentes': {
    title: 'Preguntas frecuentes de Novvin: Android, web, cuenta y mensajes',
    description: 'Respuestas sobre Novvin: dónde descargar la app, cómo entrar en la web, vincular por QR, recuperar la cuenta y compartir mensajes, videos y archivos.',
    heading: 'Preguntas frecuentes sobre Novvin.',
    sections: [
      { heading: '¿Qué es Novvin?', body: 'Novvin es una red social que reúne mensajes privados, grupos, comunidades, videos, publicaciones y salas de voz. Tiene una aplicación para Android y un acceso desde el navegador llamado Novvin Web.' },
      { heading: '¿Dónde puedo descargar Novvin para Android?', body: 'La ficha de Google Play se llama Novvin y su identificador es conectate.com. Puedes abrirla desde el enlace Google Play de esta página o desde Descargar Novvin para comprobar la compatibilidad, disponibilidad y versión publicada.' },
      { heading: '¿Necesito instalar algo para usar Novvin en la computadora?', body: 'Puedes abrir Novvin Web en un navegador actualizado con JavaScript. No necesitas un programa de escritorio: entra con la misma cuenta del teléfono y mantén conexión a internet para sincronizar tu contenido.' },
      { heading: '¿Cómo vinculo Novvin Web con mi teléfono?', body: 'Abre Iniciar sesión con QR en la web. En la app Android actualizada entra a Perfil, Vincular web, y escanea el código de tu computadora. La cuenta elegida se vincula automáticamente. La guía Vincular web con QR explica qué revisar si el código vence o no se reconoce.' },
      { heading: '¿Puedo explorar Novvin sin crear una cuenta?', body: 'La entrada como invitado permite explorar contenido público. Inicia sesión con una cuenta para enviar mensajes, publicar, comentar y participar en las funciones disponibles para tu cuenta.' },
      { heading: '¿Qué puedo enviar en un chat de Novvin Web?', body: 'Puedes compartir texto, fotos, stickers, videos, audios y documentos. En la web también puedes arrastrar una carpeta para prepararla como ZIP. Los archivos y ZIP admiten hasta 500 MiB por envío; las imágenes y los audios tienen un límite de 60 MiB. La pantalla de envío indica si un archivo supera el límite.' },
      { heading: '¿Los grupos, comunidades y salas de voz son lo mismo?', body: 'Los grupos y comunidades organizan mensajes y contenido entre sus participantes, con permisos de publicación y administración. Las salas de voz permiten conversar en tiempo real con micrófono y compartir cámara o pantalla. Para audio y video debes permitir el acceso que solicite el dispositivo.' },
      { heading: '¿Puedo abrir Mensajes mientras veo un directo?', body: 'En Novvin Web actualizado, el directo permanece en un panel flotante cuando abres otra sección. Desde ese panel puedes volver al directo, controlar el audio o salir. Si estás transmitiendo, usa Finalizar directo para terminar la emisión; cambiar de sección no la finaliza. Mantén abierta la pestaña del navegador y la conexión a internet.' },
      { heading: '¿Qué pasa cuando salgo como invitado de un directo?', body: 'Al salir del directo o dejar tu participación como invitado se desactivan tu cámara y micrófono. Si vuelves a entrar, lo haces como espectador. Para participar otra vez debes enviar una nueva solicitud y recibir la aceptación del anfitrión.' },
      { heading: '¿Puedo entrar a una sala de voz durante un directo?', body: 'Novvin indica qué sesión tienes abierta para evitar mezclar el audio de un directo y una sala de voz. Puedes volver a esa sesión o salir de ella antes de unirte a la otra. Si eres anfitrión, finalizar el directo termina la emisión para sus espectadores.' },
      { heading: '¿Cómo recupero el acceso si olvidé mi contraseña?', body: 'En Iniciar sesión, abre la opción de recuperar contraseña y sigue las indicaciones con el correo de tu cuenta. El código recibido sirve para verificar la recuperación; no es tu contraseña. Si solicitas varios códigos, utiliza el más reciente y revisa también la carpeta de correo no deseado.' },
    ],
  },
  '/descargar': {
    title: 'Descargar Novvin para Android y entrar a Novvin Web',
    description: 'Encuentra Novvin en Google Play para Android o entra a Novvin Web desde tu navegador. Usa la misma cuenta para mensajes, videos y comunidades.',
    heading: 'Novvin en tu teléfono y en tu navegador.',
    sections: [
      { heading: 'La aplicación para Android', body: 'El enlace de esta página abre la ficha de Novvin en Google Play. Allí puedes comprobar la compatibilidad con tu teléfono, la disponibilidad y la versión publicada antes de instalar. Las actualizaciones móviles se distribuyen desde esa ficha.' },
      { heading: 'Entra a la web sin descargar un programa', body: 'Desde una computadora, tablet o celular puedes abrir Novvin Web en el navegador. Inicia sesión con tu cuenta o vincúlala con el código QR aprobado desde Novvin móvil. No necesitas instalar un programa de escritorio para usar la web.' },
      { heading: 'Conserva la misma cuenta', body: 'Utiliza el mismo correo y contraseña del móvil. Tus conversaciones y tu perfil pertenecen a esa cuenta. Si compartes la computadora, cierra la sesión al terminar; guardar la dirección en favoritos no guarda tu contraseña.' },
    ],
  },
  '/ayuda/encontrar-novvin': {
    title: 'Cómo encontrar Novvin: red social, app y acceso web',
    description: 'Encuentra Novvin, la red social con mensajes, videos y comunidades. ¿Buscabas Novin, Novyn o Novvyn? Comprueba el nombre y abre el acceso web oficial.',
    heading: 'Vuelve a Novvin desde tu navegador.',
    sections: [
      { heading: 'La dirección de Novvin Web', body: 'Escribe https://novvinapp.com/ en la barra de direcciones para abrir esta web directamente. Después puedes entrar con tu cuenta o explorar videos públicos. Guarda la página en los favoritos de tu navegador para volver sin hacer otra búsqueda.' },
      { heading: 'Busca el nombre Novvin', body: 'El nombre se escribe Novvin: N-O-V-V-I-N. Puedes buscar «Novvin Web», «Novvin red social», «Novvin mensajes» o «Novvin para Android» en Google, Bing, Brave Search o DuckDuckGo. Si llegaste buscando Novin, Novyn, Novvyn, Ovin o Vvvin, comprueba que la página identifique la aplicación como Novvin. Los resultados y su orden pueden variar según el buscador, la región y el momento; comprueba la dirección antes de entrar.' },
      { heading: 'Chrome, Brave, Edge, Opera y DuckDuckGo', body: 'El navegador abre la web; el buscador encuentra páginas. Chrome, Brave, Edge, Opera y el navegador DuckDuckGo pueden utilizar distintos servicios de búsqueda según su configuración. Para entrar sin depender del orden de los resultados, utiliza la dirección completa o tu favorito.' },
      { heading: 'Si escribiste Novvyn con «y» o NoWi', body: 'Si buscabas nuestra aplicación como Novvyn, Noving, Nowing, NoWin, NoWi o Nowi, el nombre de esta red social es Novvin. Una escritura parecida también puede corresponder a otra empresa; utiliza el nombre completo Novvin Web y comprueba que el enlace conduce a esta dirección.' },
      { heading: 'Si llegaste desde X u otra aplicación', body: 'Un enlace compartido puede abrirse dentro de otra aplicación. Si tienes dificultades para vincular la cuenta, descargar un archivo o dar permiso de cámara y micrófono, utiliza la opción de abrir el enlace en tu navegador habitual y vuelve a Novvin desde allí.' },
    ],
  },
  '/mensajes': {
    title: 'Mensajes de Novvin: chats, grupos y archivos en la web',
    description: 'Conversa con tu gente en Novvin Web. Abre tus chats y grupos, comparte fotos y documentos desde la computadora y vincula tu cuenta con QR.',
    heading: 'Tus conversaciones, de tu móvil a tu computadora.',
    sections: [
      { heading: 'Chats, grupos y comunidades', body: 'Entra con la misma cuenta del móvil y encuentra tus conversaciones en la web. La lista de chats y el mensaje abierto se adaptan al espacio disponible en tu pantalla.' },
      { heading: 'Fotos, archivos y stickers', body: 'Adjunta archivos de tu computadora, revisa la selección antes de enviarla y abre las fotos en pantalla completa. Las fotos consecutivas se reúnen en un álbum para mantener ordenado el chat.' },
      { heading: 'Mantén tus conversaciones a mano', body: 'Ancla conversaciones, silencia las que prefieras y consulta tus mensajes desde el móvil o el navegador. En una computadora compartida, cierra la sesión cuando termines.' },
    ],
  },
  '/videos': {
    title: 'Videos de Novvin: descubre y comparte desde tu navegador',
    description: 'Descubre videos en Novvin, sigue a sus creadores y comparte tus favoritos con chats, grupos y comunidades. También puedes publicar desde la web.',
    heading: 'Descubre tu próximo video en Novvin.',
    sections: [
      { heading: 'Un video a la vez', body: 'Explora videos verticales y pasa al siguiente desde tu teléfono, tablet o computadora. El reproductor se ajusta a la pantalla para mantener a la vista el contenido y sus controles.' },
      { heading: 'Comparte con tu gente', body: 'Envía videos de Novvin a tus conversaciones, grupos o comunidades. Tus contactos pueden abrir la publicación desde la tarjeta compartida.' },
      { heading: 'Publica tu propio video', body: 'Elige un video del equipo, añade título y descripción y revisa su portada antes de publicar. Usa una cuenta para crear contenido, comentar y guardar tus preferencias.' },
    ],
  },
  '/salas-de-voz': {
    title: 'Salas de voz de Novvin: conversación y pantalla compartida',
    description: 'Únete a salas de voz de Novvin desde la web o Android. Activa el micrófono, comparte cámara o pantalla y usa el modo cine para ampliar el escenario.',
    heading: 'Encuentra una sala y únete a la conversación.',
    sections: [
      { heading: 'Salas con personas de tu comunidad', body: 'Consulta el tema de una sala y sus participantes antes de unirte. Desde Novvin Web puedes participar con el micrófono de tu computadora o tus auriculares.' },
      { heading: 'Cámara, pantalla y modo cine', body: 'En la web puedes compartir la cámara o una pantalla. Activa el modo cine para ampliar el escenario y ver mejor la transmisión de la sala.' },
      { heading: 'Tú controlas tu audio', body: 'Activa o silencia el micrófono, desactiva el sonido de la sala y sal cuando lo necesites. El navegador te pedirá permiso para utilizar el micrófono.' },
      { heading: 'También desde el móvil', body: 'Usa tu cuenta de Novvin para encontrar salas desde la aplicación y desde el navegador. Para conversar con estabilidad, mantén una conexión a internet y comprueba los permisos de audio.' },
    ],
  },
  '/novvin-web': {
    title: 'Novvin Web: mensajes, videos y momentos desde tu navegador',
    description: 'Usa Novvin desde una computadora, tablet o celular. Conoce los mensajes con archivos, los videos, Social y el acceso con tu cuenta móvil.',
    heading: 'Tu cuenta de Novvin, también en la web.',
    sections: [
      { heading: 'El acceso oficial a Novvin Web', body: 'Entra a tus mensajes y comunidades desde https://novvinapp.com/. Usa tu cuenta de Android para seguir conversando, compartir archivos y descubrir publicaciones desde la computadora.' },
      { heading: 'Conversa desde tu computadora', body: 'Abre tus conversaciones, grupos y comunidades con la misma cuenta que usas en el teléfono. En una pantalla grande puedes ver la lista de mensajes junto al chat. En una pantalla pequeña, cada conversación ocupa su propia vista.' },
      { heading: 'Comparte fotos y archivos', body: 'El botón de adjuntar permite elegir imágenes, videos, audios y documentos de tu equipo. Puedes revisar los archivos y añadir un texto antes de enviarlos. Las imágenes se abren en un visor con ampliación y los documentos compatibles permiten vista previa o descarga.' },
      { heading: 'Descubre videos y publicaciones', body: 'Explora videos verticales, sigue a sus creadores y comparte contenido con tus conversaciones. Social reúne publicaciones, comentarios y momentos. Los perfiles conservan el nombre, la foto, las relaciones y las preferencias de la cuenta.' },
      { heading: 'Entra con contraseña o con QR', body: 'Puedes escribir tus datos de acceso o vincular el navegador escaneando su código desde Perfil, Vincular web, en Novvin Android actualizado. La cuenta elegida queda vinculada automáticamente. Escanea solo el QR de la computadora que estás usando; el código es temporal y puedes solicitar otro si vence.' },
    ],
  },
  '/ayuda/vincular-web': {
    title: 'Cómo entrar a Novvin Web con el QR de tu teléfono',
    description: 'Guía para vincular Novvin Web: abre el código en el navegador y escanéalo desde Perfil en tu móvil para vincular automáticamente tu cuenta.',
    heading: 'Cómo vincular Novvin Web con tu teléfono.',
    sections: [
      { heading: '1. Abre el código en la web', body: 'Entra a Novvin Web y elige «Iniciar sesión con QR». Mantén abierto el código en la pantalla de la computadora o tablet mientras preparas el teléfono.' },
      { heading: '2. Escanea desde Novvin', body: 'En tu teléfono, abre Novvin con la cuenta que quieres usar. Entra a Perfil y toca «Vincular web». Permite el acceso a la cámara si el teléfono lo solicita y apunta al código completo, incluyendo su borde blanco.' },
      { heading: '3. Tu cuenta queda vinculada', body: 'Al escanear desde la app Android actualizada, la cuenta elegida se vincula automáticamente. Verás la confirmación en el teléfono y tus mensajes se abrirán en el navegador. Escanea únicamente el QR de la computadora que estás usando.' },
      { heading: 'Si el código no se reconoce', body: 'Sube un poco el brillo de la pantalla, limpia la cámara y aleja el teléfono hasta que se vea todo el código. Comprueba que la cámara tenga permiso en los ajustes del teléfono. Si el código venció, pulsa «Generar otro QR» en la web y escanea el nuevo.' },
      { heading: 'Si escanea pero no abre la sesión', body: 'Mantén conexión a internet en ambos dispositivos y actualiza la app Android. Si cambiaste de cuenta, elígela antes de escanear. También puedes entrar con contraseña o generar otro QR si el anterior caducó.' },
    ],
  },
  "/social": {
    "title": "Social de Novvin: publicaciones, amigos y comunidad",
    "description": "Comparte publicaciones en Novvin, descubre perfiles y conversa con tu comunidad. Usa Social desde tu móvil o desde Novvin Web.",
    "heading": "Comparte lo que pasa en tu mundo.",
    "sections": [
        {
            "heading": "Publicaciones con tu propia voz",
            "body": "Social reúne publicaciones de las personas de Novvin. Puedes compartir texto y contenido multimedia, abrir una publicación para leer sus comentarios y participar con tu cuenta. Desde el navegador puedes elegir archivos de la computadora y revisar lo que vas a publicar."
        },
        {
            "heading": "Personas, perfiles y amistad",
            "body": "Visita el perfil de una persona para conocer su presentación y sus publicaciones. Las solicitudes de amistad tienen apartados para recibidas y enviadas: así puedes revisar las que te llegan y reconocer cuáles siguen pendientes de respuesta."
        },
        {
            "heading": "Un lugar para descubrir",
            "body": "Además de las publicaciones, en Novvin encontrarás videos, momentos y comunidades. El ranking permite consultar personas y contenido desde la web. Los resultados disponibles dependen del contenido y la actividad de las cuentas; no son reseñas ni recomendaciones comerciales."
        },
        {
            "heading": "Tu cuenta en cada pantalla",
            "body": "Entra con la misma cuenta que utilizas en Novvin móvil. La web se adapta a computadora, tablet y celular. Si solo quieres explorar, usa la entrada como invitado para consultar contenido público; inicia sesión para publicar, comentar y relacionarte con otras personas."
        }
    ]
},
  "/momentos": {
    "title": "Momentos de Novvin: historias con fotos, videos y música",
    "description": "Comparte momentos con fotos, videos y música en Novvin. Descubre cómo publicar una historia desde tu móvil o computadora y controlar su sonido.",
    "heading": "Un momento para compartir lo que estás viviendo.",
    "sections": [
        {
            "heading": "Fotos y videos en tus momentos",
            "body": "Un momento permite compartir una foto o un video con tu gente. Desde Novvin Web puedes elegir el archivo de tu equipo y prepararlo en el editor antes de publicar. Abre Social y entra en Momentos para ver los disponibles y crear el tuyo."
        },
        {
            "heading": "Música para acompañar tu historia",
            "body": "El editor permite añadir música y ajustar la selección que acompaña al momento. Revisa el sonido antes de publicar. Al reproducir una historia, el navegador puede pedir una interacción antes de activar el audio: utiliza el control de sonido del visor."
        },
        {
            "heading": "Un visor adaptado a tu pantalla",
            "body": "Abre el momento para ver su imagen o video completo dentro del espacio disponible. Los controles permiten pausar, reanudar y avanzar entre historias. En computadora y en móvil se conserva el contenido sin estirarlo para llenar la pantalla."
        },
        {
            "heading": "Responde con tu cuenta",
            "body": "Puedes responder a un momento desde el visor cuando la función está disponible para tu cuenta. Comprueba el destinatario y el texto antes de enviarlo. El contenido que ves depende de su disponibilidad y visibilidad; entrar como invitado no da acceso a historias privadas."
        }
    ]
},
  "/comunidades": {
    "title": "Grupos y comunidades de Novvin: conversa con tu gente",
    "description": "Organiza conversaciones en grupos y comunidades de Novvin. Comparte mensajes, fotos, archivos y videos desde la aplicación móvil o la web.",
    "heading": "Un espacio para las personas con las que compartes algo.",
    "sections": [
        {
            "heading": "Crea tu grupo o tu comunidad",
            "body": "Abre Mensajes y elige Grupos o Comunidades para encontrar tus espacios. Desde la web puedes crear uno con nombre y descripción. Una comunidad también tiene opciones de categoría y visibilidad. Revisa quién podrá entrar y qué información quieres compartir antes de crearla."
        },
        {
            "heading": "Conversa y comparte archivos",
            "body": "En los espacios donde tienes permiso para publicar puedes escribir y compartir fotos, videos, audios y documentos. Las comunidades pueden limitar las publicaciones a su creador y ofrecer reacciones y preguntas para sus participantes. El selector permite revisar los archivos antes de enviarlos. Las carpetas arrastradas desde la computadora se preparan como un ZIP para conservar sus archivos y subcarpetas."
        },
        {
            "heading": "Menciones y mensajes compartidos",
            "body": "Escribe arroba y el nombre para buscar participantes y elegir a quién mencionar en la conversación. También puedes compartir un video de Novvin con un grupo o una comunidad desde la sección de videos. La tarjeta compartida permite abrir su publicación."
        },
        {
            "heading": "Participantes e historial",
            "body": "Las opciones de administración determinan quién puede añadir participantes y qué historial se comparte con las personas que entran. Consulta la información del grupo para revisar sus miembros y ajustes. Haber salido de un grupo no concede acceso a mensajes nuevos ni a contenido que no te correspondía ver."
        }
    ]
},
  "/juegos": {
    "title": "Juegos y perfil de jugador en Novvin Web",
    "description": "Organiza tu perfil de jugador en Novvin, elige tus juegos y descubre personas con intereses similares desde el móvil y la web.",
    "heading": "Encuentra personas con las que compartir tus juegos.",
    "sections": [
        {
            "heading": "Tu perfil de jugador",
            "body": "El apartado Juegos de Novvin permite presentar tus intereses como jugador. Añade los juegos del catálogo que utilizas y completa las opciones disponibles para tu perfil, como plataforma, región, idiomas y estilo de juego. Mantén estos datos actualizados para que te representen."
        },
        {
            "heading": "Descubre personas con intereses similares",
            "body": "Consulta los perfiles de jugadores y revisa sus juegos, presentación y preferencias. Las coincidencias ayudan a encontrar personas con las que podrías compartir una partida; no garantizan disponibilidad ni compatibilidad en cada juego. Usa las opciones de conexión y conversación para coordinarte."
        },
        {
            "heading": "Organiza cuándo y cómo jugar",
            "body": "Revisa la disponibilidad y las preferencias de voz de tu perfil. Un idioma, una plataforma o un horario compartido facilitan ponerse de acuerdo. Para conversar, puedes volver a Mensajes o consultar las salas de voz de Novvin con tu misma cuenta."
        },
        {
            "heading": "También desde el navegador",
            "body": "Abre Novvin Web desde una computadora, tablet o móvil y entra con tu cuenta. El catálogo y las opciones dependen del servicio disponible para esa cuenta. Esta sección organiza perfiles y conexiones; no instala juegos de otras plataformas ni sustituye sus propias cuentas."
        }
    ]
},
  "/asistente": {
    "title": "Asistente de Novvin: ayuda para usar la aplicación y la web",
    "description": "Pregunta al asistente de Novvin cómo usar mensajes, perfiles, momentos y acceso web. Conserva la conversación y pide una explicación paso a paso.",
    "heading": "Encuentra ayuda para seguir usando Novvin.",
    "sections": [
        {
            "heading": "Pregunta por una función de Novvin",
            "body": "Abre el asistente desde Mensajes para preguntar cómo vincular la web, cambiar tu perfil, compartir contenido o utilizar otras funciones. Describe qué quieres hacer y en qué pantalla estás. Una pregunta concreta ayuda al servicio a encontrar una explicación relacionada con Novvin."
        },
        {
            "heading": "Continúa la misma conversación",
            "body": "Puedes hacer una pregunta de seguimiento para continuar con el tema anterior. La web conserva un historial local de la conversación por cuenta en ese navegador. Ese historial local no es una copia sincronizada de todos tus chats ni se comparte automáticamente con otras personas."
        },
        {
            "heading": "Respuestas paso a paso",
            "body": "Activa la opción de respuesta paso a paso cuando necesites una explicación más detallada. Si el servicio propone sugerencias, puedes elegir una para continuar. También puedes detener la respuesta, volver a intentar una consulta y copiar el texto que te resulte útil."
        },
        {
            "heading": "Tu borrador sigue a mano",
            "body": "Lo que escribes sin enviar queda guardado localmente para recuperar la pregunta al volver. Puedes borrar la conversación desde su control en el asistente. El servicio requiere conexión para responder; si no está disponible, conserva tu pregunta y vuelve a intentarlo cuando tengas conexión."
        }
    ]
},
  "/ayuda": {
    "title": "Ayuda de Novvin Web: entrar, enviar archivos y usar tu cuenta",
    "description": "Guías de Novvin para iniciar sesión, vincular con QR, enviar archivos, ver videos, compartir momentos y conversar en grupos o salas de voz.",
    "heading": "Encuentra cómo hacer lo que necesitas en Novvin.",
    "sections": [
        {
            "heading": "Entrar desde la computadora",
            "body": "Abre Iniciar sesión para utilizar tu cuenta de Novvin en la web. Puedes entrar con tus datos de acceso o elegir el código QR y escanearlo desde Perfil, Vincular web, en el teléfono. La app actualizada vincula automáticamente la cuenta elegida. La guía explica cómo escanear y qué revisar si el código no se reconoce."
        },
        {
            "heading": "Enviar mensajes y archivos",
            "body": "En Mensajes abre una conversación y utiliza el botón de adjuntar para elegir fotos, videos, audios y documentos. Revisa la selección antes de enviar. Puedes arrastrar una carpeta para prepararla como ZIP; los archivos y ZIP admiten hasta 500 MiB y las imágenes y audios hasta 60 MiB. La pantalla de envío indica si un archivo supera el límite."
        },
        {
            "heading": "Buscar contenido y personas",
            "body": "Videos permite descubrir publicaciones en formato de video; Social reúne publicaciones, momentos y opciones de amistad. Los perfiles muestran la presentación y el contenido disponible de una persona. Inicia sesión para participar; la exploración como invitado se limita al contenido público."
        },
        {
            "heading": "Audio, cámara y conexión",
            "body": "Para conversar en una sala de voz o usar llamadas, permite el acceso al micrófono cuando el navegador lo solicite. Revisa el dispositivo de audio elegido y la salida de sonido. En mensajes, lo enviado sin conexión queda pendiente en ese navegador y se intenta entregar al recuperar conexión."
        }
    ]
},
  '/funciones': {
    title: 'Funciones de Novvin: mensajes, videos y redes sociales en una app',
    description: 'Conoce las funciones de Novvin con imágenes: chats con stickers y archivos, publicaciones, videos, grupos, comunidades y salas de voz para Android y web.',
    heading: 'Conoce las funciones de la red social Novvin.',
    sections: [
      { heading: 'Mensajería para conversar y compartir archivos', body: 'En Mensajes puedes conversar, compartir imágenes, videos, audios, documentos y stickers. La vista previa permite revisar los adjuntos antes de enviarlos. Los grupos y las comunidades añaden permisos para organizar la conversación entre sus integrantes.' },
      { heading: 'Videos y publicaciones sociales', body: 'Videos permite descubrir contenido vertical y seguir a sus creadores. Social reúne publicaciones y comentarios; Momentos permite compartir historias. La visibilidad del contenido depende de la cuenta y de las opciones de cada publicación.' },
      { heading: 'Espacios para reunir a tu gente', body: 'Las salas de voz permiten conversar en tiempo real. Los controles de micrófono, audio, cámara y pantalla dependen de los permisos del dispositivo. Los grupos y las comunidades conservan su propia conversación escrita y sus permisos de administración.' },
      { heading: 'Android y navegador con la misma cuenta', body: 'La aplicación Android y Novvin Web permiten acceder con la misma cuenta. La web se adapta al espacio disponible para mostrar la lista de conversaciones y el chat. La sincronización necesita conexión; el contenido disponible sin internet depende de lo que ya se haya guardado en ese dispositivo.' },
    ],
  },
  '/ayuda/stickers-animados': {
    title: 'Cómo enviar stickers animados en Novvin Web: GIF y WebP',
    description: 'Aprende a pegar o importar stickers en Novvin Web y conservar su movimiento. Qué revisar si WhatsApp para Windows copia un archivo que el navegador no recibe.',
    heading: 'Comparte stickers conservando su movimiento.',
    sections: [
      { heading: 'Pega y comprueba la vista previa', body: 'Abre una conversación en Novvin Web y pega el contenido copiado. Cuando el navegador entrega una imagen, aparece una vista previa para revisarla, quitarla o enviarla. Un original GIF o WebP conserva sus fotogramas; comprueba que se mueva en la vista previa antes de enviarlo.' },
      { heading: 'Si se pega una imagen fija', body: 'Al copiar desde otra aplicación, el navegador puede recibir únicamente una imagen fija. Ese archivo no contiene los fotogramas del sticker original. Usa Importar en el selector de stickers o adjunta el archivo GIF o WebP original para conservar el movimiento.' },
      { heading: 'Si WhatsApp para Windows no entrega ningún archivo', body: 'Algunos stickers se copian como archivos de Windows que el navegador no puede leer al pegar. Novvin avisa cuando el pegado llega vacío. Guarda el sticker original desde la aplicación de origen o arrástralo como archivo hacia el chat; también puedes usar Adjuntar o Importar para seleccionarlo.' },
      { heading: 'Archivos que no tienen extensión', body: 'Si WhatsApp guarda un archivo con un nombre como «WhatsApp Sticker» y sin extensión, Novvin comprueba su formato al importarlo. Un WebP válido conserva su movimiento; no se convierte en una fotografía ni en un ZIP por el nombre del archivo.' },
      { heading: 'Stickers de otras aplicaciones', body: 'Puedes importar imágenes compatibles de otras aplicaciones cuando tengas el archivo. Copiar un enlace a una publicación no copia el sticker; si solo tienes un enlace, compártelo como enlace o utiliza la opción de guardar el archivo que ofrezca su aplicación de origen.' },
    ],
  },
  '/ayuda/privacidad-y-seguridad': {
    title: 'Privacidad y seguridad en Novvin: cuenta, permisos y sesiones',
    description: 'Consulta los controles de privacidad de Novvin: acceso a la cuenta, vinculación web, permisos de cámara y micrófono, contenido público y dispositivos compartidos.',
    heading: 'Cuida tu cuenta y el contenido que compartes.',
    sections: [
      { heading: 'Protege el acceso a tu cuenta', body: 'Usa una contraseña que no compartas con otros servicios. Los códigos de recuperación sirven para verificar el acceso; no los compartas. Revisa la dirección de la web antes de introducir tus datos y cierra la sesión al terminar en una computadora compartida.' },
      { heading: 'Vincula únicamente tu propio navegador', body: 'El QR de acceso vincula la cuenta del teléfono con el navegador que lo muestra. Escanea solo el código de una pantalla que estés utilizando y en la que confíes. Si un código vence, solicita otro desde la página de inicio de sesión.' },
      { heading: 'Revisa cámara, micrófono y ubicación', body: 'El teléfono y el navegador controlan los permisos de cámara, micrófono y ubicación. Puedes revisarlos desde sus ajustes. Antes de participar en una llamada o una sala de voz, comprueba el estado del micrófono y del audio.' },
      { heading: 'Contenido público y conversaciones', body: 'La entrada como invitado permite explorar contenido público. Antes de compartir un archivo, una publicación o un perfil, revisa quién podrá verlo. Los grupos y comunidades tienen permisos propios; comprueba sus participantes y administradores antes de compartir información personal.' },
      { heading: 'Datos guardados en el dispositivo', body: 'La aplicación puede guardar contenido para que lo vuelvas a abrir con más rapidez. Si utilizas un dispositivo compartido, protege su acceso y cierra la sesión cuando termines. El modo sin conexión depende de los archivos que ya estén disponibles en ese dispositivo.' },
    ],
  },
} as const

const demoCaptures = [
  {src:'/images/novvin-chat-android-demo.png',alt:'Captura real de un chat de Novvin para Android con mensajes y emojis entre Level Up y Juancito',caption:'Chat de Novvin para Android. Captura real con cuentas y mensajes de demostración.',width:1080,height:2400},
  {src:'/images/novvin-chat-web-demo.png',alt:'Captura real de Novvin Web con una conversación privada de demostración en una computadora',caption:'Novvin Web en computadora. Captura real de una conversación entre cuentas de demostración.',width:1440,height:900},
  {src:'/images/novvin-perfil-android-demo.png',alt:'Captura real del perfil Level Up en Novvin para Android con avatar y portada personalizados',caption:'Perfil de Novvin para Android con avatar y portada. Cuenta de demostración Level Up.',width:1080,height:2400},
]
export const publicIllustrations: Record<string, typeof demoCaptures> = {
  '/': [demoCaptures[1]],
  '/funciones': [...demoCaptures],
  '/mensajes': [demoCaptures[0],demoCaptures[1]],
  '/momentos': [
    {src:'/images/novvin-momentos-fragmento-android.png',alt:'Selector de música de Novvin con un fragmento de seis segundos marcado',caption:'Elige el tramo de la canción que acompañará tu Momento. Captura de Android con música de prueba.',width:720,height:1612},
    {src:'/images/novvin-momentos-letras-android.png',alt:'Editor de Momentos de Novvin con la letra de una canción sobre fondo turquesa',caption:'Vista previa de las letras antes de compartir un Momento en Android.',width:720,height:1612},
  ],
  '/novvin-web': [demoCaptures[1]],
  '/descargar': [demoCaptures[0],demoCaptures[2]],
}

export function publicInfoPage(path: string) {
  return publicInfoPages[path as keyof typeof publicInfoPages]
}
