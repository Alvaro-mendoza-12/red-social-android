'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {sourceUrls,referencesObject}=require('../src/storage-content-read');
const origin='https://gallery.example.invalid',url=origin+'/storage/v1/object/public/post-media/owned.jpg';
test('gallery descriptors support only explicit string URL properties',()=>{
 const media_urls=[{url},{media_url:url},{url:42,media_url:url},{url:'',media_url:url},{href:url},{url:{url}},[url],42,null];
 for(const value of [media_urls,JSON.stringify(media_urls)])assert.deepEqual(sourceUrls('posts',{media_urls:value}),[url,url,url,url]);
});
test('unrelated fields and scalar objects do not grant storage references',()=>{
 for(const row of [{content:{url}},{media_url:{url}},{media_urls:{url}},{image_url:[{url}]}])assert.deepEqual(sourceUrls('posts',row),[]);
});
test('gallery URL parsing still enforces the exact declared object and trusted origin',()=>{
 const object={bucket_id:'post-media',name:'owned.jpg'};
 assert.equal(referencesObject('posts',{media_urls:[{url}]},object,[origin]),true);
 assert.equal(referencesObject('posts',{media_urls:[{url:url.replace(origin,'https://attacker.invalid')}]},object,[origin]),false);
 assert.equal(referencesObject('posts',{media_urls:[{url}]},{...object,name:'other.jpg'},[origin]),false);
});
