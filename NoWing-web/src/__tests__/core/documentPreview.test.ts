import { zipSync, strToU8 } from 'fflate'
import { previewDocument } from '../../core/documentPreview'
test('HTML is returned as inert text',()=>{
  expect(previewDocument(strToU8('<script>window.bad=true</script>'),'document.html')).toEqual({type:'text',text:'<script>window.bad=true</script>'})
})
test('Word paragraphs are read without rendering embedded markup or relationships',()=>{
  const data=zipSync({'word/document.xml':strToU8('<w:document xmlns:w="urn:test"><w:p><w:r><w:t>Hola Novvin</w:t></w:r></w:p></w:document>'),'word/secret.html':strToU8('<script>bad()</script>')})
  expect(previewDocument(data,'document.docx')).toEqual({type:'paragraphs',paragraphs:['Hola Novvin']})
})
test('Excel preserves empty columns and resolves shared strings',()=>{
  const data=zipSync({'xl/workbook.xml':strToU8('<workbook><sheet name="Datos"/></workbook>'),'xl/sharedStrings.xml':strToU8('<sst><si><t>Novvin</t></si></sst>'),'xl/worksheets/sheet1.xml':strToU8('<worksheet><row><c r="A1" t="s"><v>0</v></c><c r="C1"><v>42</v></c></row></worksheet>')})
  expect(previewDocument(data,'datos.xlsx')).toEqual({type:'sheet',sheets:[{name:'Datos',rows:[['Novvin','','42']]}]})
})
test('ZIP lists the folder contents without decompressing or executing files',()=>{
  const data=zipSync({'Carpeta/uno.txt':strToU8('Uno'),'Carpeta/imagen.png':new Uint8Array([1])})
  expect(previewDocument(data,'Carpeta.zip')).toEqual({type:'archive',entries:['Carpeta/uno.txt','Carpeta/imagen.png']})
})
test('unrecognized binary formats offer the original download',()=>{
  expect(previewDocument(new Uint8Array([1]),'legacy.doc')).toEqual({type:'unsupported'})
})
