import {isChunkLoadError,recoverChunkLoad} from '../../core/chunkRecovery'
beforeEach(()=>sessionStorage.clear())
test('a stale module refreshes once and cannot cause a reload loop',()=>{
 const reload=jest.fn(),error=new TypeError('Failed to fetch dynamically imported module: https://example.test/js/Old.js')
 expect(recoverChunkLoad(error,{now:10000,reload,online:true,blocked:false})).toBe(true)
 expect(recoverChunkLoad(error,{now:12000,reload,online:true,blocked:false})).toBe(false);expect(reload).toHaveBeenCalledTimes(1)
})
test('offline users, active media, and ordinary application errors never trigger automatic reload',()=>{
 const reload=jest.fn(),error=new Error('Importing a module script failed')
 expect(isChunkLoadError(error)).toBe(true)
 expect(recoverChunkLoad(error,{reload,online:false})).toBe(false)
 expect(recoverChunkLoad(error,{reload,online:true,blocked:true})).toBe(false)
 expect(recoverChunkLoad(new Error('Unexpected data'),{reload,online:true,blocked:false})).toBe(false)
 expect(reload).not.toHaveBeenCalled()
})
