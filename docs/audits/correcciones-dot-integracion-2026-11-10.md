# Integración de correcciones en correcciones-dot

Fecha: 10 de noviembre de 2026. Base de integración: `13593bb315ba8bb5e96c7f45af94d4098d4363f7`, que contiene el bloque de seguridad sobre `main` en `455d26999111116f59ae8bd76dae56a5e137aee3`.

## Alcance

Se integran los parches revisados de Android y web, manteniendo el paso de instalación de dependencias de `signaling-server` del bloque de seguridad. No se fusiona con `main` o `feature/live-gifts`, no se solicita un release productivo y no se realizan pruebas destructivas sobre datos reales.

- Android: aislamiento realtime por visita de cuenta y transacciones Room, configuración ficticia de CI separada de la configuración productiva, validación de origen release, guardado del perfil por sección, borradores y descarte, accesibilidad, recursos localizados y aviso honesto de retiros no disponibles.
- Web: conservación exacta de contraseñas, búsqueda paginada de juegos y estados vacíos/error, navegación de Videos, nombres accesibles de comentarios, títulos contextuales, diálogos modales, aviso de descarte de Momentos, contraste y aviso de monetización no disponible.
- Se conserva la actualización compatible del lockfile de `systeminformation` incluida en el parche web.

Informes detallados: [Android](2026-11-10-android-correcciones.md), [web](web-corrections-2026-11-10.md) y [seguridad](../oficial/correcciones-dot-seguridad-2026-11-10.md).

## Comprobaciones de la integración

- Suite backend completa: **1.061 aprobadas, cero fallidas, 16 omitidas**; comprobación sintáctica aprobada.
- Regresiones de seguridad: **37 aprobadas y una omitida** por no disponer de una URL de PostgreSQL aislado. La comprobación SQL se ejecutó además en PGlite y aprobó secuencialmente; no demuestra concurrencia entre conexiones.
- Web Jest: **170 suites y 956 pruebas aprobadas**.
- Web TypeScript y build aprobados con `VITE_FALLBACK_API_URL=https://novvin-test.invalid`; prerender y SEO de 19 páginas aprobados.
- Web Node después del build: **98 pruebas aprobadas**.
- Web lint: **cero errores y siete avisos existentes** de dependencias de efectos React. Persiste el aviso de tamaño del fragmento JavaScript mayor.
- Auditoría de dependencias repetida: web **16 avisos** (nueve moderados y siete bajos) y backend de producción **cuatro moderados**, sin altos/críticos. El servicio de auditoría ahora ofrece correcciones backend que no ofrecía en la primera comprobación. No se modificaron las dependencias backend ni se atribuye a este parche la variación del informe; esas actualizaciones requieren evaluación y pruebas adicionales.
- Herramientas Python combinadas: **37 pruebas aprobadas**. Los contratos Android que leen código son verificaciones estáticas, no pruebas de comportamiento en dispositivo.
- Los tres workflows Android se analizaron como YAML y los ocho archivos nuevos de recursos como XML.
- Se generó el fixture Firebase ficticio y un keystore debug temporal; `keytool -list` comprobó su estructura. No se usaron credenciales productivas.
- Las bases de los archivos se cotejaron con las huellas del árbol remoto. Los dos conflictos de contexto se resolvieron con el contenido final revisado. Las diferencias restantes de importación son líneas vacías; el contenido no vacío coincide con la revisión. `git diff --check` no informa errores.

## Pendientes y límites

- **Android no se ha compilado localmente.** No hay SDK, adb, Gradle ni compilador Kotlin en este entorno. Las 15 nuevas pruebas Kotlin/Room deben compilarse y aprobarse en CI. El workflow de calidad ahora se activa en `correcciones-dot`; que exista este workflow no equivale a una ejecución aprobada.
- Faltan emulador/dispositivo, TalkBack, recreación de Activity/muerte de proceso, adjuntos restaurados, teclado/texto grande y pruebas visuales reales de los diálogos y Videos. No se entregó APK/AAB ni se ejecutaron pagos, llamadas o cambios de contraseña contra cuentas reales.
- El aviso web de Momentos cubre sus cierres explícitos y descarga de página; no impide perder un borrador al navegar dentro de la SPA. Monetización sigue sin implementar retiros. Persisten los avisos de dependencias detallados en cada informe.
- La matriz global de cobertura debe actualizarse desde un checkout íntegro, no desde esta materialización parcial. La revisión integral y los recorridos no verificados permanecen abiertos.
- El propietario confirmó que el despliegue Vercel del commit de seguridad fue **Preview** y que Production usa `main`. Una nueva publicación puede activar automáticamente Vercel y GitHub Actions; su resultado y destino se deben comprobar para cada commit, sin tratar un estado de build exitoso como prueba del entorno.

Esta integración prepara un candidato verificable. No certifica ausencia de defectos, cumplimiento normativo ni aceptación productiva.
