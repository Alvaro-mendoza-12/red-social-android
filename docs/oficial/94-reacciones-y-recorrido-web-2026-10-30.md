# 94 — Reacciones visibles y auditoría de navegación web

Fecha: 2026-10-30, Bogotá. Continúa las revisiones 92 y 93. El propietario confirmó que su observación se refería a reacciones con emojis. Se separan errores reproducidos de demoras observadas y comportamientos previstos.

## Fallo confirmado y corregido

El selector ofrecía seis reacciones, pero tanto `ChatDetailPage` como `RoomChatPage` dibujaban únicamente 👍, ❤️ y 😂. Una reacción 😮, 😢 o 🙏 podía guardarse correctamente y quedar invisible. En el navegador de producción anterior, el servidor respondió HTTP 200 en 413 ms con `😮: 1`, mientras la lista de reacciones visibles permanecía vacía. Por tanto, este caso era un filtro de la interfaz, no un fallo del switch o pérdida de datos en el servidor.

Ambas páginas ahora muestran las claves presentes con participantes, incluyendo emojis recibidos desde otros clientes. Se conservan los contadores y la acción de volver a pulsar para retirar la reacción. No se modifica la API ni se requiere una migración.

La regresión verifica chats, grupos y comunidades con los seis emojis rápidos, uno adicional y otro sin participantes. Las tres variantes fallaron antes de la corrección (24 casos existentes aprobados); después aprobó la suite completa de 865 pruebas en 159 suites. La prueba final usa participantes distintos por emoji para respetar la regla del servidor.

## Verificación pública posterior

Publicado en `https://red-social-android.vercel.app`, despliegue `dpl_AUB7gXmXCcfaLUKYGXbjEhcfu5mH`, estado READY y alias de producción confirmado. El candidato se preparó únicamente con fuentes versionadas, sin subir archivos locales privados.

En una conversación existente entre nuestras dos cuentas sintéticas, el navegador comprobó las elecciones 😮, 😢 y 🙏: todas respondieron HTTP 200 y aparecieron en 451, 423 y 415 ms respectivamente. La última seguía visible después de recargar y tras cinco segundos adicionales. Se retiró al terminar; la conversación quedó sin reacciones de esta prueba. No se alteraron mensajes de otros usuarios.

**Comportamientos normales comprobados:** elegir un emoji cierra el selector; cambiar de emoji reemplaza la reacción anterior de esa persona. El servidor mantiene una reacción por persona y mensaje. No deben interpretarse esas dos acciones como pérdida de la reacción. El selector sin elegir permaneció conectado y abierto durante la observación de cuatro segundos. Android recorre las entradas existentes y no contiene el filtro fijo de tres emojis identificado en la web; no se genera otro APK por esta corrección.

## Recorrido y tiempos observados

Se inició sesión con una cuenta sintética y se recorrieron Mensajes, Videos, Social, Juegos y Perfil. También se abrieron las pestañas Grupos, Comunidades y Voz, y las pantallas Notificaciones, Llamadas, Salas de voz, Crear espacio, En vivo, Ajustes, Mis reportes, Ayuda, Asistente y Buscar. Las consultas registradas respondieron 200, sin excepciones de página ni avisos de error visibles durante esos recorridos. Las listas vacías de esa cuenta no certifican operaciones internas de un grupo o comunidad.

En las seis transiciones principales medidas, el clic y cambio de ruta tardaron 39–58 ms; las consultas de datos registradas tardaron 375–1.511 ms. Son observaciones de una sesión y su red, **no** LCP, INP, percentiles de usuarios reales ni una prueba de carga. La espera de una consulta no mide por sí sola ancho de banda o tiempo de procesamiento SQL.

En Social, las seis imágenes que realmente estaban dentro de la pantalla habían cargado al observarlas 2,5 segundos después. Los elementos fuera de pantalla con carga diferida no se consideran imágenes rotas. Un video visible estaba reproduciéndose con `readyState=4`, sin error; durante siete segundos siguió avanzando y un evento de espera al reiniciar el bucle duró unos 4 ms. Esta muestra no reproduce un congelamiento general ni garantiza la reproducción de todos los archivos.

Con un chat abierto, el ancho se cambió de 1440 a 390 y nuevamente a 1440 px. Al regresar reaparecieron la lista de 380 px y la conversación de 976 px, sin desbordamiento horizontal. No se reprodujo que la distribución de escritorio quedara atrapada en el modo estrecho.

Muestra del servidor durante el recorrido: API 0,41 % CPU, base de datos 3,80 %, aproximadamente 11 GiB de RAM disponibles, disco raíz al 51 % y `/ready` público HTTP 200 en 101 ms. No se observó saturación en ese instante; no constituye una certificación de capacidad. Sigue vigente el enlace Ethernet de 100 Mbps documentado en la revisión 90. Mejorarlo puede ampliar el caudal disponible, pero no corrige filtros de la interfaz ni demuestra que toda demora sea de red.

## Validación y límites

- 865 pruebas Jest aprobadas en 159 suites; las 27 de interacción se repitieron tras ajustar los participantes del fixture, todas aprobadas.
- 96 pruebas Node aprobadas.
- Lint: cero errores y siete advertencias existentes.
- Build aprobado; 19 páginas públicas comprobadas y sin JavaScript de aplicación en ellas. Continúa la advertencia existente de chunks grandes.
- 90 controles HTTP, SEO, cabeceras y recursos aprobados contra la publicación de producción.
- Código y documentación sincronizados y subidos a `main` y `feature/live-gifts`; las cachés y archivos personales no versionados pueden diferir.

No se activaron pagos, no se cambió el backend, no se publicaron paquetes Android nuevos y no se envió contenido a usuarios reales. No se realizaron llamadas, LIVE o notificaciones push entre dispositivos físicos. No se afirma ausencia de todo error, capacidad masiva ni rendimiento de todas las redes. La limpieza del disco de desarrollo de la revisión 93 no se presenta como una aceleración del servidor.

## Criterios consultados

Se contrastó la separación entre navegación y recursos con [Navigation and Resource Timing, web.dev](https://web.dev/articles/navigation-and-resource-timing), la carga fuera de pantalla con [Lazy loading, MDN](https://developer.mozilla.org/en-US/docs/Web/Performance/Guides/Lazy_loading) y el estado del selector nativo con [details, MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/details). Las conclusiones anteriores proceden de las mediciones y del código revisado; estas referencias no certifican la aplicación.
