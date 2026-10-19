import { commentThreads } from '../../core/commentThreads'
test('groups nested replies under their root, retaining chronological order',()=>{
 const result=commentThreads([{id:'root'},{id:'other'},{id:'child',parent_id:'root'},{id:'grandchild',parent_comment_id:'child'}])
 expect(result.map(group=>group.root.id)).toEqual(['root','other']);expect(result[0].replies.map(row=>row.id)).toEqual(['child','grandchild'])
})
test('orphans and cyclic imported records remain visible without infinite recursion',()=>{
 const items=[{id:'orphan',parent_id:'deleted'},{id:'a',parent_id:'b'},{id:'b',parent_id:'a'}]
 const result=commentThreads(items);expect(result.flatMap(group=>[group.root,...group.replies]).map(row=>row.id).sort()).toEqual(['a','b','orphan'])
})
