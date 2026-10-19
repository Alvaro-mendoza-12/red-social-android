export type SpellingWord = { word: string; start: number; end: number }
export function spellingWordAt(text: string, caret = text.length): SpellingWord | null {
  if(text.length>4000)return null
  for(const match of text.matchAll(/\p{L}[\p{L}\p{M}]+/gu)){
    const start=match.index!,end=start+match[0].length
    if(caret<start||caret>end)continue
    const token=[...text.matchAll(/\S+/gu)].find(part=>part.index!<=start&&part.index!+part[0].length>=end)?.[0]||match[0]
    if(match[0].length>32||/[@/#:\d_]/.test(token)||/https?:|www\./i.test(token)||/\.(png|jpe?g|gif|webp|pdf|docx?|xlsx?|zip)$/i.test(token))return null
    return {word:match[0],start,end}
  }
  return null
}
export function replaceSpellingWord(text:string,issue:SpellingWord,suggestion:string){
  if(text.slice(issue.start,issue.end)!==issue.word)return null
  return {text:text.slice(0,issue.start)+suggestion+text.slice(issue.end),caret:issue.start+suggestion.length}
}
