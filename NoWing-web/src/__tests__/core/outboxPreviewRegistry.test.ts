import {registerOutboxPreview,isOwnedOutboxPreview,clearOutboxPreviewRegistry} from '../../core/media/outboxPreviewRegistry'
import {type StorageReadIdentity} from '../../core/media/storageReadSession'
const owner={accountId:'a',backend:'fallback',generation:1} as StorageReadIdentity,other={accountId:'b',backend:'fallback',generation:2} as StorageReadIdentity
beforeEach(clearOutboxPreviewRegistry)
test('local previews are authorized only for their active account identity',()=>{registerOutboxPreview('blob:http://localhost/owned',owner);expect(isOwnedOutboxPreview('blob:http://localhost/owned',owner)).toBe(true);expect(isOwnedOutboxPreview('blob:http://localhost/owned',other)).toBe(false);expect(isOwnedOutboxPreview('blob:http://localhost/unknown',owner)).toBe(false)})
test('a new session generation cannot reuse a previous blob even for the same account',()=>{registerOutboxPreview('blob:http://localhost/owned',owner);expect(isOwnedOutboxPreview('blob:http://localhost/owned',{...owner,generation:3})).toBe(false);clearOutboxPreviewRegistry();expect(isOwnedOutboxPreview('blob:http://localhost/owned',owner)).toBe(false)})
