# Android: pausa de participación, borradores y requisitos omitidos

13 de noviembre de 2026, America/Bogota. Continúa la revisión móvil y del paquete `Novvin_Auditoria_Ecosistema`. Este bloque no cierra Android, la revisión web ni la preparación para marketing masivo. No se intervino en Ubuntu ni en datos productivos.

## Defectos reproducidos

| Hallazgo | Causa | Corrección y evidencia |
| --- | --- | --- |
| Participación después de pausar o cerrar el panel | Cancelar las tareas no invalidaba al propietario del panel. Una resolución de credenciales o respuesta que terminase pese a la cancelación podía despachar un envío, publicar datos o confirmar una creación antigua. `retry()` iniciaba otra lectura después del cierre. | El propietario activo se revoca al detener el panel, la confirmación local comparte una guardia con esa revocación y se comprueba cancelación antes/después de las esperas. Los reintentos pertenecen al panel y se cancelan. Cuatro regresiones fallaron antes y pasan con la corrección. |
| Reanudación y creación sin confirmar | Al detenerse se perdía la referencia de la tarea encargada de desuscribir. Un envío ignorado mientras había otro pendiente podía reemplazar su nonce. | Reabrir espera la limpieza de la suscripción anterior. La visita de cuenta y el nonce pendiente se conservan al pausar; otra cuenta o visita inicia un contexto nuevo. Una creación ignorada no reemplaza el nonce del envío sin confirmar. Dos pruebas adicionales verifican estos casos. |
| Borradores perdidos al restaurar la pantalla | La pestaña, el formulario, las preguntas, opciones, duración y respuestas utilizaban únicamente `remember`. | Se conserva el estado mínimo de formulario con `rememberSaveable`, dentro de las claves del directo, cuenta y pregunta correspondientes. El botón de herramientas conserva si el diálogo estaba abierto. Tres pruebas de restauración de estado fallaron primero en el emulador y pasan después. |
| Requisitos del paquete sin seguimiento | La matriz anterior recogía listas y tablas, pero omitía párrafos completos, incluidas las fases 10 y 12–17. | Se incorporan sesenta líneas de contenido o contexto. La matriz pasa de 297 a 357 entradas y conserva sus identificadores anteriores. Los requisitos nuevos siguen pendientes; el registro de una fuente no certifica su implementación. |

Los hallazgos del panel afectan a recuperación de estado y fiabilidad de envíos. No se presenta esta tanda como una demostración de acceso de terceros ni como una corrección de todos los límites de cuenta de LIVE.

La separación entre estado transitorio y persistente sigue la [documentación de estado de Compose](https://developer.android.com/develop/ui/compose/state-saving). La comprobación explícita de cancelación se apoya en el contrato de [`ensureActive`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html). La evidencia de este bloque corresponde a las implementaciones y pruebas del repositorio.

## Trazabilidad y continuidad

Se leyeron los siete documentos de entrada, incluido el Word, y el informe de avance. Se conserva el contenido original del paquete. La extracción de texto del Word permitió leer su mandato junto al Markdown; el comprobador automático controla su huella binaria, sin afirmar que valide semánticamente todas las instrucciones de un Word.

`tools/audit/check_requirement_traceability.py` comprueba las líneas no vacías de contenido de los seis Markdown de entrada, excluyendo títulos y cabeceras/separadores de tabla. Valida línea, texto, huella e identificadores de la matriz. Incluye párrafos y tablas, no solo viñetas. Antes informa sesenta líneas sin registrar y, después, ninguna. Admite el contenido equivalente en LF y las huellas CRLF históricas; una modificación del texto invalida la correspondencia. Sus ocho pruebas cubren omisiones, cambios de fuente, líneas inválidas, IDs duplicados, tablas, registros incompletos y equivalencia de finales de línea.

Se conectó ese control a los workflows de calidad Android y entrega productiva. Se comprobó su sintaxis y la ejecución local; no se afirma que GitHub Actions haya completado una ejecución remota. El [plan de implementación](../product/ECOSYSTEM_IMPLEMENTATION_PLAN.md) mantiene las 17 fases del mandato y el orden móvil, web e integración. La [trazabilidad](../product/REQUIREMENT_TRACEABILITY.csv) conserva `AND-01`, `LIVE-06` y la participación de LIVE en curso.

## Validación por entorno

| Control | Resultado y alcance |
| --- | --- |
| JVM focalizada antes | Ocho pruebas, cuatro fallos que reproducen respuestas, envíos o lecturas posteriores a la pausa. |
| JVM focalizada después | Diez pruebas, cero fallos, errores u omisiones. Incluye A → B → A, token de otra visita, nonce, pausa, retorno tardío y limpieza anterior a la reanudación. |
| Android JVM completo y Lint | 2 783 pruebas JVM en 524 suites, sin fallos, errores u omisiones. `assembleDebug`, `assembleDebugAndroidTest` y Lint K1 completados. Lint informa cero errores/fatales y 342 avisos que siguen abiertos. La ejecución combinada termina correctamente en 20 min 26 s. |
| Emulador API 35 | Cinco pruebas de componente aprobadas. Las tres nuevas fallaban antes: restaurar pregunta, encuesta con opciones/duración y respuesta del anfitrión. Se conservan publicación y borrador tras error/confirmación. |
| Herramientas Python | Ochenta pruebas aprobadas, incluidas las ocho del nuevo comprobador, con PyYAML 6.0.3 en un entorno aislado del laboratorio. |
| Shell y workflows | Los YAML se analizaron y sus nuevos pasos invocan el comprobador. Se resolvió explícitamente Bash para evitar que Windows ejecutase el acceso WSL de System32 en lugar del Git Bash seleccionado. Las pruebas del script de salud siguen ejecutándose, sin omitirlas ni cambiar sus criterios. |
| APK de laboratorio | Firma APK v2 de Android Debug verificada, cuatro clases de arranque presentes y paquete instalado en el emulador. |

Las pruebas de restauración utilizan `StateRestorationTester` sobre componentes Compose en Android. No simulan una muerte física del proceso, una instalación de producción ni el recorrido completo autenticado Android–web. El texto de formulario queda guardado; la idempotencia de una escritura pendiente después de muerte real del proceso requiere evidencia adicional y no se deduce de estos ensayos.

Los resultados, huellas de las fuentes probadas y límites se conservan en [127-resultados.json](../quality/evidence/127-resultados.json). Las incidencias de preparación del entorno se separan de los defectos del producto: fue necesario disponer de PyYAML, resolver Git Bash por ruta y citar correctamente la propiedad de Lint en PowerShell. Después se ejecutaron los controles completos sin desactivarlos.

## Paquete y publicación

El paquete ensayado es de depuración **9.1.40-debug / 180**, reconstruido con estas correcciones e instalado en el emulador. Su SHA-256 es `fcedf335beb3a5a1539ce1fe3b024862acde0ba90a5d02ada6443a920117d881`. No sustituye la APK histórica de producción ni el AAB firmado del propietario. La evidencia identifica los commits de partida, las huellas de los cuatro archivos Android cambiados y el paquete exacto: el nombre de versión compartido no implica que el APK anterior incluya esta corrección.

Las dos ramas estables deben conservar el mismo árbol al publicar. Comprobar con `git rev-parse 'main^{tree}' 'feature/live-gifts^{tree}'` y contrastar sus commits con `git ls-remote --heads origin main feature/live-gifts`. Los identificadores de los commits finales se obtienen de esas referencias; no se incorpora una huella autorreferente dentro del mismo commit.

Los cambios locales preexistentes de SEO/Vercel, los documentos de continuación 126 y los demás archivos ajenos se conservan. Las correcciones de este bloque corresponden a las ramas estables; no se modifica `correciones` ni se incorporan los experimentos del plan 105.

## Revisión abierta

Continuar con los límites de cuenta de invitaciones, puntos del canal y juego en `LiveRepository`, `LiveSessionViewModel`, `LiveChannelPointsRepository` y `LiveDrawGuessRepository`. La lectura identifica operaciones asíncronas que requieren ensayar renovación, cambio de visita, salida y respuestas atrasadas; no se declaran validadas por las pruebas de participación de este bloque. `LiveRepository` y `LiveDrawGuessViewModel` se han leído parcialmente en esta tanda. También quedan por ensayar la recuperación de la suscripción tras un fallo inicial y la conservación de borradores entre formularios cuando llega una confirmación de la otra pestaña.

Siguen abiertos el historial anterior de participación, los recorridos autenticados Android–web, SFU/TURN y revocación de medios físicos, redes independientes, notificaciones con la app cerrada, multimedia R2 externa, proveedor económico y recuperación operativa. La [cobertura por archivo](review-coverage.json), las [fases pendientes](../product/ECOSYSTEM_IMPLEMENTATION_PLAN.md) y la [decisión de salida](../operations/PRODUCTION_READINESS.md) mantienen ese alcance.
