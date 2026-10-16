const { test } = require('node:test')
const assert = require('node:assert/strict')
const { MAX_MESSAGE_UPLOAD_BYTES, assertMessageVideoDuration, validateMessagingUpload } = require('../src/messaging-upload-policy')
test('documents above 60 MB up to 500 MB', async () => {
  await validateMessagingUpload({ contentType:'application/zip', sizeBytes:400*1024*1024 })
  await validateMessagingUpload({ contentType:'application/pdf', sizeBytes:MAX_MESSAGE_UPLOAD_BYTES })
  await assert.rejects(validateMessagingUpload({ contentType:'application/zip', sizeBytes:MAX_MESSAGE_UPLOAD_BYTES+1 }), { message:'UPLOAD_TOO_LARGE', status:413 })
})
test('image and audio remain bounded', async () => {
  for (const contentType of ['image/png','audio/ogg']) await assert.rejects(validateMessagingUpload({contentType,sizeBytes:61*1024*1024}), {message:'UPLOAD_TOO_LARGE'})
})
test('ten minute video duration validation', () => {
  assertMessageVideoDuration(600); assertMessageVideoDuration(600.25)
  for (const value of [0,NaN,Infinity,-1]) assert.throws(()=>assertMessageVideoDuration(value),{message:'VIDEO_DECODE_FAILED'})
  assert.throws(()=>assertMessageVideoDuration(601),{message:'MESSAGE_VIDEO_DURATION_TOO_LONG',status:413})
})
test('MP4 and WebM duration is probed rather than trusting the client', async () => {
  for (const contentType of ['video/mp4','video/webm']) {
    let calls=0
    const run=async(command,args,options)=>{calls++;assert.equal(command,'ffprobe');assert.equal(args.at(-1),'/tmp/owned.mp4');assert.equal(options.timeout,20000);return {stdout:JSON.stringify({format:{duration:'600'}})}}
    await validateMessagingUpload({contentType,sizeBytes:100,sourcePath:'/tmp/owned.mp4'},run);assert.equal(calls,1)
    await assert.rejects(validateMessagingUpload({contentType,sizeBytes:100,sourcePath:'/tmp/owned.mp4'},async()=>({stdout:'{"format":{"duration":601}}'})),{message:'MESSAGE_VIDEO_DURATION_TOO_LONG'})
    await assert.rejects(validateMessagingUpload({contentType,sizeBytes:100,sourcePath:'/tmp/owned.mp4'},async()=>{throw Error('decoder internal path')}),{message:'VIDEO_DECODE_FAILED'})
  }
})
