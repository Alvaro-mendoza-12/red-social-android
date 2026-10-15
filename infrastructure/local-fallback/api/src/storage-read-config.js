'use strict';
const {trustedOrigins}=require('./storage-read-access');
function storageReadConfiguration(env=process.env) {
  const raw=String(env.STORAGE_READ_ENFORCE||'false').trim().toLowerCase();
  if(!['true','false'].includes(raw))throw new Error('STORAGE_READ_ENFORCE must be true or false');
  const inputs=String(env.STORAGE_READ_ORIGINS||'').split(',').map(value=>value.trim()).filter(Boolean);
  const enforced=raw==='true';
  const origins=inputs.length?trustedOrigins(inputs):[];
  if(enforced&&!origins.length)throw new Error('STORAGE_READ_ORIGINS_REQUIRED_FOR_ENFORCEMENT');
  // The existing uploader otherwise derives new reference URLs from Host.
  // Enforcement requires a configured canonical base so a client cannot
  // choose the origin of newly persisted object references.
  if(enforced) {
    let base; try { base=new URL(String(env.PUBLIC_BASE_URL||'')); } catch { throw new Error('STORAGE_READ_PUBLIC_BASE_URL_REQUIRED'); }
    if(base.protocol!=='https:'||base.username||base.password||base.pathname!=='/'||base.search||base.hash||!origins.includes(base.origin))throw new Error('STORAGE_READ_PUBLIC_BASE_URL_UNTRUSTED');
  }
  return Object.freeze({enforced,origins:Object.freeze(origins)});
}
module.exports={storageReadConfiguration};
