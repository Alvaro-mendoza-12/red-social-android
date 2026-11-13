# Juego durante un directo

En «Dibuja y adivina» el anfitrión escoge participantes presentes. El servidor asigna los turnos y la palabra, admite trazos de la persona que dibuja y calcula el marcador después de cada respuesta válida. Las consultas del otro jugador omiten la palabra reservada. Al vencer el tiempo, el proceso de rondas puede avanzar aunque nadie toque la pantalla.

La prueba de aceptación incluye jugar una ronda entre dos usuarios, recibir el trazo por WebSocket en la segunda cuenta, observar el incremento de puntos, reintentar la creación y ejecutar dos procesos de cierre sobre una ronda vencida. Se ejercitaron la API, el gateway y PostgreSQL. Falta el ensayo Android–web con trazos visibles y audio durante una transición de red.

Trivia, rompecabezas, construcción cooperativa y un puente de efectos para juegos quedan como propuestas. Antes de activar una, especificar reglas, pausa, duración máxima, accesibilidad, efecto permitido, reversión y límite por usuario. La clasificación competitiva debe poder jugarse en igualdad de condiciones sin comprar regalos.
