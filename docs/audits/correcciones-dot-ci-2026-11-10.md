# Correcciones de la primera ejecución de CI

Fecha: 10 de noviembre de 2026. Base remota: `3a4dc8efdd1fd1e52b41c27a1ca89389bf4d0967`. Destino exclusivo: `correcciones-dot`.

## Fallos observados y cambios

La ejecución [42756060269](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/42756060269) falló en tres jobs. No se presenta como aprobada ni se omite ninguna barrera para volverla verde.

- **Backend:** todos los contratos terminaron, pero `npm audit --omit=dev --audit-level=low` detectó cuatro avisos moderados en la cadena `google-gax → rimraf → glob/minimatch`. `npm audit fix --package-lock-only` no resolvió la cadena. Se añadió un override limitado a `google-gax` para `rimraf 6.1.4`; mantiene Firebase Admin, Firestore y google-gax en sus versiones anteriores y resuelve `glob 14.0.4` y `minimatch 11.0.5`. Se conservaron los metadatos de plataforma de Sharp y el override previo de UUID. El umbral de auditoría permanece intacto. Este override debe revisarse cuando google-gax actualice su dependencia; no sustituye una validación de operaciones Firestore reales.
- **Runtime:** Firebase Admin 14.1.0 ya exigía Node >=22, pero la API declaraba >=20 y los workflows usaban Node 20. La declaración de la API pasa a >=22 y los workflows a Node 24. El Dockerfile existente usa Node 22.23.4 y no cambia. Modificar el archivo del workflow productivo en esta rama no ejecuta ni publica un release productivo.
- **Web:** Node 20 rechazaba un ArrayBuffer del entorno JSDOM en la prueba de firma WebCrypto. La prueba usa ahora el entorno Jest Node, conservando el motor criptográfico real. El código de confianza productivo y su rechazo seguro de errores no cambian. El fallo se reprodujo en Node 20 antes de corregir la prueba; no se atribuye su solución únicamente al cambio de runtime de CI.
- **Android:** `SharedFlow.onSubscription` no aceptaba el `Flow` filtrado que expone el cliente realtime. Los dos consumidores LIVE usan un método que registra el callback sobre el SharedFlow y después aplica el filtro de visita de cuenta. Se conserva el orden que evita perder el primer evento; no se reemplaza por `onStart`. Detalles y límites en [el informe Android](2026-11-10-android-correcciones.md).

- **Alias Preview:** se añade únicamente `red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app` a la lista existente de orígenes del gateway público. Una regresión comprueba la ruta de login y tres casos rechazan otras ramas, otros equipos y sufijos engañosos. No cambia CORS del servidor, variables de Vercel ni la política criptográfica. Corrige un defecto de transporte reproducido; no demuestra que se haya resuelto el problema de acceso comunicado por el propietario.

## Verificación local

- Instalación limpia de backend con el lockfile corregido y scripts de instalación desactivados: aprobada. Suite completa en Node 22.23.4 y Node 24.22.1: **1.063 aprobadas, 16 omitidas, cero fallidas** en cada runtime. Sintaxis y todos los scripts `test:*` del job backend: aprobados.
- Dos pruebas nuevas comprueban, sin red ni credenciales, la construcción de clientes Firebase/Firestore y la API asíncrona de eliminación de rimraf en un directorio temporal. **2/2 aprobadas en Node 22.23.4 y Node 24.22.1**. No prueban conexiones, permisos ni operaciones contra Firebase real.
- Auditoría de dependencias productivas, con el mismo umbral del workflow: **cero vulnerabilidades** en backend, web, signaling y realtime gateway al comprobarlo. Los avisos de dependencias de desarrollo web previamente registrados no se presentan como corregidos. Los resultados dependen de la base de avisos vigente al consultar npm.
- Web: **170 suites y 960 pruebas aprobadas** tras integrar el cambio de entorno y el alias Preview. El parche aislado de la prueba criptográfica fue además comprobado con 956 pruebas de la suite completa en Node 20.20.2 y Node 24.22.1. El alias añadió cuatro casos; build, TypeScript y SEO de 19 páginas aprobaron después de integrarlo. Lint terminó con cero errores y siete avisos existentes de efectos React; persiste el aviso de tamaño del fragmento JavaScript mayor.
- Herramientas Python combinadas: **40 pruebas aprobadas**. Incluyen contratos nuevos de runtime y del orden de suscripción. Las comprobaciones de fuente Android son estáticas.
- Workflows modificados analizados como YAML y `git diff --check` aprobado. Las bases se cotejaron con el árbol remoto. Dos archivos materializados tenían un salto de línea adicional al final; se verificó esa diferencia y se conservó el contenido remoto más las modificaciones revisadas.

## Pendiente de la siguiente ejecución

La compilación Android, las 17 pruebas Kotlin/Room añadidas y los jobs completos del nuevo commit deben verificarse en CI. Este entorno no dispone de SDK, Gradle ni compilador Kotlin. No se entregó APK/AAB ni se ejecutó un emulador en esta comprobación. Tampoco se verificó concurrencia PostgreSQL entre conexiones ni se realizaron pruebas destructivas productivas.

Se verificó en el [dashboard Vercel del commit base](https://vercel.com/alvaro-s-projects1/red-social-android/6qaY3V7Ku9bTw29pRVH1YPqhF6uh) el estado **Ready**, entorno **Preview**, rama `correcciones-dot` y commit exacto `3a4dc8efdd1fd1e52b41c27a1ca89389bf4d0967`. Production sigue `main`. Esto no demuestra el destino de futuros commits: cada nuevo despliegue automático requiere comprobación independiente. No se solicitó una promoción a Production ni se modificaron ajustes Vercel, `main` o `feature/live-gifts`.
