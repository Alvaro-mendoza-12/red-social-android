# Roles y capacidades administrativas

Estado: contrato canónico de autorización de producto. La implementación efectiva
vive en `infrastructure/local-fallback/api/src/admin-capabilities.js`; ninguna
pantalla cliente decide permisos por sí sola.

## Matriz de mínimo privilegio

| Nivel persistido | Rol empresarial | Capacidades efectivas | No puede |
|---|---|---|---|
| `none` | usuario | ninguna | entrar al panel |
| `view` | auditor/soporte de lectura | `admin.read` | moderar, editar, ver salud interna, finanzas o catálogos |
| `write` | moderador / Trust & Safety | lectura, `admin.content.manage`, `admin.moderation.manage` | catálogos, pagos, salud interna, delegar acceso o borrar cuentas |
| `full` | administrador delegado | todas las anteriores, `admin.catalog.manage`, `admin.finance.manage`, `admin.system.read` | delegar acceso o borrar cuentas |
| `full` + `admin_is_owner=true` | propietario empresarial | todas las anteriores, `admin.access.manage`, `admin.users.delete` | no existe bypass implícito; también requiere autenticación normal |

`full` no significa propietario. La propiedad se almacena y verifica por separado.
Los nombres de capacidades son parte del contrato de API y una capacidad desconocida
siempre se deniega.

## Separación de credenciales

- El JWT de usuario autentica una persona y después se resuelven sus capacidades en
  PostgreSQL.
- `x-admin-token` protege sincronización/importación operativa. No representa una
  persona, no abre el panel y no debe reutilizarse como rol de producto.
- SSH/Tailscale, Docker y Cloudflare son accesos de infraestructura. No conceden
  automáticamente permisos dentro del producto.
- Insignias como “moderador” o “fundador” son presentación de perfil; nunca autorizan.

## Reglas de implementación

1. La API autoriza; Android y web sólo anticipan la experiencia.
2. Toda ruta nueva declara una capacidad nominal. No se acepta un booleano genérico
   `isAdmin` para mutaciones.
3. Lectura y escritura se separan. Un auditor no puede modificar recursos aunque
   manipule el cliente.
4. La eliminación irreversible comprueba `admin.users.delete` tanto en la ruta como
   dentro de la función transaccional.
5. Cambios de acceso y acciones de moderación generan evento auditable con actor,
   contexto, referencia y resultado, sin secretos ni PII innecesaria.
6. El endpoint `GET /v1/data/admin/access` devuelve `capabilities` y conserva
   temporalmente `canWrite`/`canDeleteUsers` para clientes anteriores.
7. Al retirar personal se revocan sesiones, nivel administrativo y credenciales de
   infraestructura en el mismo cambio controlado.

## Alta, revisión y baja

- Alta: Dirección aprueba; Seguridad asigna el nivel mínimo y fecha de revisión.
- Revisión: quincenal mientras el equipo sea pequeño; trimestral como máximo al
  escalar. Comparar personas activas, nivel, propietario y necesidad vigente.
- Escalamiento temporal: registrar motivo y caducidad; volver al nivel anterior al
  terminar la intervención.
- Baja: eliminar acceso de producto, revocar sesiones y rotar cualquier secreto
  compartido. Conservar eventos de auditoría.

## Evidencia de release

Antes de desplegar cambios administrativos deben aprobar:

```bash
cd infrastructure/local-fallback/api
npm run test:admin
npm run test:security
npm run check
```

En servidor, `verify:admin-panel-e2e` debe confirmar el contrato de capacidades y
los endpoints de lectura con una cuenta apropiada. Las mutaciones destructivas no
se prueban contra cuentas reales; usan fixtures transaccionales aislados.
