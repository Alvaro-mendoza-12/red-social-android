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

## Límites y pasos pendientes

Comprobar el despliegue anónimo de ambas páginas, guardar sus enlaces en Play Console y completar el cuestionario con las prácticas reales de la app y sus SDK. Las declaraciones no deben afirmar que Novvin no recopila datos. La aceptación final corresponde a Google y requiere un nuevo envío.

El código Android configura `targetSdk = 36`, versión 9.1.41/181. Esto no verifica el manifiesto de un AAB concreto ni acredita que Google haya recibido ese binario. La entrega nueva, firma, pruebas físicas, el acceso móvil a eliminación, los plazos operativos de retención y el cierre integral siguen abiertos.

## Referencias oficiales consultadas

- [Google Play: política Datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311?hl=es).
- [Google Play: requisitos del recurso externo de eliminación](https://support.google.com/googleplay/android-developer/answer/13327111?hl=es).
- [Google Play: nivel de API de destino](https://support.google.com/googleplay/android-developer/answer/11926878?hl=es).
