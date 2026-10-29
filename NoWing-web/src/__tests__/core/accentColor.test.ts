import {accentInk,safeAccent} from '../../core/accentColor'
test('custom colors reject CSS input and retain valid hex colors',()=>{
 expect(safeAccent('url(https://invalid)')).toBe('#8acfff')
 expect(safeAccent('#aabbcc')).toBe('#aabbcc')
})
test('near white and near black choices adapt to readable theme inks',()=>{
 expect(accentInk('#ffffff',false)).not.toBe('#ffffff')
 expect(accentInk('#000000',true)).not.toBe('#000000')
 expect(accentInk('#000000',false)).toBe('#000000')
 expect(accentInk('#ffffff',true)).toBe('#ffffff')
})
