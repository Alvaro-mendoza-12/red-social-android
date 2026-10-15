# Botella al mar: recepción visible y sin filtros de género

Fecha: 7 de octubre de 2026. Rama: `feature/live-gifts`.

## Hallazgo

La recepción ya estaba implementada, pero su tarjeta se mostraba únicamente cuando existía una botella entrante. Sin mensajes, solo se veía el formulario de envío. Abrir la pestaña registraba al usuario como receptor, sin explicarlo en pantalla.

El ViewModel también cargaba el género del perfil y elegía automáticamente una preferencia de conversación. El servidor comprobaba compatibilidad entre esos campos. Ocultar los selectores por sí solo habría conservado ese filtro.

## Cambios

| Requisito | Comportamiento esperado |
|---|---|
| RF-B01 | La pestaña se llama “Botella al mar” y muestra “Recibir botellas” aunque no haya mensajes. |
| RF-B02 | Cualquier usuario autenticado puede registrarse como receptor sin escribir ni enviar una botella. |
| RF-B03 | La recepción muestra conexión, espera o fallo; una respuesta fallida no se presenta como una bandeja conectada vacía. |
| RF-B04 | Una botella entrante muestra el texto y las acciones “Responder” y “Pasar”. Responder utiliza el endpoint de aceptación existente; pasar mantiene el recorrido hacia otra persona. |
| RF-B05 | Enviar y recibir tienen apartados visibles propios. Recibir una botella no oculta el formulario de envío; tener una botella enviada tampoco oculta la recepción. |
| RF-B06 | Se eliminan los selectores y la lectura del género del perfil en este flujo. Las solicitudes Android de botellas no envían género ni preferencias. |
| RF-B07 | El servidor ignora preferencias de versiones anteriores al registrar receptores y crear botellas. La elección de destinatario tampoco consulta compatibilidad de género en botellas ya existentes. |
| RF-B08 | Se conservan bloqueos, exclusión del remitente, destinatarios previamente intentados, conversaciones existentes, receptor ocupado y límite de chats. |
| RF-B09 | Las acciones de respuesta quedan deshabilitadas durante una operación pendiente. |

## Alcance de recepción

La recepción funciona mientras el usuario mantiene abierto este apartado. El ViewModel renueva la disponibilidad y consulta el estado en un ciclo con una espera de 2.500 ms entre iteraciones; esa espera no es una medición de latencia de entrega. Al salir del apartado o entrar a un chat anónimo, se desactiva la recepción. El servidor utiliza una disponibilidad de 15 segundos y ofertas con plazo de 45 segundos.

Este cambio no incorpora recepción en segundo plano ni notificaciones de botellas. Tampoco elimina datos de género del perfil ni modifica el registro de cuentas.

## Comprobaciones

- Backend: 12 pruebas aprobadas, cero fallos y omisiones. Incluyen recepción sin mensaje, campos antiguos ignorados, contenido recibido sin identidad del remitente y conservación de restricciones de destinatario.
- Android: APK QA compilado y siete pruebas UI aprobadas en emulador Android 35, 37,569 s. Cubren bandeja vacía, respuesta/paso, operación pendiente, error, eliminación de selectores y convivencia de envío y recepción. Las capturas de la pantalla con datos propios se revisaron sin solapamiento de texto ni botones.
- La primera compilación del paquete de pruebas falló por una importación incorrecta del test, que se corrigió. La primera ejecución cerró el proceso antes de iniciar las pruebas y no acreditó ninguna comprobación; no se recuperó un diagnóstico de ese cierre. La repetición utilizó los mismos APK y aprobó siete casos. Se conserva ese intento en la evidencia; no se presenta como prueba aprobada ni como un fallo de la lógica de botellas demostrado.
- No hay dispositivos físicos conectados por ADB en esta comprobación. Los dobles de base de datos de las pruebas de rutas no sustituyen una prueba con PostgreSQL ni una entrega real entre cuentas.
- Los cambios están en el código de la rama. No se declara desplegado el backend ni instalada esta versión en el teléfono.

Evidencia: [botella-al-mar-2026-10-07.json](evidencias/botella-al-mar-2026-10-07.json). SHA-256 del APK QA principal: `4ab62d6e435863d43c1b2f2a1293335a5bb21786254f97fccd6c2bf0776d4ae5`. Las pruebas UI usan datos locales y callbacks; no acreditan una recepción HTTP real entre dos usuarios.

## Archivos

`ProximityScreen.kt`, `ProximityViewModel.kt`, `BottleInboxStatus.kt`, `LocalFallbackApiClient.kt`, `proximity-routes.js`, `bottle-reception.test.js` y `BottleInboxUiTest.kt`.
