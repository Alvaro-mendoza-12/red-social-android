# 209 · Fotos y carruseles sin saltos al cargar en el feed Android

19 de noviembre de 2026. El modelo de publicación entrega una lista de URL y, para publicaciones antiguas, una URL individual. Ambos caminos llegan a la misma galería. Faltan dimensiones previas a la descarga en ese contrato. La versión anterior reservaba inicialmente una caja 4:3 y cambiaba su altura al recibir el tamaño de la imagen decodificada por Coil. En un carrusel también cambiaba la altura exterior al seleccionar una foto de otra proporción.

La galería reserva el espacio desde la primera medición según el ancho de la ficha, la altura de la ventana y el tipo de portada. Las fotos se ajustan completas con su proporción original. La portada de video conserva el límite anterior de 300 dp. El cambio de página, el error de carga, la decodificación conservan la posición del contenido siguiente. El usuario puede ampliar la foto y abrir la página seleccionada.

## Resultados

Dos regresiones se reprodujeron en la referencia `5806a0cafb50` al ejecutar la galería real con Compose, Coil y Robolectric sobre API 34. Los ensayos utilizaron un contenedor de 280 dp de ancho, una ventana declarada de 500 dp y PNG locales.

| Medición | Antes | Con la corrección |
| --- | --- | --- |
| Posición superior del elemento siguiente al cargar un retrato | De 210 a 360 px | 210 px antes y después para retrato, cuadrado y paisaje |
| Altura del carrusel al pasar de retrato a paisaje | De 360 a 120 dp | 210 dp en ambas páginas y apertura de la segunda foto confirmada |

Seis pruebas JVM aprobaron el cálculo por ancho de ventana, sus límites y la identificación de video. Tres pruebas adicionales renderizaron la interfaz con gráficos nativos de Robolectric. Contaron las cuatro marcas de esquina y midieron su proporción visible en cinco formas, incluidas 1:10 y 10:1. Para esta ejecución, la captura dibuja la vista de la actividad en un bitmap. La prueba instrumentada conserva la captura propia de Android. El intento inicial de captura directa del entorno JVM encontró un límite de redibujado y sus resultados se guardaron aparte.

El paquete de la revisión 208 corresponde al código anterior. El desplazamiento medido cubre estos cambios de estado y tamaños concretos. Permanecen abiertos el rendimiento de desplazamiento en dispositivos físicos, la red lenta real, los videos y las demás superficies de contenido. Las pruebas y sus entradas se conservan en `D:/Novvin-Entregas/evidencias/2026-11-19-mobile-audit209-feed-layout/`.
