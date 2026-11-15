# 152 · Cuentas guardadas y salida de Ajustes

Estado del 15 de noviembre de 2026: **selección JVM final aprobada; revisión integral abierta**. Continúa el
caso A08 del [plan de acceso 148](148-acceso-recuperacion-y-validacion-por-modulo-2026-11-15.md).
No cierra A08, el módulo de acceso ni la revisión móvil integral.

Contribuye a `AND-01`, `UX-19` y al inventario de conexiones de `REQ-295` en
`docs/product/REQUIREMENT_TRACEABILITY.csv`. Sus estados globales permanecen
abiertos; esta selección no valida todos sus criterios.

## Problema y comportamiento corregido

Guardar la cuenta o preparar «Añadir otra cuenta» usaba tareas del ViewModel que
podían sobrevivir al cambio de pantalla o de sesión. Una tarea en cola podía
guardar la cuenta que reemplazó a la que pulsó el botón; una respuesta anterior
podía limpiar el modo invitado, mostrar un aviso o abrir el acceso de otra cuenta
después de salir. La preparación también retenía su bloqueo de pulsaciones al
cancelarse antes de comenzar.

La corrección separa esas tareas del resto del acceso y captura la visita de
sesión existente al pulsar el botón. Invalida su generación antes de cancelar,
libera el estado ocupado y comprueba la visita antes y después de la operación.
Volver A → B → A no recupera el permiso de una operación anterior. El finalizador
antiguo tampoco libera una preparación nueva.

Ajustes identifica además su propia composición con un token. Al detenerse o
salir cancela únicamente las operaciones de esa pantalla. La limpieza tardía de
un Ajustes anterior conserva la preparación o el guardado del Ajustes nuevo.
El callback solo navega si continúan vigentes las entradas de los dos
controladores y la pantalla sigue al menos en `STARTED`.

La cancelación propagada por el repositorio libera la preparación y retira su
mensaje «Guardando…» pendiente. Conserva tanto un error normal como el aviso de
otro guardado actual. No borra cuentas duraderas ni cierra la sesión por salir de
Ajustes. `AuthRepository`, `AuthService` y sus contratos no cambian en este bloque.

El recorrido consultado es `AuthViewModel` →
`AuthRepository.rememberCurrentAccount` →
`AccountSessionRepository.captureCurrentAccount`. El guardado obtiene las
credenciales, confirma su cuenta/token antes de escribir y usa el almacén
cifrado existente. No necesita consultar el perfil para abrir «Añadir otra
cuenta». La lectura de estas funciones no prueba todos los fallos de Keystore,
la renovación del token ni los demás consumidores del repositorio.

Se aplica la guía de [cancelación de Kotlin](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
para propagar la cancelación y finalizar el trabajo propio, y las
[reglas de ciclo de vida de Compose](https://developer.android.com/topic/libraries/architecture/lifecycle)
para limpiar al detenerse o salir. La identidad de pantalla y sesión es una
decisión de implementación contrastada con este código; esas guías no certifican
su corrección ni sustituyen las pruebas.

## Evidencia separada

Las referencias anteriores al parche ejecutaron el ViewModel real, con
Robolectric API 34/JDK 21 y repositorios demorados de forma controlada. Las visitas
de cuenta de los tres casos adicionales usan `AuthSessionMutationGuard` real.

| Ejecución | Casos | Fallos | Alcance |
| --- | ---: | ---: | --- |
| Referencia inicial | 6 | 4 | Cancelación en cola, logout, feedback y controles de éxito/error |
| Referencia de propietario | 3 | 3 | A → B, A → B → A y guardado de otra cuenta |
| Primera corrección | 30 en 6 clases | 0 | Diez casos de cuentas y veinte regresiones de acceso/navegación |
| Corrección final | 34 en 6 clases | 0 | Catorce casos de cuentas y veinte regresiones |

El caso del finalizador antiguo no pudo admitir la segunda preparación en la
referencia inicial: falló antes de comparar los finalizadores. Su recorrido
completo aprobó después de la primera corrección; no se presenta como una
reproducción independiente anterior de ese finalizador.

El décimo caso comprueba la cancelación explícita de la visita de Ajustes. Los
cuatro casos finales añaden cancelación del repositorio, conservación de otro
feedback y limpieza de pantalla antigua frente a preparación/guardado nuevos.
Estos cinco casos se añadieron después de las referencias; sus escenarios se
derivaron de lectura de código. No se ejecutó la referencia intermedia separada
que se había preparado para el undécimo caso.

Cada tanda congela 2.040 fuentes/configuraciones Android y registra aparte las
huellas privadas, el HEAD inicial/final y el marcador realmente generado. Los
avances web/documentales de HEAD durante las primeras tandas no se atribuyen a
otra compilación Android: se comprobó el diff comprometido y las fuentes.
Las dos selecciones con parche usan `:app:testDebugUnitTest` con seis filtros
`--tests`; las referencias filtran una clase cada una. Todas usan `--offline`,
`--max-workers=1` y `--no-daemon`, sin excluir tareas ni imponer un marcador
antiguo. No hubo emulador o ensayo ADB paralelo durante estas tandas.

La selección final terminó con cero errores u omisiones y las 2.040 entradas
congeladas sin cambios. HEAD inicial `5b22040b483a` y final
`5b22040b483a`; marcador Debug realmente generado
`5b22040b483a`. El recibo conserva los tiempos UTC y las
huellas de fuentes, logs, XML y parche en
[`152-account-entry-ownership.json`](../quality/evidence/152-account-entry-ownership.json).
La publicación Git en ambas ramas se verifica mediante un recibo separado; no es
un despliegue móvil ni una APK instalada.

## Cobertura y pendientes

`AuthViewModel`, su prueba de cuentas, `AccountTasks`, `AuthEntryOperation` y
`AuthSessionMutationGuard` se examinaron completos. Ajustes, sus dos consumidores
de navegación y las funciones consultadas de `AuthRepository` y
`AccountSessionRepository` tienen lectura parcial
en esta continuación. No se les atribuye lectura completa por buscar o compilar.

La prueba del ViewModel invoca la cancelación de pantalla explícitamente: no es
un ensayo instrumental del despacho de `ON_STOP`, de las animaciones de
navegación ni del cambio de cuenta físico. Tampoco prueba atomicidad entre una
comprobación de visita y todas las mutaciones concurrentes del backend o de
medios; esa barrera global permanece abierta en 141. La salud de sesión inicial,
los demás callbacks de acceso, la entrega real del correo, ayuda/administración,
el reinicio de proceso y la seguridad completa de A01–A09 siguen pendientes.

No se generó ni instaló una APK con 152. La QA 9.1.40/180 ensayada en
[145](145-medicion-movil-avatares-y-rutas-de-video-2026-11-15.md) contiene el código
Android de 148 y no incluye estas correcciones. Tampoco cambia la política de
R2/Ubuntu ni demuestra reproducción sostenida o fluidez de desplazamiento.
El mantenimiento administrativo del host sigue identificado por el
[plan vigente 154](154-plan-host-vigente-tras-api153-2026-11-15.md).
