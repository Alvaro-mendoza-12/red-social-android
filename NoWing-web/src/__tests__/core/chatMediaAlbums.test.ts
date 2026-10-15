import { chatMediaAlbums, type AlbumMessage } from '../../core/chatMediaAlbums'
const photo = (id: string, extra: Partial<AlbumMessage> = {}): AlbumMessage => ({ id, senderId: 'alice', createdAt: '2026-10-15T12:00:00Z', contentType: 'image', mediaUrl: 'https://test.invalid/'+id+'.png', content: '', replyToId: null, deletedForAll: false, deletedBy: [], ...extra })
test('five photos form one album in their original order', () => {
  const rows = Array.from({ length: 5 }, (_, i) => photo(String(i)))
  const albums = chatMediaAlbums(rows, 'viewer')
  expect(albums.get('4')).toEqual({ leader: '0', items: rows })
})
test.each([
  { contentType: 'sticker' }, { contentType: 'text' }, { senderId: 'bob' },
  { deletedForAll: true }, { deletedBy: ['viewer'] }, { replyToId: 'other' },
  { createdAt: '2026-10-15T12:02:00Z' }, { createdAt: 'invalid' },
])('does not group across a distinct or private boundary %j', extra => {
  expect(chatMediaAlbums([photo('1'), photo('2', extra)], 'viewer').size).toBe(0)
})
test('a single photo keeps its individual controls', () => expect(chatMediaAlbums([photo('1')], 'viewer').size).toBe(0))
