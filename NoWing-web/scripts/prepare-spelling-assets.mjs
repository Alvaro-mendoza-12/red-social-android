import { mkdir, copyFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
const require=createRequire(import.meta.url)
const source=dirname(require.resolve('dictionary-es'))
const target=new URL('../public/dictionaries/es-4.0.0/',import.meta.url)
await mkdir(target,{recursive:true})
for(const name of ['index.aff','index.dic','license'])await copyFile(join(source,name),new URL(name,target))
console.log('Spanish dictionary assets and original license prepared (dictionary-es 4.0.0).')
