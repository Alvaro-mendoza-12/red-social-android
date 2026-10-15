'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const {prepareRuntimeStorage,WORK_DIRECTORIES}=require('../scripts/prepare-runtime-storage')
const uid=process.getuid?.()??0,gid=process.getgid?.()??0
function fixture(run){const parent=fs.mkdtempSync(path.join(os.tmpdir(),'novvin-startup-')),root=path.join(parent,'storage');fs.mkdirSync(root);try{return run(root,parent)}finally{fs.rmSync(parent,{recursive:true,force:true})}}
test('startup preserves administrative controls and original files including ctime',()=>fixture(root=>{
 const admin=path.join(root,'.historical-control-fixture');fs.mkdirSync(admin,{mode:0o700});const original=path.join(root,'preserved.jpg');fs.writeFileSync(original,'original');
 const state=()=>[admin,original].map(p=>{const s=fs.lstatSync(p,{bigint:true});return [s.ino,s.uid,s.gid,s.mode,s.ctimeNs,s.mtimeNs]});const before=state();
 const first=prepareRuntimeStorage(root,uid,gid);assert.deepEqual(state(),before);assert.equal(first.administrativePathsVisited,false);assert.equal(first.ownershipChanges,0);
 assert.equal(prepareRuntimeStorage(root,uid,gid).ownershipChanges,0);assert.deepEqual(state(),before);for(const name of WORK_DIRECTORIES)assert(fs.statSync(path.join(root,name)).isDirectory());
}))
test('startup never traverses a symlink under an allowed work subtree',()=>fixture((root,parent)=>{
 const outside=path.join(parent,'outside');fs.mkdirSync(outside);fs.mkdirSync(path.join(root,'.tmp'));fs.symlinkSync(outside,path.join(root,'.tmp','escape'),'dir');
 assert.throws(()=>prepareRuntimeStorage(root,uid,gid),/API_STORAGE_PATH_INVALID/);
}))
test('startup rejects symlinked work root and does not change its target',()=>fixture((root,parent)=>{
 const outside=path.join(parent,'outside');fs.mkdirSync(outside);fs.symlinkSync(outside,path.join(root,'.objects'),'dir');assert.throws(()=>prepareRuntimeStorage(root,uid,gid),/API_STORAGE_PATH_INVALID/);
}))
test('startup rejects ambiguous roots, unsafe owners and excessive traversal',()=>fixture(root=>{
 for(const candidate of ['relative','/',root+'/..'])assert.throws(()=>prepareRuntimeStorage(candidate,uid,gid),/API_STORAGE_ROOT_INVALID/);
 assert.throws(()=>prepareRuntimeStorage(root,-1,gid),/API_STORAGE_ROOT_INVALID/);assert.throws(()=>prepareRuntimeStorage(root,uid,gid,{maxEntries:1}),/API_STORAGE_ENTRY_LIMIT/);
}))
