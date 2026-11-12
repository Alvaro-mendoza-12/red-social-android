# Dominio en LIVE y encargo de auditoría — 12 de noviembre de 2026

## Alcance y correcciones

La comprobación adicional del dominio detectó que el compartido Android del visor LIVE solo enviaba texto. La promoción previa al inicio tampoco incluía un enlace. El visor ahora comparte `https://novvinapp.com/app/live/<UUID>` y la promoción previa enlaza al centro LIVE existente, sin inventar un directo todavía no creado.

Se añadió un lector HTTPS de enlaces LIVE con hosts exactos, UUID válido y rechazo de usuario/contraseña en URL, puertos, fragmentos y rutas añadidas. Los enlaces entran siempre como espectador; un parámetro `host=1` no concede ese papel. Se añadieron las rutas correspondientes al manifiesto para el dominio principal, www y el host heredado, conservando compatibilidad. Las comprobaciones de audiencia y pertenencia del servidor siguen aplicando.

En web el compartido LIVE construye la URL oficial desde el identificador de la sesión. Antes copiaba `window.location.href`, que podía pertenecer a otra pantalla mientras el directo estaba minimizado y podía conservar parámetros ajenos al destino. La dirección compartida no incluye papel de anfitrión ni parámetros de la página actual.

La moderación Android protegía UUID de enlaces propios frente a la redacción de teléfonos, pero solo reconocía hosts anteriores. Usa ahora el catálogo `PublicWebDomain.httpsHosts` y conserva enlaces de videos, directos y colecciones además de los anteriores. Mantiene controles de spam, contenido bloqueado y redacción de teléfonos y correos fuera de la URL reconocida.

Esto no certifica que todos los módulos estén íntegramente auditados ni que las APK instaladas contengan los cambios. Los dominios técnicos del backend, señalización, TURN/SFU y R2 se conservan donde sus contratos los necesitan; el dominio público no sustituye automáticamente esos servicios.

## Buscadores

La captura del propietario corresponde a Brave Search: muestra el título de Novvin y la dirección antigua de Vercel. El host antiguo redirige permanentemente sus páginas públicas al nuevo, y la web publica canónicos, sitemap y datos estructurados de novvinapp.com. La revisión 119 registra el sitemap procesado por Google y la notificación IndexNow. Brave usa un índice independiente y necesita volver a rastrear las páginas. No se afirma haber solicitado una retirada por datos personales ni un borrado de la marca, ni se garantiza plazo o posición.

Fuentes oficiales consultadas:
- https://search.brave.com/help/brave-search-crawler
- https://search.brave.com/help
- https://developers.google.com/search/docs/crawling-indexing/site-move-with-url-changes

## Prompt de auditoría independiente

El encargo está en `docs/oficial/PROMPT-AUDITORIA-GEMINI-MAIN-LIVE-GIFTS.md`. Se prepara para otro analista con acceso al proyecto: Android primero, web después e integración con el servidor. Exige comprobar herramientas y skills reales, SHA/árbol por rama, cobertura por huella, evidencia reproducible, distinción de hipótesis y defectos y un informe estructurado para Codex. No autoriza cambios de código, despliegues, borrado de archivos, uso de datos personales ni subagentes.

Distingue código publicado, APK/AAB, servidor activo, pruebas automatizadas, dispositivo y resultados de buscadores. Conserva explícitos los pendientes SMTP corporativo, firma/foto no verificadas, huella de Play App Signing, nueva entrega Android y revisión integral.

## Comprobaciones

La evidencia adjunta registra la ejecución actual. La web aprobó 1.191 pruebas en 187 suites, diez pruebas dirigidas de LIVE, TypeScript/Vite y el chequeo de 21 páginas públicas. ESLint conserva cero errores y siete avisos. Android aprobó 21 pruebas JVM en cuatro clases con casos ejecutados, sin fallos, errores ni omisiones (el runner emitió además un informe de suite con cero casos, que no se cuenta como clase ensayada): enlaces LIVE, dominio registrado, destinos compartidos y moderación. La compilación perdió el daemon de Kotlin y completó con éxito la estrategia alternativa en 14 minutos; ese fallo de preparación no se atribuye al producto.

No se generó una APK nueva, no se ejecutó un directo real ni se afirmó ensayo de dispositivo físico de este bloque. La revisión integral permanece abierta.
