# Propiedad por sistema y matriz RACI

R = ejecuta; A = aprueba y responde por el resultado; C = consultado; I = informado. En la etapa inicial el fundador puede ocupar varias columnas, pero cada cambio conserva su rol explícito.

| Sistema o proceso | R | A | C | Evidencia mínima |
|---|---|---|---|---|
| Feed y reproducción de videos | Android + Backend | Tecnología | Producto, SRE, Datos | pruebas, métricas de inicio/rebuffer, release |
| Descarga y videos sin conexión | Android | Tecnología | Privacidad, Producto, Soporte | permisos, cuota, borrado, prueba sin red |
| Momentos, música y multimedia | Android + Backend | Tecnología | Producto, Legal/Privacidad | duración/audio, derechos, prueba multidispositivo |
| Mensajes, llamadas y tiempo real | Android + Realtime | Tecnología | Seguridad, SRE | cifrado/transporte, pruebas entre redes, recuperación |
| Algoritmo y personalización | Datos/IA + Backend | Producto | Privacidad, Seguridad | versión, explicación, métricas por cuenta, rollback |
| Moderación y reportes | Trust & Safety | Dirección | Producto, Legal, Ingeniería | SLA, auditoría, apelación |
| API, PostgreSQL y almacenamiento | Backend + SRE | Tecnología | Seguridad | backup restaurable, capacidad, alertas, rollback |
| Play Store y firma | QA/Release | Dirección | Android, Legal | AAB firmado, versionado, pruebas, notas |
| Incidente P0/P1 | SRE/área afectada | Dirección | Seguridad, Soporte | línea de tiempo, mitigación, postmortem |
| Privacidad y solicitudes de datos | Privacidad | Dirección | Seguridad, Soporte | identidad, alcance, registro y cierre |
| Accesos de personal | Personas + Seguridad | Dirección | Líder del área | mínimo privilegio, fecha de expiración, revocación |

## Mapa de repositorio por propietario

- `app/`: Android.
- `infrastructure/local-fallback/api/`: Backend/API.
- `infrastructure/local-fallback/realtime/`: tiempo real.
- `infrastructure/local-fallback/signaling/`: señalización WebRTC.
- `infrastructure/local-fallback/ai/`: Datos e IA.
- `infrastructure/local-fallback/docker-compose.yml`, scripts de despliegue y observabilidad: Plataforma/SRE.
- `Conectate-web/`: Web.
- `sql/` y migraciones: Backend + Datos, con revisión de Seguridad.
- `docs/oficial/`: Arquitectura/Operación; cada área actualiza su sección con el mismo cambio.
- `docs/empresa/`: Dirección/Operaciones.

## Definición de terminado

Un cambio termina cuando el criterio de aceptación está satisfecho, los tests proporcionales al riesgo pasan, existe evidencia manual cuando aplica, observabilidad y rollback están definidos, la documentación está actualizada y el propietario acepta el resultado. “Compila” por sí solo no es terminado.
