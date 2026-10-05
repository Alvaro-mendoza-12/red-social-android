# Encuestas: editor móvil y publicación

Fecha: 4 de octubre de 2026. Rama: `feature/live-gifts`.

## Problemas reproducidos

1. Al salir hacia Mis encuestas, el diálogo integrado podía mostrar únicamente una franja de botones. En el WebView del emulador se midió el cuerpo en **0 px**, aunque el texto existía en el DOM.
2. La cabecera y la barra inferior adhesivas ocultaban contenido; las acciones requerían desplazamiento horizontal. El formulario mostraba simultáneamente opciones de análisis, imágenes, saltos y datos de publicación.
3. Publicar devolvía un error genérico de longitud o campo obligatorio. El backend exige propósito, contacto, conservación y consentimiento; el contacto vacío podía producir el mensaje de máximo 300 caracteres.

## Cambios de funcionamiento

- Primero aparecen título, descripción opcional, preguntas, tipo de respuesta y opciones. Los campos iniciales usan ejemplos como placeholders, sin publicar preguntas ficticias como valores predefinidos.
- Imágenes, saltos, variables de investigación, secciones y presentación siguen disponibles en apartados desplegables.
- Publicar encuesta ocupa una fila propia; Guardar borrador y Vista previa permanecen visibles y permiten varias líneas con texto grande. Cabecera y acciones respetan el flujo del documento.
- Datos para publicar explica quién podrá ver las respuestas. El contacto propone el nombre real de la cuenta y mensajes dentro de Novvin cuando el servidor lo proporciona; sigue siendo editable. El propósito debe escribirlo el responsable. El uso de datos predefinido se puede revisar y editar. La conservación automática conserva el valor inicial de 180 días y el límite de 1 a 3650.
- Un borrador incompleto se guarda **solo en este dispositivo** y se recupera desde Mis encuestas. Un formulario completo se guarda en la cuenta. La pantalla indica explícitamente ambos estados.
- Antes de publicar, la validación abre el apartado pendiente, enfoca el campo y muestra su error junto al control con `aria-invalid` y `aria-describedby`. Comprueba títulos, opciones, contacto y límites, rangos numéricos/fechas, secciones vacías y saltos.
- No permite eliminar las dos últimas opciones. Al agregar opción/pregunta, mantiene los desplegables y lleva el foco al campo nuevo.
- La revisión de publicación contiene audiencia, opción de aparecer en Social y fechas/texto opcionales plegados. La clave de publicación idempotente existente permanece activa.
- El diálogo usa medidas reales de hijos y viewport, con altura máxima en píxeles y desplazamiento vertical. Evita el cálculo incorrecto de `fit-content` y unidades de viewport en determinados WebViews. Considera la altura del teclado mediante `visualViewport`.

## Criterios medibles y evidencia

| Criterio | Evidencia |
| --- | --- |
| Diálogo con título, explicación y acciones legibles; cuerpo mayor de 20 px; dentro del viewport | WebView real, texto al 160 %, Motorola Android 14 y emulador Android 15 |
| Sin ancho horizontal mayor que el viewport | Aserción DOM en WebView real y revisión navegador a 360 px |
| Borrador incompleto recuperable sin solicitud de guardado inválida | Prueba Android de guardar, salir, recuperar y conservar respuestas de opciones |
| Contacto vacío y opción de 301 caracteres identificados en su campo | Prueba específica Android; no se envía publicación inválida |
| Agregar opción conserva ajustes y enfoca control visible | Prueba Android |
| Deshacer/rehacer, guardar, publicar, responder, editar, reportar y moderar conservan comportamiento | Suite de ocho pruebas del WebView con transporte controlado |
| Publicación del editor real, respuesta de otra cuenta y resultados persistidos | `SurveyWorkbenchServerTest`: WebView empaquetado → API desplegada autenticada → PostgreSQL; una respuesta verificada |
| Prueba sin mensajes ni publicaciones públicas de QA | `social=false`; se verifica ausencia de publicación Social; se eliminan exclusivamente campaña y formulario propios |

Resultado final: **9/9 pruebas Android correctas en cada dispositivo** (8 de interfaz y 1 con servidor real). Teléfono: 20,681 s; emulador: 29,226 s. Compilación y suite unitaria de la aplicación correctas.

Se conservaron los intentos fallidos: las primeras modificaciones resolvían el diálogo en navegador, pero el WebView del emulador mantuvo alturas incorrectas; la suite detectó el fallo y se reparó antes de darlo por cerrado. Una ejecución intermedia física terminó con “Process crashed”, sin excepción Java en el buffer de fallos; las suites posteriores completas pasaron. No se atribuye ese evento a una causa no demostrada.

## Alcance y límites

La participación continúa identificada, voluntaria y con consentimiento explícito. La encuesta publicada mantiene una copia de su revisión original. No se eliminan controles de privacidad, permisos, exportación o revisión del backend. Esta evidencia cierra el editor solicitado en las capturas; la certificación global de los otros once requisitos sigue en `AUDITORIA_CIERRE_11_PUNTOS.md`.

Fuentes principales: `workbench.js`, `style.css`, `SurveyWorkbenchTest.kt` y `SurveyWorkbenchServerTest.kt`. Logs y credenciales de QA permanecen privados bajo `.codex-tmp`; las capturas de interfaz no contienen información humana.
