'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
process.env.STORAGE_SCAN_ENFORCE='false';process.env.STORAGE_READ_ENFORCE='false';
const {storageScanReadiness}=require('../src/storage-routes');
test('disabled scanner readiness explicitly reports disabled without daemon calls',async()=>{
 const result=await storageScanReadiness({enforced:false,readiness:()=>{throw Error('unexpected daemon call');}});assert.deepEqual(result,{enforced:false});
});
test('enforced scanner readiness publishes verified status without policy or paths',async()=>{
 const result=await storageScanReadiness({enforced:true,readiness:async()=>({engine:'ClamAV/1.4.6',signatureVersion:'28147',signatureUpdatedAt:'2026-10-08T06:24:12.000Z',policySha256:'private-deploy-policy'})});
 assert.deepEqual(result,{enforced:true,engine:'ClamAV/1.4.6',signatureVersion:'28147',signatureUpdatedAt:'2026-10-08T06:24:12.000Z'});
});
test('enforced readiness propagates fail-closed scanner outage, expiry and policy failures',async()=>{
 for(const code of ['STORAGE_SCAN_UNAVAILABLE','STORAGE_SCAN_SIGNATURES_STALE','STORAGE_SCAN_POLICY_UNVERIFIED']){
  await assert.rejects(()=>storageScanReadiness({enforced:true,readiness:async()=>{throw Object.assign(Error(code),{code,status:503});}}),error=>error.code===code&&error.status===503);
 }
});
