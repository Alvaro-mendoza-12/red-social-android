# 153 · Juegos, Buscar personas y Botella al mar

## Juegos

Android organiza la pantalla inicial en **Catálogo**, **Jugadores** y **Actividad**. Elegir un juego lleva a la búsqueda con ese filtro. Actividad reúne solicitudes, conversaciones, coincidencias y partidas acordadas. El catálogo y los perfiles mantienen los controles existentes de privacidad y compatibilidad.

La web ofrece entradas para catálogo, jugadores, partidas, solicitudes y perfil. El catálogo puede aparecer mientras llegan las consultas de actividad y jugadores. Una respuesta antigua de jugadores se descarta cuando ya se solicitó otra búsqueda.

## Buscar personas y Botella al mar

El buscador Android prioriza nombre y usuario. La entrada de descubrimiento aparece antes de escribir y abre Personas cerca y Botella al mar. El estado de Personas cerca sustituye el barrido continuo del radar y muestra el número de perfiles y el radio aplicado. Las distancias siguen siendo aproximadas.

En web, Buscar personas enlaza directamente a Personas cerca o Botella al mar. La vista de descubrimiento distingue esas opciones de Conversaciones. Mover el radio cambia el valor local hasta pulsar Aplicar radio, y las consultas periódicas de chats se inician al abrir Conversaciones.

Botella al mar muestra la recepción, el envío y los estados de entrega. El borrador Android permanece al recrear la pantalla. En web la recepción es voluntaria. Una botella queda disponible hasta 24 horas y solo una persona elegible puede tener la oferta vigente. Aceptarla abre un chat anónimo y pasarla deja la botella disponible para otra persona. La corrección de entrega también está publicada en las ramas estables.

## Alcance

- Android compiló con `:app:compileDebugKotlin`, JDK 21 y un trabajador. La compilación terminó en 5 min 47 s. La fluidez durante el uso sigue pendiente de medir en dispositivo.
- En Chromium, con respuestas controladas y anchos de 390 y 1440 px, el catálogo apareció antes de liberar la primera consulta de jugadores. La respuesta anterior se descartó tras buscar por juego. Mover el radio cuatro veces hizo cero consultas y pulsar Aplicar hizo una. Pasaron el acceso desde Buscar personas, el arranque de recepción y la aceptación de una botella.
- Aprobaron siete pruebas web focalizadas, la compilación de producción web con 19 páginas prerenderizadas y 13 controles de API. El ensayo de entrega con varias cuentas usó PostgreSQL local.

Los rediseños y los accesos nuevos se mantienen en la rama experimental. En este bloque se validó la compilación de Android sin instalar una APK nueva. La entrega entre cuentas reales requiere su propio ensayo.
