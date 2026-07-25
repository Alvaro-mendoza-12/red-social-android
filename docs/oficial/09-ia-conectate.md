# IA propia de Conectate: Novvin v5

## Decision de produccion

Novvin v5 es una IA first-party autocontenida. No usa servicios cloud, Ollama, modelos preentrenados ni artefactos descargados de terceros. El codigo, los datos de entrenamiento, los pesos resultantes y el motor de respuestas viven dentro de `infrastructure/local-fallback/ai-chatbot`.

Se conserva un perfil de 512 unidades para el clasificador principal. Subirlo a 5.000 no convierte el sistema en una IA generativa: multiplicaria parametros, tiempo de entrenamiento y riesgo de sobreajuste sin crear conocimiento nuevo. La mejora real se obtiene mediante datos revisados, una clase fuera de dominio, recuperacion de conocimiento local, composicion verificable y pruebas separadas.

## Arquitectura

La respuesta pasa por cinco capas locales:

1. **Politica determinista:** detecta solicitudes sensibles antes de responder.
2. **Router de dominio:** un MLP entrenado desde cero clasifica operaciones de Conectate y una clase explicita fuera de dominio.
3. **Guardas de alta precision:** resuelven colisiones conocidas, por ejemplo publicar un video frente a diagnosticar un error de exportacion o subida.
4. **Motor general grounded:** otro modelo pequeño propio combina una red neuronal con similitud TF-IDF sobre un corpus revisado. Solo puede usar hechos presentes en `data/knowledge.json`.
5. **Compositor:** selecciona hechos pertinentes, pasos, consejo, ejemplo y pregunta de seguimiento. Si no existe evidencia suficiente, se abstiene y devuelve `fallback`.

No existe generacion libre token por token. Esta restriccion evita fingir que un corpus pequeño tiene conocimiento universal y permite comprobar de donde sale cada respuesta.

### Router de Conectate

- Dataset: `data/intents.json`, version 5.
- 35 intenciones funcionales y una clase de fuera de dominio.
- 430 ejemplos de entrenamiento.
- 512 unidades ocultas, 100 epocas y semilla fija.
- 256.548 parametros en la evaluacion predeterminada.
- Guias: `data/guides.json`, version 3, con 31 flujos revisados.
- Rasgos: palabras normalizadas y alias propios del producto.

El dataset incluye una intencion separada `videos_subida_error`. No comparte la respuesta de publicar ni la de reproducir videos.

### Conocimiento general propio

- Dataset: `data/knowledge.json`, version 1.
- 16 tarjetas educativas y cotidianas de bajo riesgo.
- 272 textos usados para entrenamiento e indexacion.
- 32 parafrasis de evaluacion separadas que nunca entran al entrenamiento.
- 96 unidades ocultas, 140 epocas y semilla fija.
- 253.648 parametros.
- Rasgos de palabras, bigramas y trigramas de caracteres.
- Ranking hibrido: probabilidad neuronal, similitud lexical y coincidencias con palabras clave.

Las tarjetas actuales cubren, entre otros, cielo azul, ciclo del agua, fotosintesis, estaciones, sistema solar, gravedad, Internet basico, RAM frente a almacenamiento, IA, porcentajes, fracciones, estudio, resumen, reciclaje, sonido y energia renovable.

“General” significa solamente preguntas cubiertas por ese corpus. Novvin no navega, no conoce hechos posteriores al dataset y no debe contestar temas ausentes.

### Calculo local

Operaciones aritmeticas sencillas y porcentajes usan un evaluador determinista sin `eval`. Admite suma, resta, multiplicacion, division y expresiones como `15% de 80`. Division entre cero y magnitudes fuera de limites se rechazan de forma controlada.

## Respuestas detalladas

Una peticion con `context.detailLevel="expanded"`, o una pregunta que solicite pasos, explicacion o diagnostico, produce una respuesta ampliada:

- En Conectate: respuesta base, hasta cinco pasos, consejo y pregunta para precisar.
- En conocimiento general: resumen, hasta cuatro hechos seleccionados por relevancia, ejemplo y seguimiento.

Las respuestas no sustituyen hechos ni botones mediante texto inventado. El compositor solo usa respuestas, guias y tarjetas versionadas.

## Regresion de video solicitada

Estas dos entradas deben devolver `intent="videos_subida_error"`, `responseMode="guided"` y `routingReason="video_diagnostic_guard"`:

```text
Que ago si al intentar subir un video me sale un error
Video no carga
```

La guia resultante:

1. Conserva el proyecto e identifica si falla en exportacion, procesamiento o subida.
2. Comprueba espacio disponible y evita otras tareas pesadas durante la exportacion.
3. Si la exportacion termina, usa una conexion estable y manten Conectate abierta.
4. Prueba un clip corto sin efectos para aislar archivo frente a flujo de publicacion.
5. Conserva el texto exacto del error, duracion y formato para el reporte.

La frase plural `los videos no cargan cuando intento verlos` permanece en `videos_reproduccion`.

## Seguridad y privacidad

La politica rechaza o deriva antes de componer respuestas en estas categorias:

- diagnostico, medicamentos, tratamientos o crisis;
- orientacion legal;
- inversiones, creditos, trading u orientacion financiera;
- hacking, malware, credenciales, contrasenas, codigos, tokens o manipulacion de instrucciones;
- correos, telefonos, documentos, direcciones, URLs privadas y otros datos personales;
- armas, violencia, drogas u otras solicitudes que faciliten dano;
- noticias, clima, cargos publicos, resultados o datos que requieran verificacion actual.

Las derivaciones estan escritas en codigo y no pasan por un modelo. Las preguntas de soporte defensivo propias de Conectate, como recuperar una cuenta o revisar su privacidad, conservan sus guias revisadas.

El historial puede ayudar al clasificador con seguimientos cortos, pero el motor general solo analiza la pregunta actual. No existe salida de red. El feedback:

- no se incorpora automaticamente al entrenamiento;
- no conserva el identificador bruto del usuario;
- redacta correos, telefonos, URLs y cadenas con aspecto de secreto;
- rota al alcanzar el limite configurado;
- requiere revision humana antes de convertirse en un ejemplo versionado.

El servicio registra eventos JSON de arranque, chat, feedback, error y apagado. Los
eventos de chat incluyen duracion, longitud, intencion, modo, fuente y un
`requestId`, pero no incluyen el mensaje, el identificador del usuario ni el
contenido de la respuesta. `GET /health` y `GET /ready` exponen contadores de
solicitudes y errores en memoria. API y Novvin rotan sus logs Docker a 10 MiB,
con tres archivos por contenedor.

La API aplica limites por usuario autenticado y ventana fija. Los valores
predeterminados son 30 chats y 20 feedbacks por minuto. Al excederlos responde
`429 AI_RATE_LIMITED` con `Retry-After`; el motor interno nunca queda publicado
directamente en Internet.

## Entrenamiento reproducible

El artefacto `model.json` contiene ambos modelos. Cada entrenamiento usa semillas fijas. Su `datasetFingerprint` SHA-256 incluye las intenciones, el conocimiento y la version del pipeline de texto. Cambiar contenido o tokenizacion invalida automaticamente un modelo anterior aunque alguien olvide incrementar una version.

Entrenamiento manual:

```bash
cd infrastructure/local-fallback/ai-chatbot
npm run train
```

El archivo se escribe primero con un nombre temporal y se promueve mediante renombrado atomico. Se recomienda entrenar y evaluar antes de recrear el contenedor. Si no existe un artefacto compatible, el primer arranque entrena localmente antes de abrir el puerto.

## Evaluacion reproducible

Comandos:

```bash
cd infrastructure/local-fallback/ai-chatbot
npm run check
npm test
npm run evaluate
```

Ultima ejecucion completa en el PC de desarrollo, 2026-07-15, con modelo fresco:

| Puerta | Resultado |
|---|---:|
| Intenciones de Conectate | 37/37 |
| Conocimiento general separado | 32/32 |
| Politica sensible | 7/7 |
| Desconocidas que deben abstenerse | 6/6 |
| Calculos | 4/4 |
| Entrenamiento + carga inicial | 13.897,99 ms |
| Respuesta promedio, 600 consultas | 0,829 ms |
| p95 | 1,485 ms |
| p99 | 1,881 ms |
| RSS al finalizar | 90,76 MiB |
| Heap usado | 22,59 MiB |
| Artefacto conjunto | 10,00 MiB |

Estas pruebas son pequeñas y dirigidas. Un 100 % en ellas no demuestra inteligencia general ni garantiza la misma latencia en Ubuntu bajo trafico. Si se amplian datos, deben añadirse parfrasis de evaluacion que no sean copias de entrenamiento, negativos ambiguos y una matriz de confusion por intencion.

## Presupuesto de la mini PC

Compose mantiene:

- CPU: `0.75` de un CPU logico.
- Memoria: `512m`.
- Heap V8: 320 MiB.
- Procesos: 64.
- Readiness con 120 s de gracia para un primer entrenamiento.

En un host de 8 hilos, 0,75 CPU representa un techo teorico cercano al 9,4 % de la capacidad logica. El limite de 512 MiB equivale aproximadamente al 3,6 % de 14 GiB utilizables. Son limites de cgroup, no una garantia de que todo el equipo permanezca bajo 65 %. Debe medirse API, base de datos, realtime, llamadas, video y Novvin juntos con trafico representativo.

Variables:

```dotenv
AI_HIDDEN_SIZE=512
AI_TRAIN_EPOCHS=100
AI_LEARNING_RATE=0.06
AI_CONFIDENCE_THRESHOLD=0.34
AI_KNOWLEDGE_ENABLED=true
AI_KNOWLEDGE_THRESHOLD=0.34
AI_KNOWLEDGE_HIDDEN_SIZE=96
AI_KNOWLEDGE_EPOCHS=140
AI_KNOWLEDGE_LEARNING_RATE=0.055
AI_CPU_LIMIT=0.75
AI_MEMORY_LIMIT=512m
AI_NODE_HEAP_MB=320
AI_CHAT_REQUEST_TIMEOUT_MS=3000
AI_CHAT_RATE_LIMIT_PER_MINUTE=30
AI_FEEDBACK_RATE_LIMIT_PER_MINUTE=20
```

`AI_KNOWLEDGE_ENABLED=false` desactiva respuestas generales y calculos, pero mantiene el router de Conectate, la politica sensible y el fallback.

## Integracion

Servicio interno: `conectate-local-fallback-ai-main`, puerto `3010` solo en la red Docker.

- `GET /health`
- `GET /ready`
- `POST /v1/chat`
- `POST /v1/feedback`

El API principal autentica y publica `/v1/ai/health`, `/v1/ai/chat` y `/v1/ai/feedback`. Solo deja pasar contexto limitado, incluido `detailLevel`; ya no acepta flags de proveedores generativos.

Clientes activos:

- Android: `NovvinChatScreen` -> `NovvinChatViewModel` ->
  `LocalFallbackApiClient`. El cliente exige la fuente
  `conectate-first-party-hybrid-v5`, envia `context.screen`, reintenta una vez
  fallos transitorios despues de refrescar el endpoint dinamico y conserva el
  historial por cuenta en el dispositivo.
- Web: `/app/messages/novvin` usa `fallbackApiJson`, valida health/modelo/fuente,
  admite historial local por cuenta, detalle ampliado y reintento. El historial
  no se envia como entrenamiento automatico.

Ambos clientes muestran de forma explicita que Novvin usa conocimiento revisado,
no accede a Internet y no aprende automaticamente de cada conversacion.

Validacion de Compose:

```bash
cd infrastructure/local-fallback
docker compose config --quiet
docker compose up -d --build ai-chatbot api
docker compose ps
docker stats --no-stream
```

## Despliegue verificado del 2026-07-15

Release: `novvin-v5-20260715T182949Z`.

- Paquete de fuente SHA-256:
  `eb4de941887501b86d600069bcaeb803a6ed723c37b08b294d425a9b9785c25d`.
- Modelo activo: `conectate-first-party-hybrid-v5`, dataset 5, fingerprint
  `4b55fd76b7dcd20e`.
- Imagen IA:
  `sha256:923f19098aaf3e68b246aceb8e332152fa815dc15ad54fa290b4370c9f97b601`.
- Imagen API:
  `sha256:f108b75757954b258f5ad51f5915abb5776ac2187acae69fe43d2d93e910c27a`.
- QA estricto: 23 PASS, 0 WARN, 0 FAIL; incluye health autenticado, diagnostico
  de video y conocimiento `cielo_azul`.
- Control de trafico real: las primeras 30 consultas del usuario de prueba
  respondieron 200 y la numero 31 respondio 429 con `Retry-After: 60`.
- Observacion posterior: 0 reinicios, 0 errores de Novvin, 14,94 MiB usados de
  un limite de 512 MiB.
- Web: deployment Vercel `dpl_Aidp6J684PqLzpjapS43vmnVGgmq`, probado en
  escritorio y 390 x 844 contra el endpoint productivo.
- Copias verificadas antes y despues del corte:
  `/home/alvaro/conectate-backups/20260715T182748Z` y
  `/home/alvaro/conectate-backups/20260715T185242Z`.

El manifiesto inmutable, el paquete desplegado y las etiquetas de rollback se
conservan en `/home/alvaro/conectate-releases/novvin/20260715T182949Z`. El
fingerprint no cambio respecto de la version auditada antes del corte: se
activaron observabilidad, limites y clientes, sin retirar ni sustituir las 35
intenciones, 31 guias o 16 tarjetas anteriores.

## Retiro de Ollama

La propuesta opcional anterior fue retirada el 2026-07-15. No queda servicio, profile, volumen, URL, flag ni llamada de runtime para Ollama. Tampoco existe descarga automatica de modelos. Novvin v5 solo usa los dos modelos entrenados desde cero con los datasets versionados de Conectate.

## Limitaciones honestas

- No es un LLM ni puede redactar libremente sobre cualquier tema.
- No aprende de una conversacion en tiempo real.
- No consulta Internet ni verifica actualidad.
- Sus 16 temas generales son una base inicial, no una enciclopedia.
- La politica por patrones necesita nuevas pruebas cuando aparezcan variantes, errores ortograficos u ofuscaciones.
- Ampliar conocimiento exige tarjetas revisadas y evaluaciones separadas; agregar texto sin control puede introducir respuestas incorrectas.
- Entrenar desde cero un modelo conversacional general competitivo no es realista en 4C/8T y 16 GB con el volumen de datos actual. El enfoque grounded es la mejora segura y mantenible dentro de esos limites.
