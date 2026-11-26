# Juegos de mesa, presentación de perfil y caducidad de Rondas

## Juegos en Android

La tarjeta Juegos de mesa abre el selector de tableros. Jugar con amigos crea una sala privada y permite buscar por nombre entre las amistades disponibles. Al completarse la sala aparece la mesa, con los nombres de sus jugadores, el turno y las reacciones. El acceso al perfil y a Chat y llamadas usa la navegación Android existente. Volver desde esas pantallas retoma la misma sala por identificador y vuelve a comprobar la pertenencia. La voz se inicia con el control de llamadas del chat.

Tres en raya y Cuatro en línea usan las mismas reglas para el tablero local y el servidor. Cada uno admite dos personas en el dispositivo, Novvin o una mesa en línea. El servidor valida jugadas, turno, revisión y pertenencia. La primera actualización de la API extiende la restricción de tipos de la tabla existente. El torneo conserva sus 100 posiciones, podio anterior y cuenta regresiva final en horario de Bogotá.

## Perfil en Android

Compartir permite elegir entre los chats privados existentes, buscar otra persona, copiar el enlace o compartirlo con otra aplicación. La opción de chat abre la conversación con el mensaje preparado para revisión. En Disco, tocar el arte reproduce o pausa el mismo audio y su progreso aparece integrado. El menú de visitas conduce al control de privacidad. Desactivarlo oculta la lista e inicia la limpieza de todo el historial que ya ofrecía Ajustes.

## Rondas

Una vuelta completa dura como máximo 15 minutos. Dispone de 14 minutos y 24 segundos para responder, 30 segundos de votación y 6 de podio. Los plazos se derivan de la ronda que los originó. Una lectura atrasada salta las fases vencidas, incluso con una fecha de votación ausente. La apertura y las mutaciones comparten la protección transaccional del servidor. Si existe otro reto disponible, la nueva ronda evita repetir el que acaba de cerrar.

## Validación y publicación

Android compiló en Release y pasaron 34 pruebas JVM en cuatro clases. También pasaron 16 pruebas del motor y la interfaz de las mesas, y 20 pruebas de API. La integración prepara una ronda de hace dos días con participación y comprueba que se abre otro reto. También prueba el esquema anterior de Juegos y completa partidas privadas de los dos juegos nuevos.

En un navegador local de 480 píxeles se jugó Tres en raya contra Novvin y se completó Cuatro en línea con dos jugadores locales. La vista de Tres en raya cabe en el ancho disponible.

El intento de instrumentación Release encontró pruebas de medios que necesitan el punto de entrada de la variante de auditoría. El ensayo en teléfonos físicos y la nueva entrega firmada siguen pendientes. La revisión móvil integral permanece abierta.

Ubuntu ejecuta `novvin-api:mesas-rondas-20261126T043954Z`. La actualización afectó únicamente a la API. La primera imagen se revirtió porque los permisos de directorio bloqueaban la carga de Juegos. La imagen final se verificó con el usuario real del proceso, restauró `/ready` con todas las capacidades previas y confirmó las rutas autenticadas y la restricción de los cinco juegos en PostgreSQL. El respaldo de las tablas de Rondas y Juegos se conserva en el directorio privado de la entrega en Ubuntu.
