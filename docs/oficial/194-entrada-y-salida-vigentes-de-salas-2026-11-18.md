# 194 · Entrada y salida de salas durante una respuesta tardía

18 de noviembre de 2026. Continuación de la [auditoría móvil](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md) y de las [salas de grupo](180-salas-de-voz-en-grupos-2026-11-17.md).

Si la consulta de una sala seguía pendiente, pulsar «Salir», cerrar la pantalla o elegir otra sala dejaba activa la solicitud anterior. El resultado tardío podía iniciar la primera conexión después de salir, reemplazar la sala elegida después o mostrar un error de una entrada descartada. El mismo ViewModel podía pedir una entrada y una salida al repositorio mientras ambas seguían en curso. N014 agrupa estas variantes de gravedad alta.

El ViewModel cancela la entrada reemplazada y revisa su vigencia antes de usar el detalle, conectar o publicar un error. Las entradas y salidas que ya necesitan modificar la conexión se ordenan dentro de la pantalla. Una salida explícita termina aunque la navegación cierre la pantalla durante la limpieza. La entrada de grupo conserva el micrófono silenciado. Los recorridos de contraseña, segundo plano y llamadas conservan sus interfaces existentes.

La referencia tiene seis fallos de aserción en siete casos. La candidata pasa los siete y el conjunto de 37 casos dirigidos. Las pruebas ejecutan el ViewModel con un repositorio controlado. Incluyen una respuesta que sigue pendiente pese a la cancelación y una salida solicitada mientras la admisión está en curso.

La compilación de producción iniciada con el commit `1aa044aaddb9` corresponde al bloque anterior. Esta corrección necesita su propia compilación y pruebas de voz con dos dispositivos. También queda por ensayar la transición entre dos instancias distintas de la pantalla y la entrada Android al rechazar el permiso de micrófono. Los recibos están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit194-voice-exit/`.
