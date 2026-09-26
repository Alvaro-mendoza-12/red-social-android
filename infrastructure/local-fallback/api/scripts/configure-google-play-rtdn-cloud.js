const fs = require('fs')
const { GoogleAuth } = require('google-auth-library')

const topicId = String(process.env.GOOGLE_PLAY_RTDN_TOPIC_ID || 'novvin-google-play-rtdn').trim()
const subscriptionId = String(
  process.env.GOOGLE_PLAY_RTDN_SUBSCRIPTION_ID || 'novvin-google-play-rtdn-push',
).trim()
const pushAccountId = String(
  process.env.GOOGLE_PLAY_RTDN_PUSH_ACCOUNT_ID || 'novvin-rtdn-push',
).trim()
const existingPushServiceAccountEmail = String(
  process.env.GOOGLE_PLAY_RTDN_EXISTING_PUSH_SERVICE_ACCOUNT_EMAIL || '',
).trim().toLowerCase()
const publicBaseUrl = String(process.env.PUBLIC_BASE_URL || '').trim().replace(/\/+$/, '')
const verificationToken = String(process.env.GOOGLE_PLAY_RTDN_VERIFICATION_TOKEN || '').trim()
const serviceAccountFile = String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE || '').trim()

function required(value, name) {
  if (!value) throw new Error(`${name}_REQUIRED`)
  return value
}

function addBinding(policy, role, member) {
  const current = policy || { version: 1, bindings: [] }
  current.bindings = Array.isArray(current.bindings) ? current.bindings : []
  let binding = current.bindings.find((entry) => entry.role === role)
  if (!binding) {
    binding = { role, members: [] }
    current.bindings.push(binding)
  }
  binding.members = Array.from(new Set([...(binding.members || []), member])).sort()
  return current
}

async function run() {
  required(publicBaseUrl, 'PUBLIC_BASE_URL')
  required(serviceAccountFile, 'GOOGLE_PLAY_SERVICE_ACCOUNT_FILE')
  const credentials = JSON.parse(fs.readFileSync(serviceAccountFile, 'utf8'))
  const projectId = required(credentials.project_id, 'GOOGLE_CLOUD_PROJECT_ID')
  const callerEmail = required(credentials.client_email, 'GOOGLE_CLOUD_CALLER_EMAIL')
  const auth = new GoogleAuth({
    credentials,
    scopes: ['https://www.googleapis.com/auth/cloud-platform'],
  })
  const client = await auth.getClient()
  const accessTokenResult = await client.getAccessToken()
  const accessToken = typeof accessTokenResult === 'string'
    ? accessTokenResult
    : accessTokenResult?.token
  required(accessToken, 'GOOGLE_CLOUD_ACCESS_TOKEN')

  async function api(url, options = {}, accepted = []) {
    const response = await fetch(url, {
      ...options,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(options.headers || {}),
      },
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok && !accepted.includes(response.status)) {
      const error = new Error(body?.error?.message || `GOOGLE_CLOUD_HTTP_${response.status}`)
      error.status = response.status
      throw error
    }
    return { status: response.status, body }
  }

  const project = await api(
    `https://cloudresourcemanager.googleapis.com/v1/projects/${encodeURIComponent(projectId)}`,
  )
  const projectNumber = required(project.body.projectNumber, 'GOOGLE_CLOUD_PROJECT_NUMBER')

  await api(
    `https://serviceusage.googleapis.com/v1/projects/${projectNumber}/services/pubsub.googleapis.com:enable`,
    { method: 'POST', body: '{}' },
    // Some Firebase Admin service accounts can administer an already-enabled
    // Pub/Sub service but cannot enable Google APIs. Continue and let the
    // concrete Pub/Sub call prove whether the service is available.
    [403, 409],
  )

  // Pub/Sub's managed service agent is created lazily. Materialize it before
  // binding Token Creator so fresh projects do not fail with "principal not
  // found" while configuring an authenticated push subscription.
  await api(
    `https://serviceusage.googleapis.com/v1beta1/projects/${projectNumber}/services/pubsub.googleapis.com:generateServiceIdentity`,
    { method: 'POST', body: '{}' },
    [403, 409],
  )

  const pushEmail = existingPushServiceAccountEmail ||
    `${pushAccountId}@${projectId}.iam.gserviceaccount.com`
  if (!existingPushServiceAccountEmail) {
    await api(
      `https://iam.googleapis.com/v1/projects/${projectId}/serviceAccounts`,
      {
        method: 'POST',
        body: JSON.stringify({
          accountId: pushAccountId,
          serviceAccount: { displayName: 'Novvin Google Play RTDN push' },
        }),
      },
      [409],
    )
  }

  // Keep both bindings scoped to the dedicated push identity. Granting Token
  // Creator at project level would let Pub/Sub mint tokens for unrelated
  // service accounts.
  const pushIamUrl = `https://iam.googleapis.com/v1/projects/${projectId}/serviceAccounts/${encodeURIComponent(pushEmail)}`
  const pushPolicyResponse = await api(`${pushIamUrl}:getIamPolicy`, {
    method: 'POST',
    body: JSON.stringify({ options: { requestedPolicyVersion: 1 } }),
  })
  let pushPolicy = pushPolicyResponse.body
  pushPolicy = addBinding(
    pushPolicy,
    'roles/iam.serviceAccountUser',
    `serviceAccount:${callerEmail}`,
  )
  pushPolicy = addBinding(
    pushPolicy,
    'roles/iam.serviceAccountTokenCreator',
    `serviceAccount:service-${projectNumber}@gcp-sa-pubsub.iam.gserviceaccount.com`,
  )
  await api(`${pushIamUrl}:setIamPolicy`, {
    method: 'POST',
    body: JSON.stringify({ policy: pushPolicy }),
  })

  const topicName = `projects/${projectId}/topics/${topicId}`
  const encodedTopicName = topicName.split('/').map(encodeURIComponent).join('/')
  await api(`https://pubsub.googleapis.com/v1/${encodedTopicName}`, {
    method: 'PUT',
    body: '{}',
  }, [409])
  const topicPolicyResponse = await api(
    `https://pubsub.googleapis.com/v1/${encodedTopicName}:getIamPolicy`,
    { method: 'POST', body: '{}' },
  )
  const topicPolicy = addBinding(
    topicPolicyResponse.body,
    'roles/pubsub.publisher',
    'serviceAccount:google-play-developer-notifications@system.gserviceaccount.com',
  )
  await api(`https://pubsub.googleapis.com/v1/${encodedTopicName}:setIamPolicy`, {
    method: 'POST',
    body: JSON.stringify({ policy: topicPolicy }),
  })

  const audience = `${publicBaseUrl}/webhooks/google-play/rtdn`
  // OIDC is the primary authentication mechanism recommended for Pub/Sub
  // push. A URL token remains optional defense in depth for deployments that
  // explicitly configure one.
  const pushEndpoint = verificationToken
    ? `${audience}?token=${encodeURIComponent(verificationToken)}`
    : audience
  const subscriptionName = `projects/${projectId}/subscriptions/${subscriptionId}`
  const encodedSubscriptionName = subscriptionName.split('/').map(encodeURIComponent).join('/')
  const subscriptionBody = {
    topic: topicName,
    pushConfig: {
      pushEndpoint,
      oidcToken: { serviceAccountEmail: pushEmail, audience },
    },
    ackDeadlineSeconds: 30,
    retryPolicy: { minimumBackoff: '10s', maximumBackoff: '600s' },
    expirationPolicy: { ttl: '' },
  }
  const created = await api(
    `https://pubsub.googleapis.com/v1/${encodedSubscriptionName}`,
    { method: 'PUT', body: JSON.stringify(subscriptionBody) },
    [409],
  )
  if (created.status === 409) {
    await api(`https://pubsub.googleapis.com/v1/${encodedSubscriptionName}:modifyPushConfig`, {
      method: 'POST',
      body: JSON.stringify({ pushConfig: subscriptionBody.pushConfig }),
    })
  }

  console.log(JSON.stringify({
    configured: true,
    projectId,
    projectNumber,
    topicName,
    subscriptionName,
    pushServiceAccountEmail: pushEmail,
    audience,
    playConsoleTopic: topicName,
  }, null, 2))
}

run().catch((error) => {
  console.error(JSON.stringify({
    configured: false,
    status: Number(error?.status || 0) || null,
    error: String(error?.message || error).slice(0, 300),
  }, null, 2))
  process.exit(1)
})
