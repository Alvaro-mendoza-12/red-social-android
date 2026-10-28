import { prepareIndexNow } from './indexnow-notifier.mjs'
const args = process.argv.slice(2)
try {
  if (args.some(arg => arg.startsWith('--') && !['--submit', '--dry-run', '--deleted'].includes(arg)) || (args.includes('--submit') && args.includes('--dry-run'))) throw new Error('Use public routes with --dry-run (default), --submit or --deleted')
  console.log(JSON.stringify(await prepareIndexNow({ routes: args.filter(arg => !arg.startsWith('--')), submit: args.includes('--submit'), deleted: args.includes('--deleted') })))
} catch (error) { console.error(error.message); process.exitCode = 1 }
