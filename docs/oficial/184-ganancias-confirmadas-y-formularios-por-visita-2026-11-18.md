# Continuación 184 · Ganancias confirmadas y formularios de retiro por visita

Fecha: 18 de noviembre de 2026. Continúa la auditoría móvil después de [regalos183](183-regalos-sesion-confirmacion-y-reintentos-2026-11-18.md). La revisión integral sigue abierta; estas comprobaciones no cierran toda la APK ni el monedero.

## Fallos corregidos

CreatorProgramRepository interpretaba errores HTTP de ganancias, solicitud y cuenta de cobro como objetos ausentes o listas vacías. Un cuerpo HTTP200 vacío también se aceptaba como sección vacía. Ahora las tres lecturas requieren éxito HTTP y una lista JSON de objetos; una respuesta fallida no confirma que no haya ganancias o cuenta. Las listas200[] siguen siendo válidas.

loadDashboard captura la visita antes de E/S y reutiliza accountScopedCall para renovar y obtener el token, validar su propietario y descartar respuestas de una visita anterior, incluso A→B→A. Las tres consultas independientes comienzan juntas. Si una falla, se devuelve fallo y se conserva la última instantánea de esa misma visita; no se fabrica una vista parcial con ceros.

WalletViewModel.refreshEarnings vincula su trabajo y publicación a visita/generación. El cambio de cuenta elimina la referencia privada anterior y carga la nueva; salir limpia la instantánea. Las respuestas no cooperativas antiguas no restauran datos ni desbloquean otra consulta. El progreso se reserva antes de programar, se libera al cancelar/finalizar y tiene un plazo de15 segundos que incluye credenciales. Los fallos permiten reintentar y conservan una instantánea confirmada de la misma cuenta.

Los formularios de cuenta PayPal y retiro conservaban su borrador al cambiar de cuenta y capturaban el propietario al pulsar Confirmar. Ahora su estado local se reinicia por visita y los callbacks de registrar, solicitar y cancelar pasan la visita que mostraba esos datos. CreatorPayoutOperations rechaza una confirmación retenida de otra visita. Una notificación tardía del cambio de sesión tampoco cancela una solicitud válida que ya pertenece a la visita nueva.

La pantalla solo muestra «Sin movimientos» tras una consulta confirmada, sin error actual. Conserva los ceros explícitos del resumen del servidor: cero monedas no se sustituye por cifras de otra instantánea mediante takeIf >0. Se mantienen los contratos de retiro, los importes y el método de pago; la selección no ejecuta pagos reales.

Los dos métodos de escritura heredados de CreatorProgramRepository comparten los lectores más estrictos, pero sus mutaciones y autorización completa siguen pendientes. No se incorporan funciones experimentales ni se modifica el servidor propio de Ubuntu.

## Evidencia

Carpeta externa: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit184-earnings/`.

- Referencia final: **33 casos, 25 fallos de aserción**. Los cuatro archivos productivos copiados antes del cambio se comprobaron contra los blobs del padre183, feature49d3ccba8d08797f38e1dc9498f470610a118a11. `reference-parent.json` conserva esa comprobación. Son variantes por sección/visita, no veinticinco defectos independientes.
- Candidata v2: **33/33 casos JVM directos aprobados**, compilando repositorio, ViewModel, controlador y pantalla Compose reales, con las mismas huellas que se aplicaron a los worktrees. Las lecturas usan Ktor/MockEngine; los resultados de pantalla y comandos de retiro están controlados con repositorios ficticios. No son consultas ni retiros productivos.
- Gradle Audit: **89/89 casos en diez clases, cero fallos/errores/omisiones**, compilación Kotlin/Java/Compose/Hilt correcta, entradas y HEAD estables, huellas privadas sin cambios y diferencias ajenas preservadas. Incluye las dos clases nuevas, las siete clases de183 y CreatorPayoutOperationsTest; vuelve a comprobar regalos, saldo, refresh, retiros, persistencia y cola Play.
- Se conservan los arneses iniciales reference/candidate: no compilaron porque la prueba de cancelación devolvía Unit en lugar de CreatorPayoutRequestResponse. Se corrigió únicamente el fixture. La referencia v2 reprodujo24 fallos de32 casos; la ampliación final añadió la solicitud válida que se perdía por un observador tardío, con25/33.

La primera ejecución Gradle quedó sin resultado terminal antes del reinicio de Windows de la mañana del18. No se cuenta como aprobada. Se conservaron su log y snapshots; antes de repetir se verificaron ambos HEAD, índices vacíos, locks libres y las seis huellas exactas en ambos worktrees. El recibo válido es el de gradle-earnings-restarted.

No se ensaya aquí interfaz física, cambio real de cuentas en dispositivo, producción ni cobros/SMTP/PayPal/Google Play. La selección compila las pantallas; las condiciones visuales de vacío y cero tienen revisión de código y compilación, no una prueba instrumental nueva. No hay APK nueva: la QA9.1.43/183 identificada en180 no incluye automáticamente183/184.

## Continuidad

Quedan pendientes otros estados del monedero y su actualización al cambiar de cuenta, paginación de movimientos/ganancias y totales de historiales largos, sincronización del saldo con regalos/compras, valoración/precio del regalo, escrituras heredadas y ensayos físicos. Ninguna cifra anterior cierra los módulos no revisados.

Se publican únicamente cuatro fuentes propias, dos pruebas, este informe y cobertura de índice, conservando un árbol común en main y feature/live-gifts y los cambios ajenos. CreatorEarningsScreen mantiene lectura parcial; repositorio, ViewModel, controlador y pruebas fueron examinados completos. La cobertura de lectura no afirma validación de todas sus funciones.
