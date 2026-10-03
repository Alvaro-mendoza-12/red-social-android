'use strict'
const net = require('node:net')
let reply = Buffer.alloc(0), done = false
const socket = net.createConnection({ host: '127.0.0.1', port: 3310 })
const timer = setTimeout(() => finish(1), 3000)
function finish(code) {
  if (done) return
  done = true
  clearTimeout(timer)
  socket.destroy()
  process.exitCode = code
}
socket.on('connect', () => socket.write(Buffer.from('zPING\0')))
socket.on('error', () => finish(1))
socket.on('close', () => { if (!done) finish(1) })
socket.on('data', bytes => {
  if (done) return
  if (reply.length + bytes.length > 64) return finish(1)
  reply = Buffer.concat([reply, bytes])
  const end = reply.indexOf(0)
  if (end >= 0) finish(reply.subarray(0, end).toString() === 'PONG' ? 0 : 1)
})
