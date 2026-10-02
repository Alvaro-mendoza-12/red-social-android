# Stickers: estado compacto y recuperación de la cola

Fecha: 2026-10-02. Se conserva Android 117 / 9.0.20.

## Defecto reproducido y corrección

En el emulador se reprodujo la captura del usuario: el texto «Pendiente de envío» quedaba comprimido entre el ancho del sticker y el botón de reintento, ocupando 499 píxeles de alto.

- Un sticker pendiente muestra la hora y el reloj. La cola durable realiza el envío al recuperar conexión.
- Un sticker fallido muestra un botón circular de reintento **centrado sobre la imagen**, con área táctil de 48 dp y descripción accesible. No aparece un párrafo debajo.
- Durante una subida se muestra progreso sobre la imagen; al finalizar la subida, el indicador continúa mientras se procesa. Una confirmación o un fallo reemplaza cualquier progreso antiguo.
- El espacio de imagen permanece estable al cargar. El control general de otros mensajes tampoco comprime texto junto a un botón dentro de una fila estrecha, y no ofrece reintentos manuales durante un envío pendiente.
- La corrección reside en el componente compartido por chats directos, grupos y comunidades. En este bloque se ejecutó el flujo integrado de chat directo; los otros dos siguen pendientes de su auditoría completa.

## Pruebas

**Solo se usó el emulador NovvinMediaTest, Android 15 / API 35.** No se operó el Motorola ni la tablet tras el cambio de instrucciones del usuario.

Pasaron **1.091 pruebas unitarias en 242 suites** y **siete pruebas de interfaz en 16,816 segundos**, sin fallos. Las pruebas visuales comprueban centrado con texto al doble de tamaño, activación única del callback, reloj pendiente, progreso, retirada de controles tras confirmación y ausencia de controles de envío en mensajes recibidos.

La prueba integrada conservó un sticker pendiente al reiniciar el emulador e instalar la APK con `install -r`. Se comprobó el mismo ID en la base local antes y después. El servidor tenía cero copias antes de recuperar conexión; al habilitar internet, el mensaje se envió automáticamente y quedó confirmado localmente. Una consulta de solo lectura confirmó **una única copia de ese ID original** en el servidor y la pertenencia de la cuenta ficticia destinataria a esa conversación.

Se retiró un favorito: desapareció de Favoritos y Recientes en la siguiente captura. Al restaurarlo apareció una sola entrada. Un nuevo envío mantuvo abierto el panel de stickers y quedó confirmado.

La muestra de ese envío nuevo registró **30 ms desde crear el mensaje hasta guardarlo localmente**, y **2.570 ms hasta confirmación del backend**. Se distingue respuesta local de entrega: esta muestra no cumple el objetivo de confirmación inferior a un segundo y requiere seguir midiendo token, solicitud y validación del servidor.

La APK conserva la clase generada necesaria para arrancar. Arrancó en Mensajes con su sesión y datos existentes. Resultados, hash y capturas sintéticas sin datos personales: [evidencia](../evidencias-emulador-stickers-2026-10-02/resultados.json), [reintento con texto grande](../evidencias-emulador-stickers-2026-10-02/reintento-texto-grande.png), [reloj pendiente](../evidencias-emulador-stickers-2026-10-02/pendiente-reloj.png).

La programación existente usa restricciones de red y trabajo persistente, como describe la [documentación oficial de WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work). Android puede diferir trabajo según carga y cuota; no se promete entrega instantánea en todos los estados del dispositivo.

## Orden de la auditoría y pendientes

1. **Arranque y Mensajes, en curso:** envío y reintento de cada tipo de adjunto, respuestas a stickers/video, búsqueda, guardados, programación, identidad y aislamiento de cuentas. Falta medir las fases del envío lento. La revisión encontró además que `SyncWorker` no implementa el aviso de primer plano requerido por trabajo expedito en Android anterior a 12; debe corregirse y validarse.
2. **Grupos:** creación, miembros/permisos, encuestas, cambio/retirada de voto, votantes, adjuntos y sus reintentos.
3. **Comunidades:** roles, canales, archivos, música/eventos y todas sus opciones.
4. **Voz y llamadas:** controles, cancelación, reconexión, pantalla/cámara y tiempos de negociación. Medios por debajo de un segundo siguen pendientes.
5. **Videos y LIVE:** reproducción, audio, búsqueda, edición/publicación, invitaciones, cámara/pantalla y cadencia real. No se certifican 60 FPS por pruebas de emulador.
6. **Social, juegos y perfil:** contenidos, navegación, notificaciones y estados por cuenta. Después corresponde completar la paridad web y despliegue solicitado.

Esta es una validación parcial con evidencia; **no certifica toda la app para producción**, redes físicas diferentes ni un rechazo terminal de red seguido de reintento de principio a fin. El callback y la posición del reintento sí están comprobados por las pruebas de interfaz.

## Reversión

Revertir este commit y recompilar restaura los controles anteriores. No hubo cambios en el servidor ni migraciones de base de datos en este bloque.
