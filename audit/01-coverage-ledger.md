# 01 — Registro de cobertura de auditoría

Fecha de apertura: 2026-09-26. Estado: **en curso**. «Inventariado» significa localizado y clasificado; «inspeccionado» exige lectura y comprobación de un flujo concreto. No se declara auditoría completa.

| Área | Archivos o configuraciones inspeccionados en esta pasada | Estado | Evidencia/resultado | Pendiente |
|---|---|---|---|---|
| Repositorio y build | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, paquetes web/API/signaling/IA, estado Git | Línea base | 2.162 archivos fuente filtrados; 1.026 cambios preexistentes; suites pasan; bundle release de prueba y verificadores pasan | Inventario por propietario, firma real de release y ejecución CI remota |
| Arranque Android | `AndroidManifest.xml`, `MainActivity.kt`, `SocialApp.kt`, rutas de auth y principales | Inspección inicial | Entrada Compose/Hilt, FCM, deep links y navegación identificados | Traza completa de splash, sesión, DI, fallos de red y ciclo de vida |
| Acceso y multicuentas Android | `LoginScreen.kt`, `AuthViewModel.kt`, `AuthRepository.kt`, `AccountSessionRepository.kt` | Flujo inspeccionado/corregido | Recuperación y cambio de cuenta corregidos en la tanda previa; APK y pruebas pasan | Dos cuentas reales, SMTP real, cambio con expiración de tokens |
| Mensajes Android | `MessagingViewModel.kt`, `MessageRepository.kt`, `InboxPreviewReader.kt` y prueba asociada | Flujo parcial | Se corrigió parpadeo de bandeja; prueba de reobservación pasa | Envío, multimedia, grupos, comunidades y badges en dos dispositivos |
| Videos/LIVE/Juegos/Social/Perfil Android | Rutas en `Screen.kt` y `MainNavigationRoutes.kt`; informes previos de `docs/` | Inventariado | Destinos presentes | Inspección por pantalla, UX, rendimiento, accesibilidad y pruebas manuales |
| Web | `main.tsx`, `App.tsx`, `core/runtimeBackend.ts`, `package.json` | Arranque y rutas iniciales | Build, lint y 87 tests pasan | Auth, estado, llamadas, media y cada ruta crítica |
| API | `server.js`, `package.json`, `password-reset-mailer.js`, configuración de middleware; nombres de módulos de rutas | Auth y entrada parcial | 29 archivos con 408 declaraciones estáticas `app/router.method`; API tests pasan | Matriz METHOD/PATH/AUTH y autorización de cada ruta; cargas y errores |
| Señalización | `index.js`, `package.json`, `Dockerfile`, módulos distribuidos por nombre | Entrada y pruebas | 26 pruebas pasan; integración Redis omitida | Revisión de eventos y prueba WebRTC real multicliente |
| Gateway realtime | `package.json`, `src/server.js` por entrada, suite de autenticación/autorización | Pruebas de módulo | `npm run check; npm test` pasan | Suscripciones reales, reconexión y carga con API/signaling |
| Chatbot IA | `package.json`, servicio Compose | Inventariado | Servicio separado localizado | Evaluación, privacidad, grounding y pruebas |
| PostgreSQL | `db/init/001_schema.sql`, lista de migraciones, `schema-initializer.js`, manifiesto SQL | Inventario estático | 156 nombres únicos en declaraciones; allowlist de Supabase separada | Constraints, índices, migraciones aplicadas y EXPLAIN con datos reales |
| Infra/red | `docker-compose.yml`, Nginx, Dockerfiles, runbooks previos | Inspección inicial | PostgreSQL, Redis, API, AI, signaling, edge, realtime, TURN opcional | Restore de backup, túnel nombrado, carga, fallos de red y HA |
| CI/CD | `android-ci.yml`, `android-production-release.yml`, `quality_gate.ps1/.sh`, nuevo `service-ci.yml` | Inspección y corrección puntual | PyYAML fijado en gates; 60 tests locales pasan; matriz web y cuatro servicios añadida | Ejecución remota de ambos pipelines, ramas de protección y rollback |
| Seguridad, privacidad, licencias | Manifest Android, middlewares API, configuración de secretos, Nginx, cinco locks npm | Parcial | Audits estáticos sin violaciones HIGH; Nodemailer high corregido; cinco npm audits completos en cero tras correcciones | OWASP por ruta, MASVS, SBOM, licencias de assets, retención y OCI/Gradle |
| UX y accesibilidad | Bienvenida/registro/recuperación Android en emulador de tamaño Moto; navegación web inventariada | Parcial | Capturas y retroceso verificados en tanda previa | Matriz de tamaños, TalkBack, contraste, teclado, lectores y motion |
| Observabilidad | Middleware de requests, health/ready, Crashlytics, logs Nginx | Inventariado | Puntos de instrumentación localizados | Probar correlación y alertas ante fallos reales |

Los números estáticos de rutas y tablas **no son porcentajes de cobertura funcional**. Los informes históricos en `docs/` sirven como contexto y no se transfieren automáticamente como evidencia de esta rama y fecha.

## Seguimiento de las 38 fases del encargo (0–37)

«Parcial» exige profundizar y cerrar con evidencia; «pendiente» significa que se registró el alcance pero todavía no se evaluó. Un documento creado no convierte por sí solo la fase en completa.

| Fase | Tema | Estado al 2026-09-26 |
|---:|---|---|
| 0 | Protección del proyecto | Hecho: estado Git e inventario de cambios preexistentes; ningún descarte masivo. |
| 1 | Inventario total | Parcial: superficies, tecnologías y entradas; falta propiedad por archivo. |
| 2 | Traza desde apertura | Parcial: Android, HTTP, recuperación y media; falta recorrido autenticado entero. |
| 3 | Cobertura real | Activo: este registro, con límites explícitos. |
| 4 | Arquitectura | Parcial: diagrama y puntos de acoplamiento. |
| 5 | Archivo por archivo | Pendiente: 2.162 archivos filtrados, no inspeccionados individualmente. |
| 6 | Frontend | Parcial: build, lint, tests y entrada/rutas. |
| 7 | Mobile | Parcial: acceso y algunos flujos; dos incompatibilidades API corregidas, Lint 0 errores/219 advertencias, 844 tests y bundle release de prueba pasan. |
| 8 | UX/UI | Parcial: acceso y escenarios LIVE descritos. |
| 9 | Accesibilidad | Pendiente prueba sistemática TalkBack/teclado/contraste. |
| 10 | Backend | Parcial: API y suites, auth/recuperación/media. |
| 11 | APIs | Pendiente matriz de autorización de 408 declaraciones. |
| 12 | Base de datos | Inventario estático; pendiente DB viva/EXPLAIN/restore. |
| 13 | Servidor/red | Parcial: Compose/Nginx/túnel; pendiente origen estable. |
| 14 | Escalabilidad | Pendiente ensayo concurrente y capacidad. |
| 15 | Performance | Parcial: muestras `/ready` y tamaño de APK debug; pendiente usuarios reales. |
| 16 | Seguridad | Parcial: controles y audits automáticos; pendiente revisión por ruta/dinámica. |
| 17 | Dependencias | Cinco locks npm auditados y corregidos; pendiente Android/OCI/SBOM. |
| 18 | Licencias/IP | Parcial: activos/términos localizados; pendiente procedencia por activo. |
| 19 | Privacidad | Parcial: backup/logs; pendiente retención/exportación/eliminación. |
| 20 | Estándares | Matriz inicial sin declaración de conformidad. |
| 21 | Investigación externa | Fuentes primarias enlazadas en `references.md`; ampliar según hallazgos. |
| 22 | Testing | Suites locales ejecutadas; pendiente CI remoto/E2E/dispositivo. |
| 23 | Observabilidad | Instrumentación localizada; pendiente comprobar despliegue y correlación. |
| 24 | DevOps | Gates revisados y CI servicios añadido; pendiente rollback/release real. |
| 25 | Requisitos | Los 60 IDs S60 están inventariados en `17-s60-traceability.md`; 7 con evidencia parcial y 53 pendientes de comprobar contra aceptación. |
| 26 | Procesos | Pendiente mapear registro, publicación, moderación y soporte extremo a extremo. |
| 27 | Matriz de hallazgos | Activa en `13-master-findings.md`; se amplía con pruebas. |
| 28 | Fortalezas | Documentadas en `14-strong-points.md`. |
| 29 | Deuda técnica | Clasificación inicial en `15-technical-debt.md`. |
| 30 | Roadmap | Ordenado por dependencia en `16-remediation-roadmap.md`. |
| 31 | Corrección | En curso: dependencias, CI, compatibilidad Android y documentación. |
| 32 | Refactoring | Pendiente extracción gradual de archivos extensos. |
| 33 | Mejoras UI | Parcial: tanda previa de acceso; pendiente matriz visual. |
| 34 | Carga | Pendiente escenario concurrente con métricas. |
| 35 | Resiliencia | Pendiente caos controlado/restore/failover. |
| 36 | Edge cases | Parcial en tests unitarios; pendiente dispositivo/red/cuentas reales. |
| 37 | Documentación final | Estructura 00–16 e informe creada; conclusiones siguen abiertas. |
