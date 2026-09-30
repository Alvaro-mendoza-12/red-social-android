'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { documentFormat, registerDocumentPreviewRoutes } = require('../src/document-preview-routes')

function officeForm(name = 'example.docx', bytes = Buffer.from([80, 75, 3, 4, 1])) {
  const form = new FormData(); form.append('file', new Blob([bytes]), name); return form
}
async function withServer(fetchImpl, run, authRequired = (_, __, next) => next(), options = {}) {
  const app = express()
  registerDocumentPreviewRoutes(app, { authRequired, rendererUrl: 'http://office-preview:3000', fetchImpl, maxConcurrent: 1, ...options })
  const server = app.listen(0, '127.0.0.1')
  await new Promise(resolve => server.once('listening', resolve))
  try { await run(`http://127.0.0.1:${server.address().port}`) } finally { await new Promise(resolve => server.close(resolve)) }
}
test('Office format requires both an allowed extension and its container signature', () => {
  assert.equal(documentFormat({ originalname: '../../deck.pptx', buffer: Buffer.from([80,75,3,4]) }), 'pptx')
  assert.equal(documentFormat({ originalname: 'book.xls', buffer: Buffer.from([208,207,17,224,161,177,26,225]) }), 'xls')
  assert.equal(documentFormat({ originalname: 'attack.html', buffer: Buffer.from([80,75,3,4]) }), null)
  assert.equal(documentFormat({ originalname: 'fake.docx', buffer: Buffer.from('Not Office') }), null)
})

test('extra conversion fields and multiple files are rejected', async () => {
  await withServer(() => { throw Error('Must not convert') }, async base => {
    for (const extraFile of [false, true]) {
      const form = officeForm()
      if (extraFile) form.append('file', new Blob([Buffer.from([80,75,3,4])]), 'extra.docx')
      else form.append('downloadFrom', 'http://private.invalid')
      assert.equal((await fetch(base+'/v1/documents/preview', { method:'POST', body:form })).status,400)
    }
  })
})

test('non-PDF converter output is rejected', async () => {
  await withServer(async () => new Response('<html>private output</html>'), async base => {
    const response = await fetch(base+'/v1/documents/preview', { method:'POST', body:officeForm() })
    assert.equal(response.status,503)
    assert.doesNotMatch(await response.text(), /private output/)
  })
})

test('oversized Office uploads are rejected before conversion', async () => {
  await withServer(() => { throw Error('Must not convert') }, async base => {
    const response = await fetch(base+'/v1/documents/preview', { method:'POST', body:officeForm('big.docx',Buffer.alloc(33*1024*1024)) })
    assert.equal(response.status,413)
  })
})

test('a stalled upload times out and releases the conversion slot', async () => {
  await withServer(async () => new Response('%PDF-1.7\nfixture'), async base => {
    await new Promise(resolve => {
      const request = require('node:http').request(base+'/v1/documents/preview', {
        method:'POST', headers:{ 'Content-Type':'multipart/form-data; boundary=slow', 'Transfer-Encoding':'chunked' },
      })
      request.on('error',()=>{}); request.on('close', resolve)
      request.write('--slow\r\nContent-Disposition: form-data; name="file"; filename="slow.docx"\r\n\r\nPK')
    })
    assert.equal((await fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm()})).status,200)
  }, undefined, { timeoutMs:100 })
})
test('authenticated conversion returns PDF, bounds options and does not cache private output', async () => {
  await withServer(async (url, options) => {
    assert.equal(url, 'http://office-preview:3000/forms/libreoffice/convert')
    assert.equal(options.body.get('files').name, 'document.docx')
    assert.equal(options.body.get('downloadFrom'), null)
    assert.equal(options.redirect, 'error')
    return new Response('%PDF-1.7\nfixture', { status: 200 })
  }, async base => {
    const response = await fetch(base+'/v1/documents/preview', { method:'POST', body:officeForm() })
    assert.equal(response.status,200)
    assert.match(response.headers.get('content-type'), /application\/pdf/)
    assert.equal(response.headers.get('cache-control'), 'no-store')
    assert.match(await response.text(), /^%PDF-/)
  })
})
test('authentication runs before file parsing or conversion', async () => {
  await withServer(() => { throw Error('Must not convert') }, async base => {
    const response = await fetch(base+'/v1/documents/preview', { method:'POST', body:officeForm() })
    assert.equal(response.status,401)
  }, (_,res) => res.sendStatus(401))
})
test('unsupported input and malformed conversion fail without exposing converter errors', async () => {
  await withServer(async () => new Response('private internal error', {status:400}), async base => {
    const unsupported = await fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm('attack.html')})
    assert.equal(unsupported.status,415)
    const unreadable = await fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm()})
    assert.equal(unreadable.status,422)
    assert.equal((await unreadable.json()).error,'OFFICE_DOCUMENT_UNREADABLE')
  })
})
test('a busy renderer rejects parallel work and releases its slot afterwards', async () => {
  let release, started
  const held = new Promise(resolve => { release=resolve })
  const entered = new Promise(resolve => { started=resolve })
  await withServer(async () => { started(); await held; return new Response('%PDF-1.7\nfixture') }, async base => {
    const first = fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm()})
    await entered
    const busy = await fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm()})
    assert.equal(busy.status,429); assert.equal(busy.headers.get('retry-after'),'5')
    release(); assert.equal((await first).status,200)
    assert.equal((await fetch(base+'/v1/documents/preview',{method:'POST',body:officeForm()})).status,200)
  })
})
