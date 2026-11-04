'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { registerAlgorithmRecommendationRoutes, decodeOpaqueCursor } = require('../src/algorithm-recommendation-routes')
const secret = 'qa-only-engagement-cursor-key-at-least-thirty-two-characters'
function harness(hydrateItems) {
  let handler
  const inputs = []
  registerAlgorithmRecommendationRoutes({get(_path,...chain){handler=chain.at(-1)}}, {
    pool:{}, authRequired(_req,_res,next){next()},
    getCurrentUser: async()=>({id:'viewer'}), userIdentity:()=>({localUserId:'viewer',ids:new Set(['viewer'])}),
    algorithmFeatureFlags:()=>({core:true,explanations:false}),
    findOwnedProfile:async()=>({id:'profile',revision:1,is_enabled:true,mode:'manual'}),
    loadAlgorithmConfiguration:async()=>({preferences:[],temporaryPreferences:[],feedMixes:[],dataPermissions:{}}),
    loadCandidates:async()=>({snapshotId:'stable-catalog',candidates:['one','two','three'].map(id=>({id,creatorId:'creator',item:{id,user_id:'creator'}}))}),
    rankAlgorithmCandidates:options=>{inputs.push(options.candidates);return {ranked:options.candidates.map((item,sourceIndex)=>({id:item.id,sourceIndex}))}},
    hydrateItems, cursorSecret:secret, now:()=>1_800_000_000_000,
  })
  return { inputs, async request(query={}) {
    const res={statusCode:200,status(value){this.statusCode=value;return this},setHeader(){},json(body){this.body=body;return this}}
    await handler({query:{surface:'social',limit:'2',...query}},res,error=>{throw error})
    return res
  }}
}
test('engagement is hydrated only after selecting a page and cannot change ranking or cursor continuation',async()=>{
  let total=4
  const calls=[]
  const f=harness(async({items,surface,owner})=>{
    calls.push({ids:items.map(item=>item.id),surface,owner:owner.userId})
    return items.map(item=>({...item,like_count:total,comment_count:2,liked_by_me:true,my_reaction:'FIRE',reaction_counts:{FIRE:total}}))
  })
  const first=await f.request()
  assert.equal(first.statusCode,200); assert.deepEqual(first.body.items.map(item=>item.like_count),[4,4])
  assert.deepEqual(calls[0],{ids:['one','two'],surface:'social',owner:'viewer'})
  assert.ok(f.inputs[0].every(item=>item.item.like_count===undefined))
  const cursor=decodeOpaqueCursor(first.body.page.nextCursor,secret,1_800_000_000_000)
  assert.equal(cursor.snapshotId,'stable-catalog')
  total=7
  const next=await f.request({cursor:first.body.page.nextCursor})
  assert.deepEqual(next.body.items.map(item=>item.id),['three'])
  assert.equal(next.body.items[0].like_count,7); assert.equal(next.body.page.hasMore,false)
})
test('unavailable metrics fail explicitly instead of publishing fabricated zeros',async()=>{
  const res=await harness(async()=>{throw Object.assign(Error('private details'),{code:'DB_OFFLINE'})}).request()
  assert.equal(res.statusCode,503); assert.deepEqual(res.body,{error:'RECOMMENDATION_DETAILS_UNAVAILABLE'})
})
test('response hydration cannot substitute or reorder authorized posts, including by mutation',async()=>{
  for (const hydrate of [async({items})=>items.toReversed(),async({items})=>{items[0].id='foreign';return items},async()=>[]]) {
    const res=await harness(hydrate).request()
    assert.equal(res.statusCode,502); assert.equal(res.body.error,'RECOMMENDATION_DETAILS_INVALID')
  }
})
