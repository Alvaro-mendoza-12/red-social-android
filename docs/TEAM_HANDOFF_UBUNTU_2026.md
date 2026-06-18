# Handoff tecnico para equipo de desarrollo

Fecha de ultima revision: 2026-06-18
Alcance: Android, web y backend Ubuntu de Conectate.
Objetivo: que un equipo nuevo pueda entender el estado real del proyecto, levantarlo, validarlo y continuar el desarrollo sin depender de conocimiento oral.

## 1. Orden recomendado de lectura

1. `README.md`: vision general del proyecto y backend principal actual.
2. `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md`: contrato oficial de produccion Ubuntu.
3. `docs/UBUNTU_PRODUCTION_READINESS.md`: matriz modulo por modulo.
4. `docs/UBUNTU_MIGRATION_STATUS.md`: estado honesto de migracion y pendientes.
5. `docs/SUPABASE_STORAGE_TO_UBUNTU.md`: migracion de multimedia historica.
6. `docs/MINI_PC_MIGRATION_UBUNTU_2026.md`: paso de VM a mini PC Ubuntu Server.

## 2. Estado actual en una frase

Ubuntu es el backend principal para los flujos migrados. Supabase queda como compatibilidad legacy y fuente temporal para importar datos antiguos, no como destino funcional final.

## 3. Mapa rapido del repositorio

| Ruta | Que contiene | Cuando tocarla |
| --- | --- | --- |
| `app/src/main/java/conectate/com` | APK Android Kotlin/Compose | UI movil, repositorios, Room, ViewModels |
| `Conectate-web/src` | Web React/Vite | Login web por QR, pantallas web, cliente Ubuntu |
| `infrastructure/local-fallback` | Backend Ubuntu Docker | API, Postgres, storage, edge, scripts |
| `infrastructure/local-fallback/api/src/server.js` | Auth local, QR web, sesiones web, arranque API | Cambios de autenticacion/seguridad |
| `infrastructure/local-fallback/api/src/data-routes.js` | Datos sociales, mensajes, videos, retos, voz | Nuevos endpoints de producto |
| `infrastructure/local-fallback/api/src/moments-routes.js` | Momentos | Crear/ver/borrar momentos |
| `infrastructure/local-fallback/realtime-gateway` | Realtime Ubuntu | Eventos WebSocket internos |
| `signaling-server` | WebRTC/signaling legacy o complementario | Voz, llamadas, live/signaling |
| `docs` | Documentacion oficial y auditorias | Cada cambio de contrato o despliegue |

## 4. Conceptos clave

| Concepto | Definicion |
| --- | --- |
| `FALLBACK_API_URL` | URL que usa Android para llegar a Ubuntu. En produccion debe ser HTTPS. |
| `VITE_FALLBACK_API_URL` | URL que usa la web para llegar a Ubuntu. |
| `PUBLIC_BASE_URL` | URL publica que Ubuntu usa para generar URLs de storage. |
| `edge` | nginx local que publica API, storage, realtime y signaling por el puerto `3080`. |
| `fallback_*` | Tablas Postgres locales creadas por Ubuntu. |
| `LEGACY_SUPABASE_IMPORT_ENABLED` | Habilita temporalmente importacion desde Supabase. Debe estar `false` en runtime normal. |
| `fallback_web_sessions` | Tabla de sesiones web revocables desde la APK. |
| `WEB_SESSION_REVOKED` | Respuesta de Ubuntu cuando una sesion web fue cerrada desde el movil. |

## 5. Primer dia de un programador nuevo

1. Abrir el repo en Android Studio.
2. Confirmar JDK 17.
3. Revisar `secrets.properties`:

```properties
FALLBACK_API_URL=https://TU_DOMINIO_O_NGROK
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

4. Compilar APK:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
```

5. Compilar web:

```powershell
cd Conectate-web
npm run build
```

6. Verificar backend:

```powershell
node --check infrastructure/local-fallback/api/src/server.js
```

7. Si trabaja contra VM Ubuntu, ejecutar los comandos del runbook oficial.

## 6. Flujo de desarrollo seguro

Antes de modificar:

- Identificar si el modulo ya usa Ubuntu o aun conserva legacy Supabase.
- Revisar la matriz en `docs/UBUNTU_PRODUCTION_READINESS.md`.
- Buscar endpoints existentes antes de crear rutas nuevas.
- No duplicar repositorios ni modelos si ya existe contrato local.

Despues de modificar:

```powershell
node --check infrastructure/local-fallback/api/src/server.js
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
cd Conectate-web
npm run build
```

Si se toca backend Ubuntu, desplegar en VM y validar:

```bash
cd ~/conectate-local-fallback
docker compose run --rm api node --check src/server.js
docker compose build api
docker compose up -d api edge signaling realtime-gateway
docker compose logs --tail=120 api edge signaling realtime-gateway
```

## 7. Reglas de arquitectura

- Ubuntu es la fuente de verdad para runtime de los modulos migrados.
- Supabase no debe reintroducirse en flujos ya cubiertos por Ubuntu.
- Storage nuevo debe escribirse en Ubuntu.
- Los endpoints deben devolver errores claros y estables.
- Android debe mostrar estados de error visibles cuando una accion falle.
- Web debe limpiar sesion si Ubuntu responde `INVALID_TOKEN` o `WEB_SESSION_REVOKED`.
- Las tablas nuevas deben crearse con `CREATE TABLE IF NOT EXISTS` y migraciones no destructivas.
- No borrar codigo legacy hasta tener cobertura Ubuntu equivalente verificada.

## 8. Definition of done

Un cambio se considera listo cuando cumple:

- Compila Android.
- Compila web si toca web o contratos compartidos.
- `node --check` pasa para archivos Node tocados.
- Los endpoints nuevos estan documentados.
- La matriz de produccion se actualiza si cambia cobertura Ubuntu.
- Se entregan comandos exactos para VM Ubuntu cuando aplica.
- No se rompe compatibilidad con datos ya importados.

## 9. Areas delicadas

| Area | Riesgo |
| --- | --- |
| Auth/JWT | Cambios mal hechos pueden invalidar sesiones o abrir acceso indebido. |
| Storage | URLs antiguas de Supabase pueden reaparecer si no se reescriben. |
| Mensajes/realtime | Estados locales pueden parecer enviados aunque el backend falle. |
| Videos/momentos | Duracion, cache y contadores dependen de Android + API + DB. |
| Retos | Requiere ciclo de tiempo, votos, limpieza de participaciones y podio. |
| Voz/WebRTC | Depende de permisos Android, signaling, red y sesiones validas. |

## 10. Contacto operativo interno

Si un desarrollador no sabe donde tocar, debe empezar por:

- `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md` para infraestructura.
- `docs/UBUNTU_PRODUCTION_READINESS.md` para matriz de modulos.
- `rg -n "endpoint_o_modulo"` en el repo antes de crear codigo nuevo.

La documentacion debe actualizarse el mismo dia que cambie un contrato, endpoint, variable o proceso de despliegue.
