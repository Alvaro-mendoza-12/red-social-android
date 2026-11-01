# Revisión móvil en curso: entrada, cuentas y comentarios

Este es un avance de correcciones comprobadas, no el cierre de la revisión integral ni una declaración de que toda la aplicación esté lista. Se continúa primero en Android; la web y el PPTX de aproximadamente 10,5 MB siguen pendientes para su fase.

## Problemas corregidos

- Los comentarios abiertos desde un perfil mostraban acciones de reacción, edición y menciones con callbacks vacíos. Ahora usan el mismo modelo y operaciones autenticadas del detalle de publicación. Los callbacks visibles de `CommentItem` son obligatorios en compilación.
- El formulario conservaba poco contexto ante un fallo: eliminaba el borrador antes de confirmar el envío y trataba una lectura fallida como comentarios vacíos. Conserva texto y edición hasta confirmación, muestra error con reintento y limita la espera de lectura.
- La construcción de hilos ocultaba respuestas cuyo padre ya no estaba disponible y podía recursar indefinidamente con datos cíclicos. La organización iterativa conserva cada comentario una vez y limita la sangría visual.
- Una captura de archivo entrante podía terminar después de recibir una notificación o enlace y reemplazar su destino. La nueva entrada invalida también esa captura pendiente.
- Los efectos de suspensión seguían suscritos a la cuenta anterior cuando se cambiaba de cuenta sin cerrar sesión. La suscripción y la decisión de salida ahora pertenecen a la cuenta y al destinatario correspondiente.
- Recuperación, migración, contraseña y seguridad podían recibir respuestas antiguas después de un cambio de cuenta o dejar el formulario cargando si fallaba la hidratación local. Se reutiliza el control de generación de sesión, se gestionan excepciones y cancelación, se bloquea el envío duplicado y se cancelan solicitudes al abandonar el formulario. Se conserva la contraseña exacta, incluidos sus espacios.
- Ajustes abierto desde el navegador raíz carecía de los destinos Idioma, Ayuda y Términos. Se registraron con las pantallas existentes y se comprobó navegación de ida y vuelta en el grafo real.

## Comprobación ejecutada

`testDebugUnitTest` compiló la aplicación y ejecutó **33 pruebas seleccionadas, 8 clases, sin fallos, errores ni omisiones**: comentarios vacíos/error/espera/cancelación/reintento; hilos huérfanos, cíclicos y de 10.000 niveles; hidratación de acceso; cancelación de formularios y respuesta atrasada; límites de cuenta/suspensión; guardia de mutaciones de sesión; entrada de archivos y navegación real de Ajustes.

La ejecución anterior detectó una aserción de identidad de excepción incompatible con la recuperación de trazas de las corrutinas; se cambió para comprobar tipo, mensaje y conservación de la causa original. No se ocultó ningún error del servidor.

No se generó una APK nueva, no se usó emulador en esta tanda y no se ejecutaron cambios de contraseña, correos ni pagos sobre usuarios reales. Las mejoras de presentación de comentarios todavía requieren comprobación visual. La compilación y estas pruebas no sustituyen la revisión de los demás módulos.

## Continuación obligatoria

Quedan por cerrar, entre otros, el borrador al responder, la disponibilidad de comentarios posteriores al número 50 y las respuestas atrasadas del resumen del hilo. Después continúa la lectura de los módulos móviles pendientes y sus contratos de servidor, manteniendo una matriz de cobertura por archivo y recorrido. No se pasa a web hasta completar la fase móvil.

## Segundo bloque comprobado

Se completaron esos tres pendientes de comentarios: responder conserva el texto; una revisión del borrador impide que un ACK viejo borre texto nuevo, incluso cuando se reescribe lo mismo; y las lecturas de resumen se cancelan/invalidadan al cambiar cuenta, publicación o contenido. Las menciones y el cierre de comentarios del perfil invalidan también sus lecturas anteriores.

El móvil estaba recortando a 50 un resultado que el servidor Ubuntu ya había enviado completo. Ahora conserva el resultado acotado existente del servidor y presenta ventanas de 50 mediante «Ver más comentarios», sin añadir peticiones. Una notificación puede revelar directamente un comentario posterior a esa primera ventana. No se amplió el máximo de 50.000 filas que ya aplica ese endpoint; paginar también la consulta del servidor sigue siendo una mejora pendiente para publicaciones masivas.

El envío de stickers de comentario conserva la respuesta hasta confirmación y evita envíos simultáneos. El detalle guarda el texto ante recreación y separa borradores y diálogos por cuenta y publicación. Los errores visibles de estas acciones usan mensajes habituales.

Se ejecutó después **toda la suite Android: 2.092 pruebas, 439 clases, 0 fallos, 0 errores, 0 omisiones**. Incluye las pruebas nuevas de ventanas, destino de notificación fuera de la primera página y conservación del borrador ante ACK atrasado. La invalidación de resumen/menciones está verificada por lectura de flujo y compilación; no se afirma una prueba instrumental nueva de esos controles. Continúan pendientes la revisión de otros módulos, la comprobación visual y una APK nueva.

La lectura siguiente identificó concurrencia pendiente de corregir en captura/persistencia de cuentas guardadas. Ese hallazgo y los demás módulos revisables mantienen abierta la fase móvil.
