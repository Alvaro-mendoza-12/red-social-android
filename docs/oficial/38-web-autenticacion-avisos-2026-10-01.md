# Web: recuperación por correo, identificadores y avisos

Fecha: 2026-10-01. Proyecto `D:\red social - live-gifts`, rama `feature/live-gifts`.
Android conserva versión 117 / 9.0.20. Backend de esta revisión: `eaa86df`.

## Implementación

- **Olvidé mi contraseña:** correo → código de seis dígitos → nueva contraseña y confirmación. La verificación devuelve una autorización distinta al código y el servidor controla uso y vencimiento.
- **Volver de Gmail o recargar:** se recuperan correo y paso de introducción del código durante 15 minutos. El código, autorización de cambio y contraseñas no se guardan en el borrador. Recargar después de verificar vuelve a pedir el código.
- **Segundo factor activo:** cambiar contraseña conserva la exigencia de segundo factor y lleva al inicio de sesión cuando corresponda.
- **Registro:** nombre visible repetible e identificador público único derivado inicialmente del nombre visible, editable antes de crear la cuenta. Rojo con error y alternativas cuando está ocupado; verde con check cuando está disponible. Las respuestas auth y perfil conservan el identificador permanente sin usar el correo como handle.
- **Arranque:** la pantalla se dibuja antes de terminar comprobación de disponibilidad y descubrimiento del backend. La API configurada o en caché sigue disponible; los fallos de operaciones se muestran en sus pantallas.
- **Avisos:** novedades, mantenimiento y actualizaciones visibles al abrir o regresar, descartes por cuenta y enlace a Google Play. Administración tiene formulario, horario con zona, vista previa y confirmación antes de publicar para todas las cuentas; reintento con el mismo ID y retirada de avisos. Permisos siguen controlados por el servidor.
- **LIVE web:** fallback visual del participante usa nombre público genérico si falta nombre visible; no muestra su correo como nombre.
- **Vercel:** instalación reproducible con lockfile y build Vite. Se incluye la clave pública de descubrimiento; archivos de entorno privados siguen excluidos del envío.

## Verificación anterior a publicación

| Comprobación | Resultado y alcance |
|---|---|
| Suites web | 21 suites, 93 pruebas aprobadas |
| TypeScript y producción Vite | Build completado con API HTTPS de producción configurada |
| Registro real desde navegador | HTTP 201; username confirmado en respuesta; entrada a Mensajes; cuenta temporal eliminada comprobando previamente su ID/correo |
| Identificador ocupado | `novvin` ocupado, explicación visible y alternativas recibidas desde el servidor |
| Identificador disponible | Alternativa seleccionada con estado verde y texto de permanencia |
| Recuperación contra API real | Solicitud HTTP 200 para dirección ficticia; código incorrecto HTTP 401 y explicación. Evita revelar existencia de la cuenta |
| Recarga de recuperación | Se conserva correo/paso, código vacío. No se persiste el OTP |
| Interfaz adaptable | Registro y recuperación sin desbordamiento horizontal en 320, 390 y 1280 px; capturas de 390 px revisadas |

Estas comprobaciones usaron el frontend compilado servido localmente en el origen de Vercel mediante interceptación de archivos, con la **API real**, sin interceptar el backend. La comprobación del despliegue externo debe hacerse eliminando esa interceptación.

## Publicación y comprobación externa completadas

El bloque se publicó desde el commit `0aad395` en [Novvin web](https://red-social-android.vercel.app). Vercel confirmó `READY` y destino producción para el despliegue `dpl_4VY82BDwT2sq25Gbbv7UapG2RWqa`.

Se eliminó la interceptación del frontend antes de repetir las pruebas. El origen público respondió HTTP 200 y entregó el nuevo archivo JavaScript `index-BArKT5Dq.js`.

- Registro en la web desplegada: HTTP 201, identificador permanente confirmado en la respuesta y navegación a Mensajes. La cuenta ficticia se eliminó tras comprobar su ID y correo propios.
- Recuperación en la web desplegada: solicitud para dirección ficticia HTTP 200; código incorrecto HTTP 401 con explicación visible; al recargar se conserva el paso y el campo de código queda vacío.
- Se conserva la limitación: este ensayo de producción no comprueba recepción y uso de un correo real en Gmail. La comprobación SMTP anterior y las pruebas unitarias de cambio de contraseña se documentan por separado.

La evidencia externa está en `web-production-verification.json` y `email-recovery-production-390.png` dentro del directorio de evidencias. La publicación completa este bloque; la paridad de todos los módulos continúa pendiente. El siguiente trabajo se centra en la aplicación móvil, según la indicación del usuario.

Las pruebas unitarias comprueban los tres pasos, error y cambio de contraseña, conservación de espacios en la contraseña, validación UTF-8 y segundo factor. Esta revisión de navegador todavía no demuestra recepción y uso de un correo real en Gmail con el frontend desplegado; la entrega SMTP comprobada anteriormente no sustituye esa comprobación completa. `autocomplete="one-time-code"` permite ayuda del navegador cuando existe, sin prometer lectura automática de Gmail.

## Evidencia y límites

Capturas en [evidencias web](../evidencias-mensajeria-web-2026-10-01/). Backend y SSD en [auditoría 37](37-ssd-llamadas-multimedia-2026-10-01.md).

No se publicó un aviso global de prueba para usuarios reales. Los formularios y controles administrativos se probaron con servicios simulados; la persistencia de avisos se comprobó anteriormente con tablas aisladas.

La publicación de este bloque no certifica paridad completa de web/móvil: continúan revisión de llamadas, LIVE, salas, edición/subida de vídeos, grupos/comunidades, juegos y todas sus opciones con usuarios y dispositivos reales. Tampoco certifica 60 FPS ni latencia cero.

## Interacción

Se aplican estado visible del sistema, lenguaje familiar, control y recuperación del error, prevención, reconocimiento, consistencia y diseño simple de las [heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/). Cada operación muestra progreso y error explícitos; la publicación masiva requiere revisar el mensaje y confirmarlo.
