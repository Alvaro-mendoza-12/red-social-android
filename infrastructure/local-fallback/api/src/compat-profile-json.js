// Imported CSV cells must have the same JSON types as current profiles.
// Normalize the response only; never rewrite original profiles or privacy choices.
const lists = ['interests','hobbies','linked_games','profile_section_order'];
const maps = ['game_logo_urls','profile_section_visibility','social_links'];
const booleans = {
  avatar_video_muted:true,is_private:false,favorites_public:false,
  profile_view_history_enabled:true,is_admin:false,voice_access_granted:false,
  is_verified:false,is_suspended:false,is_online:false,privacy_policy_accepted:false,
};
const numbers = {numeric_id:null,avatar_duration_ms:null,followers_count:0,
  following_count:0,post_count:0,suspension_count:0,balance:0};
const strings = {id:'',email:'',gender:'unspecified',profile_theme:'conectate',
  profile_effect:'none',profile_frame:'',break_mode:'off',profile_visibility:'everyone',
  last_seen_visibility:'everyone',online_status_visibility:'everyone',created_at:'',updated_at:''};
function parsed(value) {
  if(typeof value!=='string')return value;
  const text=value.trim();
  if(!text||text==='null')return null;
  try{return JSON.parse(text);}catch{return value;}
}
function normalizeCompatProfileJson(row) {
  const result={...row};
  for(const key of lists)if(key in result){
    const value=parsed(result[key]);result[key]=Array.isArray(value)?value.filter(v=>typeof v==='string'):[];
  }
  for(const key of maps)if(key in result){
    const value=parsed(result[key]);result[key]=value&&typeof value==='object'&&!Array.isArray(value)
      ?Object.fromEntries(Object.entries(value).filter(([,v])=>typeof v==='string')):{};
  }
  for(const [key,fallback] of Object.entries(booleans))if(key in result){
    const value=parsed(result[key]);
    // A malformed explicit visit preference must never silently enable tracking.
    result[key]=value===true||value===1?true:value===false||value===0?false:
      key==='profile_view_history_enabled'&&value!=null?false:fallback;
  }
  for(const [key,fallback] of Object.entries(numbers))if(key in result){
    const value=parsed(result[key]);const n=(typeof value==='number'||typeof value==='string')?Number(value):NaN;
    result[key]=Number.isFinite(n)&&(key==='balance'||Number.isSafeInteger(n))?n:fallback;
  }
  for(const [key,fallback] of Object.entries(strings))if(key in result){
    const value=result[key];result[key]=value instanceof Date&&Number.isFinite(value.getTime())?value.toISOString():typeof value==='string'&&value.trim()&&value.trim()!=='null'?value:fallback;
  }
  if('badges'in result){
    const value=parsed(result.badges);
    result.badges=Array.isArray(value)?value.filter(b=>b&&typeof b==='object'&&
      ['id','name','icon','color','type'].every(k=>typeof b[k]==='string')).map(b=>({
        ...b,description:typeof b.description==='string'?b.description:null,
        threshold:Number.isSafeInteger(Number(b.threshold))&&b.threshold!=null?Number(b.threshold):null,
        is_admin_badge:parsed(b.is_admin_badge)===true,
      })):null;
  }
  delete result.__source_file;delete result.__imported_at;
  return result;
}
module.exports={normalizeCompatProfileJson};
