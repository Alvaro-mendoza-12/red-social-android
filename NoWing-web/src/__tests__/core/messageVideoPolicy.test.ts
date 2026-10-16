import { assertMessageVideoDuration } from '../../core/messageVideoPolicy'
test('accepts videos up to ten minutes with container rounding tolerance',()=>{expect(()=>assertMessageVideoDuration(600)).not.toThrow();expect(()=>assertMessageVideoDuration(600.25)).not.toThrow()})
test.each([0,NaN,Infinity,-1])('rejects unreadable duration %s',value=>expect(()=>assertMessageVideoDuration(value)).toThrow('duración'))
test('rejects message videos longer than ten minutes',()=>expect(()=>assertMessageVideoDuration(601)).toThrow('10 minutos'))
