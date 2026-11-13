# NOVVIN — Investigación de mercado y benchmark
**Consulta:** 12-11-2026 | **Producto:** https://novvinapp.com

## Hallazgos públicos de Novvin
La web oficial presenta mensajes privados con archivos y stickers, vídeos, publicaciones Social, Momentos, grupos/comunidades, salas de voz, perfil de jugador/juegos y ayuda para stickers animados. La página de salas de voz describe micrófono, cámara, pantalla compartida y modo cine en web. La página de juegos describe un perfil de jugador para descubrir personas con intereses similares; eso no demuestra que Novvin ya ejecute juegos interactivos. La ficha pública de Google Play anuncia vídeos cortos, mensajería, grupos, comunidades y perfiles, en una descripción más breve. Por tanto, el agente debe verificar cada función en código y en ejecución, distinguiendo promesa pública, interfaz, implementación, funcionamiento end-to-end y pruebas superadas.

## Benchmark: patrones que merecen estudiarse
### TikTok LIVE
- Co-host: varios creadores pueden emitir juntos e invitar a otros.
- LIVE Match: modos individuales/equipos en los que likes y regalos elegibles pueden contribuir a una puntuación.
- LIVE Interactive Games: el portal TikTok Gaming describe regalos conectados a comandos de juego como objetos, mejoras, enemigos, escudos o cambios del entorno.
**Aprendizaje para Novvin:** que el directo sea participativo y jugable, no solo vídeo + chat. Crear retos y modos controlados por el creador, con reglas transparentes y límites. No copiar interfaz, activos ni nombres de TikTok.

### Twitch
- Extensions: paneles, componentes y overlays interactivos dentro de la experiencia de streaming.
- Bits en extensiones pueden activar efectos, overlays, encuestas, trivia, juegos o funciones especiales.
- Channel Points y predicciones ofrecen participación comunitaria mediante puntos.
**Aprendizaje:** construir un Live Play Event Bridge/SDK seguro, con eventos permitidos, minijuegos, encuestas, metas colectivas y recompensas de comunidad. Separar puntos gratuitos de moneda comprada y no crear apuestas.

### Instagram Live
- Live Rooms permitió al anfitrión incorporar hasta tres invitados simultáneos (referencia funcional publicada en 2021).
- Se contemplan entrevistas, tutoriales, conversaciones y herramientas de moderación.
**Aprendizaje:** invitación clara, incorporación de invitados sin cortar el directo, estados visibles y seguridad heredada durante la sesión. La fuente es histórica y no describe toda la oferta actual.

### YouTube Live
- Chat en tiempo real, replay bajo ciertas condiciones, encuestas, Q&A, Super Chat/Super Stickers y moderación.
**Aprendizaje:** preguntas destacadas, encuestas, chat filtrado con controles, mensajes fijados y replay cuando proceda.

### Discord
- Stage Channels separan anfitriones/moderadores, speakers y audiencia.
- Se usan para eventos, AMAs, conversaciones y sesiones de comunidad.
**Aprendizaje:** diferenciar llamadas privadas, salas sociales abiertas y eventos tipo escenario con solicitudes para hablar.

## Diferenciación propuesta para Novvin
1. Continuidad: una conversación puede evolucionar a llamada, invitar participantes y, con consentimiento, convertirse en sala/evento/directo cuando el diseño lo permita.
2. Directos jugables: espectadores participan mediante retos, votos, juegos y metas.
3. Regalos funcionales: además de animaciones, activan efectos predefinidos en minijuegos o modos sandbox.
4. Comunidades vivas: cada comunidad organiza eventos, salas, directos, retos y partidas.
5. Android + web con estados coherentes y capacidades explicadas.
6. Creator Studio con moderación, invitados, reglas de regalos, programación y analítica.
7. Seguridad, accesibilidad y gasto responsable desde el diseño.

## Ideas de producto a validar
- **Novvin Live Studio:** preparar directos, título, categoría, portada, privacidad, prueba de cámara/micrófono/red, co-host, moderadores, chat, encuestas, regalos, modo juego y métricas.
- **Novvin Live Play:** trivia, puzzle cooperativo, mascota comunitaria, carrera colectiva, defensa de objetivo, retos de equipo y minijuegos de reacción.
- **Gift-to-Game:** regalos confirmados pueden disparar acciones allowlisted: efecto visual, objeto, escudo temporal en modo sandbox, evento ambiental o progreso de meta comunitaria.
- **Creator tools:** cola de preguntas, filtros, slow mode, encuestas, metas, moderadores, analytics.
- **Comunidad:** eventos programados, recordatorios opt-in, canales temáticos, roles y actividades.

## Riesgos y condiciones
- Regalos y monedas virtuales: ledger inmutable, operaciones atómicas, idempotencia, saldo en unidades enteras, confirmación explícita, historial, prevención de doble gasto, conciliación, fraude y reembolsos según política.
- Google Play indica que bienes digitales, moneda virtual y funciones digitales dentro de apps distribuidas por Play generalmente usan Google Play Billing salvo excepciones. Revisar política vigente antes de implementar compras.
- Apple exige IAP para ciertos bienes/funciones digitales en apps de App Store; revisar reglas vigentes si se publica en iOS.
- TikTok establece mayoría de edad (18 o superior según jurisdicción) para ciertos artículos virtuales; Novvin debe definir elegibilidad por región y asesoría legal, sin copiar automáticamente.
- No otorgar ventajas pagadas injustas en juegos competitivos. Separar modos casual/sandbox de ranked.
- No convertir predicciones con puntos gratuitos en apuestas con dinero real.
- Para grupos de audio/vídeo, medir si P2P es suficiente o si se necesita SFU. LiveKit explica que P2P es apropiado para pocos pares y SFU reenvía pistas con adaptación por receptor. Mediasoup documenta escenarios de grupo y broadcast. No migrar sin benchmark, ADR y cálculo de coste.

## Fuentes
- Novvin web: https://novvinapp.com/
- Funciones: https://novvinapp.com/funciones
- Salas: https://novvinapp.com/salas-de-voz
- Juegos: https://novvinapp.com/juegos
- Stickers: https://novvinapp.com/ayuda/stickers-animados
- Privacidad: https://novvinapp.com/ayuda/privacidad-y-seguridad
- Google Play: https://play.google.com/store/apps/details?id=conectate.com
- TikTok LIVE Match: https://support.tiktok.com/es/live-gifts-wallet/tiktok-live/live-match
- TikTok co-host: https://support.tiktok.com/es/live-gifts-wallet/tiktok-live/cohost-on-tiktok-live
- TikTok LIVE Interactive Games: https://cp-game.tiktok.com/interactive-game-registration
- TikTok Virtual Items: https://t.tiktok.com/legal/page/row/virtual-items/es
- Twitch Extensions: https://dev.twitch.tv/docs/extensions/
- Twitch Bits: https://help.twitch.tv/s/article/using-bits-in-extensions?language=es
- Twitch Channel Points: https://help.twitch.tv/s/article/channel-points-guide
- Instagram Live Rooms: https://about.fb.com/news/2021/03/doubling-up-on-instagram-live-with-live-rooms/
- YouTube Live Chat: https://support.google.com/youtube/answer/15268877?hl=es
- YouTube Q&A/Polls: https://support.google.com/youtube/answer/2524549?hl=es
- Discord Stage: https://support.discord.com/hc/es/articles/1500005513722-Preguntas-frecuentes-sobre-los-canales-escenario
- LiveKit SFU: https://docs.livekit.io/reference/internals/livekit-sfu/
- LiveKit rooms/tracks: https://docs.livekit.io/intro/basics/rooms-participants-tracks/
- mediasoup: https://mediasoup.org/documentation/overview/
- Google Play payments: https://support.google.com/googleplay/android-developer/answer/9858738?hl=es
- Apple review: https://developer.apple.com/app-store/review/guidelines/
- Virtual gifting research (2026): https://link.springer.com/article/10.1186/s40359-026-04813-x

**Límite:** benchmark cualitativo de funciones públicas; no es un estudio cuantitativo TAM/SAM/SOM ni auditoría del código privado. No se inventan cuotas de mercado, usuarios o ingresos.
