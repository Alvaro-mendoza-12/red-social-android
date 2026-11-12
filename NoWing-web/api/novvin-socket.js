import http from 'node:http'
import { WebSocket, WebSocketServer } from 'ws'

const upstreamOrigin = 'wss://conectate.tail9b267c.ts.net'
const paths = { realtime: '/realtime/', voice: '/socket.io/', call: '/call/socket.io/', live: '/live/socket.io/' }
const maximumBufferedBytes = 1024 * 1024

function trustedOrigin(value) {
  try {
    const url = new URL(value)
    return url.origin === value && url.protocol === 'https:' && !url.port && (
      ['novvinapp.com', 'www.novvinapp.com', 'red-social-android.vercel.app', 'red-social-android-alvaro-s-projects1.vercel.app', 'red-social-android-git-main-alvaro-s-projects1.vercel.app'].includes(url.hostname)
      || /^red-social-android-[a-z0-9]+-alvaro-s-projects1\.vercel\.app$/.test(url.hostname))
  } catch { return false }
}

export function createPublicSocketServer(makeUpstream = (target, options) => new WebSocket(target, options)) {
 const wss = new WebSocketServer({ noServer: true, maxPayload: maximumBufferedBytes, perMessageDeflate: false })
 const server = http.createServer((_request, response) => { response.writeHead(426); response.end('WebSocket required') })
 server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url, 'https://novvin.invalid')
  const channel = url.searchParams.get('channel')
  if (!Object.hasOwn(paths, channel) || !trustedOrigin(request.headers.origin)) {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return
  }
  const target = new URL(paths[channel], upstreamOrigin)
  for (const name of ['EIO', 'transport', 'affinity', 'sid']) {
    const value = url.searchParams.get(name)
    if (value && value.length <= 192) target.searchParams.set(name, value)
  }
  wss.handleUpgrade(request, socket, head, client => {
    const upstream = makeUpstream(target, { headers: { Origin: request.headers.origin }, maxPayload: maximumBufferedBytes, handshakeTimeout: 10000, perMessageDeflate: false })
    const queued = []; let queuedBytes = 0
    const close = (peer, code = 1011) => { if (peer.readyState === WebSocket.OPEN) peer.close(code); else if (peer.readyState === WebSocket.CONNECTING) peer.terminate() }
    client.on('message', (data, binary) => {
      if (upstream.readyState === WebSocket.OPEN) {
        if (upstream.bufferedAmount + data.length > maximumBufferedBytes) { close(client, 1009); close(upstream, 1009); return }
        upstream.send(data, { binary })
      } else {
        queuedBytes += data.length
        if (queuedBytes > maximumBufferedBytes) { close(client, 1009); close(upstream, 1009); return }
        queued.push([data, binary])
      }
    })
    upstream.on('open', () => { for (const [data, binary] of queued) upstream.send(data, { binary }); queued.length = 0; queuedBytes = 0 })
    upstream.on('message', (data, binary) => {
      if (client.readyState !== WebSocket.OPEN) return
      if (client.bufferedAmount + data.length > maximumBufferedBytes) { close(client, 1009); close(upstream, 1009); return }
      client.send(data, { binary })
    })
    upstream.on('error', () => close(client))
    client.on('error', () => close(upstream))
    upstream.on('close', code => close(client, [1000, 1001, 1008, 1009].includes(code) || (code >= 4000 && code <= 4999) ? code : 1011))
    client.on('close', () => { queued.length = 0; queuedBytes = 0; close(upstream, 1000) })
  })
 })
 return server
}

export const config = { maxDuration: 300 }
export default createPublicSocketServer()
