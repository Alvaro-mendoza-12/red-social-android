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

El cuestionario de Play quedó completado con 22 tipos de datos y guardado. La vista previa identifica las dos URL de eliminación como `https://novvinapp.com/eliminar-cuenta` y la de privacidad como `https://novvinapp.com/privacidad`. Google mostró «Se guardó el cambio. Envíalo a revisión en Descripción general de la publicación». No se pulsó Enviar a revisión. Los casos de tratamiento de búsquedas y otras acciones requieren contraste adicional; no se afirma una auditoría exhaustiva de todos los SDK o versiones históricas.

## Acceso móvil y candidata posterior

Play Console ya contiene la versión 181 (9.1.41) pendiente, junto con cambios de privacidad, seguridad de datos y estado del comerciante. Se localizó el AAB del propietario fuera de `build`, en `D:/red social - live-gifts/app/release/app-release.aab`: 132.972.612 bytes, SHA-256 `8580eb6254c93347429b1a7c511c84b881a180e1b3bf79ebf7feb893f2e8ea1b`. Su manifiesto real indica `conectate.com`, versión 181/9.1.41, minSdk24, targetSdk36, sin depuración ni tráfico claro. Contiene el marcador de compilación 307ec9645a06; no se compararon sus bytes con la descarga de Play ni se ensayó su ejecución en esta continuación.

Se añade al código Android un acceso a la solicitud pública de eliminación desde Ajustes y desde el centro de privacidad. Abrir ese enlace no elimina cuentas y un navegador no disponible muestra el contacto en pantalla. El buscador de Ajustes reconoce eliminar cuenta/borrar datos. Se preparan tres pruebas instrumentales del componente y un caso JVM de descubrimiento; aún no se han ejecutado.

La candidata siguiente usa 9.1.42/182, porque 181 ya fue utilizada en Play. Mantiene API36 e incluye los cambios móviles de eliminación. No se entrega todavía una APK/AAB 182. Su compilación, pruebas actuales, firma, ensayos físicos y subida permanecen pendientes. Los plazos operativos de retención y el cierre integral siguen abiertos. La aceptación final corresponde a Google.

## Referencias oficiales consultadas

- [Google Play: política Datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311?hl=es).
- [Google Play: requisitos del recurso externo de eliminación](https://support.google.com/googleplay/android-developer/answer/13327111?hl=es).
- [Google Play: nivel de API de destino](https://support.google.com/googleplay/android-developer/answer/11926878?hl=es).
