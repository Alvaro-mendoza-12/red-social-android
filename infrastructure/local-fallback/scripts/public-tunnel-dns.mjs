import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const resolvers = [
  'https://dns.google/resolve',
  'https://cloudflare-dns.com/dns-query',
]

export function classifyDnsReply(reply, hostname) {
  const question = reply?.Question?.some(
    entry => String(entry.name).toLowerCase().replace(/\.$/, '') === hostname && entry.type === 1,
  )
  if (!question || reply.TC === true) return 'unknown'
  if (reply.Status === 3) return 'missing'
  if (reply.Status === 0 && reply.Answer?.some(entry => entry.type === 1 && entry.data)) return 'exists'
  return 'unknown'
}

/** Only agreement on NXDOMAIN can overrule a still-running connector. */
export async function inspectTunnelDns(publicUrl, { fetchImpl = globalThis.fetch, timeoutMs = 4_000 } = {}) {
  const url = new URL(publicUrl)
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !/^[a-z0-9-]+\.trycloudflare\.com$/.test(url.hostname) || url.pathname !== '/' ||
      url.search || url.hash) throw new Error('Expected a Quick Tunnel HTTPS origin')
  const observations = await Promise.all(resolvers.map(async resolver => {
    const query = new URL(resolver)
    query.searchParams.set('name', url.hostname)
    query.searchParams.set('type', 'A')
    try {
      const response = await fetchImpl(query, {
        headers: { accept: 'application/dns-json' },
        signal: AbortSignal.timeout(timeoutMs),
        redirect: 'error',
      })
      if (!response.ok) return { resolver, state: 'unknown' }
      return { resolver, state: classifyDnsReply(await response.json(), url.hostname) }
    } catch {
      return { resolver, state: 'unknown' }
    }
  }))
  return {
    hostname: url.hostname,
    removed: observations.every(result => result.state === 'missing'),
    observations,
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = await inspectTunnelDns(process.argv[2])
    console.log(JSON.stringify(result))
    // Exit 0 is used as a shell predicate: the current hostname must be replaced.
    process.exitCode = result.removed ? 0 : 1
  } catch (error) {
    console.error(error.message)
    process.exitCode = 2
  }
}
