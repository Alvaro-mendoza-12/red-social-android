# 130 · Navegación compacta, asistente Novvin y portada real

13 de noviembre de 2026, America/Bogota. Continúa la revisión integral. Este bloque responde a las capturas y correcciones del propietario posteriores al 129; no representa una APK ni un despliegue.

## Comportamiento

El logo Novvin incorpora la flecha para contraer y expandir el menú. Se elimina la fila separada «Contraer menú». Llamadas y Salas de voz dejan la barra principal; sus destinos funcionales siguen disponibles desde los recorridos existentes. Explorar Novvin y Cerrar sesión caben incluso en la comprobación de escritorio de 1153 × 480. El estado contraído continúa recordándose.

El tema oscuro usa superficies azules. La selección se diferencia del estado al pasar el puntero. Se ocultan las barras de desplazamiento en la aplicación y la web pública, incluido el catálogo de stickers, conservando el desplazamiento y el foco de teclado. Esto no convierte los historiales en contenido recortado.

El asistente se llama **Novvin**, confirmado por el propietario. Presenta un solo encabezado, respuestas en el flujo de conversación, mensajes propios alineados a la derecha y compositor inferior con crecimiento acotado. Se conservan cancelación, reintento, copiado, sugerencias y separación por cuenta. Su servicio de IA no se certifica con esta prueba visual: la respuesta del recorrido de navegador es sintética e interceptada.

El chat reduce el encabezado a 58 px y el compositor a 52 px en los seis tamaños medidos. El menú ofrece accesos a Programar mensaje, Traducción y Crear encuesta, conectados a las herramientas existentes. La comparación Android sigue **parcial**: Vaciar, Fondo, Bloquear, Reportar y Actividad no quedan declarados equivalentes por añadir estos tres accesos.

La portada usa fondo azul claro, una entrada principal a la web, descarga Android y tres descripciones breves. Las guías permanecen accesibles. Se retiran las ilustraciones de interfaces inventadas: las páginas públicas emplean las capturas reales disponibles y la portada presenta una conversación sintética abierta en la aplicación real, identificada como demostración.

## Validación

La [evidencia del navegador](../quality/evidence/130-navegador.json) registra los controles y las medidas. El script reproducible es `NoWing-web/scripts/verify-web-layout-local.cjs`, con un gateway HTTPS exclusivamente local, cuentas ficticias preexistentes y un chat de demostración sembrado en el laboratorio. Requiere `QA_ACCOUNTS_FILE`, `QA_DEMO_CHAT_FILE`, `QA_OUTPUT_DIR` y, cuando corresponda, `QA_CHROME_EXECUTABLE` y `QA_GATEWAY_PORT`; Playwright debe estar disponible. Bloquea la resolución de otros dominios durante el ensayo.

- Navegador: seis tamaños, de 375 × 812 a 1440 × 900, sin desbordamiento horizontal; menú accesible y sin desbordamiento en escritorio; desplazamiento real de un historial de 35 mensajes; catálogo de stickers sin barra visible; tres acciones del menú; asistente y portada. El JSON identifica los once controles agrupados.
- TypeScript/Vite y 21 páginas públicas aprobados. Las páginas públicas conservan cero bytes de JavaScript de aplicación. El aviso de un chunk privado superior a 1 MB continúa como trabajo de rendimiento, no como fallo ocultado.
- La suite general web anterior al último ajuste de compartidos aprobó 1206 pruebas en 189 suites. La ejecución posterior de asistente y menú aprobó once pruebas en dos suites; las de enlaces y middleware se detallan en 131. Son ejecuciones solapadas, no cifras acumulables de recorridos.
- Las 124 pruebas Node de salida pública y metadatos aprobaron en el worktree de desarrollo después de generar el HTML actualizado. La candidata copiada a main volvió a compilar y aprobó sus 123 pruebas: la diferencia es una prueba de cabeceras ajena que se conserva sin publicar. Una ejecución previa contra el artefacto anterior detectó correctamente que todavía contenía metadatos genéricos; se conserva ese diagnóstico en el laboratorio.
- Lint de los archivos de producción modificados: sin errores ni avisos, según la evidencia estructurada final.

[Chat real de demostración](../quality/evidence/130-chat-web.png), [menú contraído](../quality/evidence/130-menu-plegado.png), [asistente con respuesta sintética](../quality/evidence/130-asistente.png) y [portada](../quality/evidence/130-portada.png).

## Límites y continuidad

No se ensayaron teclado virtual, lector de pantalla ni WhatsApp en un teléfono físico. La ocultación visual de las barras no certifica todos los apartados. La paridad completa del menú Android continúa abierta. El bloque móvil 128 permanece local; sus catorce regresiones se reprodujeron antes de la corrección, que todavía no se ha publicado ni incluido en una APK. La revisión integral y los requisitos restantes siguen abiertos.
