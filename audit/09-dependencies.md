# 09 — Dependencias y cadena de suministro

Fecha: 2026-09-26. Alcance comprobado: manifiestos y locks de cinco paquetes npm desplegables, y dependencias Python de las pruebas de herramientas. El `package.json` raíz no tiene dependencias ni lock; `npm audit` allí devuelve `ENOLOCK` y no representa una sexta superficie. No se considera una auditoría completa de Gradle, transitivas Android, imágenes OCI, firmware ni servicios externos.

| Paquete | Orden ejecutada | Resultado de producción tras la corrección | Evidencia local |
|---|---|---:|---|
| Web | `npm audit --omit=dev --json` | 0 avisos | `build/reports/dependencies/web.json` |
| API | `npm audit --omit=dev --json` | 0 avisos | `build/reports/dependencies/api.json` |
| Signaling | `npm audit --omit=dev --json` | 0 avisos | `build/reports/dependencies/signaling.json` |
| Chatbot IA | `npm audit --omit=dev --json` | 0 avisos | `build/reports/dependencies/ai-chatbot.json` |
| Gateway realtime | `npm audit --json` | 0 avisos | `build/reports/dependencies/realtime-all.json` |

La API tenía `nodemailer@7.0.13` como dependencia directa. `npm audit --omit=dev` encontró un paquete con severidad agregada **high** y ofreció corregirlo con `10.0.10`. Se actualizó `package.json` y `package-lock.json` a la versión exacta `10.0.10`; las 232 pruebas de API pasaron, una omitida, y el nuevo audit reportó cero. El código usa `require('nodemailer')` y `createTransport`, interfaces compatibles con la publicación CommonJS de v10. La prueba del mailer usa un transporte falso, así que **SMTP real sigue sin verificarse**. Nodemailer v10 requiere Node.js 20+; el paquete API declara `engines.node >=20`, pero el despliegue debe confirmar la imagen efectiva. [Notas oficiales de v10](https://github.com/nodemailer/nodemailer/blob/master/CHANGELOG.md), [política de versiones soportadas](https://github.com/nodemailer/nodemailer/security).

Al incluir dependencias de desarrollo, el web reportó **7 avisos** (3 high, 3 moderate, 1 low), todos transitivos y con corrección disponible dentro de sus rangos: `@humanfs/node`, `baseline-browser-mapping`, `browserslist`, `fast-uri`, `js-yaml`, `postcss-selector-parser` y `qs`. `npm audit fix --ignore-scripts` actualizó su lock sin cambiar directamente el manifiesto; el nuevo `npm audit --json` reporta **0 avisos** y las 87 pruebas, ESLint y build pasan. API, signaling, chatbot y gateway realtime también reportaron cero en auditoría completa. El build web se volvió a ejecutar con una URL HTTPS temporal de proceso para satisfacer su validador; no se cambió la configuración desplegada.

Las pruebas de `tools/tests` importaban `yaml` sin instalarlo en calidad local ni GitHub Actions. Se añadió `tools/requirements.txt` con `PyYAML==6.0.3` y su instalación antes de la suite en los scripts de quality gate y los tres jobs de CI/release que la ejecutan. Con un entorno Python aislado del repositorio, las **60 pruebas pasan**. La instalación remota de Actions todavía no se ha ejecutado en este estado. [PyYAML en PyPI](https://pypi.org/project/PyYAML/).

Pendiente: inventario/SBOM completo con licencias y hashes de Android/Node/OCI; actualizaciones reproducibles en CI; prueba `npm ci` en contenedor Node 20; política de revisión periódica de avisos. Un resultado cero de `npm audit` sólo significa que su base de avisos no señaló las dependencias incluidas en esta consulta.
