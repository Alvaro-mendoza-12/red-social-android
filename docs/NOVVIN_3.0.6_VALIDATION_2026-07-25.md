# Novvin 3.0.6 — correcciones y validación

Fecha local: 2026-07-25

Rama: `main`
Versión Android: `46 (3.0.6)`

## Resultado

- Se corrigió la moderación administrativa para que verificación, insignias,
  marcos y estado de suspensión se actualicen mediante parches parciales, sin
  que una acción sobrescriba otra con datos antiguos.
- Se corrigió la proyección y la invalidación de caché del servidor. Los campos
  administrativos modificados ya regresan en la respuesta posterior y se
  publica el evento de actualización de perfil.
- El diálogo de eliminación es desplazable y adaptable al teclado y a pantallas
  pequeñas. La eliminación continúa protegida por confirmación y por los
  controles de administrador del servidor.
- Las respuestas de grupos y comunidades conservan su referencia tanto en el
  mensaje optimista como en el ACK. Si el envío falla, el texto y la respuesta
  seleccionada se restauran.
- Las reacciones ahora aceptan mensajes importados, no solamente los creados en
  el fallback local, y validan que el usuario tenga acceso al grupo o comunidad.
- Las notificaciones de la conversación visible se cancelan y FCM no vuelve a
  publicarlas. Al abrir una notificación se conserva el identificador real del
  mensaje y la búsqueda se repite cuando cambia el contenido de la lista.
- Al aparecer el teclado se mantiene visible el final de la conversación cuando
  el usuario ya estaba cerca del último mensaje.
- Las llamadas directas cargan nombre y avatar del interlocutor, actualizan la
  notificación del servicio al activar/desactivar cámara y conservan la pista de
  video nueva durante un reemplazo.
- Los controles de llamada, sala de voz y administración se pueden desplazar o
  reorganizar en pantallas estrechas para evitar botones ocultos.

## Servidor

Se desplegó únicamente el bloque API afectado mediante el flujo con respaldo y
rollback:

- Release: `api-admin-messaging-20260726T020247Z`
- Estado de API y edge: saludables.
- `/ready`: correcto.
- Las rutas administrativas y de reacción contestan `401` sin autenticación,
  confirmando que existen y que mantienen el control de acceso.
- No se eliminó ni modificó ninguna cuenta real durante la verificación.

## Verificación ejecutada

- `npm run test:admin`: aprobado.
- `npm run check`: aprobado.
- `npm run test:data-routes`: aprobado.
- `npm run test:security`: aprobado.
- Android `testDebugUnitTest`: aprobado.
- Android `assembleDebug`: aprobado.
- Android `assembleRelease`: aprobado con R8/minificación.
- `git diff --check`: aprobado.
- APK Release-mode instalado sobre la 3.0.5, conservando datos:
  `app-release-device-test-3.0.6.apk`.
- Firma de la instalación 3.0.6: coincide con la 3.0.5 previamente instalada.
- Dos arranques controlados en moto g04: uno frío en 2,319 ms; proceso vivo,
  actividad principal visible y sin crash ni ANR.

Durante una prueba inicial con `monkey`, el teléfono sufrió presión global de
memoria y registró un ANR mientras Android también eliminaba varios procesos del
sistema. El evento no se reprodujo en los dos arranques controlados posteriores.

## Pendientes de comprobación física

El código de cámara, reemplazo de pista y servicio de llamada quedó corregido,
pero cámara/audio entre dos teléfonos no puede declararse comprobado con un solo
dispositivo conectado. Debe ejecutarse la matriz
`WEBRTC_WIFI_MOBILE_TEST_MATRIX_2026-07-22.md` con ambos teléfonos usando la
misma versión 3.0.6.

La eliminación completa tampoco se probó con una cuenta real. Para la prueba
manual se debe crear una cuenta ficticia, suspenderla y confirmar su eliminación
desde una sesión administradora.
