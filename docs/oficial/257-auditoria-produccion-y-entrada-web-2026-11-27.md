# Novvin · auditoría de producción y entrada web

27 de noviembre de 2026 · La certificación integral sigue abierta. La APK firmada 9.2.1 (189), la entrada pública de Firebase y la API estable tienen verificaciones reproducibles. Todavía faltan el cambio DNS, la conciliación del despliegue Ubuntu y recorridos de voz, notificaciones y administración con usuarios de prueba en dispositivos físicos.

## Android

- La APK 189 y su AAB coinciden con las huellas de la entrega 256. En un emulador Android 16 se repitieron el arranque sin conexión, la entrada como invitado, Juegos, Perfil y el regreso al inicio de sesión. El búfer de fallos quedó vacío.
- El código de la entrega 189 aprobó **3.608 pruebas JVM en 602 clases**, sin omisiones. El ensayo de Google Play con una actualización ofrecida a la cuenta sigue pendiente. [Entrega y procedimiento 256](256-actualizacion-google-play-2026-11-26.md).

## Web y dominio

GoDaddy reenvía la portada de `novvinapp.com` al ingreso de Ubuntu. Las peticiones a `/login`, `/videos`, `/privacidad`, `/eliminar-cuenta` y `/.well-known/assetlinks.json` devuelven 404 en el dominio de marca. Vercel devuelve 402 con el despliegue desactivado.

El sitio independiente de Firebase Hosting sirve 22 documentos públicos y 10 imágenes de esos documentos. Los 43 alias HTML o con barra final redirigen de forma permanente a sus rutas canónicas. Inicio de sesión, recuperación, rutas de la app y compartidos reenvían a Ubuntu conservando los parámetros. Las hojas de estilo con huella tienen caché inmutable y los documentos se revalidan. La asociación de Android responde con el paquete esperado. Una ruta inexistente devuelve 404 con una página de Novvin. Se verificaron estos controles tanto en una vista previa como en el canal activo. La vista previa recibe la cabecera de exclusión de búsqueda de Firebase. [Entrada pública probada](https://novvin-entrada-web.web.app/) · [Configuración de Firebase Hosting](https://firebase.google.com/docs/hosting/full-config).

El propietario autorizó registrar los dos dominios y mover el tráfico. Firebase aún informa que falta verificar titularidad. Añadir primero sus TXT, comprobar el certificado emitido y conservar los registros de correo antes de sustituir el reenvío por el A y el CNAME solicitados por Firebase. Para revertir, volver a los dos reenvíos y valores registrados antes del cambio. El canal activo dispone del artefacto previo como versión de retorno. El archivo de trabajo `tmp/validacion257/dns-firebase.json` contiene los valores emitidos y se debe cotejar con Firebase en el momento del cambio. El control de Brave terminó al pulsar Esc antes de editar GoDaddy. [Dominio personalizado y certificado previo al cambio](https://firebase.google.com/docs/hosting/custom-domain).

- Compilación local de producción y 1.360 pruebas Jest más 171 pruebas Node. Chromium recorrió diez pantallas en 320, 390, 768, 1.024 y 1.440 px con datos sintéticos. Las comprobaciones de interacción confirmaron el compositor condicionado al estado del chat, el mensaje de sala malformada y el tablero favorito tras recargar.
- La respuesta de asociación contiene la huella del certificado de la APK firmada. La comprobación de Android App Links con una instalación distribuida por Play queda abierta porque su certificado de firma puede ser distinto al de carga.
- La vista pública debe validarse con perfiles públicos y privados sintéticos, videos reproducibles y rastreadores reales después del cambio de dominio. El control de rutas llega hasta el destino con la consulta intacta.

## Ubuntu, dependencias y datos

- SSH por la red privada confirmó ocho CPU lógicas, 9.780 MiB de memoria disponible aproximadamente y disco al 53 %. La API, el gateway, PostgreSQL, Redis y señalización estaban operativos. Ubuntu anuncia actualizaciones y reinicio pendiente. La API, realtime y señalización ejecutan Node 22.23.4. [Calendario de soporte de Node.js](https://nodejs.org/en/about/previous-releases).
- Los cinco archivos npm de bloqueo del árbol estable devolvieron cero avisos conocidos. El API aprobó 1.178 pruebas y omitió 63. Señalización aprobó 60 y omitió 2. Realtime pasó las pruebas de autenticación y suscripción y dos casos de recuperación. Las revisiones más recientes de varios paquetes y las imágenes instaladas requieren validación de compatibilidad y avisos de distribución por separado.
- Al normalizar solo los finales de línea, 147 JavaScript coincidieron, 34 difirieron, 17 existían solo en el servidor y 4 solo en el repositorio estable. La etiqueta del contenedor activo y el identificador informado por `/ready` difieren. Hay que conservar las integraciones activas de Capy al reconciliar código y huellas antes de un despliegue.
- PostgreSQL 16.15 escucha en loopback, con cuatro conexiones de 100 y 279 tablas en la muestra. No había índices inválidos ni bloqueos activos en dos observaciones de lectura. Los planes con identificador ficticio usaron índices en mensajes y notificaciones. Sus tiempos solo describen esas búsquedas vacías. El rol de la API observado carece de superusuario y de permiso para omitir RLS.
- Treinta y cinco migraciones concuerdan con el registro. Las 60 operaciones explícitas de adición de columna de `001_runtime_prerequisites.sql` ya tenían una columna presente, aunque falta el registro de esa migración. La otra ausencia, `026_pending_friendship_allows_conversation.sql`, sustituye el trigger de introducción. El trigger instalado aún verifica la solicitud de mensajes pendiente. Su aplicación retroactiva exige validar ese contrato, el orden de las funciones y la reversión.

## Carga, seguridad y seguimiento

Las pruebas de carga usaron PostgreSQL 17, una API y un gateway local con diez cuentas ficticias, cinco receptores y hasta ocho operaciones simultáneas configuradas. Cada operación normal recorrió feed, perfil, mensajes o notificaciones. El arnés impide usar los ingresos públicos o privados conocidos del servidor activo, incluso si se habilitan destinos de staging fuera de loopback. Sus 17 pruebas pasaron.

| Operaciones por segundo | Operaciones válidas | Entregas confirmadas | P50 / P95 / P99 de los primeros 10 s | Pico de RAM de API |
| --- | ---: | ---: | ---: | ---: |
| 4 | 180 de 180 | 45 de 45 | 9,42 / 23,66 / 24,49 ms | 155 MiB |
| 12 | 540 de 540 | 135 de 135 | 5,65 / 14,09 / 56,08 ms | 143 MiB |

Ambas etapas duraron 45 s y tuvieron cero fallos funcionales o de red. El pool no tuvo espera en las muestras. Quedan abiertos la carga sostenida en Linux equivalente, el punto de saturación, los fallos de un nodo y la recuperación de aplicación y multimedia desde respaldo. El sitio estático de Firebase reduce tráfico hacia el origen. Redis ya interviene en el procesamiento de eventos y hay una réplica de medios R2 en operación. El rendimiento de ambas rutas y los controles contra abuso en el ingreso real requieren evidencia propia. Una sola máquina sigue concentrando el estado y el ingreso.

Una segunda prueba con un máximo configurado de 16 operaciones simultáneas completó 720 de 720 a 12 por segundo durante un minuto. La etapa de 30 completó las 1.794 peticiones despachadas y las 448 entregas esperadas. El generador llegó tarde a seis de los 1.800 turnos, con un pico de dos peticiones simultáneas y ninguna descartada por alcanzar el límite de concurrencia. Por ello esa etapa no alcanzó el objetivo de 30 operaciones por segundo sostenidas.

La ruta de privacidad y la de solicitud de eliminación respondieron en Firebase. La verificación en Play Console, la entrega real de correos y la revisión de retención, atribuciones y licencias quedan pendientes. [Política de datos de Google Play](https://support.google.com/googleplay/android-developer/answer/10144311?hl=es-419) · [Reglamento peruano de protección de datos](https://www.gob.pe/institucion/anpd/normas-legales/6554453-16-2024-jus).
