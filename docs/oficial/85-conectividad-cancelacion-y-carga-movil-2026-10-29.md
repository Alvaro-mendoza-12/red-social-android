# 85 — Conectividad, cancelación y carga del cliente móvil

Fecha: 29/10/2026, America/Bogota. Continúa la [auditoría integral 84](84-auditoria-integral-movil-2026-10-29.md). Bases idénticas en contenido: `main` en `4b91db8b` y `feature/live-gifts` en `1f206f57`. Alcance: Android y los contratos de servidor que usa Android. No se modifica el frontend web ni se publica en producción.

## Correcciones verificadas

1. **Cancelación en los reintentos de compatibilidad de grupos/comunidades.** Los helpers comunes de esos caminos capturaban `CancellationException` como un error normal. Podían clasificarla como fallo de red, marcar un mensaje fallido o confirmar un resultado después de cancelar la tarea. Se propaga la cancelación y se comprueba que la coroutine siga activa antes de enviar, después de recibir y antes de procesar errores. La cancelación conserva el mensaje pendiente; no equivale a deshacer una escritura ya confirmada por el servidor. Ocho regresiones cubren cancelación lanzada/devuelta dentro de `Result`, resultados tardíos y éxito/fallo ordinarios. **Seis fallan con el código anterior; las ocho pasan con la corrección.** El envío Ubuntu principal ya tenía otro camino con sus guardas de sesión; no se atribuye ese fallo del helper a todos los envíos nativos.
2. **Esperas DNS repetidas al perder conectividad.** La resolución pública del hostname Tailscale serializaba los intentos, pero sólo compartía resultados exitosos. Tras un fallo, cada solicitud en espera podía repetir el plazo completo. `BackendAddressCache` comparte también el fallo durante cinco segundos, conserva la vigencia positiva de cinco minutos y el límite de respuesta antigua de quince minutos adicionales. Las interrupciones no generan una entrada negativa. Seis regresiones verifican concurrencia de ocho solicitudes, recuperación, renovación, respuesta antigua, vencimiento e interrupción. Se conservan los filtros de direcciones públicas y la validación TLS.
3. **Coste excesivo de perfiles importados al abrir bandejas y feeds.** `importedRowsByFieldValues` convertía la fila completa a JSON por cada alias de identidad. Para perfiles con varios alias, una CTE `MATERIALIZED` convierte cada fila una vez y conserva los filtros parametrizados, columnas históricas ausentes, límite, normalización y autorización del llamador. No incorpora una caché compartida de perfiles ni requiere migración. La integración real en PostgreSQL compara diez consultas anteriores/nuevas con datos sintéticos y comprueba el plan CTE.
4. **Borrado de mensajes de grupos/comunidades con sesión vencida o respuestas tardías.** El camino Ubuntu ocultaba el mensaje antes de obtener credenciales y podía dejarlo oculto si no había token; al fallar restauraba una copia completa antigua sin comprobar cuenta ni actualizaciones concurrentes. El camino de compatibilidad llegaba a devolver éxito aunque fallara el servidor. Ambos usan ahora `CollectionMessageDeletion`: credenciales antes de ocultar, sesión capturada, éxito sólo tras confirmación y restauración únicamente si persiste la misma versión optimista. Un cambio de cuenta o cancelación se propaga; una edición nueva no se sobrescribe. Ocho regresiones adicionales cubren rechazo, excepción, actualización concurrente, cambios de cuenta durante lectura/respuesta/restauración, cancelación y confirmación.

Fuentes: `core/network/{BackendAddressCache,PublicBackendDns}.kt`, `data/repository/collection/Collection{PendingMessage,Delete}RetrySupport.kt`, `CollectionMessageDeletion.kt` y los repositorios de grupos/comunidades bajo `app/src/main/java/conectate/com/`, y `infrastructure/local-fallback/api/src/data-routes.js`. Las regresiones acompañan esas áreas en `app/src/test/` y `api/tests/imported-profile-lookup.test.js`.

## Carga aislada: comparación antes/después

Se restauró el mismo respaldo del 26/10 en PostgreSQL separado: **256 tablas**, estadísticas `ANALYZE`, 32 cuentas y 25 conversaciones propias representadas. La imagen base es la API activa del 29/10, `f2cb913ec18a`; el candidato monta sólo fuentes locales en sus contenedores temporales. API: 3 CPU/2 GiB; PostgreSQL: 2 CPU/1 GiB; pool: diez conexiones. Red Docker interna, sin puertos publicados ni volúmenes de producción. Cada fase dura aproximadamente doce segundos.

La mezcla incluye perfil, bandejas de chats/grupos/comunidades, feed, lecturas de invitado e historial propio de hasta cincuenta mensajes. La concurrencia es de solicitudes del generador; no equivale a usuarios activos, mensajes por segundo ni sesiones audiovisuales.

| Versión | Concurrencia | Solicitudes | Solicitudes/s | p95 | Errores |
|---|---:|---:|---:|---:|---:|
| API anterior | 1 | 67 | 5,5 | 517 ms | 0 |
| API anterior | 8 | 205 | 16,5 | 2.130 ms | 0 |
| API anterior | 24 | 172 | 10,9 | 7.000 ms | 10 |
| Candidato | 1 | 97 | 8,1 | 224 ms | 0 |
| Candidato | 8 | 175 | 12,7 | 2.579 ms | 0 |
| Candidato | 24 | 500 | 30,4 | 2.177 ms | 0 |
| Candidato | 64 | 778 | 58,2 | 3.030 ms | 0 |
| Candidato | 128 | 621 | 45,8 | 6.716 ms | 1 |

El coste medio de la consulta de perfiles pasó de **82,3 ms / 342 llamadas** a **8,6 ms / 1.435 llamadas**, según `pg_stat_statements`. Son ejecuciones cortas y fases diferentes; la fase de ocho solicitudes muestra variación y no mejora. No se presenta esta comparación como una garantía de capacidad sostenida. La prueba anterior se detuvo en 24 y la nueva en 128 al cruzar los límites de latencia/errores. **No se aprueban 128 solicitudes simultáneas ni crecimiento masivo.**

El candidato terminó con diez lecturas de recuperación HTTP 200 y sin OOM. La sonda de producción dio 24/167 ms antes/después de la primera carga y 22/20 ms antes/después de la segunda. Se vigilaron disponibilidad y memoria del host. Se retiraron únicamente los contenedores y redes con el marcador de propiedad de cada auditoría; producción conservó su imagen, datos y servicios.

## Verificación actual

- **Android JVM:** 1.953 pruebas en 413 suites; cero fallos, errores u omisiones. Incluye las veintidós regresiones nuevas.
- **Servidor local:** 939 aprobadas, doce omitidas y cero fallos, de 951. Once omisiones ya estaban identificadas en 84; la duodécima es la nueva integración PostgreSQL, ejecutada aparte con **2/2 aprobadas y diez comparaciones SQL equivalentes**, dentro de una base sintética llamada `novvin_profile_lookup_test`.
- **Dependencias de la máquina:** el `node_modules` local tenía Nodemailer 10.0.10 frente a 10.1.4 del lockfile; su regresión de destinatario fallaba. `npm ci` restableció exactamente el lockfile y la suite pasó. No se actualizan paquetes ni lockfiles en esta entrega y no se atribuye ese desajuste local al servidor activo.
- **Seguridad automatizada:** auditorías runtime y manifiesto SQL sin hallazgos ni violaciones nuevas. Son controles de alcance definido, no una prueba de inexistencia de vulnerabilidades.
- **Dependencias de producción de la API:** `npm audit --omit=dev` devuelve cero vulnerabilidades registradas en esa consulta. No cubre dependencias Android ni demuestra ausencia de fallos desconocidos.
- **Producción:** monitor público aprobado a las 14:07:31 de Bogotá: ingreso público, Social/multimedia y Mensajes. Comprueba perfiles, bandejas, historial y avatares, imágenes HTTP 200 y dos rangos MP4 consecutivos HTTP 206. Las sesiones diagnósticas son temporales; no se envían mensajes ni avisos a usuarios. La primera ejecución desde el bridge Docker dio `ECONNREFUSED`; se siguió el monitor oficial con red del host, sin reiniciar servicios ni cambiar DNS/configuración de producción.
- **Compilación QA:** `assembleAudit` y `assembleAuditAndroidTest` aprobadas con el código final, junto a la suite JVM; ejecución completa en 8 min 12 s. El paquete QA es `conectate.com.qa`, separado del identificador distribuible.
- **Nativas de esta continuación:** **58/58 en 19 clases**, cero fallos u omisiones, en Android AOSP API 35, emulador nuevo con 2 GiB, dos CPU y GPU por software. Incluyen el flujo de entrada/login, registro hasta antes del envío, políticas, invitado/perfil/salida; cuatro migraciones Room; apertura inicial de video y caché; paginación Social; navegación de ranking/perfil; avatares/visitas/cabecera de momentos; Unicode, portapapeles, fotos, stickers, scroll y PDF; audio y estados visuales de llamada/búsqueda de salas. Las dos últimas áreas no son llamadas bidireccionales reales.
- **Intentos nativos no aprobados:** dos ejecuciones previas en una imagen Google APIs con 1,5/2 GiB y dos CPU terminaron con ANR de arranque antes de reportar casos JUnit. El segundo registro muestra presión de memoria `some avg10=59,86` y de CPU `some avg10=93,26`, sin excepción Java del producto en el buffer de crash. Esto describe el contexto observado y no demuestra por sí solo una causa del producto ni que el arranque esté aprobado en todos los equipos. Se conservan el fallo y la comparación AOSP.
- **Google APIs:** repetición final **2/2 aprobada**, API 35, 3 GiB y cuatro CPU: flujo de acceso/registro/invitado y arranque de `MainActivity` hasta pantalla utilizable, incluido cierre de la Activity. Una ejecución intermedia pasó el primer flujo y falló al cerrar la Activity en el segundo test, después de comprobar la pantalla. El fixture lanzaba un Intent sin acción; la app conserva `ACTION_MAIN` después de consumirlo y `ActivityScenario` perdía el seguimiento del ciclo de vida. Se alineó el Intent del test con el patrón existente en `EntryComposeRule`, conservando sus aserciones. `app/src/androidTest/java/conectate/com/smoke/MainActivitySmokeTest.kt` es el único cambio adicional de esa repetición. El APK de pruebas se recompiló y el lint se volvió a ejecutar antes del 2/2. Uno de esos dos casos repite el acceso ya cubierto por AOSP; no se presentan como dos recorridos adicionales de todos los módulos.
- **Lint:** cero errores y **320 advertencias**, tras el análisis completo y la comprobación posterior del fixture. No se deshabilitan reglas ni se añade una línea base. Las advertencias pendientes siguen visibles en el informe XML/HTML; el resultado no significa ausencia de toda deuda técnica.

La evidencia de esta ejecución queda en `work/` del chat que la realizó. En el servidor, las carpetas privadas `~/novvin-candidates/mobile-audit-20261029{,-optimized}/` conservan informes, generadores y estadísticas. Los archivos `isolated.env` contienen secretos exclusivos de la copia: no se comitean ni se incorporan a entregables. Los informes públicos sólo incluyen resultados agregados.

## Cobertura y límites

Se contrastaron la documentación canónica 00/01/02/03/06/08/78/83/84, manifiesto/permisos, respaldo, política TLS, descubrimiento firmado, sesión/cambio de cuenta, reintentos, sincronización y los contratos de los cinco módulos. La suite completa vuelve a ejecutar sus regresiones existentes de Mensajes, Videos, Social, PlayMatch y Perfil. El mapa integral y el detalle de las correcciones anteriores siguen en 84; no se cuentan pruebas históricas como ejecuciones nuevas.

La prueba de carga usa lecturas sobre una copia con respaldo histórico; no certifica escrituras simultáneas, subida/transcodificación de medios, duración prolongada, llamadas, salas, LIVE ni entrega FCM. El propietario confirmó **sólo emulador por ahora**. El recorrido autenticado completo y la comunicación entre dos equipos/redes, notificaciones y rendimiento de un artefacto firmado siguen requiriendo evidencia física. El candidato permanece en **9.1.30 / 170**; ningún paquete anterior se presenta como reconstruido con estos cambios.

## Reproducción y reversión

```powershell
# JDK 17–21 y las dependencias/cachés del proyecto ya configurados.
.\gradlew.bat :app:testAuditUnitTest :app:assembleAudit :app:assembleAuditAndroidTest :app:lintAudit -PnovvinInstrumentationBuildType=audit

Set-Location infrastructure/local-fallback/api
npm ci
node --test tests/*.test.js
# Sólo en una base efímera, con ese nombre exacto:
# IMPORTED_PROFILE_TEST_DATABASE_URL=postgres://.../novvin_profile_lookup_test
node --test tests/imported-profile-lookup.test.js
```

El helper de configuración QA no sobrescribe una configuración `app/src/audit/google-services.json` que ya exista; en ese caso se compila directamente y se conserva el archivo. Nunca se imprime ni se comitea su contenido. Las auditorías runtime/SQL y el monitor público se ejecutan según el runbook 06. Revertir los commits de esta continuación recupera los comportamientos anteriores; no hay migraciones ni cambios de datos que revertir. Publicar Android/API es una operación posterior a este cierre local.
