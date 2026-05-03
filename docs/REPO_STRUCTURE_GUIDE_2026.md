# Repo Structure Guide 2026

Fecha de revisión: 2026-04-07

## Objetivo

Dejar claro qué vive en cada zona del repositorio para que nuevas mejoras y futuras funcionalidades no vuelvan a mezclar Android, web, backend, SQL, reportes y artefactos temporales en la raíz.

## Estructura principal

- `app/`
  App Android principal. Aquí deben vivir UI, ViewModels, repositorios, Room, navegación, servicios y tests Android.

- `Conectate-web/`
  Frontend web. No debería compartir archivos temporales ni reportes manuales con `app/`.

- `signaling-server/`
  Backend Node para señalización, TURN/WebRTC y flujos multimedia efímeros.

- `supabase/`
  Edge Functions, configuración y piezas operativas ligadas a Supabase.

- `sql/`
  Punto único para scripts SQL clasificados por intención.

- `docs/`
  Documentación viva: arquitectura, runbooks, baseline técnica y guías operativas.

- `tools/`
  Scripts de soporte, auditoría y utilidades del proyecto.

## Regla para la raíz

La raíz del repo solo debería contener:

- archivos de configuración global;
- entrypoints evidentes del proyecto;
- documentación raíz realmente transversal;
- carpetas principales de producto.

No debería recibir de forma permanente:

- logs de compilación;
- dumps temporales;
- scripts SQL nuevos sin clasificar;
- pruebas manuales sueltas;
- reportes generados por herramientas locales.

## Estructura esperada para Android

Dentro de `app/src/main/java/conectate/com/`:

- `presentation/`
  Compose UI, pantallas, componentes, navegación y ViewModels.

- `data/`
  Acceso a datos, Room, Supabase, repositorios y servicios.

- `core/`
  Políticas transversales, seguridad, observabilidad, concurrencia y utilidades núcleo.

- `util/`
  Helpers concretos de soporte que todavía no justifican una capa más formal.

## Estructura esperada para repositorios grandes

Cuando un repositorio crezca demasiado, la dirección recomendada es:

- `repository/<feature>/...Store.kt`
  estado local, prefs, caché o helpers internos;

- `repository/<feature>/...Remote.kt`
  llamadas HTTP, RPC y PostgREST;

- `repository/<feature>/...Sync.kt`
  realtime, reconciliación y refresh;

- `repository/<feature>/...Media.kt`
  uploads, signed URLs y metadata.

El repositorio principal debería quedar como orquestador, no como contenedor de toda la lógica.

## Estructura esperada para SQL

Ver también:

- `sql/README.md`

Carpetas objetivo:

- `sql/migrations/`
- `sql/hotfixes/`
- `sql/diagnostics/`
- `sql/security/`
- `sql/archive/`

## Regla operativa

Antes de crear un archivo nuevo en la raíz, preguntarse:

1. ¿Esto pertenece a `docs/`, `sql/`, `tools/`, `app/`, `supabase/` o `signaling-server/`?
2. ¿Es temporal y debería quedar ignorado en `.gitignore`?
3. ¿Ya existe una carpeta temática donde encaja mejor?

Si la respuesta es sí, no debería quedar en la raíz.
