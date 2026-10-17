# Web 9.0.45 / Android 9.0.38 (135)

## Correcciones

- Visor web con zoom 100–500 %, rueda, botones, teclado, doble clic, pinza y arrastre. Cambiar de imagen restablece la vista. Se conserva la autorización de medios privados.
- Las listas de grupos y comunidades presentan «Pregunta: …», con una defensa adicional para registros históricos que contienen JSON.
- Preguntas con vistas independientes para escribir y explorar respuestas. La lista tiene desplazamiento propio y tamaño limitado. Las respuestas flotantes se eligen al azar al entrar y cambian mientras la tarjeta está visible.
- Reacciones explícitas en preguntas de Android. Los participantes pueden crear encuestas desde el chat restringido; los mensajes normales continúan sujetos al permiso del creador. Los sorteos mantienen su permiso de creador.
- Publicidad desde el menú de Social, aunque se oculte el anuncio. Carrusel de hasta diez imágenes, títulos, descripciones, destinos y botones por tarjeta. Edición, orden, vista previa y pausa persistidos en el servidor. La administración requiere el permiso existente de moderación.
- Los enlaces de productos admiten HTTPS. Las imágenes de todas las tarjetas se validan y se incluyen en las relaciones de acceso del almacenamiento, sin abrir el contenedor al público.
- Las tarjetas web de preguntas fuera de pantalla y las pestañas en segundo plano suspenden las consultas periódicas.

## Referencias consultadas

Se consultaron la guía y el centro de ayuda de Meta; exigían inicio de sesión al abrirlos. El [SDK oficial de Meta](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adcreativelinkdatachildattachment.py) permite verificar los campos por tarjeta: imagen, nombre, descripción, enlace y llamada a la acción. El carrusel implementado pertenece a Novvin; no crea campañas ni cargos en Meta.

## Validación y entrega

Las evidencias de esta entrega se guardan en `audit/2026-10-07-integral` del espacio principal. Los recibos finales identifican la imagen de API, el despliegue Vercel y el APK instalado. Esta revisión cubre las correcciones indicadas y no constituye una certificación general de todas las funciones.
