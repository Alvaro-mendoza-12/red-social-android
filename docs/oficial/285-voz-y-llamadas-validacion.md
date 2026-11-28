# Reconexión web y permisos de voz y llamadas

Validación del 28 de noviembre de 2026 sobre `7df3e779d8cb6b063e2dc62e11c854ccf4867efe` y el ajuste web de este cambio. La sala separa el mensaje de recuperación del botón **Reconectar voz**. En pantallas estrechas el botón pasa a otra línea y conserva una altura mínima de 44 px. El aviso de sonidos usa la misma disposición.

## Reconexión web con audio

Antes de la corrección, la sala podía volver a mostrar **Sala sincronizada** mientras el navegador seguía esperando una respuesta WebRTC. Al cambiar el identificador de señalización, ahora se cierran las conexiones entre participantes de la sesión anterior y se descartan sus candidatos ICE. La captura local sigue disponible para negociar con los participantes actuales.

Dos cuentas de prueba iniciaron sesión en Chromium y entraron en una sala casual. Tras cortar y restaurar la red del navegador que iniciaba la negociación, se confirmó la reincorporación y el aumento de paquetes RTP y energía de audio recibidos en ambos navegadores. Se usó un tono sintético de 440 Hz y señalización real con Redis privado. El aviso mantuvo 10 px de separación a 320 y 390 px y 16 px a 1024 px. El botón midió 44 px de alto en los tres tamaños.

Durante el aviso de reconexión, la barra de controles permanece en el flujo de la página para que no tape el botón. El ensayo verificó el acceso al botón en tres puntos bajo el puntero en cada ancho. La barra volvió a acompañar el desplazamiento una vez restablecida la sala.

## Escenario de voz

La prueba HTTP verificó la creación de salas casuales y de escenario, el ingreso de la audiencia con el micrófono silenciado, la solicitud de palabra y el rechazo de la autopromoción. El moderador pudo subir a una persona al escenario, silenciarla y devolverla a la audiencia. PostgreSQL confirmó que se habían eliminado los usuarios, las salas y sus participaciones de la prueba.

## Invitaciones a llamadas

Pasaron nueve comprobaciones de permisos y persistencia. Un tercero no pudo leer el chat privado original. Cinco reintentos simultáneos de la misma invitación dejaron un solo registro. Se verificaron el vencimiento, la renovación, los bloqueos antes de invitar y después de invitar, la salida individual y el cierre por el anfitrión.

## Entorno y estado del lanzamiento

Se usó la API candidata `novvin-api:qa-board-admin-196`, PostgreSQL 16.15 y la imagen de señalización `novvin-signaling:group-voice-1fe2dab42225-p2` en recursos Docker internos de Ubuntu. Los recursos de prueba se eliminaron al concluir. ESLint y 50 pruebas dirigidas de voz web pasaron. La compilación de publicación aprobó las 22 páginas públicas, metadatos y rutas privadas.

La API activa seguía devolviendo HTTP 503 en `/ready` por la antigüedad de las firmas de ClamAV. El ajuste web permanece pendiente de despliegue. La validación de audio en dispositivos físicos, redes que requieren TURN y notificaciones externas sigue pendiente. Este cambio corresponde a la web y a evidencia de integración de la API. El binario de Android vigente para la revisión es Novvin 9.2.3 (196), descrito en el informe 282.
