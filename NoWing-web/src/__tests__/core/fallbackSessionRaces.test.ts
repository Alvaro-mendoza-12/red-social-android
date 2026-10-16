jest.mock('../../core/runtimeBackend',()=>({getRuntimeBackendUrl:()=> 'https://api.example.invalid',refreshRuntimeBackend:jest.fn(),subscribeRuntimeBackend:jest.fn()}))
jest.mock('../../core/media/storageReadSession',()=>({invalidateStorageReadIdentity:jest.fn(),updateStorageReadIdentity:jest.fn()}))

let auth: typeof import('../../core/auth/fallbackAuth')
const token='test.'+btoa(JSON.stringify({exp:4102444800}))+'.synthetic'
const session=(id:string)=>({accessToken:token,user:{id,email:id+'@example.invalid'},expiresIn:3600})
const response=(id:string,status=200)=>({ok:status===200,status,text:async()=>JSON.stringify(status===200?session(id):{error:'INVALID_TOKEN'})}) as Response
const originalHeaders=globalThis.Headers,originalFetch=globalThis.fetch
beforeEach(()=>{
  jest.resetModules();localStorage.clear()
  Object.assign(globalThis,{Headers:class {set(){}},fetch:jest.fn()})
  auth=jest.requireActual('../../core/auth/fallbackAuth')
})
afterAll(()=>Object.assign(globalThis,{Headers:originalHeaders,fetch:originalFetch}))

test('metadata refresh from the same account keeps the in-memory token and avoids a refresh loop',()=>{
  auth.persistFallbackSession(session('owner'));const changed=jest.fn(),stop=auth.onFallbackAuthChanged(changed)
  window.dispatchEvent(new StorageEvent('storage',{key:'conectate:fallback-session',newValue:JSON.stringify({user:session('owner').user,savedAt:'new-time'})}))
  expect(auth.readFallbackSession()?.user.id).toBe('owner');expect(changed).not.toHaveBeenCalled();stop()
})

test.each([null,'invalid',JSON.stringify({user:{id:'other',email:'other@example.invalid'}})])('logout or changed metadata immediately invalidates the old account: %s',newValue=>{
  auth.persistFallbackSession(session('owner'));const changed=jest.fn(),stop=auth.onFallbackAuthChanged(changed)
  if(newValue===null)localStorage.removeItem('conectate:fallback-session');else localStorage.setItem('conectate:fallback-session',newValue)
  window.dispatchEvent(new StorageEvent('storage',{key:'conectate:fallback-session',newValue}))
  expect(auth.readFallbackSession()).toBeNull();expect(changed).toHaveBeenCalledTimes(1);stop()
})

test.each([200,401])('late refresh status %s cannot replace or clear a newly signed-in account',async status=>{
  auth.persistFallbackSession(session('before'));let finish!:(value:Response)=>void
  jest.mocked(fetch).mockImplementationOnce(()=>new Promise(done=>{finish=done}))
  const pending=auth.restoreFallbackSession();auth.persistFallbackSession(session('after'));finish(response('before',status));await pending
  expect(auth.readFallbackSession()?.user.id).toBe('after')
})

test('late successful refresh cannot undo logout',async()=>{
  auth.persistFallbackSession(session('owner'));let finish!:(value:Response)=>void
  jest.mocked(fetch).mockImplementationOnce(()=>new Promise(done=>{finish=done}))
  const pending=auth.restoreFallbackSession();auth.clearFallbackSession();finish(response('owner'));await pending
  expect(auth.readFallbackSession()).toBeNull();expect(auth.hasFallbackSessionHint()).toBe(false)
})
