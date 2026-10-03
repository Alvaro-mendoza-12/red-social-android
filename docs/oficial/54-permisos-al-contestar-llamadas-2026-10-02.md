# Permisos al contestar llamadas

Fecha del informe: 2 de octubre de 2026, America/Bogota. Las marcas UTC de las pruebas corresponden al 3 de octubre.

## Problema y corrección

En la invitación que aparece dentro de la app, Aceptar iniciaba la llamada antes de solicitar el micrófono. Android rechazaba el servicio que requiere ese permiso; concederlo después podía dejar la llamada esperando hasta agotar su tiempo de conexión.

La invitación ahora solicita el micrófono antes de aceptar. En videollamadas también solicita la cámara. Mientras se tramita esa acción, los botones impiden aceptaciones repetidas. El resultado del permiso se aplica solamente a la llamada y la cuenta que siguen vigentes. El repositorio también comprueba los permisos antes de iniciar la sesión.

La actividad de llamada entrante abierta desde una notificación ya solicitaba los permisos primero; se conserva ese flujo.

## Comprobaciones

| Comprobación | Resultado |
| --- | --- |
| Pruebas unitarias de llamadas, salas, LIVE, ICE y señalización | 169 pruebas, 45 suites, cero fallos |
| Botones durante aceptación y comportamiento normal | 2 pruebas Compose en Android 35, aprobadas |
| Aceptar sin permiso, concederlo desde el diálogo real de Android | Conexión establecida; sin excepción de permiso del servicio |
| Colgar mientras aparece el permiso y concederlo después | La cuenta receptora permaneció en la bandeja de entrada; la llamada cancelada no se reactivó |
| Colgar una llamada conectada | Resumen de llamada en ambos emuladores; la app permaneció abierta |

Se utilizaron dos emuladores Android 35 y exclusivamente cuentas de QA. Se actualizaron las APK sin borrar datos.

En la llamada iniciada sin permiso, el registro del receptor midió **1.547 ms hasta señalización** y **4.745 ms hasta conexión de medios**, contando desde la aceptación efectiva posterior al permiso. Los contadores RTP recibidos y enviados aumentaron; una muestra recibió 826 paquetes de audio, cero perdidos y jitter de 0,006 s. Los emuladores se ejecutaron sin audio del host: estos datos acreditan transporte de paquetes, no una prueba de escucha física.

## Pendientes y límites

Esta corrección resuelve el orden de permisos y la aceptación tardía de una llamada cancelada. La meta de conexión próxima a un segundo sigue pendiente: no queda cumplida por una muestra de 4,745 segundos. La latencia móvil entre redes diferentes requiere nuevas mediciones físicas. No se cambió la versión base ni el versionCode.

## Referencia oficial

Android exige conceder RECORD_AUDIO antes de iniciar un servicio de tipo microphone y aplica restricciones adicionales cuando la aplicación está en segundo plano. [Tipos de servicios en primer plano — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
