import { ensureFallbackAccessToken, fallbackApiJson } from './auth/fallbackAuth'
import { callUbuntuRpc } from './fallbackRpc'
import { isUbuntuPrimary } from './ubuntuBackend'
import { supabase } from './supabase'
import { getRuntimeBackendUrl } from './runtimeBackend'

type QueryResult<T> = { data: T | null; error: unknown | null }
type SingleMode = 'many' | 'single' | 'maybeSingle'

function encodePathSegments(path: string) {
  return path
    .split('/')
    .map((segment) => segment.trim())
    .filter(Boolean)
    .map((segment) => encodeURIComponent(segment))
    .join('/')
}

function publicStorageUrl(bucket: string, path: string) {
  return `${getRuntimeBackendUrl()}/storage/v1/object/public/${encodeURIComponent(bucket)}/${encodePathSegments(path)}`
}

function asError(value: unknown, fallback: string) {
  if (value instanceof Error) return value
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>
    const message = String(record.error || record.message || '').trim()
    if (message) return new Error(message)
  }
  const message = String(value || '').trim()
  return new Error(message || fallback)
}

class UbuntuQueryBuilder {
  private filters = new URLSearchParams()
  private orderValue = ''
  private limitValue = 0
  private rangeValue: { from: number; to: number } | null = null
  private singleMode: SingleMode = 'many'
  private insertBody: Record<string, unknown> | Record<string, unknown>[] | null = null
  private deleteMode = false

  private table: string

  constructor(table: string) {
    this.table = table
  }

  select(_columns = '*', _options?: { count?: string; head?: boolean }) {
    void _columns
    void _options
    return this
  }

  eq(column: string, value: string | number | boolean | null) {
    this.filters.set(column, `eq.${value}`)
    return this
  }

  neq(column: string, value: string | number | boolean | null) {
    this.filters.set(column, `neq.${value}`)
    return this
  }

  is(column: string, value: string | boolean | null) {
    this.filters.set(column, `is.${value}`)
    return this
  }

  gte(column: string, value: string | number) {
    this.filters.set(column, `gte.${value}`)
    return this
  }

  lte(column: string, value: string | number) {
    this.filters.set(column, `lte.${value}`)
    return this
  }

  in(column: string, values: Array<string | number>) {
    this.filters.set(column, `in.(${values.join(',')})`)
    return this
  }

  order(column: string, options?: { ascending?: boolean }) {
    this.orderValue = `${column}.${options?.ascending === false ? 'desc' : 'asc'}`
    return this
  }

  limit(count: number) {
    this.limitValue = count
    return this
  }

  range(from: number, to: number) {
    this.rangeValue = { from, to }
    this.limitValue = Math.max(0, to - from + 1)
    return this
  }

  single() {
    this.singleMode = 'single'
    this.limitValue = this.limitValue || 1
    return this
  }

  maybeSingle() {
    this.singleMode = 'maybeSingle'
    this.limitValue = this.limitValue || 1
    return this
  }

  insert(payload: Record<string, unknown> | Record<string, unknown>[]) {
    this.insertBody = payload
    return this
  }

  delete() {
    this.deleteMode = true
    return this
  }

  update(payload: Record<string, unknown>) {
    this.insertBody = payload
    this.deleteMode = false
    return this
  }

  upsert(payload: Record<string, unknown>, _options?: { onConflict?: string }) {
    void _options
    this.insertBody = payload
    return this
  }

  private buildQuery() {
    const params = new URLSearchParams(this.filters)
    if (this.orderValue) params.set('order', this.orderValue)
    if (this.limitValue > 0) params.set('limit', String(this.limitValue))
    if (this.rangeValue) params.set('offset', String(Math.max(0, this.rangeValue.from)))
    const query = params.toString()
    return query ? `?${query}` : ''
  }

  then<TResult1 = QueryResult<unknown[] | unknown>, TResult2 = never>(
    onfulfilled?: ((value: QueryResult<unknown[] | unknown>) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return this.execute().then(onfulfilled, onrejected)
  }

  private shapeResult(rows: unknown[] | null): QueryResult<unknown[] | unknown> {
    if (this.singleMode === 'single') {
      const row = rows?.[0] ?? null
      return row ? { data: row, error: null } : { data: null, error: new Error('ROW_NOT_FOUND') }
    }
    if (this.singleMode === 'maybeSingle') {
      return { data: rows?.[0] ?? null, error: null }
    }
    return { data: rows, error: null }
  }

  private async execute(): Promise<QueryResult<unknown[] | unknown>> {
    try {
      if (this.insertBody && !this.deleteMode) {
        const hasQuery = this.buildQuery().length > 1
        const rows = await fallbackApiJson<unknown[]>(
          `/rest/v1/${encodeURIComponent(this.table)}${hasQuery ? this.buildQuery() : ''}`,
          {
            method: hasQuery ? 'PATCH' : 'POST',
            headers: { Prefer: 'return=representation' },
            body: JSON.stringify(this.insertBody),
          },
        )
        return this.shapeResult(rows)
      }
      if (this.deleteMode) {
        await fallbackApiJson(`/rest/v1/${encodeURIComponent(this.table)}${this.buildQuery()}`, {
          method: 'DELETE',
        })
        return { data: [], error: null }
      }
      const rows = await fallbackApiJson<unknown[]>(
        `/rest/v1/${encodeURIComponent(this.table)}${this.buildQuery()}`,
      )
      return this.shapeResult(rows)
    } catch (error) {
      return { data: null, error }
    }
  }
}

async function uploadUbuntuStorageObject(bucket: string, path: string, file: File, options?: { upsert?: boolean; contentType?: string }) {
  try {
    const fallbackApiUrl = getRuntimeBackendUrl()
    if (!fallbackApiUrl) throw new Error('El servidor Ubuntu no esta configurado.')
    const token = await ensureFallbackAccessToken()
    if (!token) throw new Error('No hay una sesion Ubuntu activa para subir archivos.')
    const objectPath = encodePathSegments(path)
    if (!objectPath) throw new Error('Ruta de archivo invalida.')

    const headers = new Headers()
    headers.set('Authorization', `Bearer ${token}`)
    headers.set('ngrok-skip-browser-warning', 'true')
    headers.set('Content-Type', options?.contentType || file.type || 'application/octet-stream')

    const response = await fetch(
      `${fallbackApiUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${objectPath}`,
      {
        method: options?.upsert ? 'PUT' : 'POST',
        headers,
        body: file,
        credentials: 'include',
      },
    )
    const payload = (await response.json().catch(() => ({}))) as Record<string, unknown>
    if (!response.ok) throw asError(payload, 'No se pudo subir el archivo a Ubuntu.')
    const publicUrl = String(payload.publicUrl || publicStorageUrl(bucket, path))
    return {
      data: {
        Key: String(payload.Key || `${bucket}/${path}`),
        path: String(payload.path || path),
        fullPath: `${bucket}/${path}`,
        publicUrl,
      },
      error: null,
    }
  } catch (error) {
    return { data: null, error }
  }
}

export const ubuntuDataClient = {
  rpc(name: string, params: Record<string, unknown> = {}) {
    return callUbuntuRpc<unknown>(name, params)
      .then((data) => ({ data, error: null }))
      .catch((error) => ({ data: null, error }))
  },
  from(table: string) {
    return new UbuntuQueryBuilder(table)
  },
  storage: {
    from(bucket: string) {
      return {
        upload: (path: string, file: File, options?: { upsert?: boolean; contentType?: string }) =>
          uploadUbuntuStorageObject(bucket, path, file, options),
        getPublicUrl: (path: string) => ({
          data: { publicUrl: publicStorageUrl(bucket, path) },
        }),
        remove: async (paths: string[]) => {
          try {
            await Promise.all(
              paths.map((path) =>
                fallbackApiJson(
                  `/v1/storage/objects/${encodeURIComponent(bucket)}/${encodePathSegments(path)}`,
                  { method: 'DELETE' },
                ),
              ),
            )
            return { data: paths.map((name) => ({ name })), error: null }
          } catch (error) {
            return { data: null, error }
          }
        },
      }
    },
  },
}

export function getDataClient() {
  if (isUbuntuPrimary) return ubuntuDataClient
  return supabase
}
