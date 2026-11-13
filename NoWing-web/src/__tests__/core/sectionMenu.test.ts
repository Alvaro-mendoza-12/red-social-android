import {sectionMenu} from '../../core/sectionMenu'
test('voice room actions belong to the current room and contain no unrelated navigation',()=>{
 const menu=sectionMenu('/app/voice/room-qa')
 expect(menu.map(x=>x.voiceAction)).toEqual(['settings','refresh','copy','leave'])
 expect(menu.some(x=>x.to)).toBe(false)
})
test('context menus avoid personal settings on another profile and do not link to themselves',()=>{
 expect(sectionMenu('/app/profile/otra-persona').some(item=>item.to==='/app/settings')).toBe(false)
 for(const path of ['/app/voice','/app/live','/app/help']) expect(sectionMenu(path).some(item=>item.to===path)).toBe(false)
 expect(sectionMenu('/app/social').some(item=>item.to?.includes('advertising'))).toBe(false)
})
test('the directory and other sections have their own options',()=>{
 expect(sectionMenu('/app/voice').every(x=>/sala/i.test(x.label))).toBe(true)
 expect(sectionMenu('/app/profile').some(x=>x.to==='/app/settings')).toBe(true)
 expect(sectionMenu('/app/live').some(x=>x.to?.includes('voice'))).toBe(false)
})
