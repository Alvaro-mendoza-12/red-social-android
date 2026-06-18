#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
cd "$ROOT_DIR"

get_env_value() {
  local key="$1"
  grep -E "^${key}=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}

set_env_value() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

fail() {
  echo "ERROR: $*" >&2
  echo
  echo "==> Estado Docker"
  docker compose ps || true
  echo
  echo "==> Logs recientes"
  docker compose logs --tail=80 api edge || true
  exit 1
}

if [ ! -f .env ]; then
  fail "no existe .env en $ROOT_DIR"
fi

if [ -n "${PUBLIC_BASE_URL:-}" ]; then
  set_env_value PUBLIC_BASE_URL "${PUBLIC_BASE_URL%/}"
fi

PUBLIC_BASE_URL="$(get_env_value PUBLIC_BASE_URL)"
if [ -z "$PUBLIC_BASE_URL" ]; then
  fail "falta PUBLIC_BASE_URL en .env o como variable al ejecutar el script"
fi

SCHEMAS="${SUPABASE_STORAGE_REWRITE_SCHEMAS:-public,supabase_import}"
VERIFY_LIMIT_PER_BUCKET="${STORAGE_VERIFY_LIMIT_PER_BUCKET:-0}"
VERIFY_BASE_URL="${STORAGE_VERIFY_BASE_URL:-http://edge:8080}"

echo "==> Verificacion completa de Storage Ubuntu"
echo "PUBLIC_BASE_URL: $PUBLIC_BASE_URL"
echo "STORAGE_VERIFY_BASE_URL: $VERIFY_BASE_URL"
echo "Schemas a revisar: $SCHEMAS"
if [ "$VERIFY_LIMIT_PER_BUCKET" = "0" ]; then
  echo "Archivos a probar: todos los objetos importados"
else
  echo "Archivos a probar: maximo $VERIFY_LIMIT_PER_BUCKET por bucket"
fi
echo

echo "==> Levantando servicios minimos"
docker compose up -d db api edge

for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:3001/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
  [ "$i" = "60" ] && fail "API local no respondio en 3001"
done

for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:3080/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
  [ "$i" = "60" ] && fail "Edge/nginx no respondio en 3080"
done

echo
echo "==> Reescribiendo cualquier URL vieja de Supabase Storage hacia Ubuntu"
docker compose run --rm -T \
  -e PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  -e SUPABASE_STORAGE_REWRITE_SCHEMAS="$SCHEMAS" \
  api npm run rewrite:supabase-storage-urls -- --apply

docker compose up -d --force-recreate api edge

for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:3001/ready >/dev/null 2>&1 && curl -fsS http://127.0.0.1:3080/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
  [ "$i" = "60" ] && fail "servicios no respondieron despues de recrear api/edge"
done

echo
echo "==> Conteo local por bucket"
docker compose exec -T db psql -U conectate -d conectate_fallback -c "
select
  bucket_id,
  count(*) as archivos,
  pg_size_pretty(coalesce(sum(size_bytes),0)::bigint) as peso
from fallback_storage_objects
group by bucket_id
order by bucket_id;
"

echo
echo "==> Prueba HTTP interna por cada bucket + busqueda de URLs viejas"
docker compose run --rm -T \
  -e PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  -e STORAGE_VERIFY_BASE_URL="$VERIFY_BASE_URL" \
  -e SUPABASE_STORAGE_REWRITE_SCHEMAS="$SCHEMAS" \
  -e STORAGE_VERIFY_LIMIT_PER_BUCKET="$VERIFY_LIMIT_PER_BUCKET" \
  api node <<'NODE'
const fs = require('fs')
const { Pool } = require('pg')

const publicBaseUrl = String(process.env.PUBLIC_BASE_URL || '').replace(/\/+$/, '')
const verifyBaseUrl = String(process.env.STORAGE_VERIFY_BASE_URL || publicBaseUrl).replace(/\/+$/, '')
const verifyLimitPerBucket = Number(process.env.STORAGE_VERIFY_LIMIT_PER_BUCKET || '0')
const schemas = String(process.env.SUPABASE_STORAGE_REWRITE_SCHEMAS || 'public,supabase_import')
  .split(',')
  .map((schema) => schema.trim())
  .filter(Boolean)

function ident(value) {
  return '"' + String(value).replace(/"/g, '""') + '"'
}

function publicObjectUrl(baseUrl, bucket, objectPath) {
  return `${baseUrl}/storage/v1/object/public/${encodeURIComponent(bucket)}/` +
    String(objectPath)
      .split('/')
      .filter(Boolean)
      .map((segment) => encodeURIComponent(segment))
      .join('/')
}

function errorMessage(error) {
  const cause = error?.cause
  const causeText = cause
    ? [cause.code, cause.errno, cause.message].filter(Boolean).join(' ')
    : ''
  return [error?.message || String(error), causeText].filter(Boolean).join(' | ')
}

function rememberFailure(failuresByBucket, bucket, message) {
  const current = failuresByBucket.get(bucket) || { total: 0, examples: [] }
  current.total += 1
  if (current.examples.length < 5) current.examples.push(message)
  failuresByBucket.set(bucket, current)
}

async function fetchHeadOrRange(url) {
  const headers = { 'ngrok-skip-browser-warning': '1' }
  let response = await fetch(url, { method: 'HEAD', headers })
  if (!response.ok) {
    response = await fetch(url, { method: 'GET', headers: { ...headers, range: 'bytes=0-0' } })
  }
  return response
}

async function main() {
  const pool = new Pool({ connectionString: process.env.DATABASE_URL })
  try {
    const { rows: columns } = await pool.query(
      `
        select c.table_schema, c.table_name, c.column_name
        from information_schema.columns c
        join information_schema.tables t
          on t.table_schema = c.table_schema
         and t.table_name = c.table_name
        where c.table_schema = any($1::text[])
          and t.table_type = 'BASE TABLE'
          and (
            c.data_type in ('text', 'character varying', 'character', 'json', 'jsonb')
            or c.udt_name in ('text', 'varchar', 'bpchar', 'json', 'jsonb')
          )
        order by c.table_schema, c.table_name, c.ordinal_position
      `,
      [schemas],
    )

    const remaining = []
    for (const column of columns) {
      const sql = `select count(*)::int as total from ${ident(column.table_schema)}.${ident(column.table_name)} where ${ident(column.column_name)}::text ilike '%supabase.co/storage/v1/object%'`
      const result = await pool.query(sql)
      const total = Number(result.rows[0]?.total || 0)
      if (total > 0) remaining.push({ ...column, total })
    }

    if (remaining.length > 0) {
      console.log('ERROR: aun quedan URLs antiguas de Supabase Storage:')
      for (const item of remaining) {
        console.log(`  ${item.table_schema}.${item.table_name}.${item.column_name}: ${item.total}`)
      }
      process.exitCode = 2
    } else {
      console.log('OK: no quedan URLs de Supabase Storage en public/supabase_import.')
    }

    const limitSql = verifyLimitPerBucket > 0
      ? 'where ranked.row_number <= $1'
      : ''
    const params = verifyLimitPerBucket > 0 ? [verifyLimitPerBucket] : []
    const { rows: objects } = await pool.query(`
      select ranked.bucket_id, ranked.name, ranked.content_type, ranked.size_bytes
           , ranked.local_path
      from (
        select
          bucket_id,
          name,
          content_type,
          size_bytes,
          local_path,
          row_number() over (
            partition by bucket_id
            order by size_bytes desc nulls last, name
          ) as row_number
        from fallback_storage_objects
      ) ranked
      ${limitSql}
      order by ranked.bucket_id, ranked.row_number
    `, params)

    if (objects.length === 0) {
      console.log('ERROR: fallback_storage_objects esta vacio.')
      process.exitCode = 2
      return
    }

    let failed = 0
    const checkedByBucket = new Map()
    const failuresByBucket = new Map()
    for (const object of objects) {
      const url = publicObjectUrl(verifyBaseUrl, object.bucket_id, object.name)
      if (object.local_path) {
        try {
          await fs.promises.access(object.local_path, fs.constants.R_OK)
        } catch (error) {
          failed += 1
          rememberFailure(
            failuresByBucket,
            object.bucket_id,
            `MISSING_FILE ${object.name} -> ${object.local_path}`,
          )
          continue
        }
      }

      try {
        const response = await fetchHeadOrRange(url)
        const type = response.headers.get('content-type') || object.content_type || 'unknown'
        const length = response.headers.get('content-length') || object.size_bytes || '?'
        if (!response.ok) {
          failed += 1
          rememberFailure(
            failuresByBucket,
            object.bucket_id,
            `HTTP ${response.status} ${object.name} ${url}`,
          )
        } else {
          checkedByBucket.set(object.bucket_id, (checkedByBucket.get(object.bucket_id) || 0) + 1)
        }
      } catch (error) {
        failed += 1
        rememberFailure(
          failuresByBucket,
          object.bucket_id,
          `${errorMessage(error)} ${object.name} ${url}`,
        )
      }
    }

    for (const [bucket, total] of [...checkedByBucket.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      console.log(`OK ${bucket}: ${total} archivo(s) respondieron desde Ubuntu`)
    }

    if (failed > 0) {
      console.log('Resumen de errores por bucket:')
      for (const [bucket, info] of [...failuresByBucket.entries()].sort(([a], [b]) => a.localeCompare(b))) {
        console.log(`FAIL ${bucket}: ${info.total} archivo(s)`)
        for (const example of info.examples) {
          console.log(`  ejemplo: ${example}`)
        }
      }
      console.log(`ERROR: ${failed} archivo(s) no respondieron correctamente.`)
      process.exitCode = 2
    } else {
      console.log('OK: todos los objetos probados respondieron por Ubuntu.')
    }
  } finally {
    await pool.end()
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(2)
})
NODE

echo
echo "LISTO: verificacion de storage terminada."
echo "Si todo salio OK, los buckets importados ya se sirven desde Ubuntu."
