'use strict'
// Only serving-process work directories belong to this startup operation.
// Legacy originals and administrative journals are deliberately outside it.
const fs = require('node:fs')
const path = require('node:path')
const WORK_DIRECTORIES = ['.tmp', '.objects', '.quarantine', '_variants']

function prepareRuntimeStorage(root, uid, gid, { maxEntries = 100000 } = {}) {
  if (typeof root !== 'string' || !path.isAbsolute(root) || path.resolve(root) !== root || root === path.parse(root).root ||
      !Number.isSafeInteger(uid) || uid < 0 || !Number.isSafeInteger(gid) || gid < 0 ||
      !Number.isSafeInteger(maxEntries) || maxEntries < 1) throw new Error('API_STORAGE_ROOT_INVALID')
  if (fs.realpathSync(path.dirname(root)) !== path.dirname(root)) throw new Error('API_STORAGE_PATH_INVALID')
  if (!fs.existsSync(root)) fs.mkdirSync(root, { mode: 0o700 })
  if (fs.realpathSync(root) !== root) throw new Error('API_STORAGE_PATH_INVALID')
  const entries = []
  function inspect(location, descend) {
    const stat = fs.lstatSync(location)
    if (stat.isSymbolicLink() || (!stat.isDirectory() && !stat.isFile())) throw new Error('API_STORAGE_PATH_INVALID')
    entries.push({ location, stat })
    if (entries.length > maxEntries) throw new Error('API_STORAGE_ENTRY_LIMIT')
    if (descend && stat.isDirectory()) for (const name of fs.readdirSync(location)) inspect(path.join(location, name), true)
  }
  inspect(root, false)
  if (!entries[0].stat.isDirectory()) throw new Error('API_STORAGE_ROOT_INVALID')
  for (const name of WORK_DIRECTORIES) {
    const location = path.join(root, name)
    if (!fs.existsSync(location)) fs.mkdirSync(location, { mode: 0o700 })
    if (!fs.lstatSync(location).isDirectory()) throw new Error('API_STORAGE_PATH_INVALID')
    inspect(location, true)
  }
  let ownershipChanges = 0
  // Validate every entry before changing any ownership. Open without following
  // symlinks and verify the inode again before the bounded descriptor change.
  for (const { location, stat } of entries) {
    if (stat.uid === uid && stat.gid === gid) continue
    const fd = fs.openSync(location, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | (fs.constants.O_NONBLOCK || 0))
    try {
      const opened = fs.fstatSync(fd)
      if (opened.dev !== stat.dev || opened.ino !== stat.ino || opened.isSymbolicLink()) throw new Error('API_STORAGE_PATH_CHANGED')
      fs.fchownSync(fd, uid, gid); ownershipChanges++
    } finally { fs.closeSync(fd) }
  }
  return { inspected: entries.length, ownershipChanges, administrativePathsVisited: false, legacyOriginalsVisited: false }
}
module.exports = { prepareRuntimeStorage, WORK_DIRECTORIES }
if (require.main === module) {
  try { console.log(JSON.stringify(prepareRuntimeStorage(process.argv[2], Number(process.argv[3]), Number(process.argv[4])))) }
  catch { console.error('API_STORAGE_OWNERSHIP_REPAIR_FAILED'); process.exitCode = 1 }
}
