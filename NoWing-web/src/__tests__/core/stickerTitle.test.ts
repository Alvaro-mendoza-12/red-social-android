import {stickerTitle} from '../../core/stickerTitle'
test.each(['string','String','undefined','null','[object Object]',' '])('removes legacy placeholder %s',value=>expect(stickerTitle(value)).toBe('Sticker'))
test('preserves a user sticker title',()=>expect(stickerTitle(' Hola 👋 ')).toBe('Hola 👋'))
