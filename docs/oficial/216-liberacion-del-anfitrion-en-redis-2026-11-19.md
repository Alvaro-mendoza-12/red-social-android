# 216 · Liberación del anfitrión al terminar un directo

El cliente Redis de señalización recibe una colección de claves para un borrado múltiple. El cierre del directo enviaba dos argumentos independientes. Redis borraba el estado y mantenía la reserva del anfitrión hasta que venciera. Un intento inmediato de tomar esa reserva con otra sesión podía quedar rechazado aunque el cierre ya hubiera terminado.

La corrección envía las dos claves en una lista. En Redis 7.4.8 aislado, antes del cambio la reserva del directo terminado seguía presente. Tras el cambio se eliminaron su estado, su pertenencia a la lista activa, su plazo y su reserva. Se pudo asignar de inmediato el siguiente anfitrión. Otro directo que seguía abierto conservó su reserva. Pasaron las dos pruebas de integración con Redis real y la suite unitaria del estado distribuido.

Se verificaron por SHA-256 los ejecutables del proveedor y las bibliotecas de ejecución frente a los del contenedor activo. La instancia local se inició con datos ficticios, AOF y sincronización por segundo. Tras detenerla y volver a iniciarla, el almacén de Novvin recuperó el contador, la pertenencia a los directos activos y un TTL mayor de 86.000 segundos para el estado de prueba. La consulta al servidor activo fue de lectura. El reinicio, las escrituras y la eliminación de datos de prueba tuvieron lugar en la instancia local.

Los registros de referencia, candidata y reinicio están en `D:/Novvin-Entregas/evidencias/2026-11-19-audit214/redis-lab/`. Siguen pendientes la activación del parche de señalización en Ubuntu, el ensayo entre dos teléfonos y la recuperación ante una pérdida abrupta del host. Esta prueba de reinicio ordenado no mide la pérdida admisible ante un corte eléctrico.
