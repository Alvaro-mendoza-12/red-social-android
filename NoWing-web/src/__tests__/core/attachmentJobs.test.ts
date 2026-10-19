import { act, renderHook, waitFor } from '@testing-library/react'
import { retainAttachmentOwner, useAttachmentDraft, useAttachmentPreparation } from '../../core/attachmentJobs'

beforeEach(() => retainAttachmentOwner(''))

test('cancelled preparation cannot restore attachments after the preview was closed',async()=>{
 let finish!:(files:File[])=>void
 const pending=new Promise<File[]>(resolve=>{finish=resolve}),first=mount()
 act(()=>first.result.current.preparation.start(()=>pending))
 act(()=>first.result.current.preparation.cancel())
 await act(async()=>{finish([new File(['data'],'cancelled.png')]);await pending})
 expect(first.result.current.draft).toHaveLength(0)
 expect(first.result.current.preparation.preparing).toBe(false)
})

function mount(room = 'first') {
  return renderHook(() => {
    const [draft, setDraft] = useAttachmentDraft<{ previewUrl: string; file: File }>('viewer', 'chat', room)
    const preparation = useAttachmentPreparation('viewer', 'chat', room, files => setDraft(files.map(file => ({ file, previewUrl: '' }))), jest.fn())
    return { draft, preparation }
  })
}

test('finishing compression after navigation restores files only to their original chat', async () => {
  let finish!: (files: File[]) => void
  const pending = new Promise<File[]>(resolve => { finish = resolve })
  const first = mount()
  act(() => first.result.current.preparation.start(() => pending))
  expect(first.result.current.preparation.preparing).toBe(true)
  first.unmount()
  const other = mount('other')
  const file = new File(['zip'], 'Folder.zip')
  await act(async () => { finish([file]); await pending })
  expect(other.result.current.draft).toHaveLength(0)
  const resumed = mount()
  await waitFor(() => expect(resumed.result.current.draft[0]?.file).toBe(file))
  resumed.unmount()
  expect(mount().result.current.draft[0].file).toBe(file)
})

test('an account switch discards pending work instead of attaching it to the new account', async () => {
  let finish!: (files: File[]) => void
  const pending = new Promise<File[]>(resolve => { finish = resolve })
  const first = mount()
  act(() => first.result.current.preparation.start(() => pending))
  first.unmount()
  act(() => retainAttachmentOwner('another-account'))
  await act(async () => { finish([new File(['zip'], 'private.zip')]); await pending })
  expect(mount().result.current.draft).toHaveLength(0)
})
