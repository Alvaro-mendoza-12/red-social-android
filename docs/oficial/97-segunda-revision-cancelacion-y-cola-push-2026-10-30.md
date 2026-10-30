# 97 — Segunda revisión: cancelación y continuidad de la cola push

Fecha: 2026-10-30, Bogotá. Se realiza después de comitear y subir los cinco puntos de la [revisión 96](96-segundo-plano-subidas-y-perfiles-2026-10-30.md): `53b1b58b` en `feature/live-gifts` y `8e4dc947` en `main`, con árboles versionados idénticos.

## Cancelación de subidas y diagnósticos

El ensayo de interrupción dejó una evidencia adicional: la ruta de streaming había registrado una `CancellationException` esperada como fallo de `media_upload` y como transporte lento. Cancelar al salir de una pantalla, cambiar de cuenta o detener un trabajo no debe producir ese diagnóstico de fallo inesperado.

Se propaga la cancelación antes del manejador genérico en las subidas por bytes y por URI. La respuesta `Result.failure` del cliente compatible también se inspecciona para propagar una cancelación encapsulada antes de registrar métricas. La ruta TUS ya conservaba esa propagación. Los errores reales siguen devolviendo su resultado y generando diagnóstico. No se cambia la política de reintento de escrituras ni se presenta una cancelación como entrega completada.

## Registro push obsoleto y trabajos dependientes

En la revisión del encadenamiento se encontró otro caso: si la sesión cambiaba mientras se esperaba la credencial, el repositorio lanzaba una cancelación aunque la corrutina siguiera activa. Un registro de sesión anterior debe terminar como trabajo obsoleto, permitiendo la siguiente pasada encolada. Una dependencia fallida o cancelada puede impedir que continúe una cadena; el alcance del encadenamiento se contrastó con [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).

Ahora la ausencia de cuenta o la invalidación de su guardián termina ese registro sin enviar la petición. La comprobación de sesión ocurre antes de convertir una credencial ausente en error reintentable. Si una operación interna informa cancelación con la sesión ya obsoleta y la corrutina aún activa, también se completa esa pasada. `ensureActive` conserva la cancelación real del sistema o del padre; una sesión todavía válida no transforma su cancelación en éxito. La confirmación local sigue protegida por la generación de sesión y el mutex de registro.

Este segundo hallazgo procede de revisar el código y la dependencia entre trabajos. No se afirma haber reproducido una entrega FCM entre equipos físicos. Las regresiones existentes comprueban cambio de cuenta, invalidación de generación, resolución del token vigente y cancelación; la prueba nativa de cola comprueba las restricciones y la pasada dependiente sin registrar un token ficticio.

## Verificación de cierre

Se repite la suite unitaria sobre estas correcciones y se reconstruye el paquete firmado 9.1.32 / 172, con comprobación de firma, R8 y empaquetado. El análisis estático final debe usar los recursos reconstruidos. Los resultados definitivos se incorporan al cierre de la revisión 96; no se publica en Play desde esta tarea.
