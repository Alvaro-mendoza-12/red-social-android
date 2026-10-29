import {sectionMenu} from '../../core/sectionMenu'
test('voice room actions belong to the current room and contain no unrelated navigation',()=>{
 const menu=sectionMenu('/app/voice/room-qa')
 expect(menu.map(x=>x.voiceAction)).toEqual(['settings','refresh','copy','leave'])
 expect(menu.some(x=>x.to)).toBe(false)
})
test('the directory and other sections have their own options',()=>{
 expect(sectionMenu('/app/voice').every(x=>/sala/i.test(x.label))).toBe(true)
 expect(sectionMenu('/app/profile').some(x=>x.to==='/app/settings')).toBe(true)
 expect(sectionMenu('/app/live').some(x=>x.to?.includes('voice'))).toBe(false)
})
