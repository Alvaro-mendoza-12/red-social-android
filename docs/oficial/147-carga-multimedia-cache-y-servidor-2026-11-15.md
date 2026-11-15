# 147 · Carga multimedia, caché y servidor

## Cambios

El feed Android conserva en su caché acotada los bytes de la primera reproducción que el servidor autoriza con `X-Novvin-Playback-Cache` y un ETag. Así puede reutilizarlos al repetir o reabrir el video. La primera apertura sigue usando su GET autorizado. La reutilización exige una nueva comprobación de permisos y revisión. Las claves incluyen la visita de cuenta. Los invitados y los medios de una sola visualización conservan sus contratos de transmisión.

La escritura usa `CacheDataSink` de Media3, toma el bloqueo del hueco disponible y termina al llegar a un tramo ya almacenado. Los errores de E/S al escribir la caché permiten continuar la reproducción desde la red. La API oficial describe la apertura, escritura y cierre del componente. [Referencia de Android](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSink)

En web, los stickers del chat entran en la cola de prioridad de imágenes. El selector pide miniaturas de 160 px para stickers estáticos y conserva el original para los animados. El margen de precarga del selector baja a 80 px. La primera consulta del catálogo arranca sin los 200 ms de espera que siguen aplicándose a la búsqueda escrita.

La web actualiza React Router 7.18.2 a 7.18.5, Vite 7.3.7 a 7.3.8 y dependencias transitivas compatibles. La auditoría de producción sigue con cero avisos. Las herramientas pasan de un aviso alto, nueve moderados y siete bajos a cero altos, nueve moderados y siete bajos. Los dieciséis avisos de desarrollo restantes quedan abiertos.

## Ubuntu y R2

Se comparó el mismo primer MiB de un video del catálogo público, con un invitado temporal y tres GET Range por ruta. Las conexiones HTTP/1.1 se reutilizan dentro de cada serie. El origen de Ubuntu se fijó a su IPv4 pública obtenida por DNS externo, conservando TLS y SNI.

| Ruta | Primer byte, mediana | MiB completo, mediana | Rango de transferencia |
| --- | --- | --- | --- |
| Ubuntu público | 297 ms | 2.924 ms | 2.891–3.545 ms |
| R2 con autorización de Ubuntu | 624 ms | 763 ms | 678–2.846 ms |
| Pasarela web | 477 ms | 1.511 ms | 1.468–2.104 ms |

Las nueve respuestas fueron 206 y devolvieron la misma huella de bytes. El primer intento de cada ruta incluye su conexión inicial. Esta muestra permite comparar estas rutas y este objeto. La medición del desplazamiento del feed y la capacidad masiva siguen pendientes. Las rutas de entrega mantienen su configuración tras la comparación.

La tanda física del otro asistente, registrada en QA145, obtuvo primer cuadro de 5.439, 1.013 y 910 ms en el Moto g04 por Wi-Fi y R2. Esa APK procede de la base 144 con reglas QA145. La experiencia física con los cambios de caché de 147 sigue pendiente.

## Salud y dependencias del servidor

La muestra inicial registra 11.449 MiB de memoria disponible, swap sin uso, disco raíz al 54 %, cero unidades systemd fallidas y API con cero reinicios y cero eventos OOM. Ethernet negocia 1.000 Mbps. PostgreSQL registra cero deadlocks y cero bytes temporales en su contador acumulado consultado. Redis usa 11,69 MiB de un límite de 256 MiB, con política `noeviction`, cero expulsiones y cero conexiones rechazadas. El inventario R2 registra 1.025 réplicas listas y cuatro eliminadas.

La API activa tenía ocho avisos npm de producción. Se construyó `novvin-api:media147-deps-20261115` sobre su imagen efectiva `novvin-api:review136-c2c4e24b93d3`, usando los manifiestos que ya estaban versionados en el repositorio. La huella conjunta de `src` se conserva. Bcrypt, JWT, Sharp, Firebase, S3, PostgreSQL, Express y Socket.IO se comprobaron en un contenedor aislado. La candidata y la imagen activada tienen cero avisos npm de producción.

Se recreó únicamente el servicio API después de contrastar las variables de Compose y respaldar el override y el marcador de fuente. El directorio `/home/alvaro/novvin-media147-deps` conserva el build, los manifiestos, las huellas, los respaldos y el recibo `activated.json`. El script de activación restaura el override anterior si falla la comprobación de disponibilidad. El marcador de fuente conserva la procedencia 136 de las fuentes efectivas. La imagen y sus manifiestos identifican esta actualización de dependencias.

Después de la activación, `/ready` y `/health` públicos devolvieron 200 y `ok:true`. El verificador HTTP de módulos aprobó 48 controles con una cuenta sintética, p95 de 833 ms y eliminación de la cuenta verificada. Estos controles cubren los endpoints que enumera la evidencia. La revisión de sus recorridos completos en pantalla permanece abierta.

El host anuncia doce paquetes actualizables, incluidos Docker, containerd, Tailscale y Node. Ese mantenimiento del host y sus posibles reinicios queda pendiente. La auditoría de estabilidad y crecimiento continúa por módulo.

## Pruebas y publicación

La suite web aprobó 1.271 pruebas en 200 suites y el build verificó 21 páginas públicas. El navegador aislado comprobó el chat y el selector en 375, 768 y 1.440 px, sin errores de página ni desbordamiento horizontal. En 375 px, la versión 146 pidió 25 originales del catálogo de prueba. La candidata pidió trece miniaturas estáticas y doce originales animados. Los datos, imágenes y sesión de esta comprobación son ficticios. El ensayo identifica tipos de petición y uso del original para los animados, y deja pendiente medir la latencia percibida con stickers reales de una conversación.

La continuación 146 ya estaba publicada en ambas ramas estables y desplegada por Git en `novvinapp.com`. Su tanda Android pendiente terminó con 26 pruebas focalizadas aprobadas. La caché nueva de 147 aprobó 26 pruebas JVM en cinco clases, con cero fallos, errores u omisiones. La compilación y la tanda focalizada terminaron en 16 min 37 s. Sus casos incluyen primera descarga reutilizable, revisión actualizada, rechazo de acceso, cambio de cuenta y liberación de bloqueos.

Los resultados estructurados están en [la evidencia de 147](../quality/evidence/147-media-delivery.json). La nueva APK y los ensayos físicos con esta caché, la fluidez al desplazar Videos y la revisión integral de producción permanecen abiertos.
