# PROMPT MAESTRO — NOVVIN
## Auditoría de producto, análisis competitivo, reingeniería y evolución del ecosistema social
**Dominio:** https://novvinapp.com | **Plataformas:** Android, web, backend y servicios en tiempo real.

## MANDATO
Actúa como un equipo senior multidisciplinario: CPO/product strategist, investigador de mercado, arquitecto de software, especialista WebRTC/streaming, Android, web, backend, UX/UI, QA, DevSecOps, SRE, monetización y Trust & Safety.

La meta es evolucionar Novvin a una plataforma social integrada donde se pueda conversar, publicar, descubrir, crear, transmitir, participar, jugar y formar comunidades. Estudia TikTok, Instagram, WhatsApp, Facebook, Twitch, YouTube y Discord como referencias de patrones, nunca como plantillas para copiar. Diseña una identidad propia.

No entregues solo recomendaciones: inspecciona el proyecto real, demuestra problemas, implementa correcciones autorizadas, ejecuta pruebas y documenta evidencias. No garantices “cero errores” ni “competir por encima” sin métricas y validación real.

### Reglas obligatorias
1. No asumas que una función está terminada porque exista un botón, una página pública o una pantalla.
2. Clasifica cada función: operativa end-to-end, parcial, defectuosa, solo UI, no implementada o no verificable.
3. No sustituyas tecnologías sin comprenderlas, medirlas y documentar una decisión ADR.
4. No borres datos, secretos, ramas, historial Git, emuladores o archivos sin autorización.
5. No hagas force-push ni cambios destructivos en producción.
6. No uses datos simulados para aparentar que pagos, llamadas o integraciones funcionan.
7. No registres contraseñas, tokens ni datos sensibles en logs.
8. No afirmes E2EE, certificaciones ISO o cumplimiento legal sin evidencia.
9. Si una mejora requiere infraestructura/servicios de pago, pide autorización.
10. Android y web deben probarse por separado y en conjunto.

# FASE 1 — DESCUBRIMIENTO
Inspecciona repositorios, ramas, módulos Android/web/backend, APIs, base de datos, migraciones, autenticación, permisos, WebSockets, WebRTC, señalización, STUN/TURN, almacenamiento, CDN, transcodificación, notificaciones, colas, jobs, variables de entorno (sin mostrar secretos), CI/CD, monitorización y despliegue.

Analiza el workspace de gran tamaño y separa código fuente, medios, dependencias, cachés, builds, emuladores y datos. No trates los 60 GB como 60 GB de código.

Recorre cada pantalla, ruta, botón, menú, icono, modal, formulario y estado. Crea matriz con ruta, componente, API, modelo de datos, eventos, permisos, pruebas, diferencias Android/web y evidencia.

La web pública de Novvin anuncia mensajes, vídeos, Social, Momentos, grupos/comunidades, salas de voz, juegos/perfil de jugador y stickers animados; las páginas públicas describen cámara/pantalla compartida en salas web. La ficha de Google Play tiene una descripción más breve. Contrasta toda promesa con la implementación real. El perfil de jugador no demuestra que exista un motor de juegos.

Entregables:
- docs/product/PRODUCT_INVENTORY.md
- docs/product/FEATURE_STATUS_MATRIX.csv
- docs/architecture/CURRENT_ARCHITECTURE.md
- docs/research/COMPETITIVE_BENCHMARK.md
- docs/risks/INITIAL_RISK_REGISTER.md
- docs/quality/BASELINE_TESTS.md

No realices aún cambios masivos. Presenta diagnóstico y plan priorizado.

# FASE 2 — PRODUCT STRATEGY Y MERCADO
Usa `INVESTIGACION_MERCADO_BENCHMARK.md`, vuelve a verificar fuentes oficiales y actualiza los hallazgos.

Analiza:
- TikTok LIVE: co-host, LIVE Match, gifts y juegos interactivos.
- Twitch: Extensions, overlays, Bits, Channel Points y predicciones.
- Instagram Live: invitados y seguridad de Live Rooms.
- YouTube Live: chat, Q&A, encuestas, Super Chat/Super Stickers y moderación.
- Discord: Stage, roles de speaker/audience y eventos de comunidad.

Para cada patrón indica valor, fricción, dependencia, coste, riesgo, oportunidad y diferenciación posible. No inventes cuota de mercado, ingresos o usuarios activos. No implementes todas las ideas a la vez: prioriza por valor/impacto, complejidad, coste operativo y riesgo. Define hipótesis y experimentos con usuarios.

# FASE 3 — ECOSISTEMA SOCIAL
Audita y mejora, según lo existente:
- perfiles, avatar, portada, biografía, privacidad, seguidores/seguidos, intereses;
- feed y descubrimiento, búsqueda, recomendaciones, controles del usuario;
- publicaciones de texto/foto/vídeo/carrusel, borradores, privacidad, menciones, comentarios, reacciones, guardados, compartir y reportar;
- Momentos/estados con foto, vídeo, texto y música con derechos; audiencia, caducidad, vistas y respuestas;
- mensajería 1:1 y grupos: texto, audio, fotos, vídeo, documentos, stickers, GIF, respuestas, reacciones, edición/eliminación, lectura/entrega, typing, presencia, búsqueda, fijados y sincronización;
- grupos y comunidades: roles, canales, permisos, invitaciones, solicitudes, moderación, anuncios, eventos y salas;
- juegos: separar perfil de jugador, matchmaking, minijuegos nativos, juegos externos e interacción de directo.

# FASE 4 — NOVVIN LIVE STUDIO
Diseña un flujo completo para el creador:
- título, descripción, categoría, etiquetas, portada, privacidad y audiencia;
- programación y recordatorios opt-in;
- selección y prueba de cámara, micrófono, red y orientación;
- preview antes de emitir;
- co-hosts, invitados y moderadores;
- chat, preguntas, encuestas, mensajes fijados, reacciones;
- regalos y animaciones;
- metas colectivas y modos de juego;
- métricas y estado de calidad;
- controles para pausar, reconectar y finalizar.

Durante el directo, no mostrar estado “en vivo” si el backend no lo confirma. Si falla cámara/micrófono/red, ofrecer alternativa y explicación.

# FASE 5 — CO-HOST, INVITADOS Y AUDIENCIA
Implementa o corrige:
1. Host abre Invitar.
2. Busca contactos/seguidores elegibles.
3. Backend valida bloqueos, permisos, edad y límites.
4. Envía invitación con expiración.
5. Invitado recibe contexto, acepta o rechaza.
6. Verifica permisos y calidad de dispositivo.
7. Entra a sala de espera si procede.
8. Host aprueba.
9. Se publica el medio y el layout se actualiza para todos.
10. Host/moderador puede silenciar, retirar, bloquear y denunciar.
11. Invitado puede salir sin terminar la transmisión.
12. Reconexión segura sin duplicar participantes.
13. Usuarios bloqueados no pueden reincorporarse por rutas alternativas.

Añade solicitudes de audiencia para subir a hablar, cola de speakers y roles claros. Los límites de participantes deben surgir de pruebas de capacidad, no de cifras inventadas.

# FASE 6 — LIVE MATCH Y MODOS DE DIRECTO
Evalúa modos:
- creador solo;
- entrevista;
- co-host;
- panel;
- preguntas y respuestas;
- batalla amistosa individual/equipos;
- evento comunitario;
- directo de juego;
- sesión educativa/creativa.

Para partidas:
- reglas y duración visibles;
- puntuación autoritativa del backend;
- likes y acciones gratuitas con límites;
- regalos elegibles solo si host lo activa;
- anti-bot, anti-spam y anti-brigading;
- desempate y resultado auditable;
- opción de desactivar regalos;
- no apuestas de dinero real ni azar monetizado.

# FASE 7 — NOVVIN LIVE PLAY: JUEGOS INTERACTIVOS
Crear propuesta de producto para que el directo sea jugable. No es suficiente mostrar regalos flotando.

Prototipos a evaluar:
- trivia;
- puzzle cooperativo;
- mascota comunitaria;
- carrera colectiva;
- defensa de objetivo;
- reto por equipos;
- minijuego de reacción;
- votación de rutas;
- evento ambiental;
- sandbox de supervivencia.

Los regalos pueden activar efectos predefinidos, por ejemplo:
- aparición de objeto;
- efecto visual;
- escudo temporal en modo sandbox;
- cambio ambiental;
- aparición de enemigo en modo no competitivo;
- progreso de meta colectiva;
- desbloqueo cosmético;
- evento comunitario.

No permitir comandos arbitrarios ni código ejecutable aportado por usuarios. Crear allowlist de acciones por juego, sesión y creador. No permitir pay-to-win oculto en ranked. En competitivo, priorizar cosméticos o efectos simétricos. El espectador debe conocer el efecto y coste antes de confirmar.

### Gift-to-Game Event Bridge
Diseña un servicio de eventos que incluya:
- validación de transacción por backend;
- evento firmado;
- idempotency key;
- secuencia/timestamp;
- sesión y actor autorizados;
- payload con esquema versionado;
- rate limits por usuario/sesión;
- expiración;
- allowlist de acciones;
- reintentos controlados;
- dead-letter queue;
- auditoría y métricas.

Eventos conceptuales: `live.session.created`, `viewer.joined`, `gift.confirmed`, `viewer.vote.submitted`, `game.action.requested`, `game.action.applied`, `game.action.rejected`, `community.goal.updated`, `live.session.ended`. Adapta los nombres al contrato existente.

El cliente no puede inventar regalos, modificar saldo ni ejecutar acciones arbitrarias.

# FASE 8 — REGALOS VIRTUALES Y ECONOMÍA
Implementa solo tras verificar requisitos comerciales y de tienda:
- catálogo con coste y preview;
- animaciones 2D/3D optimizadas;
- cola, agrupación y prioridad;
- reducción de movimiento y fallback estático;
- no tapar controles/subtítulos;
- confirmación explícita;
- ledger inmutable;
- operaciones atómicas;
- unidades enteras, nunca float para saldo;
- idempotencia y prevención de doble gasto;
- recibo e historial;
- conciliación, reembolsos y chargebacks según política;
- fraude y abuso;
- payout de creadores solo si hay base legal, fiscal y operativa.

Revisar Google Play Billing, Apple IAP si iOS, edad mínima por país, reglas de moneda virtual y normativa peruana/países objetivo. No copiar la política de TikTok como si fuera ley de Novvin. No lanzar loot boxes sin revisar transparencia y reglas aplicables.

# FASE 9 — LLAMADAS, VIDEOLLAMADAS Y SALAS
Audita 1:1, conversión a grupal, agregar participantes sin cortar, invitaciones, sala de espera, permisos, cámara, micrófono, Bluetooth, altavoz, segundo plano, notificaciones entrantes, reconexión, historial, privacidad, bloqueo y denuncia.

En salas:
- host/moderador/speaker/audiencia;
- solicitar hablar;
- cola;
- cámara/pantalla;
- invitaciones;
- eventos;
- moderación;
- reconexión.

Descubre arquitectura actual P2P/SFU/MCU/proveedor. Evalúa ICE, STUN/TURN, simulcast/SVC, bitrate adaptativo, regiones y observabilidad. Compara opciones como LiveKit/mediasoup solo si aportan valor probado. No migres sin benchmark, ADR, coste, compatibilidad y rollback.

# FASE 10 — MULTIMEDIA
Verifica GIF, WebP animado, APNG, stickers, vídeo, audio, música, miniaturas, compresión, transcodificación, CDN, caché, subida reanudable, límites, moderación y derechos. Recupera animación de stickers si hay regresión. Probar en Android gama media y navegadores soportados.

# FASE 11 — CREATOR STUDIO
Evaluar panel de creador con:
- programación;
- directos y co-host;
- moderadores;
- preguntas/encuestas;
- regalos y reglas;
- Live Play;
- metas;
- analytics de concurrencia, retención, tiempo visto, interacción y calidad;
- historial y reportes.

Métricas deben derivarse de eventos reales, con consentimiento y minimización de datos.

# FASE 12 — TRUST & SAFETY
Reportar usuario, mensaje, publicación, comentario, directo, sala, regalo y juego. Bloqueo, mute, expulsión, filtros, slow mode, restricción de enlaces, anti-bot, anti-brigading, apelación, auditoría, privacidad, protección de menores y moderación en tiempo real. Heredar bloqueos entre llamadas, co-host, directos y comunidades.

# FASE 13 — ESCALABILIDAD Y OPERACIONES
Medir base de datos, índices, caché, colas, WebSocket, media server, storage, CDN, workers, transcodificación, backups, recuperación, multi-región y coste.
Pruebas controladas: mensajes concurrentes, espectadores, invitados, ráfagas de regalos, subida de vídeos y reconexión masiva.
Definir SLI/SLO para disponibilidad, latencia, errores, entrega, calidad de medios, tiempo de recuperación y coste por hora/GB. No inventar capacidad máxima.

# FASE 14 — UX/UI
Crear design system propio: tokens, colores, tipografía, espaciado, iconos, componentes, estados, accesibilidad y responsive. Mejorar web y Android con consistencia. Inspirarse en patrones, no copiar pantallas, assets, marcas ni animaciones protegidas.

# FASE 15 — SEO Y DOMINIO
Verificar `novvinapp.com`, canonical, redirects, sitemap, robots, metadata, Open Graph, favicon, structured data, enlaces compartidos, páginas indexables, imágenes originales, alt text y Search Console/Bing. No afirmar indexación hasta comprobarla. La marca debe escribirse Novvin.

# FASE 16 — SEGURIDAD Y CALIDAD
Referencias: OWASP ASVS, MASVS, API Top 10; ISO/IEC 27001/27002; ISO/IEC 25010; IEEE 29119; WCAG 2.2; NIST CSF. Revisar auth, permisos, IDOR/BOLA, inyección, XSS/CSRF/SSRF según contexto, secretos, almacenamiento, webhooks, pagos y eventos. No declarar certificación sin auditoría formal.

# FASE 17 — CAMBIOS Y PRODUCCIÓN
Trabajar por cambios pequeños, PR/commit, tests, revisión, documentación y merge controlado. Mantener `main` y ramas existentes; no borrar historial ni hacer force push. Backups y rollback para cambios críticos. Staging antes de producción. No publicar si fallan pruebas críticas o quedan riesgos críticos no aceptados.

# ENTREGABLES
- `docs/research/COMPETITIVE_BENCHMARK.md`
- `docs/product/PRODUCT_INVENTORY.md`
- `docs/product/PRODUCT_STRATEGY.md`
- `docs/product/PRD_NOVVIN_LIVE.md`
- `docs/product/PRD_NOVVIN_LIVE_PLAY.md`
- `docs/product/PRD_VIRTUAL_GIFTS.md`
- `docs/architecture/CURRENT_ARCHITECTURE.md`
- `docs/architecture/TARGET_ARCHITECTURE.md`
- `docs/architecture/REALTIME_MEDIA_ARCHITECTURE.md`
- `docs/security/THREAT_MODEL.md`
- `docs/security/TRUST_AND_SAFETY.md`
- `docs/quality/TEST_STRATEGY.md`
- `docs/quality/ACCEPTANCE_MATRIX.csv`
- `docs/operations/PRODUCTION_READINESS.md`
- `docs/risks/RISK_REGISTER.md`
- ADRs para decisiones importantes.

Cada hallazgo debe incluir evidencia, severidad, causa, corrección, pruebas, resultado y riesgo residual. Distingue hecho, hipótesis, propuesta y no verificado.

## MENSAJE DE ARRANQUE
Comienza únicamente con descubrimiento. No modifiques producción ni realices operaciones destructivas. Inspecciona el repositorio, reconstruye la arquitectura y el inventario funcional, valida las afirmaciones públicas y entrega un plan priorizado. Después de revisar el diagnóstico, ejecuta por fases. No declares un módulo terminado solo porque compile: exige pruebas end-to-end, seguridad, observabilidad y documentación.
