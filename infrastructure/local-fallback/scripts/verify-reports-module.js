const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const mediaTestImage = process.env.MEDIA_TEST_IMAGE
const runId = Date.now().toString()
const password = 'Conectate123!'
const maxRequestMs = Number(process.env.MAX_MEDIA_REQUEST_MS || 5000)

function encodedObjectPath(objectPath) {
  return String(objectPath || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/')
}

async function request(route, { token, method = 'GET', body, expected = 200 } = {}) {
  const startedAt = Date.now()
  const response = await fetch(`${apiUrl}${route}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body == null ? {} : { 'content-type': 'application/json' }),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      assert.fail(`${method} ${route} returned non-JSON: ${text}`)
    }
  }
  assert.equal(response.status, expected, `${method} ${route}: ${response.status} ${text}`)
  return { elapsedMs: Date.now() - startedAt, payload }
}

async function register(label) {
  const { payload } = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-reports.${label}.${runId}@example.local`,
      password,
      displayName: `Reports ${label}`,
    },
  })
  return { id: payload.user.id, token: payload.accessToken }
}

async function uploadEvidence(user, label, bytes) {
  const objectPath = `${user.id}/reports/${runId}-${label}.png`
  const startedAt = Date.now()
  const response = await fetch(
    `${apiUrl}/storage/v1/object/report-evidence/${encodedObjectPath(objectPath)}`,
    {
      method: 'PUT',
      headers: {
        authorization: `Bearer ${user.token}`,
        'content-length': String(bytes.length),
        'content-type': 'image/png',
      },
      body: bytes,
    },
  )
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, 200, `PUT report-evidence: ${response.status} ${text}`)
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return { ...payload, elapsedMs: Date.now() - startedAt }
}

async function deleteEvidence(user, objectPath) {
  await request(
    `/v1/storage/objects/report-evidence/${encodedObjectPath(objectPath)}`,
    { token: user.token, method: 'DELETE' },
  )
}

async function deleteOwnAccount(user) {
  await request('/rest/v1/rpc/delete_own_account', {
    token: user.token,
    method: 'POST',
    body: {},
  })
}

async function main() {
  assert.ok(mediaTestImage, 'MEDIA_TEST_IMAGE is required')
  const imagePath = path.resolve(mediaTestImage)
  assert.ok(fs.statSync(imagePath).isFile(), `MEDIA_TEST_IMAGE is not a file: ${imagePath}`)
  const imageBytes = fs.readFileSync(imagePath)

  const owner = await register('owner')
  const reporter = await register('reporter')
  const unrelated = await register('unrelated')
  const reporterEvidence = await uploadEvidence(reporter, 'owned', imageBytes)
  const foreignEvidence = await uploadEvidence(owner, 'foreign', imageBytes)

  assert.ok(reporterEvidence.elapsedMs < maxRequestMs, `Evidence upload took ${reporterEvidence.elapsedMs}ms`)

  const { payload: createdPost } = await request('/v1/data/feed/posts', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { content: `Contenido reportable ${runId}`, visibility: 'public' },
  })

  const validContentReport = await request('/v1/data/reports/content', {
    token: reporter.token,
    method: 'POST',
    expected: 201,
    body: {
      contentType: 'post',
      contentId: createdPost.post.id,
      reportedUserId: owner.id,
      reportedUserName: 'Nombre no confiable',
      reason: 'Contenido de prueba',
      description: 'Evidencia valida y persistente',
      evidenceUrl: reporterEvidence.publicUrl,
    },
  })
  assert.ok(validContentReport.elapsedMs < maxRequestMs, `Content report took ${validContentReport.elapsedMs}ms`)
  assert.equal(validContentReport.payload.report.content_type, 'post')
  assert.equal(new URL(validContentReport.payload.report.evidence_url).pathname, new URL(reporterEvidence.publicUrl).pathname)

  await request('/v1/data/reports/content', {
    token: reporter.token,
    method: 'POST',
    expected: 403,
    body: {
      contentType: 'post',
      contentId: createdPost.post.id,
      reportedUserId: owner.id,
      reason: 'Evidencia ajena',
      evidenceUrl: foreignEvidence.publicUrl,
    },
  })

  await request('/v1/data/reports/content', {
    token: reporter.token,
    method: 'POST',
    expected: 400,
    body: {
      contentType: 'post',
      contentId: createdPost.post.id,
      reportedUserId: unrelated.id,
      reason: 'Propietario incorrecto',
      evidenceUrl: reporterEvidence.publicUrl,
    },
  })

  await request('/v1/data/reports/users', {
    token: reporter.token,
    method: 'POST',
    expected: 201,
    body: {
      reportedUserId: owner.id,
      reason: 'Reporte de usuario valido',
      evidenceUrl: reporterEvidence.publicUrl,
    },
  })

  await request('/rest/v1/rpc/secure_submit_report', {
    token: reporter.token,
    method: 'POST',
    expected: 403,
    body: {
      p_reported_user_id: owner.id,
      p_reason: 'RPC con evidencia ajena',
      p_evidence_url: foreignEvidence.publicUrl,
    },
  })

  await request('/rest/v1/reports', {
    token: reporter.token,
    method: 'POST',
    expected: 403,
    body: {
      reported_user_id: owner.id,
      reason: 'Ruta heredada con evidencia ajena',
      evidence_url: foreignEvidence.publicUrl,
    },
  })

  const ownReports = await request('/rest/v1/reports?select=*', { token: reporter.token })
  assert.ok(Array.isArray(ownReports.payload))
  assert.ok(ownReports.payload.some((report) => report.content_type === 'post'))
  assert.ok(ownReports.payload.some((report) => report.content_type === 'user'))

  await request(`/v1/data/feed/posts/${createdPost.post.id}`, { token: owner.token, method: 'DELETE' })
  await deleteEvidence(reporter, reporterEvidence.path)
  await deleteEvidence(owner, foreignEvidence.path)
  await deleteOwnAccount(reporter)
  await deleteOwnAccount(owner)
  await deleteOwnAccount(unrelated)

  process.stdout.write(JSON.stringify({
    ok: true,
    evidenceUploadMs: reporterEvidence.elapsedMs,
    contentReportMs: validContentReport.elapsedMs,
    ownedEvidenceAccepted: true,
    foreignEvidenceRejected: true,
    legacyPathsProtected: true,
    contentOwnerValidated: true,
    reportsVisibleToReporter: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
