# 133 · Formularios de puntos y herramientas LIVE por visita de cuenta

13 de noviembre de 2026, America/Bogota. Continúa la revisión móvil después de 132. Se corrigen los formularios de puntos y la apertura de herramientas de juego y puntos en las pantallas activas y en el anfitrión y visor clásicos. La revisión integral sigue abierta.

## Cambios y alcance

La apertura de juego, puntos y selección de comentario estaba recordada por el identificador del directo, o sin clave de cuenta. El formulario de puntos recordaba título, descripción, costo, cupos y espera sin distinguir cuentas. Se añade una visita que identifica directo, cuenta y generación de autenticación. A → B → A constituye una visita nueva, incluso si ambas transiciones llegan antes del siguiente cuadro. El refresco ordinario del token conserva la visita.

Las acciones de apertura y envío conservan la visita que las creó y comprueban su vigencia bajo la guardia de autenticación. Un callback retenido no puede abrir ni enviar el formulario de la cuenta que regresa. El diálogo de puntos cambia toda su identidad de composición al cambiar de visita, por lo que no hereda los campos ni las reglas editadas. El cierre de un diálogo anterior actúa sobre su estado anterior.

El saldo y las recompensas se leen con una instantánea protegida por cuenta, generación y directo, antes de que el consumidor asíncrono termine de limpiar el estado compartido. La salida del panel de puntos identifica a su visita original: una disposición antigua no cancela la lectura de otra cuenta. Esto no modifica ni certifica el ciclo global del motor LIVE.

El formulario borraba los datos de una nueva recompensa inmediatamente después de pulsar «Crear». Ahora espera una confirmación de creación de su visita. Un fallo conserva los datos para corregirlos o reintentar; una creación confirmada limpia el formulario aunque falle la lectura posterior del catálogo. Los campos de recompensa quedan deshabilitados durante el envío para evitar que la confirmación borre una edición posterior. Un acuse antiguo no cambia el contador de la visita siguiente. La creación de recompensas todavía no tiene el diario de idempotencia de los canjes: perder su confirmación requiere revisar el catálogo antes de reintentar.

## Validación

La batería general JVM aprobó **2828 pruebas en 529 clases**, sin fallos, errores ni omisiones. Incluye 22 controles de herramientas y puntos en dos clases: invalidación inmediata, A → B → A, captura invalidada durante su construcción, callback retenido, lectura protegida antes de la limpieza asíncrona, identificador normalizado, salida antigua y confirmaciones de creación. Las cifras focalizadas no se suman a la general. La [evidencia JVM](../quality/evidence/133-jvm.json) identifica las clases y las huellas de sus reportes.

Compilaron el APK debug y el instrumental. En Android 15 / API 35 / x86_64 pasaron ocho pruebas de Compose en 45,309 segundos: cinco del diálogo real de puntos y tres regresiones del juego de 132. Comprueban el borrador tras fallo/confirmación, la vuelta de cuenta en el mismo cuadro, el cambio de directo, la renovación del token y el rechazo de un botón retenido. El [registro instrumental](../quality/evidence/133-emulador.txt) conserva sus nombres y estados. Usan fixtures de estado, API y socket; no prueban el AuthRepository real ni medios contra Ubuntu. El emulador se cerró sin borrar sus datos.

El control obligatorio `qualityGateDebug` aprobó las pruebas JVM y Lint K1: **cero errores y 342 avisos**. No se declara revisado todo ese conjunto de avisos. Las herramientas de trazabilidad y cobertura aprobaron doce pruebas. La matriz mantiene 368 entradas y UX-19 continúa `EN_CURSO`.

El paquete archivado de laboratorio es **9.1.40-debug / 180**, SHA-256 `ccd0a2fcb9440782660e18530077dfe6c1a9b98980e829c49e7b59e1d2aa4cc4`. Pasó firma debug v2 y presencia de las cuatro clases de arranque, entre 86352 definiciones. La [evidencia estructurada](../quality/evidence/133-resultados.json) vincula los diez blobs Android, los paquetes y los resultados. No es la entrega final de producción.

No se ejecutó una tanda anterior al parche que reproduzca estos defectos; se encontraron por lectura. El primer comando Gradle rechazó la posición de `--tests`, y la compilación siguiente identificó el visor clásico todavía sin convertir. Lint detectó después una lectura directa de `StateFlow.value` dentro de Compose: se eliminó en favor de la generación observada, sin suprimir la regla. Se conservaron registros y paquetes intermedios y se repitieron compilación, batería, emulador y gate sobre las fuentes finales. No se cuentan esos intentos como validaciones aprobadas.

## Límites y siguiente recorrido

No se afirma aislado todo LIVE por corregir sus herramientas. La lectura parcial de `LiveRepository`, `LiveSessionViewModel`, el servicio y sus consumidores encuentra recorridos que aún no están ligados a una visita: el atajo de entrada por directo y rol, la captura de credenciales de entrada y cierre, callbacks de socket y medios, y la acción de finalizar de una notificación. Deben reproducirse con el repositorio y los servicios reales, corregirse y probarse antes de dar por cerrado el cambio de cuenta con un directo activo. Una comparación del identificador de usuario no resuelve A → B → A.

Quedan también la restauración del borrador de puntos tras recreación, los borradores de configuración y permisos del anfitrión, las herramientas de colaboración y moderación completas, las invitaciones y la paridad de recorridos web. En selección de comentario se protege la visibilidad y el despacho del callback de interfaz; esto no acredita la propiedad de las peticiones asíncronas de moderación del repositorio.

No se desplegó Ubuntu, no se certificaron redes o teléfonos físicos y no se entrega una APK final firmada para producción. El APK de laboratorio se identifica en la evidencia; no sustituye al último AAB histórico ni incluye automáticamente correcciones posteriores. La preparación para marketing masivo y la matriz completa permanecen abiertas. El requisito UX-19 sigue `EN_CURSO`.

Las correcciones terminadas se publican en `main` y `feature/live-gifts` con árboles iguales. Se preservan los cambios ajenos y la separación de `correciones` y del plan experimental 105. La cobertura registra lectura completa únicamente de los archivos realmente examinados; los archivos extensos de pantalla, repositorio y ViewModel conservan lectura parcial. El servicio leído por completo conserva defectos pendientes: `code-read` no equivale a corregido.
