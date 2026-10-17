import { attachmentPreparationError, compressFiles, droppedAttachments, prepareAttachmentFiles, folderArchiveParts, MAX_MESSAGE_UPLOAD_BYTES } from '../../core/attachmentPreparation'
import { unzipSync, strFromU8 } from 'fflate'
jest.mock('../../core/messageVideoPolicy',()=>({validateMessageVideo:jest.fn(async()=>{})}))
const file=(name:string,text:string)=>new File([new TextEncoder().encode(text)],name)
beforeAll(()=>{Object.defineProperty(Blob.prototype,'arrayBuffer',{configurable:true,value:function(){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=reject;reader.readAsArrayBuffer(this)})}})})
const bytes=(blob:Blob)=>new Promise<Uint8Array>((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(new Uint8Array(reader.result as ArrayBuffer));reader.onerror=reject;reader.readAsArrayBuffer(blob)})
test('retains folder paths, file contents and empty directories',async()=>{const packed=await compressFiles([{path:'Fotos/',file:null},{path:'Fotos/vacía/',file:null},{path:'Fotos/nota.txt',file:file('nota.txt','Hola Novvin')}],'Fotos');const result=unzipSync(await bytes(packed));expect(packed.name).toBe('Fotos.zip');expect(Object.keys(result)).toEqual(['Fotos/','Fotos/vacía/','Fotos/nota.txt']);expect(strFromU8(result['Fotos/nota.txt'])).toBe('Hola Novvin')})
test.each(['../private.txt','carpeta/../../private.txt','/private.txt','carpeta/./private.txt','carpeta/evil\\file.txt'])('rejects unsafe archive path %s',async path=>{await expect(compressFiles([{path,file:file('a','a')}],'a')).rejects.toThrow()})
test('rejects duplicate archive paths',async()=>{await expect(compressFiles([{path:'a',file:file('a','1')},{path:'a',file:file('a','2')}],'a')).rejects.toThrow('mismo nombre')})
test('enforces the source size and file count before allocating',async()=>{await expect(compressFiles([{path:'a',file:{...file('a','1'),size:501*1024*1024}}],'a')).rejects.toThrow('500 MB');await expect(compressFiles(Array.from({length:5001},(_,i)=>({path:String(i),file:null})),'a')).rejects.toThrow('5000')})
test('preserves small or already compressed attachments',async()=>{const source=file('test.txt','hello');expect(await prepareAttachmentFiles([source])).toEqual([source])})
test('snapshots directory entries before the drop event becomes unavailable',async()=>{let calls=0;const entry={name:'Grupo',isDirectory:true,isFile:false,createReader:()=>({readEntries:(ok:(entries:unknown[])=>void)=>ok(calls++?[]:[{name:'nota.txt',isFile:true,isDirectory:false,file:(ok:(value:File)=>void)=>ok(file('nota.txt','hola'))}])})};const transfer={items:[{kind:'file',webkitGetAsEntry:()=>entry}],files:[]} as unknown as DataTransfer;const pending=droppedAttachments(transfer);(transfer as unknown as {items:unknown[]}).items=[];const packed=await pending;const result=unzipSync(await bytes(packed[0]));expect(strFromU8(result['Grupo/nota.txt'])).toBe('hola')})

test('technical worker errors never reach the attachment UI',()=>{expect(attachmentPreparationError(new Error('Worker failed at https://internal.example.invalid'))).toBe('No se pudieron preparar los archivos. Inténtalo de nuevo.')})

test('normalizes Windows ZIP MIME without altering the archive bytes',async()=>{const source=new File([new Uint8Array([0x50,0x4b,3,4,5])],'Folder.zip',{type:'application/x-zip-compressed',lastModified:123});const [prepared]=await prepareAttachmentFiles([source]);expect(prepared.type).toBe('application/zip');expect(prepared.name).toBe(source.name);expect(prepared.lastModified).toBe(123);expect(await bytes(prepared)).toEqual(await bytes(source))})

test.each(['documento.doc','datos.json','apuntes.md','dibujo.dwg'])('packages unsupported document %s without changing its contents',async name=>{const source=file(name,'Contenido original'),[prepared]=await prepareAttachmentFiles([source]);expect(prepared.name).toBe(name+'.zip');expect(prepared.type).toBe('application/zip');expect(strFromU8(unzipSync(await bytes(prepared))[name])).toBe('Contenido original')})

test.each(['imagen.gif','video.mp4','presentación.pptx','hoja.xlsx'])('keeps directly supported %s unchanged',async name=>{const source=file(name,'source');expect(await prepareAttachmentFiles([source])).toEqual([source])})

test.each([['Carpeta.zip','application/zip'],['clip.mp4','video/mp4']])('rejects oversized %s before sending an impossible upload',async(name,type)=>{
 const source=new File(['stub'],name,{type});Object.defineProperty(source,'size',{value:501*1024*1024})
 await expect(prepareAttachmentFiles([source])).rejects.toThrow('500 MB')
})

test('rejects oversized plain files before creating an archive or reading their contents',async()=>{const source={...file('big.bin','a'),size:501*1024*1024};await expect(prepareAttachmentFiles([source])).rejects.toThrow('500 MB')})

test.each(['libro.pdf','presentación.pptx','tabla.xlsx','vídeo.mp4'])('rejects oversized %s even when Windows supplies no MIME',async name=>{
 const source={...file(name,'a'),size:501*1024*1024}
 await expect(prepareAttachmentFiles([source])).rejects.toThrow('500 MB')
})

test('bounds the aggregate picker selection before reading or compressing',async()=>{
 await expect(prepareAttachmentFiles(Array.from({length:5001},()=>file('nota.txt','a')))).rejects.toThrow('5000')
 const files=Array.from({length:3},()=>({name:'libro.pdf',size:50*1024*1024}) as File);expect(await prepareAttachmentFiles(files)).toEqual(files)
})

test('large folders split into independently readable ZIP parts, retaining paths',()=>{
 const files=[{path:'Universidad/',file:null},...Array.from({length:4},(_,i)=>({path:`Universidad/${i}.pdf`,file:{name:`${i}.pdf`,size:200*1024*1024} as File}))]
 const parts=folderArchiveParts(files);expect(parts).toHaveLength(2);expect(parts.flat()).toEqual(files)
 for(const part of parts) expect(part.reduce((n,v)=>n+(v.file?.size||0),0)).toBeLessThanOrEqual(MAX_MESSAGE_UPLOAD_BYTES)
})
test('reads bounded slices rather than whole source files',async()=>{
 const data=new Uint8Array(3*1024*1024+7).fill(65),source=new File([data],'large.txt'),slice=jest.spyOn(source,'slice')
 Object.defineProperty(source,'arrayBuffer',{value:()=>{throw Error('whole source allocation')}})
 const packed=await compressFiles([{path:source.name,file:source}], 'large')
 expect(slice).toHaveBeenCalledTimes(4);const result=unzipSync(await bytes(packed))['large.txt'];expect(result.length).toBe(data.length);expect(result.every(value=>value===65)).toBe(true)
})
test('accepts PDF and ZIP above 128 MB without a fake aggregate limit',async()=>{
 const files=['book.pdf','folder.zip'].map(name=>({name,type:name.endsWith('zip')?'application/zip':'application/pdf',size:200*1024*1024} as File))
 expect(await prepareAttachmentFiles(files)).toEqual(files)
})

test('folder picker preserves nested paths and creates a ZIP automatically',async()=>{
 const source=file('nota.txt','Contenido de carpeta');Object.defineProperty(source,'webkitRelativePath',{value:'Universidad/curso/nota.txt'})
 const [packed]=await prepareAttachmentFiles([source]);expect(packed.name).toBe('Universidad.zip');expect(strFromU8(unzipSync(await bytes(packed))['Universidad/curso/nota.txt'])).toBe('Contenido de carpeta')
})

test('folder picker splits more than 5000 entries automatically and preserves every path',async()=>{
 const source=Array.from({length:5001},(_,i)=>{const f=file(`${i}.txt`,'');Object.defineProperty(f,'webkitRelativePath',{value:`Carpeta/${i}.txt`});return f})
 const progress:string[]=[];const parts=await prepareAttachmentFiles(source,message=>progress.push(message))
 expect(parts.map(p=>p.name)).toEqual(['Carpeta-parte-1-de-2.zip','Carpeta-parte-2-de-2.zip'])
 const paths:string[]=[];for(const part of parts)paths.push(...Object.keys(unzipSync(await bytes(part))))
 expect(new Set(paths).size).toBe(5001);expect(paths).toContain('Carpeta/5000.txt');expect(progress.some(p=>p.includes('Comprimiendo'))).toBe(true)
},30000)
test('splits empty subdirectories by entry count as well as source bytes',()=>{
 const entries=Array.from({length:10001},(_,i)=>({path:`Carpeta/sub${i}/`,file:null}))
 const parts=folderArchiveParts(entries);expect(parts.map(p=>p.length)).toEqual([5000,5000,1]);expect(parts.flat()).toEqual(entries)
})

test('combines dropped folders into one ordinary ZIP and reports counts and sizes',async()=>{
 const folder=(name:string)=>{let read=false;return {name,isDirectory:true,isFile:false,createReader:()=>({readEntries:(ok:(items:unknown[])=>void)=>{ok(read?[]:[{name:'nota.txt',isDirectory:false,isFile:true,file:(done:(f:File)=>void)=>done(file('nota.txt',name))}]);read=true}})}}
 const progress:string[]=[],folders=[folder('A'),folder('B')]
 const result=await droppedAttachments({items:folders.map(value=>({kind:'file',webkitGetAsEntry:()=>value})),files:[]} as unknown as DataTransfer,message=>progress.push(message))
 expect(result).toHaveLength(1);expect(result[0].name).toBe('Carpetas.zip')
 const contents=unzipSync(await bytes(result[0]));expect(strFromU8(contents['A/nota.txt'])).toBe('A');expect(strFromU8(contents['B/nota.txt'])).toBe('B')
 expect(progress.at(-1)).toContain('2 archivos');expect(progress.at(-1)).toContain('originales');expect(progress.at(-1)).toContain('Listo para enviar')
})

test('identifies the oversized entry and total folder size in the error',()=>{
 expect(()=>folderArchiveParts([{path:'Curso/grande.pdf',file:{size:600*1024*1024} as File}])).toThrow(/Curso\/grande.pdf.*600 MB/)
})
