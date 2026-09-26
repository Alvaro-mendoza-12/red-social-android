# 06 — Base de datos (inventario inicial)

Fecha: 2026-09-26. Principal: PostgreSQL `16.15-alpine` en Compose, esquema inicial `infrastructure/local-fallback/db/init/001_schema.sql`, 33 archivos bajo `db/migrations/`, y `schema-initializer.js` complementario. Una búsqueda estática de declaraciones `CREATE TABLE IF NOT EXISTS` en el esquema/rutas mostró 156 nombres únicos; no describe el esquema vivo ni equivale a 156 tablas desplegadas. El área `sql/` contiene además migraciones de Supabase/legado separadas por manifiesto.

Se ejecutó `tools/security_sql_audit.py` con manifiesto `sql/release-manifest.txt`: cero violaciones nuevas de sus reglas. No se aplicaron migraciones ni se ejecutaron consultas mutantes sobre producción durante esta pasada.

Pendiente para una conclusión técnica: extraer `pg_catalog` del despliegue real sin datos sensibles; comparar migraciones aplicadas, constraints, FK, índices, políticas de acceso, secuencias, tamaños y cardinalidad; correr `EXPLAIN (ANALYZE, BUFFERS)` con parámetros y volumen representativos para feed, chat, notificaciones, música y regalos. Probar restauración de un backup en entorno aislado y verificar integridad; un backup creado sin restore ensayado no prueba recuperabilidad.
