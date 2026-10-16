import { onStorageReadInvalidated, type StorageReadIdentity } from './storageReadSession'
const owners=new Map<string,StorageReadIdentity>()
export function registerOutboxPreview(url:string,identity:StorageReadIdentity){if(url.startsWith('blob:')&&identity.accountId)owners.set(url,identity)}
export function isOwnedOutboxPreview(url:string,identity:StorageReadIdentity){return Boolean(identity.accountId&&owners.get(url)===identity)}
export function clearOutboxPreviewRegistry(){owners.clear()}
onStorageReadInvalidated(clearOutboxPreviewRegistry)
