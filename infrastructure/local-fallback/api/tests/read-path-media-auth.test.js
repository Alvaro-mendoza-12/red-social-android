'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {mediaReadOptions}=require('../scripts/verify-social-read-paths');
const base='https://app.example.invalid',storage=base+'/storage/v1/object/public/avatars/photo.png';
test('media diagnostics authenticate protected same-origin storage and reject redirects',()=>{
 assert.deepEqual(mediaReadOptions(storage,base,'test-token'),{redirect:'error',headers:{authorization:'Bearer test-token'}});
 assert.equal(mediaReadOptions(storage,base,'').headers,undefined);
});
test('media diagnostic credentials never follow a foreign origin, port, userinfo or nonstorage path',()=>{
 for(const url of [storage.replace('app.example.invalid','foreign.invalid'),storage.replace('app.example.invalid','app.example.invalid:444'),storage.replace('https:','http:'),storage.replace('https://','https://user@'),base+'/avatar.png'])assert.equal(mediaReadOptions(url,base,'test-token').headers,undefined);
});
test('loopback HTTP remains usable only in a matching controlled fixture',()=>{
 const url='http://127.0.0.1:1234/storage/v1/object/public/test/file';assert.ok(mediaReadOptions(url,'http://127.0.0.1:1234','test-token').headers);
 assert.equal(mediaReadOptions(url,'http://127.0.0.1:1235','test-token').headers,undefined);
});
