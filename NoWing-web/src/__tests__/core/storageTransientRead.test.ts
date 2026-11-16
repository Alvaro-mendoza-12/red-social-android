/** @jest-environment node */
import { ReadableStream } from 'node:stream/web'
import { readBoundedStorageBlob, readStorageDownloadBlob } from '../../core/media/boundedStorageRead'
import { getStorageReadIdentity, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

const base='https://backend.test', url=base+'/storage/v1/object/public/stickers/fixture.png'
const fetcher=jest.fn()
function response(status=200, headers: Record<string,string>={}) {
  return {status,ok:status===200,redirected:false,type:'basic',url,headers:new Headers({'Content-Type':'image/png',...headers}),
    body:new ReadableStream<Uint8Array>({start(c){if(status===200)c.enqueue(new Uint8Array([137,80,78,71]));c.close()}})} as Response
}
function input(controller=new AbortController()) {
  return {url,backendBase:base,identity:getStorageReadIdentity(),maxBytes:1024,signal:controller.signal,fetcher,getAccessToken:async()=> 'fixture.test.signature'}
}
function settled(job:Promise<Blob>) {return job.then(value=>({value,error:null as Error|null}),error=>({value:null,error:error as Error}))}
beforeEach(()=>{jest.useFakeTimers();jest.setSystemTime(new Date('2026-11-16T00:00:00Z'));fetcher.mockReset();updateStorageReadIdentity('fixture-a','fallback')})
afterEach(()=>{invalidateStorageReadIdentity();jest.useRealTimers()})

test('a temporary 503 recovers with one bounded origin retry',async()=>{
 fetcher.mockResolvedValueOnce(response(503)).mockResolvedValueOnce(response())
 const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(500)
 const result=await job;expect(result.error).toBeNull();expect(result.value?.size).toBe(4);expect(fetcher).toHaveBeenCalledTimes(2)
})
test('a transport failure can recover before any response bytes were received',async()=>{
 fetcher.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(response())
 const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(500)
 expect((await job).error).toBeNull();expect(fetcher).toHaveBeenCalledTimes(2)
})
test.each(['2','Mon, 16 Nov 2026 00:00:02 GMT'])('Retry-After %s is honored before the sole retry',async(header)=>{
 fetcher.mockResolvedValueOnce(response(503,{'Retry-After':header})).mockResolvedValueOnce(response())
 const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(1999);expect(fetcher).toHaveBeenCalledTimes(1)
 await jest.advanceTimersByTimeAsync(1);expect((await job).error).toBeNull();expect(fetcher).toHaveBeenCalledTimes(2)
})
test('persistent server failure stops after the additional attempt',async()=>{
 fetcher.mockImplementation(async()=>response(503));const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_503');expect(fetcher).toHaveBeenCalledTimes(2)
})
test.each([401,403,404])('permission or missing-file status %s is never retried',async(status)=>{
 fetcher.mockImplementation(async()=>response(status));const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_'+status);expect(fetcher).toHaveBeenCalledTimes(1)
})
test('rate limiting is surfaced without an extra request',async()=>{
 fetcher.mockImplementation(async()=>response(429,{'Retry-After':'20'}));const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_429');expect(fetcher).toHaveBeenCalledTimes(1)
})
test.each([url+'?lease=one-use',base+'/storage/v1/object/sign/stickers/fixture.png?token=capability'])('capability %s is not replayed',async(source)=>{
 fetcher.mockImplementation(async()=>response(503));const job=settled(readBoundedStorageBlob({...input(),url:source}));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_503');expect(fetcher).toHaveBeenCalledTimes(1)
})
test('explicit downloads are not restarted automatically',async()=>{
 fetcher.mockImplementation(async()=>response(503));const job=settled(readStorageDownloadBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_503');expect(fetcher).toHaveBeenCalledTimes(1)
})
test('cancellation during backoff prevents another request',async()=>{
 fetcher.mockImplementation(async()=>response(503));const controller=new AbortController(),job=settled(readBoundedStorageBlob(input(controller)))
 await jest.advanceTimersByTimeAsync(0);controller.abort();await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error).not.toBeNull();expect(fetcher).toHaveBeenCalledTimes(1)
})
test('changing accounts during backoff prevents replay with the old identity',async()=>{
 fetcher.mockImplementation(async()=>response(503));const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(0)
 updateStorageReadIdentity('fixture-b','fallback');await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error).not.toBeNull();expect(fetcher).toHaveBeenCalledTimes(1)
})
test('a longer server delay is returned to the UI instead of being shortened',async()=>{
 fetcher.mockImplementation(async()=>response(503,{'Retry-After':'20'}));const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error?.message).toBe('MEDIA_READ_503');expect(fetcher).toHaveBeenCalledTimes(1)
})

test('the retry does not restart the original thirty-second deadline',async()=>{
 fetcher.mockImplementation(()=>new Promise(resolve=>setTimeout(()=>resolve(response(503)),29750)))
 const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(30001)
 expect((await job).error?.name).toBe('AbortError');expect(fetcher).toHaveBeenCalledTimes(1)
})
test('a failure after response bytes started does not replay the object',async()=>{
 const body={getReader:()=>({read:jest.fn().mockResolvedValueOnce({done:false,value:new Uint8Array([1])}).mockRejectedValueOnce(new TypeError('body lost')),cancel:async()=>{},releaseLock:()=>{}})}
 fetcher.mockResolvedValue({...response(),body});const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(1000)
 expect((await job).error?.message).toBe('body lost');expect(fetcher).toHaveBeenCalledTimes(1)
})
test('a signed capability under a configured base path is not replayed',async()=>{
 fetcher.mockImplementation(async()=>response(503));const job=settled(readBoundedStorageBlob({...input(),backendBase:base+'/api',url:base+'/api/storage/v1/object/sign/stickers/fixture.png'}))
 await jest.advanceTimersByTimeAsync(5000);expect((await job).error?.message).toBe('MEDIA_READ_503');expect(fetcher).toHaveBeenCalledTimes(1)
})
test('a redirected response cannot trigger another request',async()=>{
 fetcher.mockResolvedValue({...response(503),redirected:true});const job=settled(readBoundedStorageBlob(input()));await jest.advanceTimersByTimeAsync(5000)
 expect((await job).error).not.toBeNull();expect(fetcher).toHaveBeenCalledTimes(1)
})
