# 148 · Acceso, recuperación y validación por módulo

Estado del 15 de noviembre de 2026: **revisión abierta**. Este documento organiza
la continuación solicitada por el propietario. No certifica el módulo de acceso,
el dispositivo completo, una APK final ni la preparación para marketing masivo.

Se corrigió el formulario de recuperación que quedaba ocupado al volver atrás
antes de iniciar una petición o una selección de archivos. La siguiente operación
puede comenzar y una respuesta cancelada no libera su estado de envío. La
selección JVM de esta corrección aprobó veinte casos en cinco clases.

## Alcance y criterio de cierre

El orden solicitado es acceso, recuperación y ayuda; después Mensajes, sus
notificaciones, cargas y precargas; después los demás módulos Android. La revisión
web e integración continúa tras el móvil. Cada corrección terminada se publica en
`main` y `feature/live-gifts` con árboles iguales; los experimentos 105 conservan su
rama exclusiva.

Un caso se considera comprobado únicamente para la huella, variante, dispositivo,
cuenta y red identificados por su evidencia. La lectura de código, una prueba JVM,
un ensayo físico y un despliegue son resultados distintos. El cierre de un módulo
requiere comprobar sus recorridos, errores y recuperación, corregir sus defectos
conocidos y conservar sus pendientes explícitos; no se deduce de que apruebe una
selección de pruebas.

Para seguridad se usan como guía los controles de autenticación, almacenamiento,
red y privacidad de [OWASP MASVS](https://mas.owasp.org/MASVS/03-Using_the_MASVS/).
Su uso como referencia no representa una certificación. Para arranque se separan
el primer dibujo (TTID) y la disponibilidad completa (TTFD), según la
[documentación Android](https://developer.android.com/topic/performance/issues/launch-time).
El primer cuadro del video, la fluidez del desplazamiento y las pausas por falta
de datos requieren mediciones propias.

## Acceso, recuperación y ayuda

| Caso | Comprobación necesaria | Estado en esta continuación |
| --- | --- | --- |
| A01 | Arranque en frío con sesión válida, vencida y ausente; destino y datos correctos | Pendiente de ensayo actual |
| A02 | Acceso válido, contraseña incorrecta, cuenta suspendida y segundo factor | Revisión de consumidores en curso |
| A03 | Volver atrás o cambiar de formulario antes y durante una petición | Cuatro casos del ViewModel aprobados; recorrido físico pendiente |
| A04 | Recuperación por correo, código, vencimiento, reenvío y respuesta perdida | Lectura parcial; entrega real del correo pendiente |
| A05 | Recreación de proceso y restauración del avance sin almacenar contraseñas | Almacén leído y cinco casos JVM de navegación aprobados; ensayo físico pendiente |
| A06 | Recuperación asistida, selección de archivos y confirmación del contacto | Cancelación del ViewModel comprobada; contrato y ensayos completos pendientes |
| A07 | Ayuda, quejas, directorio administrativo y apertura del chat de soporte | Centro de ayuda leído; configuración/servidor todavía parciales |
| A08 | Logout, entrada posterior y cambio A → B → A con respuestas demoradas | Pendiente de revisión completa de propiedad de sesión |
| A09 | Autorización, límites de intentos, archivos, errores y registros sin secretos | Pendiente de contraste completo con API y dispositivo |

`AuthViewModel`, `AccountTasks`, `AuthEntryOperation`,
`AuthenticationJourneyRestoration`, `EmailRecoveryDraftStore`,
`AuthNavigationRoutes`, `AuthRepository` y `HelpAndTermsScreens` fueron leídos
completos en esta continuación antes del parche. El ViewModel corregido y la
prueba nueva se examinaron completos de nuevo antes de integrar. La evidencia
distingue las huellas de los dos worktrees y comprueba su equivalencia al
normalizar finales de línea.
`AuthService`, `LoginScreen`, `SettingsViewModel`,
`SettingsScreen` y `LocalFallbackApiClient` tienen lectura parcial; no se les
atribuye revisión completa por haber localizado funciones o compilado.

H148-1: en la referencia 147 las operaciones asistidas activaban
`assistedRecoverySubmitting` antes de encolar su coroutine. `resetAuthState`,
`clearEmailRecovery` y el logout cancelaban el grupo de tareas sin liberar ese
estado. Si la tarea no llegaba a comenzar, su `finally` no se ejecutaba. Además,
los `finally` carecían de una identidad que los distinguiera de la siguiente
operación.

`AuthViewModelRecoveryOwnershipTest` ejecutó cuatro casos sobre el ViewModel real,
con transporte controlado y Robolectric API 34/JDK 21: tres fallos de aserción,
un control aprobado y cero errores u omisiones. Las 2.033 entradas congeladas y
HEAD permanecieron iguales durante esa ejecución. Los fallos comprobaron el
estado ocupado retenido y el rechazo de la siguiente confirmación. El recorrido
que contrasta específicamente el `finally` antiguo no pudo avanzar hasta la
segunda confirmación en la referencia; no se presenta como una reproducción
independiente anterior del finalizador tardío.

La corrección invalida la generación antes de cancelar, sustituye el grupo de
tareas y publica el estado libre. La selección de archivos, la solicitud y la
confirmación solo publican o liberan su propia operación. Al cancelar conserva
la selección y el identificador del caso. La repetición terminó con veinte casos
en cinco clases, cero fallos, errores u omisiones y las 2.033 entradas congeladas
sin cambios. Los cuatro casos nuevos aprobaron; ahora sí se ejecutó y aprobó el
control del finalizador antiguo frente a la confirmación nueva. Los otros
dieciséis casos comprueban cancelación y errores de entrada, validación de cambio
de contraseña y restauración de navegación. No prueban la entrega de correo ni
representan validación física.

La referencia anterior se ejecutó con
`:app:testDebugUnitTest --tests conectate.com.presentation.auth.AuthViewModelRecoveryOwnershipTest`.
La repetición añadió `AuthEntryOperationTest`, `AuthEntryFormCancellationTest`,
`PasswordChangeValidationTest` y `AuthenticationJourneyRestorationTest`. Ambas
usaron `--offline --max-workers=1 --no-daemon`, JDK 21 y HEAD 147 estable;
la segunda incluye el parche no publicado durante el ensayo. Las fuentes, los
XML y los logs tienen huellas en
`docs/quality/evidence/148-auth-recovery-ownership.json`. No se ejecutó otra
compilación ni emulador en paralelo y no se entregó una APK en este bloque.

## Mensajes y módulos posteriores

La revisión siguiente deberá contrastar la primera bandeja y su actualización,
último mensaje y autoría, historial paginado, envío/recepción, pendientes sin red,
reintento sin duplicados, adjuntos/stickers, grupos/comunidades y notificaciones.
Cada recorrido debe contemplar reinicio, cambio de red, permisos y cambio de
cuenta. Las comprobaciones históricas siguen identificadas por sus continuaciones;
esta lista no las convierte en casos actuales aprobados.

Perfil/Social, Videos, llamadas, LIVE y salas de voz conservan sus recorridos
pendientes. La preferencia del propietario por entrega directa desde Ubuntu se
contrasta con lecturas equivalentes desde el teléfono, la subida disponible y la
latencia. Una velocidad de descarga aislada no demuestra capacidad de entrega,
carga inmediata ni concurrencia máxima. Las mediciones 145 y la revisión 147 de
medios/infraestructura se registran por separado, sin cerrar este módulo.

Los requisitos originales `REQ-139` (registros sin secretos), `REQ-295`
(inventario y revisión de conexiones), `REQ-327` (infraestructura) y `REQ-329`
(SLI/SLO y capacidad sin cifras inventadas) permanecen vinculados a
`docs/product/REQUIREMENT_TRACEABILITY.csv`. No se cambia su estado global por
estas lecturas o por pruebas todavía no ejecutadas.
