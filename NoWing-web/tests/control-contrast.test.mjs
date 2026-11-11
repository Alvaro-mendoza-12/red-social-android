import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import postcss from 'postcss'

// Source-token contrast checks, not a substitute for rendered UI verification.
const sheet = name => postcss.parse(fs.readFileSync(new URL(`../src/ui/novvin/${name}`, import.meta.url), 'utf8'))
function value(file, selector, property) {
  let result
  sheet(file).walkRules(selector, rule => rule.walkDecls(property, decl => { result = decl.value }))
  return result
}
const rgb = hex => hex.replace('#', '').match(/../g).map(part => parseInt(part, 16))
const luminance = color => rgb(color).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0)
const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + .05) / (Math.min(luminance(a), luminance(b)) + .05)

for (const theme of [':root', '.theme-light', '.theme-system']) {
  test(`interactive outlines are visible on all default ${theme} surfaces`, () => {
    const border = value('tokens.css', theme, '--control-outline') ?? value('tokens.css', theme, '--outline')
    for (const token of ['--background', '--surface', '--surface-raised', '--surface-container']) {
      const surface = value('tokens.css', theme, token)
      const normalized = surface === '#fff' ? '#ffffff' : surface
      assert.ok(contrast(border, normalized) >= 3, `${theme} ${border} on ${surface} needs 3:1; got ${contrast(border, normalized).toFixed(2)}`)
    }
  })
}

test('shared fields and secondary buttons use the interactive outline token', () => {
  assert.equal(value('foundation.css', '.text-input,\nselect,\ntextarea', 'border'), '1px solid var(--control-outline)')
  assert.equal(value('foundation.css', '.ghost-btn,\n.small-btn', 'border-color'), 'var(--control-outline)')
})

test('authentication field outline contrasts with field fill and neighboring light surfaces', () => {
  const border = value('auth.css', '.auth-shell', '--control-outline') ?? value('auth.css', '.auth-shell', '--outline')
  for (const surface of ['#fafbff', '#ffffff', '#eef0fb', '#e5e8ff', '#d9f4f5']) {
    assert.ok(contrast(border, surface) >= 3, `${border} on ${surface} needs 3:1`)
  }
  assert.equal(value('auth.css', ".auth-shell form input:not([type='checkbox'])", 'border'), '1px solid var(--control-outline)')
})

test('authentication focus does not inherit bright cyan from the dark theme', () => {
  const focus = value('auth.css', '.auth-shell', '--focus-ring') ?? value('tokens.css', ':root', '--brand-cyan')
  for (const surface of ['#fafbff', '#ffffff', '#eef0fb', '#e5e8ff', '#d9f4f5']) {
    assert.ok(contrast(focus, surface) >= 3, `${focus} on ${surface} needs 3:1`)
  }
  assert.equal(value('foundation.css', ':focus-visible', 'outline'), '2px solid var(--focus-ring)')
})

const mix = (accent, base, proportion) => '#' + rgb(accent).map((v, i) => Math.round(v * proportion + rgb(base)[i] * (1 - proportion)).toString(16).padStart(2, '0')).join('')

test('interactive outlines remain visible on tinted surfaces including custom accent extremes', () => {
  for (const theme of [':root', '.theme-light']) {
    const border = value('tokens.css', theme, '--control-outline')
    const dark = theme === ':root'
    const accents = ['#000000', '#ffffff', '#767676']
    sheet('tokens.css').walkDecls(dark ? '--accent-dark' : '--accent-light', decl => {
      if (/^#[0-9a-f]{6}$/i.test(decl.value)) accents.push(decl.value)
    })
    for (const accent of accents) {
      for (const [base, proportion] of dark ? [['#13161c', .03], ['#19212c', .09], ['#202735', .15]] : [['#ffffff', .03], ['#ffffff', .09], ['#ffffff', .15]]) {
        const surface = mix(accent, base, proportion)
        assert.ok(contrast(border, surface) >= 3, `${theme} ${border} on ${surface} with accent ${accent}: ${contrast(border, surface).toFixed(2)}`)
      }
    }
  }
})
