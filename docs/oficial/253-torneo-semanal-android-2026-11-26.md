# Cierre y recuperación del torneo semanal de Juegos

La cuenta regresiva de los últimos cinco minutos parte del tiempo del servidor. El conteo visible usa un reloj monotónico para conservar la duración si cambia la hora del teléfono. Al terminar la semana, la vista consulta la clasificación nueva. Si la conexión falla, conserva los datos cargados y permite volver a consultar al recuperar la red, tras cinco segundos o con «Actualizar clasificación». Los reintentos se detienen al salir de la vista.

En teléfonos, al abrir el torneo se lleva el foco al encabezado y el podio se muestra en una columna. La clasificación vacía tiene un mensaje propio. Se reprodujeron cuatro fallos antes de la corrección. En una base aislada se ensayó el cambio de semana con el endpoint de clasificación, un fallo temporal de conexión, la publicación del podio de tres posiciones y la primera victoria de la semana nueva. El ensayo físico sigue pendiente.
