# 146 · Conectividad, Rondas y asistente

## Ruta pública

Tres conexiones HTTPS nuevas desde la laptop a `https://novvinapp.com/ready` devolvieron HTTP 200. El tiempo hasta el primer byte fue 2,994 s, 2,042 s y 2,259 s, con mediana de 2,259 s. Ubuntu anunció 4,1 ms, 3,4 ms y 8,6 ms en `Server-Timing: app`, respectivamente. La portada pública dio una mediana de 0,364 s en tres conexiones nuevas. La consulta directa desde la laptop resolvió a la dirección privada de Tailscale. Sirve como comparación de esa red y deja pendiente una medición externa directa del origen.

La diferencia entre el primer byte y el tiempo anunciado por la aplicación muestra espera fuera del procesamiento instrumentado de Ubuntu en estas muestras. Sigue pendiente aislar los saltos de la ruta pública y medir con sesión real la apertura de Perfil, Grupos, Comunidades y el primer fotograma de Videos.

## Android

Rondas muestra la fase, el título y el tiempo en una sola tarjeta. La primera búsqueda tiene un estado propio y el cambio al llegar a cero solicita la actualización de la fase. El sticker puede mostrar una miniatura distinta mientras carga el original animado. El reintento manual vuelve a comenzar por el original y mantiene la posibilidad de recurrir a la miniatura.

## Web

Tras resolver la ronda activa, la web pide en paralelo la consigna, las participaciones y los perfiles del podio. Las lecturas autenticadas de la ronda se vinculan a la cuenta. Una actualización conserva la ronda visible y descarta los resultados de lecturas anteriores. La participación aparece antes del ranking y el estado de carga o error muestra una acción de recuperación cuando corresponde.

Videos muestra sus controles mientras llega el feed. La petición de directos se abre después de cargar suficientes videos de la cuenta actual para colocar una tarjeta de directo. Las peticiones simultáneas que detectan un fallo del mismo servidor comparten la búsqueda de una ruta alternativa confiable.

El asistente centra el campo de entrada al iniciar la conversación y lo mantiene abajo al mostrar el historial. Conserva las sugerencias, la opción de responder paso a paso, la copia, la cancelación y el reintento. El ensayo de navegador usa respuestas simuladas.

## Verificación

La compilación Kotlin de Android en la variante `debug` terminó y la comprobación en teléfono sigue abierta. El build web comprobó 21 páginas públicas y la plantilla de las rutas privadas. La suite web pasó 1.270 pruebas en 200 suites. El navegador sin ventanas comprobó el asistente, la recuperación de Rondas y la carga de Videos a 375, 768 y 1.440 px, con una sesión ficticia y peticiones interceptadas. Las dos consultas de detalle de Rondas empezaron con una diferencia de 0 a 1 ms aunque cada respuesta fue retenida 500 ms. La medición pública de arriba corresponde a conexiones reales e independientes de este ensayo.

Quedan abiertos el ensayo físico del cambio Android, la medición del inicio autenticado bajo redes reales y la revisión integral previa a producción y publicidad.
