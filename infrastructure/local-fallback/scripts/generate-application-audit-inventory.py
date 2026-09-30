"""Reproducible inventory, not a claim of line-by-line or runtime certification."""
import collections
import csv
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[3]
areas = collections.defaultdict(lambda: {'files': 0, 'lines': 0, 'functionDeclarations': 0})
files = []
routes = []
encoding_issues = []
for base, kind in [(root / 'app/src/main/java/conectate/com', 'android'),
                   (root / 'infrastructure/local-fallback/api/src', 'backend')]:
    for path in sorted(base.rglob('*')):
        if path.suffix not in ['.kt', '.java', '.js']:
            continue
        try:
            source = path.read_text(encoding='utf-8-sig')
        except UnicodeDecodeError:
            encoding_issues.append(path.relative_to(root).as_posix())
            source = path.read_text(encoding='utf-8-sig', errors='replace')
        relative = path.relative_to(base)
        area = kind + '/' + ('/'.join(relative.parts[:2]) if kind == 'android' and len(relative.parts) > 2 else relative.parts[0].split('.')[0])
        functions = len(re.findall(r'\bfun\s+(?:[\w.<>?]+\.)?[\w]+\s*\(|\bfunction\s+\w+\s*\(', source))
        record = {'area': area, 'path': path.relative_to(root).as_posix(),
                  'lines': len(source.splitlines()), 'functionDeclarations': functions}
        files.append(record)
        areas[area]['files'] += 1
        areas[area]['lines'] += record['lines']
        areas[area]['functionDeclarations'] += functions
        if kind == 'backend':
            for match in re.finditer(r'\bapp\.(get|post|put|patch|delete)\(\s*([\'\"])(.+?)\2\s*,', source):
                header = source[match.end():match.end() + 200].split('=>')[0]
                routes.append({'method': match[1].upper(), 'path': match[3],
                               'source': record['path'], 'line': source[:match.start()].count('\n') + 1,
                               'middlewareHint': 'authRequired' if 'authRequired' in header else 'review route context'})
output = root / 'docs'
with (output / 'AUDITORIA-INVENTARIO-2026-09-30.csv').open('w', newline='', encoding='utf-8') as handle:
    writer = csv.DictWriter(handle, fieldnames=['area', 'path', 'lines', 'functionDeclarations'])
    writer.writeheader()
    writer.writerows(files)
summary = {'scope': 'Inventory of every Android main source and backend API source; lexical counts only',
           'androidFiles': sum(value['files'] for key, value in areas.items() if key.startswith('android/')),
           'backendFiles': sum(value['files'] for key, value in areas.items() if key.startswith('backend/')),
           'totalLines': sum(value['lines'] for value in areas.values()),
           'areas': dict(sorted(areas.items())), 'declaredHttpRoutes': routes,
           'encodingIssues': encoding_issues}
(output / 'AUDITORIA-INVENTARIO-2026-09-30.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps({key: value for key, value in summary.items() if key not in ['areas', 'declaredHttpRoutes']} |
                 {'routeDeclarations': len(routes)}, ensure_ascii=False))
