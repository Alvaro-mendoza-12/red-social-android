const PLAYBACK_SCOPES=new Set(['content-video','content-profile','content-moment','catalog-music'])
function admitsPlaybackCache(row,admission){return row?.scan_state==='clean'&&admission?.allowed===true&&!admission.viewOnce&&PLAYBACK_SCOPES.has(admission.scope)&&/^(video|audio)\//.test(String(row.content_type||''))}
module.exports={admitsPlaybackCache}
