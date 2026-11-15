# 157 — Recuperación confirmada y limpieza local

Estado: **90 casos JVM aprobados; revisión integral abierta**. La revisión móvil integral
y A03–A05 permanecen abiertos; este bloque no acredita preparación de marketing.

## Fallo reproducido y cambio previsto

Cuando `AuthRepository` devolvía un usuario o indicaba que la contraseña había
cambiado y faltaba segundo factor, `AuthViewModel` intentaba borrar el borrador
y descartaba el fallo local. Presentaba éxito o continuaba a segundo factor sin
explicar que el avance seguía guardado. Admitía además otra confirmación con el
mismo permiso y otras instancias del almacén podían restaurarlo.

La corrección conserva temporalmente el resultado clasificado por el repositorio,
sin contraseña ni permiso. Si falla la limpieza muestra «Tu contraseña ya cambió»
y dos acciones: reintentar exclusivamente el borrado o continuar reconociendo
el fallo. El aviso y sus acciones pertenecen a la instancia que los produjo;
no actúan sobre una forma nueva ni después de destruir su ViewModel. Durante
un reintento se deshabilitan ambas acciones.

La navegación automática de Login espera la transición de recuperación. Si hace
falta segundo factor, esa espera continúa hasta un acceso nuevo aprobado: editar
un formulario fallido no convierte una sesión anterior en una verificación nueva.
Los campos de contraseña y el permiso recordado por la pantalla se vacían cuando
aparece el aviso.
Esta espera corresponde al recorrido `confirmMigratedPasswordCode`; los otros
recorridos de migración y recuperación con códigos guardados siguen en revisión.

El almacén registra en memoria las huellas SHA-256 de los últimos 64 permisos
confirmados durante el proceso. No vuelve a presentarlos como borradores activos
y rechaza sus callbacks de confirmación retenidos; conserva los contratos y el
archivo cifrado existentes. Esa caché facilita la UI y no autoriza operaciones:
la invalidez real del permiso depende del servidor.

## Evidencia por tipo

| Selección JVM | Casos | Fallos de aserción | Errores / omisiones |
| --- | ---: | ---: | ---: |
| Referencia inicial sin parche | 8 | 6 | 0 / 0 |
| Referencia ampliada sin parche | 10 | 8 | 0 / 0 |
| Corrección y regresiones | 90 en 9 clases | 0 | 0 / 0 |

La primera referencia terminó `2026-11-15T19:44:25Z`. Los 2.046 archivos
enumerados permanecieron iguales. Main pasó de `2555f2c10c6e` a `049aee838cf0`
por un recibo documental de 153, sin delta Android/Gradle; el marcador Debug
real de esa ejecución fue `2555f2c10c6e`, sin override ni exclusiones.

La ampliación añade cancelación antes de una respuesta clasificada y cancelación
durante la limpieza posterior. Repite los ocho casos anteriores y hace explícito
el locale español; no se suman como dieciocho casos distintos. Después del parche
se añaden nueve controles adicionales, sin referencia roja separada, de reintento,
continuación, segundo factor, señal de sesión, repetición durante E/S, callbacks
retenidos, reemplazo por otro flujo y destrucción de pantalla.

La ampliación terminó `2026-11-15T20:03:18Z` con 10 casos, ocho fallos de aserción,
dos controles y ningún error u omisión. Conservó las 2.046 entradas y el marcador
real `049aee838cf0`. Antes del parche se incorporaron por separado Juegos158/159:
la comparación posterior encuentra siete rutas Android de Gaming y verifica que
Auth, navegación, los tres recursos y los privados del ensayo siguen iguales.
El recibo original no se reescribe. La reparación parte de `ba9cc10c61d7`, con las
2.050 entradas actuales, incluidas esas incorporaciones y el diálogo nuevo.

La repetición terminó `2026-11-15T22:42:46.939851+00:00` con 90 casos en nueve clases,
cero fallos, errores u omisiones, 19 casos propios y 71 regresiones. Las 2.050
entradas y cuatro huellas privadas permanecieron iguales. HEAD inicial
`ba9cc10c61d7`, final `ba9cc10c61d7` y marcador
Debug real `ba9cc10c61d7` se conservan en
[`157-confirmed-recovery-cleanup.json`](../quality/evidence/157-confirmed-recovery-cleanup.json),
con huellas de XML, logs, parche completo y snapshots. Se usó un índice privado
para capturar el candidato, sin añadir entradas a los índices compartidos. La publicación
se comprueba mediante un recibo independiente. No se generó ni instaló APK157.

`AuthViewModelConfirmedRecoveryCleanupTest` ejecuta ViewModel, almacén, JSON y
SharedPreferences reales de Robolectric API34. No se sustituye el almacén. Se
controlan la apertura criptográfica, `AuthRepository` y `LocalFallbackApiClient`;
no se ejecutan sus efectos reales de sesión o red. El commit de prueba puede
actualizar la preferencia y después notificar `false` o cancelación: ejecuta la
restauración real añadida en 156. Las pausas de E/S se esperan y liberan de forma
explícita; no representan un fallo físico de almacenamiento.

El control de anuncio de sesión usa una señal de repositorio controlada y el
colector real del ViewModel. No equivale a una navegación instrumental completa
ni a la ejecución del servicio/repositorio de producción. Las cuatro pruebas
nuevas del diálogo en `EmailRecoveryUiAuditTest` quedan preparadas para la
instrumentación de una APK nueva; no se contabilizan como pruebas aprobadas aquí.

Se registran las huellas de fuentes/configuración seleccionadas, cuatro archivos
privados y el agregado del entorno suministrado. No se publican sus valores y
no se afirma que todos los binarios externos estén congelados.

## Contrato del servidor y referencia primaria

La lectura del endpoint `/v1/auth/password-reset/confirm` en
`infrastructure/local-fallback/api/src/server.js` encuentra selección de permiso
vigente/no consumido, bloqueo de filas, cambio de contraseña, `consumed_at` y
revocación de sesiones dentro de la transacción. El COMMIT precede a la respuesta
o a la petición de segundo factor. No se modificó ni se volvió a ejecutar ese
contrato contra cuentas reales en este bloque.

OWASP recomienda que los permisos de recuperación tengan vencimiento y sean de
un solo uso. La UI evita reenviar un permiso ya confirmado; esa defensa local
no sustituye la transacción del servidor ni constituye una certificación MASVS
o ASVS. [OWASP: Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

## Pendientes que siguen abiertos

Continuar después del aviso puede dejar el avance anterior en las preferencias:
reconocer el fallo no equivale a borrado durable. La supresión de 64 huellas no
persiste después de morir el proceso. Si el dispositivo no puede guardar, no se
promete eliminar bytes ni restaurar un recibo durable; hay que comprobar reinicio,
vencimiento y nueva entrada en dispositivo real. Los permisos siguen sujetos al
uso único del servidor. Esta limitación no cierra A05.

Este bloque parte de respuestas ya clasificadas como éxito o segundo factor por
`AuthRepository`. Siguen pendientes los fallos intermedios después del ACK en
`AuthService`/`AuthRepository` y los que ocurran en API después del COMMIT antes de
devolver una respuesta utilizable. No se deduce que una excepción en esas capas
signifique que la contraseña no cambió. También siguen pendientes confirmación
perdida, Keystore, SMTP, entrega real del correo y navegación física completa.

La QA145 física 9.1.40/180 contiene código148 y no incorpora 152, 155, 156 ni 157.
No se genera ni instala una APK nueva en la selección JVM. Mensajes, Videos,
llamadas, LIVE, salas de voz, seguridad integral y las restantes entradas de la
trazabilidad conservan sus recorridos pendientes. Los cambios de Juegos del otro
asistente y los experimentos 105 se mantienen fuera de este bloque.
