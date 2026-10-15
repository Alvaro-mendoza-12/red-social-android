const test=require('node:test')
const assert=require('node:assert/strict')
const {spawnSync}=require('node:child_process')
const path=require('node:path')

test('private PostgreSQL request and publication mention acceptance',{
  skip:!process.env.NOVVIN_MESSAGE_REQUEST_TEST_DATABASE_URL,
  timeout:120_000,
},()=>{
  const result=spawnSync(process.execPath,[path.join(__dirname,'../scripts/verify-social-message-requests-private.js')],{
    env:{...process.env,DATABASE_URL:process.env.NOVVIN_MESSAGE_REQUEST_TEST_DATABASE_URL},encoding:'utf8',timeout:110_000,
  })
  assert.equal(result.status,0,result.stdout)
  const report=JSON.parse(result.stdout)
  assert.equal(report.passed,true)
  assert.ok(report.cases.length>=18)
  assert.ok(report.cases.every(item=>item.passed))
  assert.equal(report.productionAccountsCreated,false)
  assert.equal(report.productionMessagesSent,false)
})
