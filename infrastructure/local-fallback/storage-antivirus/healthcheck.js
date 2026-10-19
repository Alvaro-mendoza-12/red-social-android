'use strict'
const fs = require('node:fs'), crypto = require('node:crypto'), net = require('node:net')
// FreshClam still checks every two hours; fail closed if the official database is over 48 hours old.
const MAX_AGE_MS = 48 * 60 * 60 * 1000
function recentSignature(value, now = Date.now()) {
  const match = /^ClamAV ([0-9.]+)\/([1-9]\d*)\/([A-Za-z]{3} [A-Za-z]{3} +\d{1,2} \d{2}:\d{2}:\d{2} \d{4})(?:\|? COMMANDS: (.+))?$/.exec(value)
  if (!match) return false
  const updated = Date.parse(match[3] + ' UTC')
  const commands = new Set((match[4] || '').split(/[ ,]+/))
  return Number.isFinite(updated) && updated <= now + 60000 && now - updated <= MAX_AGE_MS &&
    commands.has('INSTREAM') && commands.has('VERSIONCOMMANDS')
}
function policyValid(directory = '/policy') {
  const policy = JSON.parse(fs.readFileSync(directory + '/storage-scan-policy.json', 'utf8'))
  const config = fs.readFileSync(directory + '/clamd-storage.conf')
  if (crypto.createHash('sha256').update(config).digest('hex') !== policy.configSha256) return false
  const required = { StreamMaxLength:'500M', MaxFileSize:'500M', MaxScanSize:'1000M', MaxThreads:'2', MaxQueue:'4',
    AlertExceedsMax:'yes', AlertEncrypted:'yes', ScanArchive:'yes', ScanPE:'yes', ScanOLE2:'yes' }
  const lines = config.toString('utf8').split(/\r?\n/).map(line => line.trim()).filter(line => !line.startsWith('#'))
  for (const [key,value] of Object.entries(required)) {
    const entries = lines.filter(line => line.startsWith(key + ' '))
    if (entries.length !== 1 || entries[0] !== key + ' ' + value) return false
  }
  return policy.schema === 1 && policy.timezone === 'UTC' && policy.maxFileBytes === 524288000 &&
    policy.streamMaxBytes === 524288000 && policy.maxScanBytes === 1048576000 && policy.maxThreads === 2 && policy.maxQueue === 4 &&
    policy.alertEncrypted === true && policy.alertExceedsMax === true && policy.scanArchive === true && policy.scanPE === true && policy.scanOLE2 === true
}
function dailyFileRecent(file = '/var/lib/clamav/daily.cvd', now = Date.now()) {
  let fd
  try {
    fd = fs.openSync(file, 'r'); const bytes = Buffer.alloc(512), count = fs.readSync(fd, bytes, 0, bytes.length, 0)
    const fields = bytes.subarray(0,count).toString('ascii').trim().split(':')
    const timestamp = Number(fields[8]) * 1000
    return fields[0] === 'ClamAV-VDB' && /^\d+$/.test(fields[2] || '') && Number.isFinite(timestamp) &&
      timestamp <= now + 60000 && now - timestamp <= MAX_AGE_MS
  } catch { return false } finally { if (fd !== undefined) fs.closeSync(fd) }
}
async function daemonRecent() {
  return new Promise(resolve => {
    let done = false, bytes = Buffer.alloc(0)
    const socket = net.createConnection({ host:'127.0.0.1',port:3310 })
    const timer = setTimeout(() => finish(false),3500)
    function finish(result) { if(done)return;done=true;clearTimeout(timer);socket.destroy();resolve(result) }
    socket.on('connect',()=>socket.write(Buffer.from('zVERSIONCOMMANDS\0')))
    socket.on('error',()=>finish(false));socket.on('end',()=>finish(false));socket.on('close',()=>finish(false))
    socket.on('data',chunk=>{
      if(bytes.length+chunk.length>4096)return finish(false)
      bytes=Buffer.concat([bytes,chunk]);const end=bytes.indexOf(0)
      if(end>=0)finish(end===bytes.length-1&&recentSignature(bytes.subarray(0,end).toString('utf8')))
    })
  })
}
async function main() {
  if(process.env.TZ!=='UTC')return 1
  try {
    if(process.argv.includes('--database-only'))return dailyFileRecent()||dailyFileRecent('/var/lib/clamav/daily.cld')?0:1
    return policyValid()&&await daemonRecent()?0:1
  } catch { return 1 }
}
module.exports={recentSignature,policyValid,dailyFileRecent,daemonRecent}
if(require.main===module)main().then(code=>{process.exitCode=code},()=>{process.exitCode=1})
