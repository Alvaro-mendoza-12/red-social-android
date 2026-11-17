# 176 · Teclado y desplazamiento de chats en Android

Los chats privados, de grupo y de comunidad leían la altura animada del teclado durante la composición. El panel de stickers ahora mide la altura durante el cálculo del diseño y la recuerda cuando el teclado se estabiliza. La lista evita recomponerse por esa lectura del panel. [Insets en Compose](https://developer.android.com/develop/ui/compose/system/insets-ui)

El corrector abre su sesión con la primera consulta elegible después de la pausa de escritura. Cada edición invalida los resultados anteriores en memoria. La cancelación del sistema se solicita al enviar una consulta de reemplazo, y la secuencia descarta las respuestas tardías. [Sesión del corrector de Android](https://developer.android.com/reference/android/view/textservice/SpellCheckerSession)

El historial omite los saltos al último mensaje cuando ya está al final o Compose llega allí con el primer ajuste. Actualiza el seguimiento de mensajes al terminar el deslizamiento y conserva la desaceleración nativa durante el gesto.

Compilaron los ocho archivos modificados con Kotlin y el compilador de Compose del proyecto. Pasaron nueve pruebas JVM, cuatro del corrector con Robolectric aislado y cinco del seguimiento del historial. La prueba nueva del corrector reprodujo en la referencia una consulta al servicio con el editor vacío. Los registros y huellas de entradas están en `D:/Novvin-Entregas/evidencias/2026-11-17-chat-input-scroll/`. Quedan pendientes la medición de tiempos y cuadros en un teléfono y la generación de un paquete Android con estos cambios.
