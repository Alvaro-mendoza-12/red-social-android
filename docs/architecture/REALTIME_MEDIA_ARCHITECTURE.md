# Eventos y transporte de medios

Una conversación privada usa el canal de su chat. En grupos y comunidades, el canal de sala notifica que cambió el historial y la API verifica qué mensajes puede leer esa persona. El gateway comprueba el acceso al recurso correcto. El cliente limita suscripciones simultáneas y recupera el historial al confirmar la suscripción. El contenido de una sala privada se sigue entregando bajo su política de acceso.

En llamadas, la web solicita acceso al dispositivo, prepara la sala y negocia medios con el otro navegador. La comprobación local llega a pistas remotas de ambos extremos. Para redes diferentes, comprobar candidato ICE seleccionado, bytes recibidos y reproducción de audio y vídeo con TURN.

En directos, API y SFU tienen responsabilidades distintas. Verificar la invitación, el token y el registro de presencia precede a verificar el transporte real de los invitados. En «Dibuja y adivina» el canal de eventos tiene una comprobación propia de rol. La palabra que debe dibujarse se recupera por API para el dibujante. [Arquitectura LiveKit](https://docs.livekit.io/reference/internals/livekit-sfu/)

El navegador descarga documentos privados mediante lectura autenticada iniciada por la página. Aplica verificación de identidad, origen y límite de bytes. El almacén local suministró los archivos de las pruebas. La caída del servidor puede afectar al descubrimiento del feed y a la autorización aunque R2 conserve los objetos.
