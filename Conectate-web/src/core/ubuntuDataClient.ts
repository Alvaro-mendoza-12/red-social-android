import { fallbackApiJson } from './auth/fallbackAuth'
import { callUbuntuRpc } from './fallbackRpc'
import { isUbuntuPrimary } from './ubuntuBackend'
import { supabase } from './supabase'

type QueryResult<T> = { data: T | null; error: unknown | null }

class UbuntuQueryBuilder {
  private filters = new URLSearchParams()
  private orderValue = ''
  private limitValue = 0
  private insertBody: Record<string, unknown> | Record<string, unknown>[] | null = null
  private deleteMode = false

  private table: string

  constructor(table: string) {
    this.table = table
  }

  select(_columns: string) {
    return this
  }

  eq(column: string, value: string) {
    this.filters.set(column, `eq.${value}`)
    return this
  }

  in(column: string, values: string[]) {
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
    this.insertBody = payload
    return this
  }

  private buildQuery() {
    const params = new URLSearchParams(this.filters)
    if (this.orderValue) params.set('order', this.orderValue)
    if (this.limitValue > 0) params.set('limit', String(this.limitValue))
    const query = params.toString()
    return query ? `?${query}` : ''
  }

  then<TResult1 = QueryResult<unknown[]>, TResult2 = never>(
    onfulfilled?: ((value: QueryResult<unknown[]>) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return this.execute().then(onfulfilled, onrejected)
  }

  private async execute(): Promise<QueryResult<unknown[]>> {
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
        return { data: rows, error: null }
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
      return { data: rows, error: null }
    } catch (error) {
      return { data: null, error }
    }
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
        upload: async (_path: string, _file: File) => ({ error: null }),
        getPublicUrl: (path: string) => ({
          data: { publicUrl: `${import.meta.env.VITE_FALLBACK_API_URL}/v1/storage/public/${bucket}/${path}` },
        }),
      }
    },
  },
}

export function getDataClient() {
  if (isUbuntuPrimary) return ubuntuDataClient
  return supabase
}
