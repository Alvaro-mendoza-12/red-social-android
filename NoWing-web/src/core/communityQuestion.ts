export function questionReference(content:string):{questionId:string;prompt:string;maxLength:number}|null {
  try {const value=JSON.parse(content);return value && typeof value.prompt==='string'&&value.prompt.trim().length>0&&value.prompt.length<=300&&/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(value.questionId)&&Number.isInteger(value.maxLength)&&value.maxLength>=20&&value.maxLength<=40?value:null}catch{return null}
}
