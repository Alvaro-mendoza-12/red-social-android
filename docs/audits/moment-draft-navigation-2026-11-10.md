# Momentos web: conservación del borrador y aislamiento de sesión

Base remota comprobada: `correcciones-dot`, `77480351eda89a53be841e3abf2f2141463b7893`. La preparación y revisión se hicieron en una copia local aislada. La integración se publica exclusivamente en `correcciones-dot`, sin cambiar `main` o `feature/live-gifts` ni promover a Production. La publicación puede activar automáticamente CI y Vercel; cada resultado y entorno requiere comprobación independiente.

## Problema y decisión

El editor guardaba sus campos en el componente. Atrás/Adelante o cambiar de sección desmontaba la página y perdía el borrador, aunque el aviso de cierre/recarga funcionaba. Se conserva ahora un único borrador en la memoria de la pestaña, vinculado a la identidad y generación de sesión existentes. No se cambia el router ni se guardan fotos, audio o texto privado en almacenamiento persistente.

- La foto o video, descripción, duración, audiencia, respuestas, texto superpuesto y música seleccionada sobreviven a la navegación interna.
- Al regresar se ofrece **Continuar borrador**, sin abrir el editor ni publicar automáticamente. La selección y lectura local de audio también sobreviven al desmontaje.
- Cerrar, Escape y el fondo conservan la confirmación para descartar. La recarga/cierre de pestaña advierte incluso mientras el usuario está en otra sección.
- Cerrar sesión, cambiar de cuenta/proveedor o invalidar el backend elimina el borrador y cancela la preparación local de audio. Las respuestas tardías no pueden restaurarlo ni borrar el borrador de una sesión posterior.
- Una publicación en curso mantiene un bloqueo síncrono entre desmontajes. Regresar no inicia otra solicitud. El éxito limpia el borrador y actualiza el feed; el fallo conserva la selección para un reintento explícito.

## Límite de sesión al publicar

La revisión detectó además un defecto anterior: comprobar solo el ID de cuenta no impide que una subida pendiente continúe tras cerrar sesión e iniciar otra con la misma cuenta. El flujo captura ahora la identidad de sesión, la comprueba entre cada subida y la publicación, y la transmite solo como opción interna a los helpers de subida/POST.

El cliente Ubuntu comprueba esa opción antes y después de obtener el token, antes de refrescar por un 401 y antes de aceptar la respuesta. La opción se elimina antes de llamar a `fetch` y no forma parte del cuerpo HTTP. Los demás consumidores no necesitan pasarla. La ruta de compatibilidad también comprueba la sesión entre subida e inserción.

Esto impide que una operación vieja inicie el siguiente paso con una sesión nueva. **No revierte una solicitud que ya llegó al servidor**, ni promete eliminar archivos que una subida anterior ya haya creado.

## Verificación local

Node 24.22.1, datos sintéticos y servicios externos simulados en las pruebas:

- Regresiones observadas antes de corregir: pérdida al navegar, pérdida de lectura de audio, feed sin actualizar tras finalizar fuera del editor, continuación de una publicación tras renovar sesión y refresco de sesión por un 401 tardío.
- `npm test -- --runInBand`: **174 suites, 992 pruebas aprobadas**.
- Las cuatro suites específicas del editor/navegación/publicación/sesión: **33 pruebas aprobadas**.
- `VITE_FALLBACK_API_URL=https://novvin-test.invalid npm run build`: aprobado, incluido TypeScript y las 19 páginas de la puerta SEO.
- `npm run test:node`: **98 aprobadas**.
- `npm run lint`: cero errores y siete avisos existentes de dependencias de hooks.
- `git diff --check`: aprobado.
- Revisión independiente final: sin hallazgos accionables restantes en la ruta Ubuntu; 82 pruebas en 14 suites, TypeScript y diff aprobados. También se comprobaron ocho escenarios del flujo real con respuestas de red simuladas, incluidos reingreso a la misma cuenta, refresco y 401 tardíos.

El primer intento de build falló por faltar `vercel.json` en la copia parcial local. Se incorporó la copia idéntica al blob remoto `133db55164b9ee19a264749b10a884dc4d0e12a4`, sin modificar su contenido, y se repitieron build y pruebas Node con éxito.

## Límites pendientes

- El borrador es de esta pestaña y sesión; no se recupera tras recargar, cerrar la pestaña, perder el proceso o cambiar de dispositivo. El navegador puede omitir `beforeunload` en algunos cierres móviles. No se afirma recuperación durable.
- La ruta heredada Supabase tiene comprobaciones entre pasos, pero no se verificó su espera interna de token del SDK; la revisión de la frontera de envío se centra en el backend Ubuntu vigente.
- Las pruebas de navegación usan el router real en JSDOM y StrictMode. Quedan pendientes los recorridos de navegador/dispositivo real, teclado/foco y advertencia nativa de salida.
- Sigue el aviso preexistente de un bundle de aproximadamente 1,1 MB minificado. Esta corrección no cierra la auditoría general ni certifica producción.

## Estado de la base al integrar

En la ejecución [42761248033](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/42761248033), backend y client/realtime aprobaron. Android terminó fallido con la anotación «The hosted runner lost communication with the server» después de compilar Kotlin/Java; no hubo una conclusión aprobada de lint o pruebas JVM y release-smoke se omitió. No se atribuye la pérdida de comunicación a una causa concreta de CPU, memoria o red. Al estar terminada esa ejecución, el nuevo commit puede obtener una comprobación propia sin duplicar una ejecución activa.
