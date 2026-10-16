import {cropSelection} from '../../core/imageCrop'
const full={x:0,y:0,w:1,h:1}
test('free drawing works in every direction',()=>{expect(cropSelection({x:.7,y:.8,w:0,h:0},'draw',-.5,-.6)).toMatchObject({x:expect.closeTo(.2),y:expect.closeTo(.2),w:.5,h:.6})})
test('moving and all eight handles remain inside the image',()=>{for(const mode of ['move','n','s','e','w','nw','ne','sw','se'])for(const delta of [-4,.02,4]){const s=cropSelection(full,mode,delta,delta);expect(s.x).toBeGreaterThanOrEqual(0);expect(s.y).toBeGreaterThanOrEqual(0);expect(s.x+s.w).toBeLessThanOrEqual(1.00001);expect(s.y+s.h).toBeLessThanOrEqual(1.00001)}})
test('cropping can select a narrow strip smaller than the old six percent minimum',()=>expect(cropSelection(full,'w',.98,0).w).toBeCloseTo(.02))
