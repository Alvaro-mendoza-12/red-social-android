# 158 · Rediseño de Juegos en Android y web

15 de noviembre de 2026. El alcance es **Juegos**, la cuarta pestaña de Novvin. El rediseño usa PlayMatch y la API propia de Ubuntu. La ruta web es `/app/games` y su acceso depende de la capacidad de PlayMatch de la cuenta.

## Recorridos

- **Inicio** muestra juegos populares y abre el catálogo, los jugadores, la partida activa o la actividad.
- **Catálogo** separa Explorar, Mis juegos y Guardados. Explorar busca en el catálogo del servidor y permite continuar con el cursor devuelto. Guardados persiste por cuenta en el navegador o en el dispositivo Android donde se guarda.
- **Jugadores** descubre compañeros por juego, plataforma y disponibilidad. La web también permite filtrar por región. La paginación conserva los filtros que se aplicaron, las búsquedas nuevas desplazan a las anteriores y un error de red ofrece reintento.
- **Partidas** informa qué falta para iniciar la búsqueda y muestra el estado de la sesión. En web, la primera aceptación conserva la sesión mientras se espera al segundo jugador. Omitir la coincidencia reanuda la búsqueda de su propietario.
- **Actividad** agrupa solicitudes recibidas, enviadas, coincidencias y chats de conexiones aceptadas. En Android abrir Inicio ya no marca la bandeja como vista.

## Carga y rendimiento

El descubrimiento de jugadores comienza al abrir Jugadores o elegir un juego. Inicio se habilita con la respuesta de capacidades y recibe por separado el catálogo popular y el perfil. Android utiliza listas con claves por entidad y conserva la posición de sus secciones. En web, volver a Explorar conserva el catálogo abierto y la paginación. Las portadas del catálogo se solicitan de forma diferida.

## Evidencia

- Android compiló en modo debug y pasaron 17 casos JVM de PlayMatch en cinco clases. Los casos cubren modelos, alcance por cuenta, concurrencia de búsqueda y paginación, requisitos de partida y agrupación de la bandeja.
- La compilación web de producción aprobó TypeScript, Vite y las comprobaciones de 21 páginas públicas. El módulo de Juegos de esa compilación ocupa 9,94 kB comprimido con gzip.
- La suite web aprobó 1.279 pruebas en 202 suites, incluidas siete de Juegos que cubren reintentos, guardados, respuestas tardías y confirmación de coincidencias. Las 123 pruebas complementarias de Node también aprobaron.
- Chromium sin interfaz, con respuestas y una cuenta controladas, aprobó a 320, 390, 768 y 1440 px. Confirmó que Inicio aparece con el perfil todavía pendiente y sin una petición de descubrimiento. Validó guardar un juego y recuperarlo después de recargar, aplicar filtros y paginar, iniciar y cancelar una búsqueda, y obtener el enlace al chat tras aceptar una solicitud. Las cuatro vistas pasaron sin desbordamiento horizontal ni excepciones del navegador.
- Los dos archivos de pruebas de contrato de PlayMatch en la API aprobaron. En el dominio público, `/ready`, `/health`, `/app/games` y `/.well-known/assetlinks.json` respondieron HTTP 200. La capacidad PlayMatch pidió autenticación (401). El documento de enlaces identifica `conectate.com` y delega la apertura de las URLs admitidas a Android.

La validación de rendimiento en un dispositivo físico y con una cuenta habilitada en producción continúa pendiente. Las pruebas con datos controlados no miden latencia real de Ubuntu, FPS, consumo en segundo plano ni el tiempo de primer fotograma del feed de videos. Esta entrega no distribuye una APK nueva.
