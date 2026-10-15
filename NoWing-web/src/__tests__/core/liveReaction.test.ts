import { liveReactionEmoji } from '../../core/liveReaction'
test('the actual heart-kind signaling payload renders a heart instead of text',()=>{expect(liveReactionEmoji({kind:'heart'})).toBe('❤️')})
test('rejects arbitrary server labels while preserving supported reactions',()=>{expect(liveReactionEmoji({emoji:'Me gusta'})).toBe('❤️');expect(liveReactionEmoji({emoji:'🔥'})).toBe('🔥')})
