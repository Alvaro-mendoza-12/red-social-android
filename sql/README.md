# SQL Structure Plan

Este directorio es la estructura destino para ir sacando los scripts SQL de la raíz del repositorio sin romper el historial ni mezclar migraciones, hotfixes, diagnósticos y experimentos.

## Estructura objetivo

- `migrations/`
  Scripts base y cambios persistentes del esquema.

- `hotfixes/`
  Cambios correctivos aplicados a producción o staging.

- `diagnostics/`
  Consultas de diagnóstico, auditoría y verificación.

- `security/`
  Hardening, RLS, auditorías y validaciones de seguridad.

- `archive/`
  Scripts históricos, superseded o de referencia que no deberían seguir compitiendo como fuente principal.

Estas carpetas base ya quedaron creadas para que los próximos movimientos no vuelvan a caer en la raíz.

## Regla de transición

Durante esta fase de saneamiento:

- no se moverán masivamente los archivos viejos sin revisar dependencias y contexto;
- cada nueva ronda moverá grupos pequeños y seguros;
- la raíz del repo debe dejar de recibir scripts nuevos sin clasificación.

Primera ola ya movida:

- hotfixes recientes `supabase_hotfix_*_2026.sql` y `polls_blocking_receipts_hotfix_2026.sql` a `sql/hotfixes/`
- scripts `supabase_live_streaming_*_2026.sql` y `supabase_phase2_feed_observability_moderation_media_2026.sql` a `sql/migrations/`
- auditoría `supabase_security_rate_limit_audit_2026.sql` a `sql/security/`
- verificación `supabase_verify_hotfixes_1_to_7_2026.sql` a `sql/diagnostics/`

Segunda ola ya movida:

- scripts claros de diagnóstico/prueba/verificación como `audit_*`, `check_*`, `debug_*`, `diagnose_*`, `test_*`, `verify_*` y `verificar_*` a `sql/diagnostics/`

Tercera ola ya movida:

- scripts explícitos de esquema/evolución como `add_*`, `create_*`, `*_schema.sql`, `*_rpc.sql`, `update_*`, `enable_*` e `increment_*` a `sql/migrations/`

Cuarta ola ya movida:

- scripts claros de RLS, policies, hardening y seguridad operativa a `sql/security/`

Quinta ola ya movida:

- scripts legacy o superseded como `FINAL_*`, `fixer_sql_v*`, `*nuclear*` y operaciones directas de constraint a `sql/archive/`

Sexta ola ya movida:

- scripts correctivos restantes de la raíz (`fix_*`, `repair_*`, `restore_*`, `force_*`, `*_final*`, `sync_*` y similares) a `sql/hotfixes/`
- scripts manuales/mixtos de operación puntual como `DIAGNOSTICO_Y_GENERAR_RETO.sql`, `borrar_sala_prueba_y_activar_admin.sql` y `HABILITAR_VOTOS_MULTIPLES.sql` a `sql/archive/`

## Manifiesto de despliegue

El único camino permitido para despliegue automatizado es `sql/release-manifest.txt`.

- Todo archivo listado ahí se considera deployable y debe pasar la auditoría de seguridad.
- Todo archivo fuera del manifiesto se trata como histórico, manual, diagnóstico o referencia.
- Si un script contiene políticas permisivas, `DISABLE ROW LEVEL SECURITY`, `GRANT ALL` amplio o funciones sin hardening suficiente, debe quedar fuera del manifiesto hasta ser corregido.

## Convención sugerida

Nombre recomendado:

`YYYYMMDD_area_intent.sql`

Ejemplos:

- `20260407_messages_read_receipts_fix.sql`
- `20260407_security_profiles_rls_hardening.sql`
- `20260407_diagnostics_realtime_healthcheck.sql`

## Prioridad de migración

1. scripts de diagnóstico
2. scripts de seguridad
3. hotfixes recientes con prefijo `supabase_hotfix_*`
4. scripts legacy ambiguos (`fix_*`, `final_*`, `debug_*`)
