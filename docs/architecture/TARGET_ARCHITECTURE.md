# Evolución de la arquitectura

| Cambio | Dependencia verificable | Señal de aceptación |
| --- | --- | --- |
| Extraer rutas de lectura del módulo de datos | Conservar parámetros, permisos, forma de respuesta y filas importadas | Misma salida para las dos direcciones de seguimiento, bloqueo y cuentas antiguas. |
| Ampliar capacidad de comunicaciones | Redis Streams de señalización ya existe. Inventariar salas, sesiones, gateway, cachés y límites conservados en memoria | Dos nodos, reconexión a otro nodo y una sola entrega, con carga medida fuera de producción. |
| Separar pruebas de red y base | Entornos locales con dirección de escucha explícita y cuentas sintéticas | Las pruebas se ejecutan tras reiniciar las dependencias y sin credenciales productivas. |
| Extender LIVE Play | Eventos autorizados por sala, persistencia del resultado y representación por cliente | Repetición y recuperación no alteran el marcador ni producen cobros. |

Las decisiones de escalar réplicas, crear servicios o cambiar el proveedor de archivos requieren las mediciones y autorizaciones correspondientes. [Registro de riesgos](../risks/RISK_REGISTER.md)
