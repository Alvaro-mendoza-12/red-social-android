'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')

// Verify literal CommonJS imports without executing the API or touching its
// database. Run in the final Docker context, not only in the developer checkout.
function missingSourceDependencies(root) {
  const missing = []
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name)
      if (entry.isDirectory()) visit(filename)
      else if (entry.isFile() && entry.name.endsWith('.js')) {
        const resolve = createRequire(filename).resolve
        const source = fs.readFileSync(filename, 'utf8')
        for (const match of source.matchAll(/\brequire\(\s*['"]([^'"]+)['"]\s*\)/g)) {
          try { resolve(match[1]) } catch {
            missing.push({ file: path.relative(root, filename), dependency: match[1] })
          }
        }
      }
    }
  }
  visit(path.resolve(root))
  return missing
}

if (require.main === module) {
  const missing = missingSourceDependencies(process.argv[2] || path.join(__dirname, '../src'))
  console.log(JSON.stringify({ ok: missing.length === 0, missing }, null, 2))
  if (missing.length) process.exitCode = 1
}

module.exports = { missingSourceDependencies }
