# 156 — Recuperación compartida y escrituras tardías

Estado: **71 casos JVM aprobados; revisión integral abierta**. Continúa la revisión móvil de A03–A05;
no cierra acceso, los demás módulos ni la preparación para marketing masivo.

## Problema y comportamiento corregido

Dos instancias de `AuthViewModel` podían modificar el mismo borrador cifrado con
mutex independientes. Un borrado, un código o un permiso de recuperación
anteriores podían terminar después y eliminar o reemplazar el avance posterior.
La lectura de un borrador vencido también podía borrar su reemplazo válido.
Además, un reemplazo que notificaba fallo podía haber cambiado las preferencias
en memoria y el tratamiento del error inicial eliminaba el predecesor.

El almacén comparte ahora la propiedad de escritura dentro del proceso, por
contexto de aplicación. La pantalla captura su visita antes de poner trabajo en
cola; la escritura comprueba esa visita dentro de la exclusión compartida.
Crear una pantalla lectora no retira a la vigente. Una acción explícita de una
nueva pantalla reclama la escritura y cancela las tareas de correo anteriores,
sin cancelar por ese motivo otras tareas de acceso o recuperación asistida.

Un Atrás nuevo desde una pantalla ya obsoleta puede cerrarla localmente, pero
no reclama ni borra el borrador vigente. Los callbacks de operaciones antiguas
siguen descartados. La limpieza por vencimiento vuelve a leer bajo el bloqueo
de escritura y conserva el reemplazo válido. Si `commit()` falla, se intenta
restaurar el valor previo dentro de ese mismo bloqueo. El fallo local anterior
al envío se presenta como almacenamiento del dispositivo; no se solicita correo
ni se elimina el avance anterior.

Se conserva el esquema cifrado, el archivo de preferencias y sus campos. No se
guardan contraseñas. Los métodos públicos independientes `save` y `clear`
siguen disponibles como intenciones explícitas; los escritores asíncronos de
producción utilizan `saveForVisit` y `clearForVisit`. Se examinaron sus consumidores
en `AuthNavigationRoutes` y el ensayo `EmailRecoveryUiAuditTest` antes de conservar
ese contrato. La búsqueda de consumidores no se presenta como lectura completa
de todas las pantallas.

## Reproducción y verificación

| Selección | Casos | Fallos de aserción | Errores / omisiones |
| --- | ---: | ---: | ---: |
| Primera referencia sin parche | 8 | 4 | 0 / 0 |
| Referencia ampliada sin parche | 10 | 6 | 0 / 0 |
| Corrección y regresiones | 71 en 8 clases | 0 | 0 / 0 |

La ampliación repite los ocho casos iniciales: son diez casos distintos, no
dieciocho. Los seis fallos reproducen borrado, código y permiso anteriores;
limpieza de vencimiento; `commit(false)` después de actualizar la preferencia;
y fallo local antes de enviar que debe conservar el predecesor.

`EmailRecoverySharedStoreRaceTest` ejecuta el almacén, el JSON y las preferencias
reales de Robolectric API34 con dos ViewModels reales. Se controla la apertura
criptográfica y se sustituyen `AuthRepository` y `LocalFallbackApiClient`; sus
efectos reales sobre la sesión y el servidor no se ejecutan en este ensayo.
Las pausas detienen la entrada al método
y después ejecutan `callOriginal()`: las comprobaciones y escrituras del almacén
se ejecutan realmente. El editor controlado permite que el commit real actualice
la preferencia y luego notifique `false` una vez; no simula un fallo físico de disco.
Los datos usan correos `.invalid` y preferencias de prueba independientes.

Después del parche se añaden tres controles sin referencia roja separada:
reabrir el mismo flujo y guardar un código posterior; impedir que un guardado
anterior resucite el flujo después de Atrás; y cerrar una pantalla obsoleta sin
retirar a la actual. La selección final aprobó 13 casos de almacén y
58 regresiones en otras siete clases. Los 24 casos de persistencia de 155 cambian
su adaptador al nuevo contrato de visita; dos pausas de fallo inicial se sitúan
ahora en la escritura inicial, porque ese fallo ya no intenta borrar el predecesor.

La repetición terminó `2026-11-15T19:19:53.380606+00:00` con 71 casos en ocho clases,
cero fallos, errores u omisiones y los 2.045 archivos enumerados sin cambios.
HEAD inicial `eab999e1d8d8`, final `eab999e1d8d8`
y marcador Debug real `eab999e1d8d8` se conservan en
[`156-email-recovery-shared-store.json`](../quality/evidence/156-email-recovery-shared-store.json),
con las huellas de XML, logs, parche y fuentes. La publicación en ambas ramas se
comprueba con un recibo separado. No se ha generado ni instalado una APK con 156.

Cada tanda registra los 2.045 archivos enumerados, cuatro huellas privadas
(`local.properties`, `keystore.properties`, `secrets.properties` y
`app/google-services.json`), la huella agregada del entorno suministrado y el
marcador Debug generado sin override. No se guardan valores privados en la
evidencia publicada. No se afirma que todos los binarios externos del entorno
de compilación estén congelados.

## Criterios contrastados con documentación primaria

Android documenta que los cambios de SharedPreferences pueden reflejarse en
memoria antes de quedar persistidos y que el booleano de `commit()` no da detalles
del fallo. Esa limitación justifica comprobar el resultado y conservar el
predecesor; la restauración de este parche es un intento comprobado, no una
garantía frente a fallos repetidos de disco. Se conserva el almacenamiento
cifrado existente; una migración a otro almacén requerirá revisar sus consumidores
y su recuperación. [Android: SharedPreferences](https://developer.android.com/reference/android/content/SharedPreferences),
[Editor.commit](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()).

Kotlin documenta la seguridad concurrente de StateFlow. Aquí se utiliza para
avisar del cambio de visita; la admisión y las mutaciones del almacén conservan
sus propios bloqueos, porque notificar un estado no hace atómica una escritura
de preferencias. [Kotlin: StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/).

## Límites y siguientes recorridos

La propiedad es local a un proceso y un contexto de aplicación; no es un
protocolo de escritura entre procesos. No se ensayan Keystore real, reinicio de
proceso, disco físico, SMTP, entrega a la bandeja de entrada ni navegación física.
La restauración puede fallar de nuevo y ese error se conserva como excepción
suprimida: no se promete recuperar bytes que el dispositivo no pudo persistir.

Sigue abierta la limpieza que falla después de una contraseña ya confirmada por
el servidor, incluida la continuación a segundo factor; un fallo local no puede
deshacer ese cambio remoto. El módulo completo de acceso/ayuda, los recorridos
de Mensajes, las llamadas, LIVE, salas y seguridad integral siguen abiertos.
La QA145 física 9.1.40/180 contiene código148 y no incluye 152, 155 ni 156.
No se cambia R2, el servidor, sus paquetes ni la configuración de red en este bloque.

Las lecturas se registran por huella en `review-coverage.json`; lectura de código,
pruebas JVM, ensayo físico y despliegue son evidencias diferentes. El inventario
de requisitos sigue en 387 entradas y no se declara íntegramente implementado.
