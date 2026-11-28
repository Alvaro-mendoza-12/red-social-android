# Novvin 9.2.3 (196) y comprobaciones de producción

28 de noviembre de 2026. La APK 196 es una candidata firmada. La certificación del servicio completo sigue abierta mientras falle la comprobación de disponibilidad de Ubuntu y queden recorridos entre dispositivos pendientes.

## Android

La APK ARM64, la APK universal y el AAB se compilaron desde `f4c50646bbcbcc78bac9d78c1e9bba579801e45a`. Apuntan a Android 16, conservan la firma de carga de la 195 y pasaron la comprobación de 16 KiB. Bundletool validó el AAB, que pasó también el presupuesto de tamaño. El análisis de R8 comprobó 17 componentes del manifiesto. Los cinco recursos de Juegos presentes en cada paquete coinciden byte por byte con Git.

La 196 incorpora los modos de juego y la cuadrícula estable de ajedrez, la reacción durante una jugada y la recuperación tras cortes al aceptar invitaciones, confirmar revanchas y enviar mensajes. El ensayo de instalación actualizó de 195 a 196 en Android 16. Comprobó el arranque sin conexión y el regreso al acceso, así como Mensajes, Videos, Social, Juegos y Perfil dentro del modo invitado. La búsqueda de personas y la lectura de comentarios se recuperaron al restaurar la conexión. El búfer de fallos de Novvin quedó vacío.

La suite JVM de release aprobó 3.643 pruebas en 606 clases, sin fallos ni omisiones. Incluye pruebas de acceso, notificaciones, mensajería, perfil, social, video, juegos, LIVE, voz, llamadas, recompensas y persistencia.

| Paquete | Bytes | SHA-256 |
| --- | ---: | --- |
| APK ARM64 | 87680549 | `4dbcdd93539ece148a81e17def1f69207f8e61ec34f48dc61f06a93396abcba1` |
| APK universal | 214010402 | `5783baac7dc6729834c80a1254c6ed473a0f3eea18ea509cbbb83e3dc3eee1ed` |
| AAB Google Play | 125436649 | `219d88561111d3f54229a6e958130723e9095c0cba8a428a7c8c83279ef70e88` |

## Web pública

WebKit abrió 30 vistas de los dominios con y sin `www`, entre ellas seis formularios de acceso, en dos tamaños de iPhone simulados y escritorio. Todas las vistas respondieron 200, sin desbordamiento horizontal ni errores JavaScript. Las seis respuestas 503 registradas pertenecieron a la comprobación `/ready` de Ubuntu. Privacidad y eliminación de cuenta respondieron con su contenido público. `assetlinks.json` incluye el certificado de las APK verificadas. Queda pendiente comprobar el enlace desde una instalación distribuida por Google Play.

## Ubuntu y funciones con datos de prueba

Se comprobó por SSH que la API, PostgreSQL, el servicio en tiempo real, la señalización y Redis están activos. PostgreSQL publica su puerto en loopback y Redis no tiene puerto publicado. API, señalización y tiempo real ejecutan Node 22.23.4.

La imagen activa entrega invitaciones de Juegos por el canal en tiempo real. Se armó una imagen candidata sobre esa misma imagen para añadir la entrega push preservando los contratos que usa Capy. En el ensayo aislado la entrega simulada recibió el juego y el texto correctos aunque fallara el canal en tiempo real.

El ensayo con una base sintética reprodujo un 500 de Estadísticas cuando faltan tablas históricas. Tras añadir al candidato el parche `4bc97dd5`, pasaron 34 peticiones de Administración con cuatro niveles de acceso y datos nativos, importación parcial y completa. Rondas pasó 45 peticiones en el mismo candidato, incluidas concurrencia, votos reintentados, podio y apertura siguiente. La activación de ese candidato queda pendiente de la disponibilidad de Ubuntu.

## Estado de producción

`/health` responde 200 y `/ready` responde 503. El motor y el actualizador del antivirus de almacenamiento están en estado degradado. El contenedor tiene ClamAV 1.4.6 y firmas diarias 28194 del 25 de noviembre a las 06:26 UTC. `sigtool` verificó la firma y el canal oficial todavía anuncia esa misma versión. La versión 1.4.7 consta como candidata en el informe 259. La promoción del backend, la prueba de subidas y la comprobación completa con dos cuentas y teléfonos siguen pendientes. [Gestión de firmas de ClamAV](https://docs.clamav.net/manual/Usage/SignatureManagement.html).

Quedan por verificar en teléfonos la entrega push, las llamadas, las salas de voz y la actualización ofrecida por Play.
