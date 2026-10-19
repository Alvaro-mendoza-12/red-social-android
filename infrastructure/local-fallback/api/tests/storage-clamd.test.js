'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { EventEmitter } = require('node:events')
const { createStorageClamd, parseDaemonVersion } = require('../src/storage-clamd')
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const at = Date.UTC(2026, 9, 7, 12)
const version = 'ClamAV 1.4.6/28150/Wed Oct 7 11:00:00 2026 COMMANDS: INSTREAM VERSIONCOMMANDS PING'
const config = ['StreamMaxLength 500M','MaxFileSize 500M','MaxScanSize 1000M','MaxThreads 2','MaxQueue 4','AlertExceedsMax yes','AlertEncrypted yes','ScanArchive yes','ScanPE yes','ScanOLE2 yes','MaxScanTime 600000'].join('\n')
function fixture(mode = 'clean', currentTime = at) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 's-')), file = path.join(root,'inert.txt'), policyFile = path.join(root,'policy.json'), configFile = path.join(root,'clamd.conf')
  const bytes = Buffer.alloc(192 * 1024, 'A'); fs.writeFileSync(file,bytes); fs.writeFileSync(configFile,config)
  const policy = Buffer.from(JSON.stringify({ schema:1,timezone:'UTC',maxFileBytes:500*1024*1024,streamMaxBytes:500*1024*1024,maxScanBytes:1000*1024*1024,alertExceedsMax:true,alertEncrypted:true,maxThreads:2,maxQueue:4,scanArchive:true,scanPE:true,scanOLE2:true,configSha256:hash(Buffer.from(config)) }))
  fs.writeFileSync(policyFile,policy)
  const sockets = []; let framedBytes=0, frames=0
  function connect() {
    const socket = new EventEmitter(); let command, pending = Buffer.alloc(0)
    socket.destroyed=false; socket.destroy=()=>{socket.destroyed=true;queueMicrotask(()=>socket.emit('close'))}
    socket.write = chunk => {
      if (socket.destroyed) throw Error('destroyed')
      if (!command) {
        command=chunk.toString()
        if (command==='zVERSIONCOMMANDS\0') queueMicrotask(()=>socket.emit('data',Buffer.from((mode==='stale' ? version.replace('Wed Oct 7 11:00:00','Mon Oct 5 11:00:00') : version)+'\0')))
        else if (command==='zINSTREAM\0' && mode==='early') queueMicrotask(()=>socket.emit('data',Buffer.from('stream: OK\0')))
        return true
      }
      if (mode==='timeout') return true
      pending=Buffer.concat([pending,chunk])
      while(pending.length>=4) {
        const size=pending.readUInt32BE(0)
        if(pending.length<4+size) break
        pending=pending.subarray(4+size)
        if(size===0) {
          const reply = mode==='budget' ? 'stream: Heuristics.Limits.Exceeded.MaxScanTime FOUND\0' : mode==='encrypted' ? 'stream: Heuristics.Encrypted.Zip FOUND\0' : mode==='found' ? 'stream: Inert.Test.Result FOUND\0' : mode==='limit' ? 'INSTREAM size limit exceeded. ERROR\0' : mode==='partial' ? 'stream: OK' : mode==='extra' ? 'stream: OK\0stream: x FOUND\0' : 'stream: OK\0'
          queueMicrotask(()=>{ socket.emit('data',Buffer.from(reply)); if(mode==='partial')socket.emit('end') })
        } else {framedBytes+=size;frames++}
      }
      if(mode==='backpressure') {queueMicrotask(()=>socket.emit('drain'));return false}
      return true
    }
    sockets.push(socket);queueMicrotask(()=>socket.emit('connect'));return socket
  }
  const scanner=createStorageClamd({host:'inert.invalid',connect,now:()=>currentTime,timeoutMs:mode==='timeout'?20:1000,attestationFile:policyFile,policySha256:hash(policy),daemonConfigFile:configFile})
  return {root,file,bytes,policyFile,configFile,scanner,sockets,stats:()=>({framedBytes,frames}),cleanup(){assert.ok(root.startsWith(path.resolve(os.tmpdir())+path.sep+'s-'));fs.rmSync(root,{recursive:true,force:true})}}
}
test('fresh signatures and required commands are mandatory',()=>{
  assert.equal(parseDaemonVersion(version,at,24*60*60*1000).signatureVersion,'28150')
  assert.throws(()=>parseDaemonVersion(version.replace('Wed Oct 7','Mon Oct 5'),at,24*60*60*1000),/SIGNATURES_STALE/)
  assert.throws(()=>parseDaemonVersion(version.replace(' INSTREAM',''),at,24*60*60*1000),/COMMANDS_UNVERIFIED/)
})
test('default readiness accepts signatures through 48 hours and rejects older signatures', async t => {
  const published = Date.UTC(2026, 9, 7, 11)
  for (const age of [25, 47.9, 48, 48.001, 72]) await t.test(String(age), async () => {
    const f = fixture('clean', published + age * 3600000)
    try {
      if (age <= 48) assert.equal((await f.scanner.readiness()).signatureVersion, '28150')
      else await assert.rejects(f.scanner.readiness(), error => error.code === 'STORAGE_SCAN_SIGNATURES_STALE')
    } finally { f.cleanup() }
  })
})
test('streaming protocol sends all framed bytes with digest, including backpressure',async t=>{
  for(const mode of ['clean','backpressure'])await t.test(mode,async()=>{
    const f=fixture(mode);try{const result=await f.scanner.scan(f.file,{expectedSize:f.bytes.length});assert.equal(result.verdict,'clean');assert.equal(result.sha256,hash(f.bytes));assert.equal(result.bytesScanned,f.bytes.length);assert.deepEqual(f.stats(),{framedBytes:f.bytes.length,frames:3});assert.ok(f.sockets.every(socket=>socket.destroyed))}finally{f.cleanup()}
  })
})
test('FOUND, limit, incomplete, extra, early, timeout and stale replies fail closed',async t=>{
  for(const mode of ['found','limit','partial','extra','early','timeout','stale'])await t.test(mode,async()=>{
    const f=fixture(mode);try{await assert.rejects(f.scanner.scan(f.file,{expectedSize:f.bytes.length}),/STORAGE_/);assert.ok(f.sockets.every(socket=>socket.destroyed))}finally{f.cleanup()}
  })
})
test('daemon config modification fails readiness before any bytes are sent',async()=>{
  const f=fixture();try{fs.appendFileSync(f.configFile,'\nMaxFileSize 1M');await assert.rejects(f.scanner.scan(f.file,{expectedSize:f.bytes.length}),/POLICY_UNVERIFIED/);assert.equal(f.sockets.length,0)}finally{f.cleanup()}
})

test('scan budget exhaustion and encrypted containers stay rejected with actionable distinct codes',async t=>{
  for(const [mode,code,status] of [['budget','STORAGE_SCAN_LIMIT_EXCEEDED',422],['encrypted','STORAGE_ARCHIVE_ENCRYPTED',415],['found','STORAGE_FILE_REJECTED',415]])await t.test(mode,async()=>{
    const f=fixture(mode)
    try{await assert.rejects(f.scanner.scan(f.file,{expectedSize:f.bytes.length}),error=>error.code===code&&error.status===status)}finally{f.cleanup()}
  })
})
