import { unzipSync, strFromU8 } from 'fflate'
export type DocumentContent = { type: 'text'; text: string } | { type: 'paragraphs'; paragraphs: string[] } | { type: 'sheet'; sheets: Array<{ name: string; rows: string[][] }> } | { type: 'archive'; entries: string[] } | { type: 'unsupported' }
const LIMIT = 8 * 1024 * 1024
function xml(data: Uint8Array) {
  if (data.length > LIMIT) throw new Error('Este documento es demasiado grande para la vista previa.')
  const parsed = new DOMParser().parseFromString(strFromU8(data), 'application/xml')
  if (parsed.querySelector('parsererror')) throw new Error('El documento no se pudo leer.')
  return parsed
}
export function previewDocument(data: Uint8Array, filename: string): DocumentContent {
  const extension = filename.split(/[?#]/)[0].split('.').at(-1)?.toLowerCase()
  if (['txt','csv','json','xml','md','log','html','htm','css','js','ts','yaml','yml'].includes(extension || '')) return { type: 'text', text: new TextDecoder().decode(data.subarray(0, 1_000_000)) + (data.length > 1_000_000 ? '\n… Vista previa limitada al primer MB.' : '') }
  if (!['docx','xlsx','pptx','odt','ods','odp','zip'].includes(extension || '')) return { type: 'unsupported' }
  let total = 0; const names: string[] = []
  const entries = unzipSync(data, { filter: file => {
    names.push(file.name)
    if (names.length > 2000) throw new Error('El documento contiene demasiados archivos.')
    if (extension === 'zip') return false
    if (!/^(word\/document\.xml|xl\/(sharedStrings|workbook|worksheets\/sheet\d+)\.xml|ppt\/slides\/slide\d+\.xml|content\.xml)$/.test(file.name)) return false
    total += file.originalSize
    if (file.originalSize > LIMIT || total > LIMIT * 2) throw new Error('El documento es demasiado grande para la vista previa.')
    return true
  } })
  if (extension === 'zip') return { type: 'archive', entries: names }
  if (extension === 'xlsx') {
    const strings = entries['xl/sharedStrings.xml'] ? [...xml(entries['xl/sharedStrings.xml']).getElementsByTagName('si')].map(node => node.textContent || '') : []
    const workbook = entries['xl/workbook.xml'] ? [...xml(entries['xl/workbook.xml']).getElementsByTagName('sheet')].map(node => node.getAttribute('name') || 'Hoja') : []
    const sheets = Object.keys(entries).filter(name => name.startsWith('xl/worksheets/')).sort((a,b) => a.localeCompare(b,undefined,{numeric:true})).map((name,index) => ({ name: workbook[index] || `Hoja ${index+1}`, rows: [...xml(entries[name]).getElementsByTagName('row')].slice(0,500).map(row => {
      const cells: string[] = []
      for (const cell of [...row.getElementsByTagName('c')].slice(0,100)) {
        const column = (cell.getAttribute('r')?.match(/^[A-Z]+/)?.[0] || 'A').split('').reduce((n,c) => n*26+c.charCodeAt(0)-64,0)-1
        if (column >= 100) continue
        const raw = cell.getElementsByTagName('v')[0]?.textContent || cell.getElementsByTagName('t')[0]?.textContent || ''
        cells[column] = cell.getAttribute('t') === 's' ? strings[Number(raw)] || '' : raw
      }
      return Array.from({length:cells.length},(_,i) => cells[i] || '')
    }) }))
    return { type: 'sheet', sheets }
  }
  const paragraphs = Object.keys(entries).sort((a,b) => a.localeCompare(b,undefined,{numeric:true})).flatMap(name => {
    const document = xml(entries[name]), nodes = [...document.getElementsByTagName('*')].filter(node => node.localName === 'p')
    return nodes.map(node => [...node.getElementsByTagName('*')].filter(child => child.localName === 't' || child.localName === 'span').map(child => child.textContent || '').join('') || node.textContent || '').filter(Boolean)
  })
  return { type: 'paragraphs', paragraphs: paragraphs.slice(0,10000) }
}
