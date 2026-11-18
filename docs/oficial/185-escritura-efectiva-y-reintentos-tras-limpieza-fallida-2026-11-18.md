# Continuación 185 · Escritura efectiva y limpieza fallida de reintentos

Fecha: 18 de noviembre de 2026. Continúa la auditoría móvil después de [Ganancias184](184-ganancias-confirmadas-y-formularios-por-visita-2026-11-18.md) y [regalos183](183-regalos-sesion-confirmacion-y-reintentos-2026-11-18.md). Esta corrección amplía las garantías del guardado; no cierra la revisión integral.

## Fallos y corrección

La aplicación admite API24. En la implementación de [SharedPreferences de Android7](https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/app/SharedPreferencesImpl.java), commit cambia memoria antes de intentar el disco; con un archivo existente y sin cambios nuevos, la escritura puede omitirse. Por ello, volver a guardar una clave idéntica después de un fallo no basta para confirmar durabilidad en esa versión. La lectura del código AOSP es una comprobación de implementación; no es un ensayo en teléfono.

Los diarios de regalos y retiros ahora incluyen una revisión de escritura diferente en el mismo Editor que guarda, elimina o restaura el intento. Así se evita esa omisión. Se conservan las claves UUID y su formato, las huellas de operación y la serialización de retiros anterior. La marca es interna, acotada a una entrada por archivo y no se envía al servidor.

El borrado de una clave de regalo con commit fallido también la eliminaba de memoria. Aunque el servidor ya hubiera confirmado, el reintento podía escoger una clave nueva y descontar de nuevo. Ahora se restaura la clave anterior antes de devolver el fallo. Si la restauración vuelve a informar fallo de disco, se conserva la identidad en memoria y el reintento sigue obligado a confirmar una escritura antes de enviar. La prueba exige una sola transacción del servidor ficticio.

Los retiros ya restauraban el intento anterior tras un borrado fallido; se conserva esa compensación y se fuerza también su escritura efectiva. El fixture distingue memoria y copia persistida y añade la regla antigua de omitir commits idénticos. No cambia el comportamiento de llamadas, grupos, notificaciones ni contratos del servidor propio.

## Evidencia

Carpeta externa: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit185-gift-storage/`.

- Referencia válida reference-linked: **31 casos, cinco fallos de aserción**. Incluye cuatro escenarios nuevos de fallo/recuperación de regalos y uno del diario de retiros, más sus controles existentes. Los cuatro snapshots productivos se comprobaron contra el padre184, `feature/live-gifts` `f0dd2b2ddb063ef951a18bd2f12618f93ab192a1`.
- Candidata válida candidate-linked: **31/31 casos JVM directos aprobados**, con entradas estables. Se compilaron repositorios, coordinador y diario reales con sus pruebas; el contexto/preferencias/API están controlados, no son cargos ni archivos de usuario productivos.
- Gradle Audit: **122/122 casos en13 clases, cero fallos/errores/omisiones**, BUILD SUCCESSFUL; Kotlin/Java/Compose/Hilt compilados en la misma selección de fuentes, sin entradas privadas ni HEAD modificados. Recibo terminal `gradle-storage-verified/result.json`, de12:20:21 a12:23:07 UTC. El verificador confirma también que las nueve huellas propias y los cambios ajenos quedaron intactos. Incluye todos los controles de184, compatibilidad del almacenamiento y las pruebas de reintento/límites de cuenta del repositorio de retiros.
- La primera ejecución Gradle compiló Kotlin/Java/Hilt y completó122 casos con un fallo: UncaughtExceptionsBeforeTest al iniciar CreatorPayoutOperationsTest. El XML registra una continuación cancelada en IO que intentó volver a Dispatchers.Main después de resetMain. Los cuatro fixtures ViewModel ahora esperan que termine su Job antes de restablecer Main; no se modificó el producto para ocultar el fallo. El recibo inicial gradle-storage se conserva y la segunda ejecución queda en gradle-storage-final:119 casos y un fallo de inicialización de WalletRefreshConfirmationTest, porque su nuevo @After infirió Unit? en lugar de void. Se corrigió a Unit explícito. La ejecución final usa gradle-storage-verified.
- El archivo AOSP descargado y su huella están en `shared-preferences-platform`; platform-proof.json registra URL, SHA256 y minSdk24. No se añade el código de Android al repositorio.
- La referencia inicial de regalos reprodujo cuatro fallos de18 casos y la candidata aprobó18/18. La primera ampliación a retiros mezcló un store recompilado con un caller de otro módulo, provocando NoSuchMethod; no se cuenta como fallo del producto. reference-linked/candidate-linked compilan ambos y son los recibos válidos para los31 casos.

Las preferencias son un modelo de memoria/disco controlado a partir de la rama AOSP; no se acreditan fallos físicos, reinicios de Android, almacenamiento de fabricantes ni todos los fallos combinados posibles. No hay APK nueva ni despliegue de servidor. La QA9.1.43/183 identificada en180 no incluye automáticamente los commits183–185.

## Publicación y continuidad

Se publican dos fuentes productivas, seis pruebas actualizadas (cuatro ajustan el cierre de los fixtures), una prueba nueva, este informe y cobertura del índice, con el mismo árbol en main y feature/live-gifts. Los nueve archivos propios se examinaron completos; eso no afirma que todas las funciones del repositorio estén validadas. Se conservan todas las modificaciones ajenas.

Siguen abiertos otros estados y recorridos del monedero, sincronización de saldo e historial, precio/valoración de regalos, mutaciones heredadas, demás módulos de la APK y ensayos físicos. Las comprobaciones de183/184/185 tienen alcance concreto; no constituyen el cierre de la auditoría integral.
