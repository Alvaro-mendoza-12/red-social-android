# Confirmación y recuperación de revanchas en línea

28 de noviembre de 2026. Corrección en los recursos de juegos de mesa compartidos entre Android y web.

Al aceptar una revancha, el resultado muestra que se está guardando la respuesta y desactiva el botón. Si se pierde la confirmación, consulta el resultado anterior para comprobar si se guardó el voto. El usuario conserva la opción de reintentar si el servidor sigue sin confirmar. Un voto confirmado muestra cuántos jugadores han aceptado y cambia a «Esperando al rival» o «Esperando a los demás» hasta que la nueva mesa esté lista.

La revancha requiere pulsar «Entrar a la revancha». Si esa mesa ya se cerró o dejó de estar disponible, se conserva en pantalla el resultado de la partida anterior y se explica por qué la nueva mesa no puede abrirse.

Cuatro regresiones fallaron con el cliente anterior. La corrección pasó 56 pruebas de cliente y 3 suites de API con una base temporal. En Chromium se simuló el corte al confirmar el voto en 360 y 620 píxeles. Cada escenario hizo un solo envío y una lectura de verificación. El primero abrió la revancha tras la confirmación del usuario y el segundo conservó el resultado al recibir una mesa cerrada.

Quedan pendientes la compilación de una APK que incluya estos recursos, la prueba en dos teléfonos y el despliegue de la web. La APK 9.2.2 (195) corresponde a un árbol anterior.
