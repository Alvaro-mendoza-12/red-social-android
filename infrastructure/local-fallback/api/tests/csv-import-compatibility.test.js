const { test } = require('node:test')
const assert = require('node:assert/strict')
const { parse } = require('csv-parse/sync')

test('patched CSV parser preserves import fields, BOM, quotes and embedded newlines', () => {
  const rows = parse('\ufeffid,name,metadata\n1,"María, Luz","line one\nline two"\n\n', {
    bom: true, columns: true, skip_empty_lines: true, trim: true, relax_column_count: true,
  })
  assert.equal(rows.length, 1)
  assert.equal(rows[0].id, '1')
  assert.equal(rows[0].name, 'María, Luz')
  assert.equal(rows[0].metadata, 'line one\nline two')
})

test('CSV column names cannot replace the record prototype', () => {
  const [record] = parse('__proto__,name\ninjected,Ana', { columns: true })
  assert.equal(record.name, 'Ana')
  assert.notEqual(Object.getPrototypeOf(record), 'injected')
  assert.equal(Object.prototype.injected, undefined)
})
