const { googlePlayRtdnConfiguration } = require('../src/google-play-rtdn')
const { googlePlayConfiguration } = require('../src/postgrest-compat-routes')

const billing = googlePlayConfiguration()
const rtdn = googlePlayRtdnConfiguration()
const report = {
  billingReady: billing.enabled,
  rtdnEnabled: rtdn.enabled,
  rtdnReady: rtdn.ready,
  audienceConfigured: Boolean(rtdn.audience),
  pushServiceAccountConfigured: Boolean(rtdn.pushServiceAccountEmail),
  verificationTokenConfigured: Boolean(rtdn.verificationToken),
  pendingRefundPreference: rtdn.pendingRefundPreference,
  packageName: billing.packageName,
  route: rtdn.route,
}

console.log(JSON.stringify(report, null, 2))
if (!billing.enabled || !rtdn.ready) process.exitCode = 1
