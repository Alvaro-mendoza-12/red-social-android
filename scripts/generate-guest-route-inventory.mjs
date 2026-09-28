import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'infrastructure/local-fallback/api/src');
const routes = new Map();
for (const file of fs.readdirSync(src).filter((file) => file.endsWith('.js'))) {
  const source = fs.readFileSync(path.join(src, file), 'utf8');
  for (const match of source.matchAll(/app\.(get|post|patch|put|delete|head)\(\s*(\[[^\]]+\]|'[^']+'|"[^"]+")\s*,\s*([^\n]{0,180})/g)) {
    const guard = ['authRequired', 'requireGuest', 'recoveryAdminRequired', 'adminRequired', 'authOptional']
      .find((value) => match[3].includes(value)) || 'control propio del flujo';
    for (const literal of match[2].matchAll(/['"]([^'"]+)['"]/g)) {
      const method = match[1].toUpperCase();
      const route = literal[1];
      routes.set(`${method} ${route}`, { method, route, guard, source: `${file}:${source.slice(0, match.index).split('\n').length}` });
    }
  }
}
const rows = [...routes.values()].sort((a, b) => a.route.localeCompare(b.route) || a.method.localeCompare(b.method));
const document = [
  '# Inventario de rutas y acceso del modo invitado', '',
  'Rama: `feature/live-gifts`. Generado con `node scripts/generate-guest-route-inventory.mjs`. Identifica declaraciones literales y conserva el último controlador de cada método/ruta. Las expresiones regulares y variables se describen debajo. Este inventario estático no sustituye las pruebas de ejecución.', '',
  '`authRequired` exige sesión registrada y rechaza la audiencia `novvin-guests`; `requireGuest` admite únicamente esa audiencia. Los controles de administrador exigen su capacidad o credencial administrativa.', '',
  '| Método | Ruta | Control declarado | Fuente |', '| --- | --- | --- | --- |',
  ...rows.map((row) => `| ${row.method} | \`${row.route}\` | ${row.guard} | ${row.source} |`), '',
  '## Rutas dinámicas y controles propios', '',
  '- `/rest/v1/<tabla>` y `/rest/v1/rpc/<función>`: entrada `authOptional` y exigencia de sesión registrada dentro del controlador, salvo resolver el correo de acceso.',
  '- `/storage/v1/object/<bucket>/<path>`: escritura y firma requieren sesión registrada; lectura pública depende del bucket.',
  '- Registro, acceso, recuperación, segundo factor, renovación y vinculación web: cada flujo exige su prueba correspondiente; entrar como invitado no sustituye credenciales ni códigos.',
  '- `/v1/public/profiles/<identifier>`: vista previa HTML de perfil público, campos explícitos, privacidad compartida con invitado y límite de 60 consultas/minuto/IP.',
  '- Rutas internas, webhooks y callbacks: credencial específica del servicio; el JWT de invitado no proporciona esa autorización.',
  '- `health` y `ready`: comprobaciones de disponibilidad; no autorizan acciones sociales.', '',
];
fs.writeFileSync(path.join(root, 'docs/INVENTARIO_RUTAS_MODO_INVITADO_2026-09-27.md'), document.join('\n'));
console.log(JSON.stringify({ routes: rows.length, registered: rows.filter((row) => row.guard === 'authRequired').length }));
