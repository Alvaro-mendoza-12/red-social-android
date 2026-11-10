# Actualización del benchmark de Novvin a noviembre de 2026

Fecha de revisión: 10 de noviembre de 2026.

Esta adenda actualiza el benchmark funcional versión 1.0 del 26 de julio de 2026. Conserva sus identificadores B01–B30 para evitar un backlog paralelo. No modifica el documento original ni declara implementadas o probadas las capacidades que describe.

## Conclusión

Novvin necesita estabilizar sus recorridos principales y comprobar una razón concreta para regresar antes de ampliar funciones. El público inicial de 18–30 años es una decisión de audiencia, pero todavía no constituye un nicho ni una necesidad validada. No se recomienda competir como una combinación generalista de WhatsApp, TikTok, Facebook e Instagram.

El benchmark anterior ya recomienda estabilidad antes de expansión, diferencia señales de foros de demanda cuantificada y advierte que las funciones propuestas existen parcialmente en otras plataformas. La actualización necesaria es incorporar novedades competitivas, convertir prioridades editoriales en requisitos comprobables y elegir un experimento de producto.

## Alcance y calidad de la evidencia

- Se inspeccionaron el PDF de 21 páginas y el contenido del DOCX del benchmark. Ambos contienen la estructura y el backlog B01–B30. El DOCX inspeccionado no contiene comentarios ni cambios controlados.
- El README de la rama `correcciones-dot` identifica Novvin como beta pública Android/web con mensajería, videos, perfiles y salas de voz. Declara una revisión integral pendiente y la intención de completar móvil antes de web. Es documentación del proyecto, no una prueba independiente de funcionamiento.
- Las fuentes oficiales citadas abajo confirman anuncios y capacidades descritas por sus proveedores. Un anuncio no demuestra disponibilidad idéntica en todos los países o cuentas.
- Las entrevistas, retención, costes y demanda de usuarios de Novvin no se han verificado en este análisis.
- No se revalidó cada referencia histórica de Reddit y Discord del benchmark. Deben conservarse fechadas, no presentarse como opiniones actuales comprobadas.
- Los umbrales y tamaños de piloto de esta adenda son propuestas, no resultados actuales ni estándares universales.

## Reconciliación con el trabajo previo

### Conservar

- Fase 0 de estabilidad, pruebas de contrato, identificación de versiones y despliegues controlados.
- Audiencia explícita al publicar y autorización comprobada en servidor.
- Moderación con motivos comprensibles y posibilidad de revisión.
- Separación entre datos privados y recomendaciones o IA.
- Métricas de utilidad, errores y retorno, no solo tiempo de pantalla.
- Consentimiento y retención definidos antes de grabar o transcribir voz.

### Matizar

La afirmación del resumen sobre demanda persistente de organización de guardados deriva de solicitudes y herramientas comunitarias. Es una señal cualitativa, no demanda validada entre usuarios de Novvin. Las estimaciones de impacto y esfuerzo de B01–B30 son editoriales y deben recalcularse con el código, uso y costes actuales.

La existencia de Dúo o Pegar demuestra disponibilidad de un patrón de producto, no demanda específica de esa función en Novvin. IA opcional, control del feed y comunidades ordenadas pueden aportar calidad, pero no constituyen ventajas exclusivas comprobadas.

## Actualización competitiva

### WhatsApp

El 5 de noviembre de 2026 Meta anunció eventos con invitaciones y RSVP para personas sin cuenta, recordatorios y un espacio de gestión de eventos, con despliegue durante las semanas siguientes. B12 no debe presentarse como novedad exclusiva. Su posible valor está en el flujo de una comunidad concreta, no en ofrecer invitaciones web por sí solas. [S4]

### TikTok

TikTok anunció bulletin boards para difusión a seguidores en noviembre de 2025. El benchmark debe incluirlos al evaluar comunidad y comunicación de creadores. El anuncio establecía un mínimo de 18 años y 50.000 seguidores para crear un canal; no se comprobó si esos requisitos siguen idénticos en noviembre de 2026. [S5]

### Facebook e Instagram

Meta describió Your Algorithm de Instagram en mayo de 2026 y anunció controles de recomendaciones para Facebook en octubre. B01–B03 siguen teniendo sentido como requisitos de control, pero requieren mejor ejecución o utilidad contextual, no una afirmación de exclusividad. [S6, S7]

Facebook también anunció herramientas para vendedores y verificación gratuita de personas en julio de 2026. Comunidades locales e identidad confiable ya son áreas de inversión del competidor. [S8]

### Escala del mercado

DataReportal informa 5.810 millones de identidades sociales en octubre de 2026, con crecimiento interanual del 2,7%. Son identidades, no necesariamente personas únicas. El informe se titula Digital 2027, aunque recoge datos de 2026. Esta escala no demuestra oportunidad accesible, intención de migrar ni demanda para Novvin. [S9]

## Hipótesis de diferenciación

Estas opciones son hipótesis, no necesidades validadas:

1. **Comunidades pequeñas que hacen algo juntas.** Conversación, contenido recuperable y una actividad recurrente específica. Depende de poder reclutar organizadores y participantes reales.
2. **Creadores pequeños con participación repetida.** Preguntas, respuestas y sesiones que reduzcan trabajo del creador y den valor al participante. Debe contrastarse también con Discord y Telegram antes de elegirla.
3. **Experiencia ligera y predecible.** Web compartible, Android fluido, ahorro de datos y notificaciones controlables. Solo puede sostenerse como ventaja si las mediciones comparativas la demuestran.

Se recomienda explorar primero la primera opción si el usuario puede reunir comunidades. No desarrollar las tres simultáneamente.

## Requisitos funcionales de base

Antes del piloto debe existir un recorrido fiable de registro, recuperación de acceso, perfil, publicación, respuesta y notificación. Los permisos deben aplicarse tanto en la interfaz como en enlaces y API.

- Registro, inicio y cierre de sesión y recuperación con errores comprensibles; revocación efectiva de sesiones y sin revelar la existencia de cuentas en recuperación.
- Audiencia visible al publicar y acceso cruzado entre cuentas probado negativamente.
- Publicación y mensajería con estados de progreso y error, reintentos idempotentes y sin pérdida de mensajes confirmados.
- Bloqueo, denuncia y procedimiento de revisión operativo, con responsable definido.
- Notificaciones configurables y enlaces que conduzcan al destino correcto o expliquen que ya no está disponible.

Estos requisitos son base de funcionamiento, no diferenciación comercial demostrada. Deben enlazarse a los requisitos técnicos existentes del repositorio, sin asumir que están ausentes ni crear duplicados.

## Backlog reconciliado B01–B30

P0 significa necesario antes de ampliar el piloto; P1 significa candidato a un único experimento; P2 significa opción posterior condicionada a evidencia. Las funciones ya activas requieren mantenimiento y seguridad aunque su ampliación se posponga.

| ID | Función del benchmark | Prioridad propuesta | Criterio de aceptación y condición |
| --- | --- | --- | --- |
| B01 | Feed Seguidos y Descubrir | P1 | Dos vistas claras; Seguidos no introduce autores ajenos salvo excepción explícita; preferencia persistente al volver. No empezar con seis presets. |
| B02 | Por qué veo esto | P1 si se usan recomendaciones | La explicación corresponde a señales realmente utilizadas; ofrece controles que modifican la selección; no inventa una causa. |
| B03 | Temas bloqueados | P1 condicionado | Contenidos etiquetados con un tema bloqueado quedan excluidos en las superficies definidas; explicar límites de clasificación y comprobarlos con casos de prueba. |
| B04 | Colecciones de guardados | P1 alternativo | Guardar, organizar y recuperar contenido; retirar acceso al perder permiso sobre el original; medir recuperación efectiva y no solo número de guardados. |
| B05 | Resumen de notificaciones | Controles P0; resumen P1 | Silencios y preferencias respetados entre dispositivos; sin duplicados ni resúmenes de contenido ya inaccesible. Validar antes si un resumen periódico resuelve un problema. |
| B06 | Motivos de moderación | P0 | Decisión vinculada a una regla y contenido afectado, con explicación y registro de actuación accesible según permisos. |
| B07 | Apelación estructurada | P0 mínimo | Existe un canal de revisión, identificador, estado y responsable; no exige una automatización avanzada antes del piloto. |
| B08 | Roles predefinidos | P0 para comunidades | Propietario, administrador, moderador y miembro tienen permisos explícitos y pruebas negativas en servidor. |
| B09 | Vista como rol | P2 | La previsualización coincide con los permisos reales; no sustituye las pruebas de autorización. |
| B10 | Canales temáticos | P1 condicionado | Administrador configura una estructura mínima sin soporte técnico; acceso por canal verificado y navegación comprensible para participantes. |
| B11 | Perfil por comunidad | P2 | Alias y datos locales no filtran información del perfil global ni permiten suplantar roles; validar necesidad antes de ampliar identidad. |
| B12 | Eventos comunitarios | P1 condicionado | Crear actividad, participar y consultar resultado sin buscar entre todo el chat; invitación web no filtra contenido privado. No se presenta como función exclusiva. |
| B13 | Pausa de canal | P1 para comunidades | Moderador fija motivo y duración; nuevas escrituras se bloquean también por API; reapertura y registro comprobables. |
| B14 | Sala casual y escenario | P2 | Dos flujos con permisos y estados definidos; añadir el segundo solo si el uso demuestra necesidad. |
| B15 | Reconexión de voz | P0 si voz está activa | Cambio de red recupera estado de forma controlada; no duplica participantes ni reactiva un micrófono que debía permanecer silenciado. |
| B16 | Subtítulos en voz | Según accesibilidad del piloto | Calidad y latencia evaluadas con usuarios, idiomas y condiciones previstas; consentimiento y procesamiento claros. Puede ser necesario para accesibilidad aunque B17 se posponga. |
| B17 | Resumen de sala | P2 | Solicitud y consentimiento verificables, retención definida, corrección posible y advertencia de omisiones; evaluar coste y precisión. |
| B18 | Moderación de directo | P0 si directos están activos | Silenciar y expulsar producen efecto real; reportes y responsable operativo definidos antes de abrir el formato. |
| B19 | Repetición con capítulos | P2 | Marcadores corresponden a posiciones válidas; reproducción y permisos respetan retirada o eliminación del original. |
| B20 | Remix con atribución | P2 | Permiso de reutilización explícito, atribución y enlace; política de revocación definida y probada antes del lanzamiento. |
| B21 | Búsqueda semántica | P2 | Recupera solo contenido autorizado; revocaciones se reflejan en el índice; evaluación de utilidad frente a búsqueda convencional. |
| B22 | Resumen de hilos | P2 | Enlaces a fuentes accesibles, control de acceso y aviso de errores; no inventa mensajes o acuerdos. |
| B23 | Asistente de redacción | P2 | Revisión previa a publicar; envío separado y deliberado; opción de no usar el servicio y datos de procesamiento claros. |
| B24 | IA para moderadores | P2 | Sugerencias verificables con evidencia, posibilidad de corregir y revisión humana; evaluar falsos positivos antes de ampliar. |
| B25 | Panel de datos de IA | Obligatorio cuando se active IA | Historial de procesamiento comprensible, exclusión, borrado y retención comprobables. No posponerlo después de activar B21–B24. |
| B26 | Pruebas de contrato | P0 | Flujos activos cubren sesión vencida, respuestas nulas/lentas, reintentos, duplicados y eventos fuera de orden; pruebas negativas entre cuentas. |
| B27 | Identidad de versión | P0 | App y backend identifican versión, commit y entorno; toda evidencia de prueba registra esos datos. |
| B28 | Despliegue gradual | P0 | Despliegue y recuperación ensayados en entorno separado; criterio y responsable de rollback definidos. |
| B29 | Panel de salud | P0 | API, base de datos, almacenamiento y voz tienen estado distinguible; alertas verificadas con fallos controlados. |
| B30 | Exportación de datos | Planificación temprana; ampliación según uso | Separar datos personales básicos de exportación avanzada de colecciones; paquete legible y autorizado, sin datos de terceros no autorizados. Prioridad legal según jurisdicción y tratamiento. |

## Requisitos no funcionales propuestos

Estos objetivos requieren aprobación y medición sobre una versión identificada. No representan garantías actuales.

| Área | Objetivo y aceptación propuesta |
| --- | --- |
| Rendimiento | Definir dispositivo Android, navegador, datos y red de referencia. Contenido útil en ≤3 s p95 y confirmación de texto en ≤2 s p95 bajo esas condiciones, excluyendo desconexión. Separar métricas por plataforma. |
| Estabilidad | Objetivo inicial ≥99,5% de sesiones Android sin cierre inesperado, publicando ventana y muestra; cero defectos críticos abiertos de acceso a datos o pérdida de mensajes confirmados. |
| Seguridad | Pruebas de acceso cruzado para recursos privados; límites de abuso en autenticación, mensajes y subidas; secretos fuera del cliente y repositorio; validación de archivos. No anunciar E2E sin diseño y verificación específicos. |
| Privacidad | Inventario, finalidad, acceso y retención; exportación y eliminación comprobables, contemplando copias de seguridad; ningún token o contenido privado en logs o analítica. |
| Accesibilidad | Objetivo WCAG 2.2 AA en recorridos web principales; Android probado con TalkBack, texto ampliado y foco; requisitos audiovisuales definidos según el piloto. |
| Red intermitente | Borradores persistentes; reintentos sin duplicados; ensayos de pérdida de red durante subida y envío; ahorro de datos respetado. |
| Recuperación | Restauración real en entorno separado. RPO ≤24 h y RTO ≤4 h son objetivos iniciales a aprobar solo si equipo e infraestructura pueden demostrarlos. |
| Compatibilidad y carga | Matriz publicada de Android y navegadores; estimar concurrencia del piloto y probar al doble. No prometer escala masiva sin medición. |
| Coste | Medir almacenamiento, transferencia y minutos audiovisuales por comunidad activa; presupuesto, alertas y límites definidos antes de ampliar. |

## Validación y decisión de producto

La investigación previa no contiene una muestra representativa de usuarios de Novvin de 18–30 años. Se propone entrevistar primero a 10–15 personas reclutables y algunos organizadores. Pedir ejemplos recientes del problema, frecuencia, herramientas actuales y consecuencias. Una opinión favorable sobre una idea no equivale a adopción.

Después, realizar un piloto propuesto de 3–5 comunidades durante cuatro semanas. Son parámetros de experimento, no previsiones de demanda ni una garantía de significación estadística.

Medir:

- Invitación, incorporación y primera interacción útil.
- Retorno por cohortes y participación sin recordatorios personales constantes.
- Respuestas recibidas y utilidad percibida de la actividad recurrente.
- Tiempo que dedica el administrador y causas de abandono.
- Errores, denuncias, resolución y coste operativo.

Acordar antes del piloto el comportamiento esperado y las condiciones para continuar. No fijar una retención universal como éxito sin contexto. Los resultados deben decidir si ampliar el experimento, cambiar el problema abordado o detener funciones nuevas.

## Integración y límites

Conservar el benchmark de julio como antecedente y enlazar esta adenda. No reemplazar B01–B30 ni convertir estimaciones en compromisos de implementación. Contrastar los estados con el código y pruebas vigentes antes de marcar cualquier requisito como completado.

Las recomendaciones históricas del benchmark sobre ramas, agentes o despliegues son documentación de proyecto, no autorización operativa. Cualquier ejecución debe seguir las instrucciones y autorizaciones vigentes.

## Fuentes

Consultadas el 10 de noviembre de 2026, salvo las referencias históricas internas del benchmark, que conservan su fecha original.

- **S1.** Benchmark funcional y hoja de ruta de Novvin, PDF v1.0, 26/07/2026. Necesidades: páginas 9–12; propuesta: 12–14; fases: 15; métricas: 16; backlog: 17–18; referencias: 19–21. https://github.com/Alvaro-mendoza-12/red-social-android/blob/correcciones-dot/Benchmark_funcional_y_hoja_de_ruta_Novvin.pdf
- **S2.** Versión editable del benchmark. https://github.com/Alvaro-mendoza-12/red-social-android/blob/correcciones-dot/Benchmark_funcional_y_hoja_de_ruta_Novvin.docx
- **S3.** README de Novvin en la rama correcciones-dot. https://github.com/Alvaro-mendoza-12/red-social-android/blob/correcciones-dot/README.md
- **S4.** Meta, WhatsApp Events Brings People Together Beyond the Group Chat, 05/11/2026. https://about.fb.com/news/2026/11/whatsapp-events-brings-people-together-beyond-the-group-chat/?noamp=mobile
- **S5.** TikTok, Introducing bulletin board, 24/11/2025. https://newsroom.tiktok.com/introducing-bulletin-board?lang=en-GB
- **S6.** Meta, New Supervision Tools Give Parents Insights Into Their Teen’s Algorithm and More, 12/05/2026. https://about.fb.com/news/2026/05/new-supervision-tools-parents-insights-teens-algorithm/
- **S7.** Meta, Your Algorithm Your Words, Facebook, 13/10/2026. https://about.fb.com/news/2026/10/introducing-your-algorithm-facebook/
- **S8.** Meta, Connecting Real People on Facebook, julio de 2026. https://about.fb.com/news/2026/07/connecting-real-people-on-facebook/?noamp=mobile
- **S9.** DataReportal, Digital 2027 Global Overview Report, datos de octubre de 2026. https://datareportal.com/reports/digital-2027-global-overview-report
