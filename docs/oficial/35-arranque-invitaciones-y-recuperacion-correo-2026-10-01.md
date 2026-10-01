# LIVE: invitaciones inmediatas y recuperación por correo

Fecha: 2026-10-01. Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Versión mantenida: `versionCode 117`, `versionName 9.0.20`.

## Alcance de esta entrega

Esta revisión continúa el cierre móvil descrito en los documentos
[33](33-fluidez-y-auditoria-modulos-2026-09-30.md) y
[34](34-cadencia-live-salas-2026-10-01.md). Corrige la actualización de invitaciones de LIVE,
el aislamiento de eventos al cambiar de cuenta y una lectura de perfil que bloqueaba el
arranque del espectador. Comprueba además la recepción real del correo de recuperación.
Estos resultados no certifican todas las pantallas, la cadencia de PK ni la aplicación web.

## Invitaciones y reconexión

### Hallazgo

Android consultaba las invitaciones cada cuatro segundos. El backend publicaba sus cambios
en un canal de colaboración al que Android no estaba suscrito y que el gateway no permite
suscribir. Permitir ese canal indiscriminadamente habría expuesto invitaciones privadas a
otros espectadores.

### Cambios

- El backend entrega la invitación completa únicamente a los canales personales del anfitrión
  y del invitado. El aviso del canal de sala no contiene la invitación ni su nonce.
- Android observa el canal personal dentro del trabajo de colaboración. El canal compartido
  con notificaciones continúa disponible cuando el usuario sale del LIVE.
- La confirmación de suscripción dispara una lectura inmediata: recupera cambios ocurridos
  antes de conectar o durante una desconexión. El colector se registra antes de suscribir.
- Las respuestas HTTP y los eventos se combinan por fecha del servidor. Una lectura antigua
  no puede convertir una aceptación nueva en pendiente ni borrar una invitación recién recibida.
- Los eventos se filtran por cuenta y LIVE. La generación del socket invalida callbacks de
  conexiones anteriores; el cierre de un socket antiguo no puede retirar una conexión nueva.
- También se filtran los eventos de borrado de cuenta antes de notificarlos a la sesión activa.
- Se conserva el sondeo como reconciliación ante fallos del canal de eventos.

### Espectador

La consulta remota del LIVE sigue siendo la comprobación de acceso. Para presentar el nombre
y avatar del espectador se usa el perfil propio en caché; su actualización remota deja de
bloquear el inicio de los medios. Sin caché se presenta «Usuario». La cancelación o cambio
de cuenta impide continuar con una conexión obsoleta.

### Mediciones comprobadas

| Prueba | Resultado | Qué demuestra |
|---|---|---|
| Backend: solicitud pendiente hacia anfitrión/invitado | 32,55 / 32,88 ms | Eventos recibidos por ambos clientes PC |
| Backend: aceptación hacia anfitrión/invitado | 33,33 / 34,36 ms | Actualización por push, sin sondeo |
| Tercera cuenta | No recibió la invitación | Aislamiento del destinatario |
| Suscripción privada de sala por tercero | Rechazada | Se mantiene la autorización del gateway |
| Primera comprobación Android, emulador | Aceptación en 270 ms | Cliente Android real, sin esperar cuatro segundos |
| Comprobación final Android, emulador | Aceptación en 311 ms | Push y preservación de aceptación ante lectura antigua |
| Socket frío y solicitud anterior a la conexión | 1.393 ms, una sola solicitud | Reconciliación al suscribir; sin temporizador de sondeo |

La primera prueba Android repitió la misma solicitud dos veces durante el arranque del socket.
Motivó la reconciliación al confirmar la suscripción. La comprobación final usó una sola
solicitud enviada antes de abrir el socket, además de medir la aceptación posterior. El socket
frío superó un segundo en esta ejecución; no se presenta como un resultado inferior a ese límite.

Estas medidas son tiempos de actualización de invitaciones. No son tiempos de primer cuadro
ni latencia de voz, y no sustituyen las mediciones físicas del documento 34.

## Recuperación por correo recibida realmente

Se usó un alias nuevo y exclusivo del buzón de servicio autorizado. La contraseña de aplicación
SMTP permaneció en memoria en el servidor. La auditoría sólo buscó mensajes dirigidos a ese
alias, con remitente y asunto esperados; abrió el buzón en modo lectura y utilizó `BODY.PEEK`.
No leyó mensajes de otras personas ni almacenó contraseñas, códigos o tokens en las evidencias.

Resultado del ciclo real del servidor:

- Código de **seis dígitos** recibido en el buzón, aproximadamente **13.635 ms** tras solicitarlo.
- Código incorrecto rechazado y código correcto convertido en autorización de cambio.
- Reutilización del código y de la autorización de cambio rechazadas.
- Contraseña nueva aceptada para iniciar sesión; contraseña anterior rechazada.
- Sesión anterior revocada.
- Cambio de contraseña desde una sesión válida y nuevo inicio de sesión comprobados.
- Cuenta temporal borrada y su token rechazado con HTTP 401.

Una primera ejecución del auditor omitió `codeFormat: numeric`, por lo que solicitó el formato
legado y no pudo extraer seis dígitos. Se corrigió la prueba para exigir `delivery: email` y
`codeLength: 6` antes de esperar el mensaje. Ese fallo del auditor no demuestra un fallo de
entrega de la aplicación. Cinco tests locales verifican que el parser sólo acepta el destinatario,
remitente, asunto y longitud esperados, conserva ceros iniciales e ignora adjuntos.

El ciclo anterior ejecuta los endpoints reales en el servidor y la entrega SMTP/Gmail. No prueba
por sí solo salir de Android a Gmail, volver, pegar el código y entrar a la app en un teléfono.
Las pantallas y la persistencia cifrada se comprueban por separado mediante instrumentación.
La app no lee la bandeja de Gmail del usuario: éste copia o pega el código recibido.

Las cinco pruebas de componentes/persistencia de recuperación pasaron en Android, junto con
la prueba de invitaciones (seis en total, 18,873 s): pasos de código/contraseña separados y en
español, campos y botones sin solaparse, rechazo visual de contraseñas diferentes, controles
deshabilitados durante la carga, progreso disponible al recrear el almacén y descarte de
progreso vencido. La preferencia temporal estaba aislada y se borró después. Se comprobó que
el XML no contiene en texto plano el correo ni la autorización sintética. Se inspeccionaron
ambas capturas del componente; la de contraseña muestra el estado tras desplazar la vista
hacia su botón. Estas pruebas no ejecutan el ViewModel completo ni un cierre forzado del proceso.

Auditor reproducible: copiar el script al servidor y ejecutarlo allí, con el buzón propio autorizado.
Los tests del parser no necesitan servidor ni conexión al correo:

```sh
python3 api/scripts/verify-password-reset-mailbox.py --owned-mailbox BUZON_PROPIO
python3 api/scripts/test_verify_password_reset_mailbox.py
```

El envío utiliza SMTP con TLS. La documentación primaria de Google describe
[IMAP/SMTP](https://developers.google.com/workspace/gmail/imap/imap-smtp) y
[contraseñas de aplicación](https://support.google.com/mail/answer/185833?hl=en).
IMAP se usa en este auditor; no es una dependencia añadida al aplicativo Android.

Se borraron las tres cuentas temporales de la tanda LIVE, comprobando sus IDs y correos antes
de borrar la primera. Sus tokens respondieron HTTP 401. Se retiraron los fixtures privados del
PC y del emulador; no se cambió la cuenta del propietario. La cuenta del auditor de correo
también fue eliminada según su comprobación independiente.

## Servidor y reversión

Antes de desplegar se verificó el respaldo completo
`/home/alvaro/conectate-backups/20261001T114943Z`: PostgreSQL, storage y AI, hashes y lectura de
los artefactos correctos. Se guardaron las fuentes previas en
`/home/alvaro/conectate-source-backups/live-invitation-push-20261001` y la imagen anterior como
`novvin-api-before-live-invitation-push:20261001`.

Se desplegaron el publicador de eventos, su importación en LIVE y el comando de tests.
La API respondió preparada y los nueve servicios estaban saludables. Los **94 archivos JS**
del directorio `api/src` desplegado coincidían con la fuente local. Esta comprobación excluye
configuración, esquema y otros contenedores. La metadata general del stack continúa indicando
el despliegue del 28/09; no se usa como prueba de un despliegue completo de esta revisión.

Para revertir el backend, restaurar las fuentes anteriores indicadas, recuperar la etiqueta de
imagen anterior y recrear únicamente el servicio API bajo el bloqueo de Compose del runbook.
No se modificó el esquema. No restaurar la base completa por un cambio de código. Revertir
Android mediante el commit de esta entrega y repetir su compilación y pruebas.

## Pendientes antes de cerrar móvil y pasar a la web

1. Estabilidad de PK prolongada y con espectadores simultáneos: persiste la ejecución histórica
   que cayó a 8 FPS aunque las siguientes recuperaron aproximadamente 30.
2. Arranque de medios: los primeros cuadros físicos siguen alrededor de 3–5 segundos; esta
   mejora de invitaciones no certifica un arranque completo inferior a un segundo.
3. Audio escuchado por personas, Bluetooth, cambios de red, temperatura y más dispositivos.
4. Recuperación física con salida/retorno desde Gmail y llamada entrante real con app cerrada
   y teléfono bloqueado. El Motorola no estuvo disponible en ADB en esta tanda.
5. Validación de release con la firma original mediante el proceso manual del propietario.
6. Después: matriz por opción, pruebas visuales/funcionales de la web y despliegue Vercel.

El Motorola probado anuncia como máximo 30 FPS en esta captura. Los 60 FPS solicitados siguen
sin cumplirse en ese equipo; elevar la petición del software no crea un formato del hardware.

Las evidencias públicas de esta entrega se conservan en
`../evidencias-live-arranque-2026-10-01/`, con resultados del servidor, Android, correo,
proveniencia de API, limpieza y capturas de recuperación.

## Compilación y calidad final

La APK debug y su APK de instrumentación compilaron con la fuente final. Pasaron **1.026 tests
unitarios Android** en 228 clases, sin errores, fallos u omisiones; **seis tests nativos** y
**cinco tests del parser de correo**. La suite API ejecutó 333 tests: 331 pasaron, cero fallaron
y dos se omitieron en Windows porque requieren fixtures Bash/Linux. Esas omisiones no se
presentan como nuevas pruebas de integración Linux superadas.

Lint final terminó con cero errores; el número de advertencias y el SHA-256 de la APK están en
[quality.json](../evidencias-live-arranque-2026-10-01/quality.json). La versión no se incrementó.
Esta APK corresponde al árbol de trabajo validado antes del commit de entrega; no se atribuye
el nuevo commit a la metadata anterior del stack del servidor.
