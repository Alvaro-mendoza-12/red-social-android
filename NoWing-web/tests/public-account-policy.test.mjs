import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { loadPublicPageModel } from '../scripts/public-page-model.mjs'

const root = path.resolve(import.meta.dirname, '..')
const { pages } = loadPublicPageModel(root)
const html = route => {
  const page = pages.find(page => page.route === route)
  assert.ok(page, `Missing anonymous public page: ${route}`)
  return page.content
}

test('an uninstalled user can request Novvin account deletion without signing in', () => {
  const page = html('/eliminar-cuenta')
  assert.match(page, /Eliminar tu cuenta de Novvin/)
  assert.match(page, /href="mailto:administracion@novvinapp\.com\?subject=Eliminar%20mi%20cuenta%20de%20Novvin"/)
  assert.match(page, /sin instalar la app/)
  assert.match(page, /No necesitas descargar Novvin ni iniciar sesión/)
  assert.match(page, /correo asociado a la cuenta/)
  assert.match(page, /Nunca envíes tu contraseña ni códigos/)
  assert.doesNotMatch(page, /<script|<iframe/)
})

test('deletion explains scope, verification, exceptions and the privacy contact', () => {
  const page = html('/eliminar-cuenta')
  for (const phrase of ['Verificación y confirmación', 'Qué datos se eliminan', 'Excepciones y conservación', 'copias de respaldo', 'plazo aplicable', 'conectate.com']) assert.ok(page.includes(phrase), phrase)
  assert.match(page, /href="\/privacidad#eliminacion"/)
})

test('public privacy identifies Novvin, actual integrated services and a visible deletion journey', () => {
  const page = html('/privacidad')
  for (const phrase of ['Política de privacidad de Novvin', 'conectate.com', 'Firebase Cloud Messaging', 'Firebase Crashlytics', 'Google Play', 'Vercel', 'Cloudflare', 'HTTPS']) assert.ok(page.includes(phrase), phrase)
  assert.match(page, /href="\/eliminar-cuenta">Eliminar mi cuenta/)
  assert.match(page, /id="eliminacion"/)
  assert.match(page, /href="#eliminacion"/)
  for (const page of pages) assert.match(page.content, /href="\/eliminar-cuenta"/)
})
