# Auditoría incremental de correcciones-dot — 10 de noviembre de 2026

## Alcance y estado

Esta tanda parte de `7f25192bad3eb9f2fd8297e84909e101886bfde3`. La autorización actual limita las publicaciones a `correcciones-dot`; no se actualizan las ramas estables ni se despliega en producción. Conserva las correcciones previas. Es una auditoría incremental, no el cierre integral de Android, web o backend.

## Defectos reproducidos y correcciones

- **P2, iconos del menú web:** Ayuda usaba Ajustes, reportes usaban Notificaciones, llamadas usaban cámara, personas cercanas usaban el globo de Social y otros destinos repetían símbolos ajenos. Se separó `SecondaryNavigation` y se asociaron once rutas con iconos explícitos; siete cambian. Se mantienen nombres accesibles, rutas y cierre al seleccionar. Commit `f5fe52312a5a783072316c3f5e702ad46aa02320`.
- **P3, sección activa incorrecta:** `primarySection` consideraba `/app/messages-other` y `/app/messagesarchive` parte de Mensajes. Se exige el límite de segmento, como en los demás apartados. Commit `3a6cfffc6f7cc9b665f8e52fb1e65b26b537d031`.
- **P1, resultados antiguos en solicitudes de amistad:** aceptar una solicitud y cambiar a Enviadas antes de su respuesta volvía a cargar Recibidas dentro de la pestaña Enviadas, incluso enlazando al perfil equivocado. Cambiar de cuenta conservaba el bloqueo de la acción anterior. Se invalidan los resultados de una vista anterior al cambiar de contexto o desmontar, se conserva el bloqueo de escritura de cada cuenta entre pestañas, se impide el envío duplicado de la acción vigente y se recarga solo la vista actual de la misma cuenta. Las pruebas controlan respuestas pendientes, cambios de pestaña y cuenta, una operación nueva y reintento tras error. Commit `11902007b225cb7b0f1b024e966680136fc63b87`. Esto valida el estado de la pantalla; no certifica por sí solo autorización del servidor.

## Evidencia local

- Regresión inicial de navegación: nueve fallos esperados antes de corregir (siete símbolos y dos rutas); después 34/34 comprobaciones focalizadas aprobadas.
- Regresión de amistad: fallos reproducidos de perfil incorrecto en Enviadas y botón bloqueado tras cambiar de cuenta; la revisión añadió ida/vuelta de pestañas y cuentas, el contrato legado Supabase, desmontaje y recarga de estado confirmado. Se conserva el bloqueo de escritura por cuenta entre vistas y se actualiza únicamente la vista vigente.
- Batería final después de la revisión: **183 suites Jest, 1.067 pruebas aprobadas**, incluidas nueve de solicitudes de amistad. Lint, build aislado y 106 pruebas Node se repitieron satisfactoriamente sobre este código.
- Primera batería completa: 181 suites Jest, 1.058 pruebas aprobadas.
- TypeScript `tsc -b`: aprobado.
- ESLint: cero errores, siete avisos de dependencias/limpieza de efectos en LIVE, Momentos y solicitudes. No se silencian ni se presentan como defectos demostrados por el aviso solo.
- `npm run build`: aprobado con `VITE_FALLBACK_API_URL=https://ci.novvin.invalid`, el origen aislado que ya usa el workflow Android. El candidato **no sirve para producción**. Puerta SEO: 19 páginas, JavaScript público cero, controles de metadatos, CSP, rutas, assets y privacidad aprobados.
- `npm run test:node`: 106/106 aprobadas después del build. El intento anterior sin `dist` encontró archivos ausentes; no se contabiliza como aprobado.
- La compilación sin variable de API falla explícitamente. No se añadió una dirección supuesta ni se alteró la configuración real.
- La instalación inicial de dependencias no pudo crear la caché de npm fuera del espacio de trabajo. Se completó con caché temporal explícita. No cambia el lockfile.

## Móvil e integración: cobertura limitada

Se leyeron los resolutores de notificaciones de conversación, retorno de llamada, enlaces de salas y enlaces de contenido compartido, y la prueba de destino de notificaciones. Se contrastaron tipos y destinos de navegación; no se identificó en esa lectura un defecto nuevo confirmado que justifique cambiar Android. No se afirma que Android esté libre de errores.

En este entorno no se encontró SDK, adb ni emulador Android. No se ejecutaron pruebas instrumentadas, pruebas JVM Android ni un build APK/AAB local. La evidencia histórica de las revisiones 101–113 sigue siendo histórica y no se reutiliza como aprobación de esta tanda.

## Prioridad siguiente y criterios observables

Estos son criterios técnicos de auditoría propuestos, no funciones nuevas aprobadas ni resultados ya comprobados:

1. **P1, identidad y permisos:** entrada, renovación y salida de cuenta; A→B con cargas y acciones pendientes. Ningún dato/error/acuse de A debe sobrescribir B. Comprobar también el servidor, no solo componentes simulados.
2. **P1, amistad y mensajería:** descubrir una persona, invitar, aceptar/rechazar, abrir conversación, enviar texto/archivo, perder y recuperar red. Conservar borradores, evitar duplicados y verificar destinatario y confirmación real en ambos clientes.
3. **P1, privacidad y moderación:** bloquear/reportar, contenido privado y enlaces compartidos. Verificar permisos con cuentas de prueba separadas y registrar denegación, no inferirla de ocultar botones.
4. **P2, publicación y reproducción:** crear/editar contenido, fallo de subida, reintento, regresar y cambiar pestaña. Conservar el trabajo y medir tiempos reales con red/dispositivo identificados. El editor web no acredita paridad completa con Android.
5. **P2, accesibilidad y apariencia:** comprobar iconos renderizados, foco, Escape/Tab, zoom, contraste, temas y anchos móviles/escritorio en navegador. Las pruebas DOM no sustituyen comprobación visual.
6. **P2, capacidad y mantenimiento:** investigar el chunk de más de 1 MB señalado por Vite antes de cambiar límites; medir transferencia y carga en lugar de prometer fluidez. Separar deuda de efectos React de errores funcionales reproducidos.

La web ya dispone de modo claro/oscuro/sistema, acentos y escala de texto (`core/webPreferences.ts`); el siguiente trabajo es verificar sus recorridos y usabilidad, no duplicar esas opciones. La audiencia inicial 18–30 años aún no define un nicho: no se justifica añadir funciones masivas sin demostrar que los recorridos básicos funcionan.

## Límites de la primera entrega web

No se usaron cuentas reales para enviar invitaciones/mensajes, hacer pagos ni modificar datos. El bloqueo es de esta instancia de pantalla: la idempotencia y las transiciones de estado atómicas del servidor frente a múltiples pestañas/dispositivos siguen pendientes de auditoría. No se cambió el servidor, no se publicó una web y no se generó APK nueva. Publicar commits en esta rama no equivale a desplegar ni aprobar CI. El estado de GitHub Actions debe consultarse para el SHA entregado.

## Continuación: decisión de amistad en la API nativa

Se reprodujo contra el handler real y SQL de PostgreSQL embebido que un segundo PATCH contrario recibía 200 y reemplazaba una decisión ya confirmada. El candidato ahora permite `pending → accepted/rejected` mediante un UPDATE condicional atómico. Repetir la misma decisión devuelve 200 con la fila existente **sin reescribirla**; una decisión contradictoria devuelve 409 `FRIEND_REQUEST_ALREADY_RESOLVED`. El receptor ajeno y el ID inexistente siguen recibiendo 404, también después de resolverse la solicitud. Se mantienen el formato de éxito y las rutas, sin migración.

La regresión nueva usa PGlite 0.5.16 como dependencia de desarrollo fijada en el lockfile y se ejecuta por defecto con `npm test`. Prueba decisiones en ambos sentidos, reintentos sin cambio de versión de fila, handlers simultáneos, entrada inválida y autorización del receptor. Puede ejecutarse con PostgreSQL local externo solo en la base aislada `novvin_friend_request_test`, usando `FRIEND_REQUEST_TEST_DATABASE_URL`.

**Límite importante:** PGlite y la modalidad externa de esta prueba usan una sola sesión. Los handlers se intercalan, pero esto no mide contención entre dos conexiones/servidores PostgreSQL ni latencias de producción. Esa comprobación queda pendiente. No había PostgreSQL nativo ni Docker disponible en este entorno.

La suite de API terminó con 1.064 pruebas aprobadas, 16 omitidas y cero fallos; `npm run check` y la validación en seco del lockfile también pasaron. El primer intento tuvo un fallo de preparación en `production-config.test.js`: faltaban las dependencias del proyecto vecino `signaling-server`. Tras instalarlas desde su lockfile, la suite pasó sin alterar esa prueba.

Esta primera corrección cubre el PATCH nativo. Las rutas PostgREST y DELETE Android se revisan por separado en la continuación siguiente. No se desplegó el código de API ni se modificaron datos reales.

## Continuación de cancelaciones y compatibilidad PostgREST

El DELETE nativo de solicitud por ID permite borrar únicamente `pending` o `rejected`, tanto al emisor como al receptor. Una aceptación confirmada devuelve 409 y no se elimina mediante una cancelación/rechazo tardío. Se conserva la limpieza de rechazadas usada por clientes existentes; repetir un DELETE exitoso devuelve 404. Eliminar una amistad deliberadamente sigue disponible en la ruta explícita por usuario, sin cambio contractual. Se añadieron pruebas de participantes, actor ajeno, estados, unfriend explícito y la intercalación PATCH/DELETE.

La respuesta de amistad por PostgREST conserva el origen nativo de la lectura en metadatos internos. Si la solicitud era nativa, actualiza únicamente su ID y pareja mientras siga pendiente: una eliminación intercalada no puede recrearla ni responder una solicitud nueva de la misma pareja. Repetir una decisión confirmada no reescribe la fila; la contraria devuelve 409. Para filas importadas, el UPSERT solo puede actualizar una pareja nativa todavía pendiente, y se consulta la decisión nativa antes de acusar éxito sobre un importado resuelto.

Las siete regresiones nuevas de compatibilidad ejecutan el handler real y SQL con PGlite. Incluyen importaciones obsoletas, autorización, reintentos sin cambio de versión y una eliminación inyectada exactamente después de la lectura nativa. Esta última prueba falló antes de separar UPDATE nativo y materialización importada. La batería final de API pasa **1.071 pruebas, con 16 omitidas y cero fallos**; `npm run check` y `git diff --check` también pasan. La revisión independiente del diff final no halló bloqueantes.

**Pendiente P1, cancelación importada:** el DELETE genérico de compatibilidad no deja una marca persistente de eliminación para `supabase_import.friends`. Una fila importada `pending` puede seguir visible y materializarse otra vez después de eliminar su copia nativa. Esta entrega no resuelve esa cancelación ni afirma que todas las rutas de amistad estén cerradas. Se necesita diseñar la marca de eliminación y su relación con nuevas solicitudes, probar permisos y lecturas, y revisar migración/retención antes de desplegar.

Todas estas pruebas locales siguen usando una sola sesión: reproducen intercalaciones, no contención PostgreSQL entre conexiones independientes. No se probó Android en dispositivo/emulador ni se generó APK; tampoco se desplegó API o web ni se tocaron datos reales. Los commits y el estado de CI son evidencias distintas.

## Seguimiento de CI y dependencia XML

El job backend-contracts del commit `897fbdf5` terminó en rojo por `npm audit --omit=dev --audit-level=low`, después de pasar los scripts de contratos. El aviso [GHSA-474x-hrwr-fgx4](https://github.com/advisories/GHSA-474x-hrwr-fgx4), revisado el 10 de noviembre, afecta fast-xml-parser hasta 5.11.2 y señala 5.12.0 como corregida. Es una dependencia transitiva de Firebase Admin mediante Google Cloud Storage. La vulnerabilidad descrita requiere la opción no predeterminada stopNodes; no se ha demostrado su explotación en Novvin.

Se actualizó el lockfile a 5.12.0 con sus cuatro dependencias necesarias, conservando el rango del consumidor y los metadatos de plataformas. Instalación limpia aprobada; auditoría de producción: cero vulnerabilidades. Comprobación sintáctica y suite: 1.071 aprobadas, 16 omitidas, cero fallos. No se desactivó la auditoría ni se rebajó su umbral.

La inspección de `android-ci.yml` identificó que su job backend-contracts recorre scripts `test:*`, no `npm test`. `service-ci.yml` ya contempla `npm test` con descubrimiento general. Se añadió `test:friendship` para ejecutar también en backend-contracts las dos pruebas SQL nuevas; su ejecución local pasa ocho casos. También pasó localmente el bucle completo de scripts `test:*` del job. Hasta que se complete el nuevo run no se declara CI verde. El job web/client del run anterior sí pasó; Android seguía ejecutándose al registrar esta evidencia.

## Viabilidad visual y Android

La vista previa local pudo iniciarse, pero el navegador de esta sesión no pudo conectarse a ella (`ERR_CONNECTION_REFUSED`), incluso tras iniciar el servidor fuera del aislamiento. Se detuvieron ambos procesos de vista previa. No hay captura visual comprobada de esta tanda ni se publicó un sitio para resolver el acceso.

El workflow de calidad Android contiene pruebas JVM y lint mediante `qualityGateDebug`, además de ensamblado y auditorías. El workflow instrumentado separa tres pruebas de cuentas en `correcciones-dot`, requiere despacho manual y trata el acceso temporal KVM como autorización independiente. No se modificaron permisos ni se lanzó esa ejecución. La falta de SDK/emulador local sigue impidiendo afirmar validación de dispositivo.

La investigación posterior reprodujo el P1 importado incluso secuencialmente: DELETE de importado pending seguido de PATCH accepted crea una fila nativa nueva. La revisión del diseño detectó lectores adicionales de permisos (`fallback_request_pair_trusted` y friendsOnly) y escritores de consentimiento de chat. Por ello no se publicó una marca parcial que solo ocultara la solicitud. El diseño exige vínculo por origen/ID histórico, supervivencia al borrado nativo, transacciones coordinadas y pruebas con dos conexiones reales, sin bloquear invitaciones nuevas de la misma pareja.
