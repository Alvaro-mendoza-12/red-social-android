# 03 — UX/UI (primera pasada)

Fecha: 2026-09-26. Alcance real: las pantallas Android de bienvenida, registro, login y recuperación revisadas en un emulador de 360 dp en la tanda previa; rutas principales inventariadas. Las imágenes y reportes del usuario señalan problemas históricos de recorte/espacio, pero no sustituyen una nueva captura de cada pantalla en esta revisión.

| Recorrido | Evidencia | Estado y criterio siguiente |
|---|---|---|
| Entrada y registro | Logo y contenido ya respetan barra de estado; la bienvenida centró el bloque principal. En esta pasada se instaló la APK nueva en emulador Android 35, se capturó bienvenida/registro y la flecha volvió sin excepción fatal (`build/reports/novvin-emulator-{startup,register}.png`). | Correcto en emulador probado. Revisar tamaños compactos, teclado, orientación y Moto G04. |
| Recuperación | Campos de contraseña/confirmación con visibilidad y código; cuando el servidor responde `disabled`, UI informa la falta de envío. | UI implementada; servicio completo bloqueado por SMTP de producción. |
| Cambio de cuentas | Acción «Añadir otra cuenta» guarda sesión actual y abre login. | Falta probar dos cuentas reales y que badges/datos se aíslen. |
| Bandeja | Se eliminó emisión vacía al volver a observar la misma cuenta; test de reobservación pasa. | Falta prueba visual de lista real y redes lentas. |
| LIVE | Catálogo de regalos ilustrados/animados, confirmación de precio y estado de regalo desactivado; directorio distingue error de lista vacía. | Falta captura/revisión en creador y espectador durante transmisión real, 240/360/600 dp. |

La prioridad de experiencia es cerrar flujos incompletos antes de sumar controles: un botón que promete recuperar contraseña sin entrega real genera más fricción que una opción ausente. Cada pantalla crítica debe probar estado vacío, carga, error, reintento, éxito, teclado abierto y regreso. En video y LIVE medir tiempo hasta contenido visible y continuidad tras reconexión; no inferir fluidez de que la APK compile.
