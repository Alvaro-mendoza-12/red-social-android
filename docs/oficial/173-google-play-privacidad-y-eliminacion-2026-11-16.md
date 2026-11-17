# 173 · Google Play: privacidad y solicitud de eliminación

16 de noviembre de 2026, America/Bogota. La revisión integral permanece abierta.

## Problema comprobado

El propietario muestra dos rechazos de Google Play: política de privacidad no válida y enlace de eliminación de cuenta no válido, además del requisito de API 36. La URL pública `/privacidad` responde 200; `/eliminar-cuenta` respondía 404 antes de este cambio. No se atribuye al 404 la causa exacta del rechazo sin disponer del detalle de revisión de Google.

Mediante accesibilidad de Windows se pudo leer la pestaña autenticada de Play Console. El campo de privacidad mostraba `https://novvinapp.com/privacidad`. El cuestionario abierto tenía seleccionado «No» en recopilación de datos. Al corregir esa selección aparecieron los métodos de cuenta y el campo de eliminación; el recurso para eliminar datos conservaba `https://alvaro-mendoza-12.github.io/conectate/`. Las modificaciones de un formulario abierto no prueban que ya estén guardadas o enviadas a revisión.

## Corrección de código

- Página pública `/eliminar-cuenta`, disponible sin sesión ni JavaScript, con solicitud por correo a `administracion@novvinapp.com`, identificación de Novvin/conectate.com, verificación de titularidad, alcance, excepciones y advertencia de no enviar contraseñas ni códigos.
- Política pública con contacto, servicios Firebase de notificaciones/diagnóstico, compras mediante Google Play, infraestructura, permisos, controles y un enlace visible al recorrido de eliminación. No se inventa un plazo fijo de conservación que la operación no haya confirmado.
- Acceso a eliminación en el pie de las páginas públicas; rutas canónicas, sitemap y CSP regenerados para el HTML estático.
- Retirada de un import sin uso de `StatusView` que hacía fallar ESLint en CI.

El correo prepara una solicitud; cargar la página no envía correos ni elimina ninguna cuenta. No se ensayó la eliminación de una cuenta real, ni se afirma aquí la entrega o atención de un correo.

## Verificación local

- `npm run build`: salida 0; TypeScript y Vite aprobados. Verificación de 22 páginas públicas, CSP, sitemap, rutas, recursos y shell privado. Cero bytes JavaScript de aplicación en páginas públicas. Permanece una advertencia de tamaño de bundle privado.
- `npm run lint`: salida 0; cero errores y nueve advertencias de hooks preexistentes.
- `node --test tests/public-home.test.mjs tests/public-account-policy.test.mjs`: 75 casos, 75 aprobados, cero fallos/omisiones. Tres comprueban el recorrido público de privacidad y eliminación, sin envío ni borrado.

Logs durables: `D:/Novvin-Entregas/evidencias/2026-11-16-play173/`. Los resultados locales se distinguen del despliegue y del estado efectivo de Play Console.

## Despliegue y formulario efectivo

El despliegue `dpl_BSpvDnTRVBg3iDzyxbHz7BgWVykb` quedó listo y asociado a `novvinapp.com`. Seis GET anónimos (navegador, Googlebot y WhatsApp, ambas páginas) comprobaron estado 200, contacto, recorrido sin aplicación/sesión, ausencia de módulos JavaScript y autorización del JSON-LD por CSP; el sitemap incluye eliminación. No acreditan disponibilidad desde todos los países ni entrega de correo.

El cuestionario de Play quedó completado con 24 tipos de datos y guardado. La vista previa identifica las dos URL de eliminación como `https://novvinapp.com/eliminar-cuenta` y la de privacidad como `https://novvinapp.com/privacidad`. Google mostró «Se guardó el cambio. Envíalo a revisión en Descripción general de la publicación». No se pulsó Enviar a revisión. La publicación administrada ya estaba activada; se conservó esa configuración y la publicación de la actualización queda separada de su revisión.

El detalle del rechazo identifica la política antigua `https://alvaro-mendoza-12.github.io/Pol-ticas-de-privacidad/` por no hacer referencia al nombre de la aplicación o del desarrollador de la ficha. El rechazo de eliminación especifica el recurso externo de eliminación de datos. El formulario ahora conserva los enlaces de Novvin después de volver a abrirlo; la vista general permite enviar los cambios.

Se contrastaron búsquedas y acciones sociales adicionales. El historial Android permanece en preferencias locales. Los dos handlers activos de búsqueda de perfiles/semántica coinciden con sus bloques examinados; el archivo completo de rutas difiere y no se declara equivalente. El módulo semántico y su servidor de IA, y el middleware de observabilidad, coinciden con las fuentes examinadas: calculan resultados en memoria y registran longitud/métricas, no el término en ese registro técnico. La consulta se declaró recopilación efímera opcional para la función de búsqueda; las acciones sociales/juegos persistidas se declararon aparte. No constituye una auditoría exhaustiva de todos los SDK o versiones históricas.

## Acceso móvil y candidata posterior

Play Console ya contiene la versión 181 (9.1.41) pendiente, junto con cambios de privacidad, seguridad de datos y estado del comerciante. Se localizó el AAB del propietario fuera de `build`, en `D:/red social - live-gifts/app/release/app-release.aab`: 132.972.612 bytes, SHA-256 `8580eb6254c93347429b1a7c511c84b881a180e1b3bf79ebf7feb893f2e8ea1b`. Su manifiesto real indica `conectate.com`, versión 181/9.1.41, minSdk24, targetSdk36, sin depuración ni tráfico claro. Contiene el marcador de compilación 307ec9645a06; no se compararon sus bytes con la descarga de Play ni se ensayó su ejecución en esta continuación.

Se añade al código Android un acceso a la solicitud pública de eliminación desde Ajustes y desde el centro de privacidad. Abrir ese enlace no elimina cuentas y un navegador no disponible muestra el contacto en pantalla. El buscador de Ajustes reconoce eliminar cuenta/borrar datos. Compilaron las tres pruebas instrumentales; su ejecución sigue pendiente. El nuevo caso JVM de descubrimiento está incluido en las baterías descritas a continuación.

## Validación Android de la candidata

La primera compilación con heap de 3 GiB se interrumpió sin casos ejecutados: la ocupación de la generación antigua permanecía en 99,97% y el último intervalo se consumía en GC. Se conservaron los registros y las huellas estables; solo se detuvieron el wrapper y daemon propios comprobados. El segundo ensayo, con 4 GiB y un worker de 768 MiB, compiló producción e instrumentación Audit y terminó 3.097 casos/555 clases: 1 fallo de aserción, cero errores/omisiones y entradas/HEAD estables. No se presenta como batería aprobada.

El único fallo estaba en la expectativa de causa inmediata de `aSpeculativeCacheExceptionUsesTheRecoverableIoFailurePath`. Su aserción anterior de IOException recuperable ya aprobaba. La prueba se corrigió para exigir que el upstream inyectado se abra exactamente una vez y que la misma excepción original permanezca en la cadena causal, admitiendo envoltorios intermedios. No se cambió producción para ese ajuste. La repetición focalizada aprueba 10 casos/3 clases, sin fallos/errores/omisiones, sobre precarga, parada de voz en tres API y descubrimiento de eliminación. La repetición completa final y el paquete firmado siguen pendientes.

La repetición completa de 9.1.42/182 aprobó los 3.097 casos JVM en 555 clases y produjo los APK Audit y de instrumentación. El intento de empaquetado de producción terminó con salida 1 en `lintRelease`, por diez cadenas de recuperación ausentes en alemán, portugués, japonés, francés y chino. No produjo un AAB de producción. Se añadieron las diez traducciones en cada uno de los seis archivos, incluidos chino simplificado y tradicional. La comprobación de nombres únicos y la compilación AAPT2 de los seis XML aprobaron.

La candidata usa 9.1.42/182, porque 181 ya fue utilizada en Play. Mantiene API36 e incluye los cambios móviles de eliminación. La repetición de lint, el AAB firmado, los ensayos físicos y su subida se comprueban por separado. Los plazos operativos de retención y el cierre integral siguen abiertos. La aceptación final corresponde a Google.

## Referencias oficiales consultadas

- [Google Play: política Datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311?hl=es).
- [Google Play: requisitos del recurso externo de eliminación](https://support.google.com/googleplay/android-developer/answer/13327111?hl=es).
- [Google Play: nivel de API de destino](https://support.google.com/googleplay/android-developer/answer/11926878?hl=es).
