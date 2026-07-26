# Implementación del benchmark funcional de Novvin

Fecha: 26 de julio de 2026  
Documento analizado: `Benchmark_funcional_y_hoja_de_ruta_Novvin.pdf` (21 páginas)

## Criterio aplicado

El documento se trató como una hoja de ruta de producto, no como una orden para
activar treinta funciones simultáneamente. Cada función se implementará con
contrato, estados de error, pruebas y despliegue verificable. No se eliminó
ninguna funcionalidad existente: cuando dos capacidades se solapen se
integrarán en una sola experiencia conservando sus datos y permisos.

Antes de comenzar se creó y verificó el respaldo local
`backups/pre-roadmap-20260726-2b2c81d` y el respaldo del servidor
`/home/alvaro/conectate-backups/20260726T191221Z`.

## Incrementos implementados

### Fase 0 — estabilidad y trazabilidad

- **B26, pruebas de contrato:** la CI valida sintaxis y contratos del backend,
  salud del sistema, feed, administración y seguridad. Los endpoints nuevos
  tienen pruebas independientes.
- **B27, identidad de versión:** Android muestra versión, código, commit y
  entorno. Cada solicitud envía esos mismos datos junto con un identificador de
  petición para correlacionar errores entre aplicación y servidor.
- **B29, panel de salud:** el servidor expone el estado independiente de API,
  base de datos, almacenamiento, tiempo real, voz y directos. El panel de
  administración lo presenta con estados y mensajes comprensibles.
- El despliegue registra el commit activo en el servidor y `/ready` publica las
  capacidades compatibles. Los errores HTTP incluyen un `requestId` sin
  exponer tokens ni contenido privado.

### Fase 1 — control del feed

- **B01, Seguidos/Descubrir:** hay dos vistas independientes y la elección
  predeterminada persiste en el dispositivo. `Seguidos` es cronológico y
  `Descubrir` excluye la repetición de cuentas ya seguidas. El servidor conserva
  el modo heredado para pantallas de perfil y clientes anteriores.
- **B02, “¿Por qué veo esto?”:** una publicación recomendada muestra entre una
  y tres razones basadas en conexión, intereses, afinidad, tipo de contenido,
  conversación o actualidad. Desde el mismo panel se puede pedir ver menos de
  una cuenta o silenciar un tema.
- **B03, temas bloqueados:** palabras, temas y cuentas ocultas se guardan de
  forma persistente. La persona puede revisar y revertir sus preferencias desde
  el selector del feed.

### Fase 1.1 — biblioteca personal

- **B04, colecciones de guardados:** cualquier publicación visible se puede
  guardar en la colección predeterminada o en una colección creada por la
  persona. Cada elemento admite etiquetas normalizadas y una nota privada.
- La biblioteca sincronizada permite buscar por colección, autor, contenido,
  etiqueta o nota. También permite abrir la publicación y quitarla de la
  colección sin borrar la publicación original.
- El servidor vuelve a comprobar permisos al guardar y al leer. Si la
  publicación fue eliminada, pasó a ser privada o dejó de ser visible, no
  expone su contenido y muestra un elemento no disponible.
- La eliminación de una publicación limpia sus referencias guardadas. La
  eliminación completa de una cuenta también borra colecciones e items para no
  dejar registros huérfanos.

## Estado del backlog B01–B30

| ID | Estado | Decisión |
|---|---|---|
| B01 | Implementado en este incremento | Dos feeds, preferencia persistente y compatibilidad API |
| B02 | Implementado en este incremento | Motivos comprensibles y controles inmediatos |
| B03 | Implementado en este incremento | Temas/cuentas silenciados y reversibles |
| B04 | Implementado en este incremento | Colecciones, etiquetas, notas, búsqueda y privacidad sincronizadas |
| B05 | Planificado | Resumen configurable sin sustituir notificaciones urgentes |
| B06 | Próximo bloque | Unificar motivos existentes con regla y evidencia visible |
| B07 | Próximo bloque | Apelación, estados y auditoría con revisión humana |
| B08–B13 | Fase 2 | Ampliar comunidades existentes con roles, canales y eventos |
| B14 | Fase 3 | Añadir modo escenario sin reemplazar las salas casuales |
| B15 | Implementación técnica existente; falta matriz física | Reconexionar voz y restaurar estado entre Wi‑Fi/datos |
| B16–B17 | Fase 3/4 | Solo opt-in, consentimiento visible y política de retención |
| B18–B20 | Fase 3 | Moderación en vivo, repeticiones y remix con atribución |
| B21–B25 | Fase 4 | IA aislada, opcional, auditable y con borrado |
| B26 | Implementado en este incremento | Contratos en CI |
| B27 | Implementado en este incremento | Versión/commit/entorno visibles |
| B28 | Parcial | CI y artefactos internos listos; activación porcentual requiere Play Console |
| B29 | Implementado en este incremento | Salud API/DB/storage/realtime/voz/live |
| B30 | Planificado | Exportación de perfil, posts y futuras colecciones |

## Verificación de este incremento

- Backend: comprobación sintáctica y pruebas de salud, feed, administración y
  seguridad, todas aprobadas.
- Android: 31 suites, 132 pruebas, 0 fallos, 0 errores y 0 omitidas. Kotlin y
  Android Lint aprobados.
- Debug: `app-debug.apk`, firmado con la clave Debug, SHA-256
  `04102C81E48D76895636ACDC6E67C7CEF7F3678FBF2DB6184B010DB8C114D1DD`.
- Release: `app-release-unsigned.apk`, R8 y reducción de recursos aprobados,
  SHA-256
  `E4F3434DB726DC7ACCE490E69D18553EEA735E56EAF572F99F4C01B47270B2F4`.
  Continúa sin firma porque la clave oficial de Play Store no está disponible
  en este entorno.
- Servidor: despliegue `conectate-mini-pc-20260726T212847Z`, commit
  `2433fcd915d9`, precedido por el respaldo
  `/home/alvaro/conectate-backups/20260726T212857Z`.
- La comprobación pública de `/ready` y `/v1/system/health` resultó operativa
  para API, base de datos, almacenamiento, tiempo real, voz y directos.
- B04: prueba integral pública aprobada con la cuenta ficticia
  `module-saved.1785101637041@example.local`. Cubrió crear, guardar, buscar,
  quitar y limpiar referencias al borrar una publicación. La autoeliminación
  terminó sin error y una consulta posterior confirmó `0` cuentas con ese
  correo.
- Dispositivos: las llamadas, salas y directos seguirán requiriendo la matriz
  física entre dos teléfonos y redes distintas antes de declararse totalmente
  verificados. No había ningún dispositivo visible por ADB durante este cierre.
