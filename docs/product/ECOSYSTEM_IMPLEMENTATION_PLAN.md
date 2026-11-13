# Implementación del paquete de auditoría Novvin

13 de noviembre de 2026, America/Bogota. El objetivo sigue abierto. Este plan organiza el trabajo autorizado; no certifica funciones ni sustituye sus pruebas.

## Fuentes y seguimiento

Se leyeron `README.md`, `PROMPT_MAESTRO_NOVVIN.md`, su versión `.docx`, `INVESTIGACION_MERCADO_BENCHMARK.md`, `PRD_NOVVIN_LIVE_LIVE_PLAY.md` y `MATRIZ_ACEPTACION_QA.md`, además de `FUENTES_Y_REFERENCIAS.md`, dentro de `Novvin_Auditoria_Ecosistema`. `INFORME_AVANCE_CODEX.md` registra avances anteriores, con sus límites y entornos. El Word y el Markdown contienen el mismo mandato; sus diferencias de portada y numeración no constituyen funcionalidades adicionales.

La [trazabilidad](REQUIREMENT_TRACEABILITY.csv) conserva 359 entradas del paquete y del encargo de interfaz. La revisión 127 incorpora sesenta párrafos y líneas de contexto que faltaban en las 297 entradas anteriores, sin renumerarlas. Cada entrada mantiene su texto, fuente y huella. `ENTREGADO` en una fila de documentación significa que existe ese artefacto, no que toda la funcionalidad descrita funcione. Las propuestas del benchmark se contrastan con fuentes oficiales antes de convertirse en decisiones de producto. Los resultados históricos no se trasladan a una APK posterior sin comprobar sus commits.

`python tools/audit/check_requirement_traceability.py` comprueba que no falten líneas de contenido, que su texto y huella correspondan a la fuente y que no haya identificadores duplicados. El Word se controla por huella binaria y su lectura se documenta por separado. Este control no certifica implementación. Las huellas de texto nuevas usan UTF-8/LF; se reconocen las huellas históricas del contenido equivalente en CRLF, sin aceptar modificaciones de contenido.

| Archivo de entrada | Lectura y aplicación al proyecto |
| --- | --- |
| `README.md` | Exige diagnóstico, fases, evidencia y conservación del alcance. Su advertencia sobre el benchmark impide tratar promesas públicas como funciones probadas. |
| `PROMPT_MAESTRO_NOVVIN.md` | Define el mandato, las 17 fases y sus condiciones de aceptación. La omisión de párrafos en la matriz anterior dejaba sin seguimiento multimedia, moderación, capacidad, UX, SEO, seguridad y cambios productivos; se incorporan como pendientes. |
| `PROMPT_MAESTRO_NOVVIN.docx` | Versión editable del mandato, leída desde el documento. La portada y numeración no añaden funcionalidades; se preserva la huella binaria y el vínculo con la especificación Markdown. |
| `PRD_NOVVIN_LIVE_LIVE_PLAY.md` | Exige coherencia Android–web, invitados autorizados, consentimiento de medios, participación, juegos y economía autoritativa. Las pruebas locales existentes no prueban SFU/TURN físico, puente general regalo–juego ni conciliación de proveedor. |
| `MATRIZ_ACEPTACION_QA.md` | Sus criterios se conservan en la matriz de aceptación. Un PASS debe identificar entorno, versión y evidencia; un ensayo de componente no permite cerrar un criterio que incluye redes o dispositivos reales. |
| `INVESTIGACION_MERCADO_BENCHMARK.md` | Ofrece patrones y propuestas, no mediciones del código de Novvin. Cada propuesta requiere contraste, priorización y validación; no autoriza copiar interfaces ni asumir capacidades, mercado o políticas económicas. |
| `FUENTES_Y_REFERENCIAS.md` | Registra fuentes públicas por producto, arquitectura, política y seguridad. Su vigencia se comprueba al utilizar cada afirmación; leer la lista no constituye verificar sus enlaces ni adoptar automáticamente su contenido. |

El informe de avance se usa para localizar pruebas y pendientes anteriores. No reemplaza los requisitos de entrada ni certifica su cierre.

## Orden de ejecución

Primero se revisa Android, junto con los contratos de servidor que necesita. Después se aborda la web y se ensayan los recorridos entre plataformas. Los defectos se reproducen y se corrigen por bloques pequeños. Cada bloque conserva pruebas, alcance, versión, riesgos residuales y el siguiente recorrido pendiente. La revisión se declara por archivo y huella mediante `tools/audit/update_review_coverage.py`; una búsqueda, compilación o prueba no cuenta como lectura.

| Orden | Trabajo y requisitos de origen | Evidencia necesaria para cerrarlo |
| --- | --- | --- |
| 1 | Descubrimiento y cobertura, fase 1 | Inventario vigente por huella y acción, dependencias reales, defectos reproducibles y riesgos priorizados. Los archivos pendientes siguen abiertos. |
| 2 | Sesión móvil y cambios de cuenta, fases 3 y 16; AUTH-01, AND-01 | Acceso, renovación, salida, A → B → A, segundo plano, recreación y muerte de proceso; sin respuestas ni credenciales de una visita anterior. |
| 3 | Mensajería móvil y medios, fases 3 y 10; CHAT-01/02, MEDIA-01 | Entrega y persistencia entre cuentas, reintentos tras pérdida del ACK, cola sin red y después de reiniciar, original privado y animación conservados. |
| 4 | Social, perfil, vídeos y Momentos móviles, fase 3 | Crear, editar, reaccionar, comentar, compartir, reportar, audiencia y caducidad; feed autenticado en equipos representativos y bajo cambio de red. |
| 5 | Llamadas y salas móviles, fases 9 y 12; CALL-01–04, ROOM-01/02 | Permisos reales, tercer participante, audio/vídeo remoto, roles, expulsión, retorno del segundo plano, Wi-Fi/datos y TURN seleccionado. |
| 6 | Preparación, invitados y participación LIVE móvil, fases 4–6; LIVE-01–06 | Confirmación del backend, invitación/expiración sin duplicados, bloqueo heredado, consentimiento de medios, salida del invitado, preguntas/votos coherentes y recuperación. |
| 7 | Live Play y puente de eventos, fase 7; PLAY-01–03 | Una experiencia autorizada completa, secuencia y expiración de eventos, allowlist, idempotencia, rechazo de falsificaciones, reintentos, auditoría y reglas visibles. Los experimentos del plan 105 mantienen su rama exclusiva. |
| 8 | Regalos y economía, fase 8; GIFT-01/02 | Coste y confirmación, débito y recibo atómicos en unidades enteras, doble envío, conciliación, reversos, accesibilidad de animaciones y elegibilidad. Proveedor y reglas comerciales verificados antes de activación económica. |
| 9 | Moderación, privacidad y seguridad móvil/API, fases 12 y 16; SEC-01/02 | Matriz de recursos y roles, bloqueo bilateral, reportes confirmados, revocación, datos/logs/artefactos sin secretos, límites de abuso y protección de menores verificables. |
| 10 | UX, rendimiento y accesibilidad Android, fase 14 | Borradores y errores recuperables, texto grande, lector de pantalla, foco y controles accesibles; medidas de inicio, primer cuadro, fluidez y memoria con dispositivo y versión identificados. |
| 11 | Web y equivalencia funcional, fases 3–12 y 14; WEB-01 | Tras la fase móvil, revisar rutas, estados, acciones y permisos de cada módulo web; teclado, reflujo y dispositivos soportados; repetir recorridos Android–web. |
| 12 | Mercado y Creator Studio, fases 2 y 11 | Fuentes oficiales contrastadas, oportunidades priorizadas por valor/coste/riesgo y métricas de eventos reales; hipótesis de preferencia verificadas con personas. No inventar usuarios, ingresos ni capacidad. |
| 13 | Dominio y descubrimiento público, fase 15; SEO-01 | HTTP público vigente, canonical/redirects/robots/sitemap, enlaces de terceros, promesas alineadas con funciones comprobadas y evidencia real de indexación. |
| 14 | Operación y escalabilidad, fase 13; OPS-01 | Staging con cargas controladas, SLI/SLO medidos, coste, respaldo restaurado en destino aislado, tiempo de recuperación y rollback compatibles con los clientes. |
| 15 | Entrega y preparación de campaña, fase 17 | Pruebas críticas aprobadas en la versión entregada, riesgos pendientes explícitos, APK identificada y firmada cuando corresponda, huella/firma verificadas, aceptación de los recorridos físicos y estado productivo contrastado. |

Las filas agrupan dependencias para ordenar el trabajo. No eliminan los requisitos individuales de las 17 fases ni convierten propuestas en implementaciones. Cada requisito se resuelve en la trazabilidad y cada criterio de QA en la [matriz de aceptación](../quality/ACCEPTANCE_MATRIX.csv).

## Publicación y convivencia de cambios

Las correcciones terminadas se publican en `main` y `feature/live-gifts` y se comprueba que sus árboles Git sean idénticos. Se utilizan sus worktrees existentes. Los cambios preexistentes sin confirmar se inspeccionan y se conservan fuera del bloque correspondiente; no se incorporan para aparentar limpieza. No se modifica `correciones`. Las funciones y rediseños del plan 105 permanecen exclusivamente en `codex/experiencias-con-proposito` hasta una instrucción expresa de incorporación.

La publicación de commits no equivale a actualizar Ubuntu, distribuir una APK ni validar hardware. No se emplean usuarios reales, movimientos de dinero o nuevos servicios de pago para aparentar una prueba completa. Los contratos de importación del servidor propio se conservan hasta sustituir y verificar todos sus consumidores.

## Preparación para marketing masivo

La competencia frente a grandes redes sociales es una meta de producto que requiere pruebas con usuarios, fiabilidad y operación observadas. La [decisión de salida](../operations/PRODUCTION_READINESS.md) y el [registro de riesgos](../risks/RISK_REGISTER.md) mantienen las condiciones abiertas. Ni una suite aprobada, ni una comparación pública de funciones, ni una APK compilada prueban por sí solas esa preparación.

La [continuación 129](../oficial/129-web-menu-plegable-e-invitaciones-de-llamada-2026-11-13.md) atiende la prioridad web concreta del propietario del 13 de noviembre: menú plegable e invitaciones durante una llamada. Añade UX-09 y UX-10; CALL-02 permanece parcial por la integración física y Android–web pendientes. La revisión móvil 128 continúa local y sin publicar.
