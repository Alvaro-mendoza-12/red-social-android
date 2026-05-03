import { createClient } from '@supabase/supabase-js'

const supabaseUrl = process.env.SUPABASE_URL?.trim()
const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY?.trim()
const bucketArgs = process.argv.slice(2).map((value) => value.trim()).filter(Boolean)
const bucketIds = bucketArgs.length > 0 ? bucketArgs : ['challenges', 'moment-media']

if (!supabaseUrl || !serviceRoleKey) {
  console.error('Faltan variables de entorno.')
  console.error('Define SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY antes de ejecutar este script.')
  process.exit(1)
}

const supabase = createClient(supabaseUrl, serviceRoleKey, {
  auth: {
    autoRefreshToken: false,
    persistSession: false,
  },
})

async function main() {
  console.log(`Buckets objetivo: ${bucketIds.join(', ')}`)

  for (const bucketId of bucketIds) {
    console.log(`\nVaciando bucket: ${bucketId}`)

    const { data, error } = await supabase.storage.emptyBucket(bucketId)

    if (error) {
      console.error(`Fallo al vaciar ${bucketId}`)
      console.error(`Mensaje: ${error.message}`)
      if (error.statusCode) {
        console.error(`Status: ${error.statusCode}`)
      }
      process.exitCode = 1
      continue
    }

    console.log(`Bucket vaciado correctamente: ${bucketId}`)
    if (data != null) {
      console.log(`Respuesta: ${JSON.stringify(data)}`)
    }
  }
}

main().catch((error) => {
  console.error('Error inesperado al vaciar buckets.')
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
})
