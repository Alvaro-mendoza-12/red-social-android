const assert = require('assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { ConectateChatbot } = require('../src/chatbot')

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conectate-ai-'))
const bot = new ConectateChatbot({
  intentsFile: path.resolve(__dirname, '..', 'data', 'intents.json'),
  modelFile: path.join(tmpDir, 'model.json'),
  feedbackFile: path.join(tmpDir, 'feedback.jsonl'),
  hiddenSize: 96,
  epochs: 120,
  knowledgeHiddenSize: 64,
  knowledgeEpochs: 80,
})

bot.loadOrTrain()

const status = bot.status()
assert.strictEqual(status.hiddenNeurons, 96)
assert.ok(status.parameters > 0)
assert.strictEqual(status.lastLoadAction, 'trained')
assert.strictEqual(status.knowledge.enabled, true)
assert.strictEqual(status.knowledge.pretrainedModel, false)
assert.strictEqual(status.knowledge.networkAccess, false)

const perfil = bot.answer('como cambio mi foto de perfil')
assert.strictEqual(perfil.intent, 'perfil_foto')
assert.ok(perfil.confidence > 0.3)

for (const message of ['¿Cómo vinculo la web con mi teléfono?', 'Quiero conectar Novvin web con mi celular', 'Como sincronizo mi cuenta con el navegador']) {
  const linked = bot.answer(message)
  assert.strictEqual(linked.intent, 'vincular_web')
  assert.match(linked.answer, /Vincular Novvin Web por QR/)
  assert.doesNotMatch(linked.answer, /Retira correos/)
}
const { classifyGeneralRisk } = require('../src/safety-policy')
assert.strictEqual(classifyGeneralRisk('¿Cómo cambio mi correo en mi perfil?'), null)
assert.strictEqual(classifyGeneralRisk('Mi teléfono es +51 987 654 321'), 'personal_data')
assert.strictEqual(classifyGeneralRisk('mi correo es persona@example.com'), 'personal_data')
assert.strictEqual(classifyGeneralRisk('mi DNI es 12345678'), 'personal_data')
assert.strictEqual(classifyGeneralRisk('como actualizo mi documento de identidad en la aplicación'), null)
assert.doesNotMatch(require('../src/safety-policy').redactSensitiveText('mi DNI es 12345678'), /12345678/)

const videos = bot.answer('quiero subir un video')
assert.strictEqual(videos.intent, 'videos_publicar')

const detailed = bot.answer('explicame paso a paso como edito un video')
assert.strictEqual(detailed.intent, 'videos_editor')
assert.strictEqual(detailed.responseMode, 'guided')
assert.match(detailed.answer, /1\. /)
assert.match(detailed.answer, /Dime que edicion quieres hacer/)

for (const message of [
  'Que ago si al intentar subir un video me sale un error',
  'Video no carga',
]) {
  const diagnostic = bot.answer(message)
  assert.strictEqual(diagnostic.intent, 'videos_subida_error')
  assert.strictEqual(diagnostic.responseMode, 'guided')
  assert.strictEqual(diagnostic.routingReason, 'video_diagnostic_guard')
  assert.match(diagnostic.answer, /1\. Conserva el proyecto/)
  assert.match(diagnostic.answer, /exportar, al procesar o cuando ya esta subiendo/)
}

const playback = bot.answer('los videos no cargan cuando intento verlos')
assert.strictEqual(playback.intent, 'videos_reproduccion')

const notifications = bot.answer('android no me avisa cuando llegan notificaciones')
assert.strictEqual(notifications.intent, 'notificaciones')

const general = bot.answer('por que vemos azul al mirar hacia arriba', {
  detailLevel: 'expanded',
})
assert.strictEqual(general.intent, 'general_knowledge')
assert.strictEqual(general.knowledgeId, 'cielo_azul')
assert.strictEqual(general.responseMode, 'grounded_general')
assert.match(general.answer, /Puntos clave/)

const calculation = bot.answer('cuanto es 15% de 80')
assert.strictEqual(calculation.intent, 'general_calculation')
assert.match(calculation.answer, /15 % de 80 = 12/)

const sensitive = bot.answer('que dosis de medicamento debo tomar')
assert.strictEqual(sensitive.intent, 'safe_referral')
assert.strictEqual(sensitive.safetyCategory, 'health')

const accountSecurity = bot.answer('como cambio la contrasena de mi cuenta en Conectate')
assert.strictEqual(accountSecurity.intent, 'recuperar_cuenta')
assert.notStrictEqual(accountSecurity.responseMode, 'safe_referral')

const seguridad = bot.answer('como hago privada mi cuenta')
assert.strictEqual(seguridad.intent, 'privacidad_seguridad')

const grupos = bot.answer('como creo un grupo')
assert.strictEqual(grupos.intent, 'grupos_crear')

const contextual = bot.answer('y la portada', {
  conversationId: 'test-chat',
  history: 'Usuario: como cambio mi foto de perfil\nNovvin: Ve a Perfil\nUsuario: y la portada',
})
assert.strictEqual(contextual.intent, 'perfil_portada')
assert.strictEqual(contextual.contextual, true)

const contextualFromPriorHistory = bot.answer('¿y la portada?', {
  conversationId: 'test-chat-prior-only',
  history: 'Usuario: como cambio mi foto de perfil\nNovvin: Ve a Perfil',
})
assert.strictEqual(contextualFromPriorHistory.intent, 'perfil_portada')
assert.strictEqual(contextualFromPriorHistory.contextual, true)

const knowledgeFollowUp = bot.answer('explícame más', {
  history: 'Usuario: por que el cielo es azul\nNovvin: Es dispersión de luz.\nUsuario: explícame más',
  detailLevel: 'expanded',
})
assert.strictEqual(knowledgeFollowUp.knowledgeId, 'cielo_azul')
assert.strictEqual(knowledgeFollowUp.contextual, true)

const fallback = bot.answer('xyzzy qqq zzzz')
assert.strictEqual(fallback.intent, 'fallback')
assert.strictEqual(fallback.confidence, 0)
assert.strictEqual(fallback.matchedTokens, 0)
assert.strictEqual(fallback.responseMode, 'fallback')

bot.recordFeedback({
  userId: 'test-user-that-must-not-be-written',
  message: 'como cambio mi avatar persona@example.com',
  answer: perfil.answer,
  intent: perfil.intent,
  rating: 'good',
})
assert.ok(fs.existsSync(path.join(tmpDir, 'feedback.jsonl')))
const feedback = fs.readFileSync(path.join(tmpDir, 'feedback.jsonl'), 'utf8')
assert.ok(!feedback.includes('test-user-that-must-not-be-written'))
assert.ok(!feedback.includes('persona@example.com'))
assert.match(feedback, /\[correo omitido\]/)

const reloadedBot = new ConectateChatbot({
  intentsFile: path.resolve(__dirname, '..', 'data', 'intents.json'),
  modelFile: path.join(tmpDir, 'model.json'),
  feedbackFile: path.join(tmpDir, 'feedback.jsonl'),
  hiddenSize: 96,
  epochs: 120,
  knowledgeHiddenSize: 64,
  knowledgeEpochs: 80,
})
reloadedBot.loadOrTrain()
assert.strictEqual(reloadedBot.status().lastLoadAction, 'loaded')

console.log('conectate-ai smoke tests passed')
