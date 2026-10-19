import { act, renderHook } from '@testing-library/react'
import { COMMENT_AUDIO_MAX_BYTES, COMMENT_AUDIO_MAX_MS, useCommentAudioRecorder } from '../../core/useCommentAudioRecorder'

class FakeRecorder {
  static isTypeSupported = () => true
  static instances: FakeRecorder[] = []
  state = 'inactive'
  mimeType = 'audio/webm'
  onstop: (() => void) | null = null
  onerror: (() => void) | null = null
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  stop = jest.fn(() => { if(this.state==='inactive')throw new Error('InvalidStateError');this.state='inactive' })
  start = jest.fn(() => { this.state='recording' })
  constructor() { FakeRecorder.instances.push(this) }
  data(bytes=3) { this.ondataavailable?.({ data: new Blob([new Uint8Array(bytes)], { type: this.mimeType }) }) }
}
const track = () => ({ stop: jest.fn() })
const media = (value=track()) => ({ value, getTracks: () => [value] })
const getUserMedia = jest.fn()
let files: jest.Mock, errors: jest.Mock
const mount = () => renderHook(({ owner }) => useCommentAudioRecorder(owner, files, errors), { initialProps: { owner: 'first' } })

beforeEach(() => {
  jest.useFakeTimers(); FakeRecorder.instances=[]; files=jest.fn();errors=jest.fn();getUserMedia.mockReset()
  Object.defineProperty(navigator,'mediaDevices',{ configurable:true,value:{getUserMedia} })
  Object.defineProperty(globalThis,'MediaRecorder',{ configurable:true,value:FakeRecorder })
})
afterEach(()=>{jest.useRealTimers();Object.defineProperty(document,'hidden',{configurable:true,value:false})})

test('pending microphone permission can be cancelled and a late grant releases its tracks',async()=>{
 let grant!:(value:unknown)=>void
 const pending=new Promise(resolve=>{grant=resolve}),source=media();getUserMedia.mockReturnValue(pending)
 const hook=mount();let request!:Promise<void>
 act(()=>{request=hook.result.current.start();void hook.result.current.start()})
 expect(hook.result.current.phase).toBe('requesting');expect(getUserMedia).toHaveBeenCalledTimes(1)
 act(()=>hook.result.current.cancel())
 await act(async()=>{grant(source);await request})
 expect(source.value.stop).toHaveBeenCalled();expect(FakeRecorder.instances).toHaveLength(0)
 expect(hook.result.current.phase).toBe('idle');expect(files).not.toHaveBeenCalled()
})

test('repeated stop before the queued stop event does not throw or duplicate the local preview',async()=>{
 const source=media();getUserMedia.mockResolvedValue(source);const hook=mount()
 await act(async()=>hook.result.current.start());const recorder=FakeRecorder.instances[0]
 act(()=>{hook.result.current.finish();hook.result.current.finish()})
 expect(recorder.stop).toHaveBeenCalledTimes(1);expect(hook.result.current.phase).toBe('finishing')
 act(()=>{recorder.data();recorder.onstop?.()})
 expect(files).toHaveBeenCalledTimes(1);expect(files.mock.calls[0][0].type).toBe('audio/webm')
 expect(hook.result.current.phase).toBe('idle');expect(source.value.stop).toHaveBeenCalled();expect(errors).not.toHaveBeenCalled()
})

test('events queued by a failed recorder cannot stop a later recording',async()=>{
 const old=media(),next=media();getUserMedia.mockResolvedValueOnce(old).mockResolvedValueOnce(next)
 const hook=mount();await act(async()=>hook.result.current.start());const first=FakeRecorder.instances[0],staleStop=first.onstop
 act(()=>first.onerror?.());expect(errors).toHaveBeenCalledTimes(1)
 await act(async()=>hook.result.current.start())
 act(()=>staleStop?.())
 expect(next.value.stop).not.toHaveBeenCalled();expect(hook.result.current.phase).toBe('recording')
 expect(files).not.toHaveBeenCalled()
})

test('switching account releases the microphone and cannot deliver the old account recording',async()=>{
 const source=media();getUserMedia.mockResolvedValue(source);const hook=mount()
 await act(async()=>hook.result.current.start());const recorder=FakeRecorder.instances[0],staleStop=recorder.onstop
 hook.rerender({owner:'second'})
 act(()=>staleStop?.())
 expect(source.value.stop).toHaveBeenCalled();expect(hook.result.current.phase).toBe('idle');expect(files).not.toHaveBeenCalled()
})

test('leaving the tab stops recording and retains only a local draft',async()=>{
 const source=media();getUserMedia.mockResolvedValue(source);const hook=mount()
 await act(async()=>hook.result.current.start());const recorder=FakeRecorder.instances[0]
 act(()=>{jest.advanceTimersByTime(2000)})
 expect(hook.result.current.seconds).toBe(2)
 Object.defineProperty(document,'hidden',{configurable:true,value:true})
 act(()=>document.dispatchEvent(new Event('visibilitychange')))
 expect(recorder.stop).toHaveBeenCalledTimes(1);expect(source.value.stop).toHaveBeenCalled()
 act(()=>{recorder.data();recorder.onstop?.()})
 expect(files).toHaveBeenCalledTimes(1);expect(hook.result.current.phase).toBe('idle')
})

test('size limit rejects the recording and the duration limit stops it without sending',async()=>{
 getUserMedia.mockResolvedValue(media());const hook=mount()
 await act(async()=>hook.result.current.start())
 act(()=>FakeRecorder.instances[0].data(COMMENT_AUDIO_MAX_BYTES+1))
 expect(errors).toHaveBeenCalledWith(expect.stringContaining('10 MB'));expect(files).not.toHaveBeenCalled()
 await act(async()=>hook.result.current.start());const next=FakeRecorder.instances[1]
 act(()=>jest.advanceTimersByTime(COMMENT_AUDIO_MAX_MS))
 expect(next.stop).toHaveBeenCalledTimes(1);expect(hook.result.current.phase).toBe('finishing')
})

test('denied permissions recover and unmount releases an active microphone',async()=>{
 getUserMedia.mockRejectedValueOnce(new DOMException('Denied','NotAllowedError')).mockResolvedValueOnce(media())
 const hook=mount();await act(async()=>hook.result.current.start());expect(hook.result.current.phase).toBe('idle');expect(errors).toHaveBeenCalledTimes(1)
 await act(async()=>hook.result.current.start());const recorder=FakeRecorder.instances[0]
 hook.unmount();expect(recorder.stop).toHaveBeenCalledTimes(1);expect(recorder.onstop).toBeNull()
})
