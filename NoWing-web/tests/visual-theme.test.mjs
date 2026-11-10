import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import postcss from 'postcss'

// Source-level contracts for known cascade defects. These do not replace
// rendered browser contrast, reflow or assistive-technology checks.
const css = name => postcss.parse(fs.readFileSync(new URL(`../src/ui/novvin/${name}`, import.meta.url), 'utf8'))
function declaration(file, selector, property) {
  let result
  css(file).walkRules(selector, rule => rule.walkDecls(property, value => { result = value.value }))
  return result
}

test('Videos navigation keeps the theme surface paired with theme-dependent control ink', () => {
  assert.equal(declaration('screens.css', '.route-videos .novvin-bottom-nav', 'background'), 'var(--surface)')
  assert.equal(declaration('screens.css', '.route-videos .novvin-bottom-nav', 'color'), 'var(--foreground)')
})

test('Social labels do not use an accent certified only against a different background', () => {
  assert.equal(declaration('webParity.css', '.social-intro .social-eyebrow', 'color'), 'var(--foreground)')
})

test('Social selected tab text stays readable on its tinted container', () => {
  assert.equal(declaration('webParity.css', '.social-page .social-tab-bar .chip-btn.active', 'color'), 'var(--foreground)')
})

const rgb = hex => hex.slice(1).match(/../g).map(part => parseInt(part,16))
const luminance = value => value.map(x => x/255).map(x => x <= .04045 ? x/12.92 : ((x+.055)/1.055)**2.4).reduce((sum,x,i) => sum+x*[.2126,.7152,.0722][i],0)
const contrast = (a,b) => (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05)
test('the light foreground remains readable on a gray custom-accent container', () => {
  const surface = rgb('#767676').map(x => Math.round(x*.15 + 255*.85))
  const foreground = rgb(declaration('tokens.css', '.theme-light', '--foreground'))
  assert.ok(contrast(rgb('#767676'),surface)<4.5, 'The regression fixture must reproduce the accent contrast failure')
  assert.ok(contrast(foreground,surface)>=4.5)
})

// The review fixture is a light card after custom-accent surface tinting.
// Resolve the actual footer declaration against it; this is not a browser cascade engine.
test('Explore footer links meet normal-text contrast on the gray custom-accent surface', () => {
  const surface = rgb('#fafafa')
  const theme = {
    'var(--primary)': '#767676',
    'var(--foreground)': declaration('tokens.css', '.theme-light', '--foreground'),
  }
  const color = declaration('../../pages/explore.css', '.explore-card footer a', 'color')
  const resolved = theme[color] ?? color
  assert.ok(contrast(rgb('#767676'), surface) < 4.5, 'The fixture must expose the original accent contrast failure')
  assert.ok(contrast(rgb(resolved), surface) >= 4.5, `Footer ink ${resolved} must reach 4.5:1 on #fafafa`)
})
