'use strict';
(() => {
  const app = document.getElementById('app'), pending = new Map(), assets = new Map()
  const params = new URLSearchParams(location.search), typeLabels = { text: 'Respuesta corta', paragraph: 'Párrafo', single: 'Opción única', multiple: 'Varias opciones', dropdown: 'Lista desplegable', scale: 'Escala', number: 'Número', date: 'Fecha', matrix: 'Matriz', file: 'Archivos / fotos' }
  const measures = { nominal: 'Cualitativa nominal', ordinal: 'Cualitativa ordinal', discrete: 'Cuantitativa discreta', continuous: 'Cuantitativa continua' }, roles = { descriptive: 'Descriptiva', independent: 'Independiente', dependent: 'Dependiente', control: 'Control' }
  let account, form, dirty = false, view = 'home', campaign, survey, answers = {}, consent = false, sectionIndex = 0, sectionStack = [], editing = false, preview = false, sequence = 0, filter = 'active', librarySearch = '', allForms = [], allCampaigns = [], stats, page = 0, busy = false, clockOffset = 0, resultFilters = { from: '', to: '', search: '' }
  const e = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
  const id = () => crypto.randomUUID ? crypto.randomUUID() : `q${Date.now()}${Math.random().toString(36).slice(2)}`
  const date = v => v ? new Date(v).toLocaleString('es') : 'Sin cierre programado'
  const num = n => n == null ? '—' : Number(n).toLocaleString('es', { maximumFractionDigits: 3 })
  const campaignStatus = c => c.moderation_blocked ? 'Restringida por moderación' : c.status !== 'open' ? ({ closed: 'Cerrada', archived: 'Archivada', trashed: 'Papelera' }[c.status] || c.status)
    : c.closes_at && Date.parse(c.closes_at) <= Date.now() ? 'Cerrada' : c.opens_at && Date.parse(c.opens_at) > Date.now() ? 'Programada' : 'Abierta'
  function toast(message) { const n = document.getElementById('notice'); n.textContent = message; n.style.display = 'block'; setTimeout(() => n.style.display = 'none', 7000) }
  function request(path, method = 'GET', body) {
    return new Promise((resolve, reject) => {
      const requestId = String(++sequence), timer = setTimeout(() => { pending.delete(requestId); reject(new Error('La operación tardó demasiado. Recarga para comprobar si se guardó.')) }, 90000)
      pending.set(requestId, { resolve, reject, timer })
      const payload = { type: 'novvin-survey-request', id: requestId, path: `/v1/surveys${path}`, method, body, accountId: account?.id }
      if (window.NovvinSurveys) window.NovvinSurveys.request(JSON.stringify(payload))
      else { clearTimeout(timer); pending.delete(requestId); reject(new Error('Abre Encuestas desde Novvin.')) }
    })
  }
  window.surveyReceive = function (message) {
    const p = pending.get(message.id); if (!p) return
    clearTimeout(p.timer); pending.delete(message.id)
    message.error ? p.reject(new Error(message.error)) : p.resolve(message.value)
  }
  function download(result) {
    if (window.NovvinSurveys) window.NovvinSurveys.download(JSON.stringify(result))
    else throw new Error('La descarga está disponible en la app Android.')
  }
  function button(label, action, data = '', cls = '') { return `<button type="button" class="${cls}" data-action="${action}" data-id="${e(data)}">${e(label)}</button>` }
  function setBusy(value) { busy = value; app.setAttribute('aria-busy', String(value)) }
  function input(label, path, value, kind = 'text', extra = '') { return `<label>${e(label)}<input data-field="${e(path)}" type="${kind}" value="${e(value)}" ${extra}></label>` }
  function area(label, path, value, extra = '') { return `<label>${e(label)}<textarea data-field="${e(path)}" ${extra}>${e(value)}</textarea></label>` }
  function select(label, path, value, options) { return `<label>${e(label)}<select data-field="${e(path)}">${Object.entries(options).map(([k, v]) => `<option value="${e(k)}" ${k === String(value) ? 'selected' : ''}>${e(v)}</option>`).join('')}</select></label>` }
  function checkbox(label, path, value) { return `<label class="choice"><input data-field="${e(path)}" type="checkbox" ${value ? 'checked' : ''}>${e(label)}</label>` }
  async function modal(title, body) {
    const d = document.getElementById('modal'); if (d.open) return false
    document.getElementById('modal-title').textContent = title; document.getElementById('modal-body').innerHTML = body
    d.returnValue = 'cancel'; d.showModal(); return new Promise(resolve => d.addEventListener('close', () => resolve(d.returnValue === 'ok'), { once: true }))
  }
  document.querySelectorAll('[data-dialog]').forEach(button => button.addEventListener('click', () => document.getElementById('modal').close(button.dataset.dialog)))
  async function guard() { return !dirty || await modal('Hay cambios sin guardar', '<p>Si sales, conservarás un borrador en este dispositivo. ¿Deseas salir del editor?</p>') }
  let undoStack = [], redoStack = [], rememberedSchema
  function markDirty() {
    if (rememberedSchema && JSON.stringify(form.schema) !== JSON.stringify(rememberedSchema)) { undoStack.push(rememberedSchema); if (undoStack.length > 50) undoStack.shift(); redoStack = [] }
    rememberedSchema = structuredClone(form.schema)
    dirty = true
    if (account) localStorage.setItem(`novvin-survey-form:${account.id}`, JSON.stringify(form))
    const status = document.getElementById('save-status'); if (status) status.textContent = 'Cambios sin guardar · borrador local'
    const undo = app.querySelector('[data-action="undo"]'), redo = app.querySelector('[data-action="redo"]')
    if (undo) undo.disabled = !undoStack.length
    if (redo) redo.disabled = !redoStack.length
  }
  function newForm() {
    rememberedSchema = null; undoStack = []; redoStack = []
    form = { schema: { title: 'Nueva encuesta', description: '', purpose: '', contact: '', retention: '', retentionDays: 180, consent: 'He leído el propósito y acepto participar voluntariamente. Entiendo que mi cuenta y mis respuestas serán visibles para quien creó la encuesta.', theme: 'violet', sections: [{ id: 'section1', title: 'Sección 1', description: '' }], questions: [] } }
    addQuestion(); dirty = true; renderEditor()
  }
  function addQuestion() {
    form.schema.questions.push({ id: id(), title: `Pregunta ${form.schema.questions.length + 1}`, description: '', section: form.schema.sections.at(-1).id, type: 'single', required: false, measure: 'nominal', role: 'descriptive', options: [{ id: id(), label: 'Opción 1' }, { id: id(), label: 'Opción 2' }], rows: [{ id: id(), label: 'Fila 1' }], min: null, max: null, maxLength: 500 })
    markDirty()
  }
  async function reportSurvey() {
    const accepted = await modal('Reportar encuesta', '<label>Motivo<select id="report-reason"><option value="privacy">Privacidad o uso de datos</option><option value="fraud">Fraude</option><option value="harassment">Acoso</option><option value="sensitive">Contenido sensible</option><option value="spam">Spam</option><option value="other">Otro</option></select></label><label>Describe el problema<textarea id="report-detail" minlength="10" maxlength="1500"></textarea></label><p>No incluyas tus respuestas ni información privada en el reporte. El equipo de moderación revisará la encuesta.</p>')
    if (!accepted) return
    await request(`/campaigns/${survey.id}/report`, 'POST', { reason: document.getElementById('report-reason').value, detail: document.getElementById('report-detail').value })
    toast('Reporte recibido. Gracias por describir el problema.')
  }
  async function reviewReports() {
    if (!await guard()) return
    view = 'moderation'; dirty = false
    const data = await request('/admin/reports')
    app.innerHTML = `<h1>Reportes de encuestas</h1><p>Revisa el instrumento y el motivo. Las respuestas de los participantes siguen siendo privadas.</p>${button('Mis encuestas', 'home')}${data.reports.map(r => `<article class="card"><h2>${e(r.title)}</h2><p>${e(r.reason)} · ${r.status === 'pending' ? 'Pendiente' : 'Revisado'} · ${date(r.created_at)}</p><p>${e(r.detail)}</p><p>${r.moderation_blocked ? 'Encuesta restringida' : 'Sin restricción'} · ${e(r.moderation_note)}</p><div class="actions">${button('Ver preguntas', 'review-instrument', r.campaign_id)}${account.can_moderate ? button('Restringir', 'moderation-block', r.campaign_id, 'danger') + button('Levantar restricción', 'moderation-unblock', r.campaign_id) + button('Cerrar reporte sin restricción', 'moderation-dismiss', r.campaign_id) : ''}</div></article>`).join('') || '<p>No hay reportes.</p>'}`
  }
  async function reviewInstrument(target) {
    const c = await request(`/admin/campaigns/${target}`)
    await modal('Instrumento reportado', `<h2>${e(c.schema.title)}</h2><p>${e(c.schema.purpose)}</p><p>Contacto: ${e(c.schema.contact)}</p><p>${e(c.schema.retention)} · ${e(c.schema.retentionDays)} días</p><p>${e(c.schema.consent)}</p>${c.schema.questions.map(q => `<h3>${e(q.title)}</h3><p>${e(q.description)}</p><ul>${q.options.map(o => `<li>${e(o.label)}</li>`).join('')}</ul>`).join('')}`)
  }
  async function moderate(target, action) {
    if (!await modal('Decisión de moderación', '<p>La restricción impide abrir y responder la encuesta por Social y QR. El responsable conserva sus datos y no puede quitar esta restricción.</p><label>Motivo de la decisión<textarea id="moderation-note" minlength="10" maxlength="1000"></textarea></label>')) return
    await request(`/admin/campaigns/${target}/moderation`, 'POST', { action, note: document.getElementById('moderation-note').value })
    return reviewReports()
  }
  async function home() {
    if (!account) return boot()
    if (!await guard()) return
    view = 'home'; dirty = false; app.innerHTML = '<p role="status">Cargando tus encuestas…</p>'
    const [f, c] = await Promise.all([request('/forms'), request('/campaigns')]); allForms = f.forms; allCampaigns = c.campaigns; renderHome()
  }
  function renderHome() {
    const matches = row => filter === 'trash' ? !!row.trashed_at || row.status === 'trashed' : filter === 'archive' ? row.archived || row.status === 'archived' : !row.trashed_at && !row.archived && !['trashed', 'archived'].includes(row.status)
    const draft = account && localStorage.getItem(`novvin-survey-form:${account.id}`)
    app.innerHTML = `<h1>Mis encuestas</h1><p class="muted">Crea planillas reutilizables, publícalas en Social y consulta las respuestas en privado.</p><div class="actions">${button('Crear encuesta', 'new', '', 'primary')}${draft ? button('Recuperar borrador', 'draft') : ''}${button('Actualizar', 'home')}</div><nav class="tabs" aria-label="Estado de encuestas">${[['active', 'Activas'], ['archive', 'Archivadas'], ['trash', 'Papelera']].map(([v, label]) => button(label, 'filter', v, filter === v ? 'active' : '')).join('')}</nav><h2>Planillas</h2><div class="grid">${allForms.filter(matches).map(f => `<article class="card"><h3>${e(f.schema.title)}</h3><small>Revisión ${f.revision} · ${date(f.updated_at)}</small><div class="actions">${button(f.trashed_at || f.archived ? 'Ver' : 'Editar', 'edit', f.id)}${button('Duplicar', 'duplicate', f.id)}${button('Historial', 'revisions', f.id)}${!f.trashed_at && !f.archived ? button('Archivar', 'form-archive', f.id) + button('Papelera', 'form-trash', f.id) : button('Restaurar', 'form-restore', f.id)}${f.trashed_at ? button('Eliminar definitivamente', 'form-purge', f.id, 'danger') : ''}</div></article>`).join('') || '<p class="empty">No hay planillas aquí.</p>'}</div><h2>Encuestas publicadas</h2><div class="grid">${allCampaigns.filter(matches).map(c => `<article class="card"><span class="chip">${c.status === 'open' && c.closes_at && Date.parse(c.closes_at) <= Date.now() ? 'Cerrada' : { open: 'Abierta', closed: 'Cerrada', archived: 'Archivada', trashed: 'Papelera' }[c.status]}</span><h3>${e(c.schema.title)}</h3><p>${c.response_count} respuestas · ${e({ public: 'Pública', followers: 'Seguidores', private: 'Solo tú' }[c.visibility])}</p><small>${date(c.closes_at)}${!c.post_exists ? ' · Sin publicación en Social' : ''}</small><div class="actions">${button('Administrar y resultados', 'manage', c.id)}${button('Ver encuesta', 'respond', c.id)}</div></article>`).join('') || '<p class="empty">Publica una planilla para empezar a recibir respuestas.</p>'}</div>`
    if (account.can_review_reports) app.querySelector('.actions').insertAdjacentHTML('beforeend', button('Revisar reportes', 'review-reports'))
    app.querySelector('.tabs').insertAdjacentHTML('afterend', `<label>Buscar planillas y encuestas<input id="library-search" value="${e(librarySearch)}" placeholder="Título de la encuesta"></label>`)
    filterLibrary()
    app.querySelectorAll('.grid article.card').forEach(card => {
      const control = card.querySelector('[data-action="manage"]'), c = allCampaigns.find(item => item.id === control?.dataset.id)
      if (c) card.querySelector('.chip').textContent = campaignStatus(c)
    })
    app.setAttribute('aria-busy', String(busy))
  }
  function filterLibrary() { document.querySelectorAll('#app .grid article.card').forEach(card => { card.hidden = !card.querySelector('h3').textContent.toLocaleLowerCase('es').includes(librarySearch.toLocaleLowerCase('es')) }) }
  function renderEditor() {
    view = 'editor'; const s = form.schema, locked = form.archived || form.trashed_at
    app.innerHTML = `<h1>${form.id ? 'Editar planilla' : 'Crear encuesta'}</h1><p class="muted">Guardar crea una revisión de la planilla. Las encuestas ya publicadas mantienen sus preguntas originales.</p><fieldset ${locked ? 'disabled' : ''}><div class="card">${input('Título', 'title', s.title, 'text', 'maxlength="180" required')}${area('Descripción', 'description', s.description, 'maxlength="4000"')}${area('Propósito de la investigación *', 'purpose', s.purpose, 'maxlength="3000" required')}${input('Contacto del responsable *', 'contact', s.contact, 'text', 'maxlength="300" required')}${area('Uso y conservación de datos *', 'retention', s.retention, 'maxlength="800" required')}${input('Conservación de cada respuesta (días) *', 'retentionDays', s.retentionDays ?? '', 'number', 'min="1" max="3650" step="1" required')}<p class="muted">Las respuestas y sus adjuntos se eliminan al cumplir este plazo desde el envío. El plazo queda fijado al publicar. Las descargas del responsable y las copias de seguridad tienen su propia conservación.</p>${area('Texto de consentimiento *', 'consent', s.consent, 'maxlength="3000" required')}${select('Color', 'theme', s.theme, { violet: 'Violeta', blue: 'Azul', green: 'Verde' })}<p class="identity">Las respuestas no son anónimas: el creador ve la cuenta y el nombre. Los demás participantes solo ven la encuesta y la cantidad de respuestas.</p></div><h2>Secciones</h2>${s.sections.map((section, i) => `<details class="card"><summary>${e(section.title)}</summary>${input('Nombre', `sections.${i}.title`, section.title)}${area('Descripción', `sections.${i}.description`, section.description)}${s.sections.length > 1 ? button('Eliminar sección y mover sus preguntas a la primera', 'remove-section', section.id, 'danger') : ''}</details>`).join('')}<div class="actions">${button('Agregar sección', 'add-section')}</div><h2>Preguntas</h2>${s.questions.map((q, i) => `<details class="card question" open><summary>${i + 1}. ${e(q.title)}</summary>${input('Pregunta *', `questions.${i}.title`, q.title, 'text', 'maxlength="500"')}${area('Ayuda / descripción', `questions.${i}.description`, q.description)}<div class="grid">${select('Tipo', `questions.${i}.type`, q.type, typeLabels)}${select('Sección', `questions.${i}.section`, q.section, Object.fromEntries(s.sections.map(a => [a.id, a.title])))}${select('Medición', `questions.${i}.measure`, q.measure, measures)}${select('Rol de la variable', `questions.${i}.role`, q.role, roles)}</div>${checkbox('Respuesta obligatoria', `questions.${i}.required`, q.required)}<div class="file">${q.image ? `<img class="question-image" data-asset="${e(q.image)}" alt="Imagen de la pregunta">${button('Quitar imagen', 'remove-image', q.id)}` : ''}<label>Imagen de pregunta<input type="file" accept="image/*" data-upload-question="${e(q.id)}"></label></div>${['single', 'multiple', 'dropdown', 'matrix'].includes(q.type) ? `<h3>Opciones</h3>${q.options.map((o, j) => `<div class="card">${input(`Opción ${j + 1}`, `questions.${i}.options.${j}.label`, o.label)}${['single', 'dropdown'].includes(q.type) ? select('Después de esta sección', `questions.${i}.options.${j}.next`, o.next || '', { '': 'Continuar', ...Object.fromEntries(s.sections.slice(s.sections.findIndex(a => a.id === q.section) + 1).map(a => [a.id, a.title])), end: 'Enviar / terminar' }) : ''}<div class="file">${o.image ? `<img width="90" data-asset="${e(o.image)}" alt="Imagen de opción">${button('Quitar imagen de opción', 'remove-option-image', `${q.id}:${o.id}`)}` : ''}<label>Foto de opción<input type="file" accept="image/*" data-upload-option="${e(q.id)}:${e(o.id)}"></label>${button('Eliminar opción', 'remove-option', `${q.id}:${o.id}`, 'danger')}</div></div>`).join('')}${button('Agregar opción', 'add-option', q.id)}${checkbox('Mezclar opciones al responder', `questions.${i}.randomize`, q.randomize)}<small>Para usar saltos, esta debe ser la última pregunta de su sección.</small>` : ''}${q.type === 'matrix' ? `<h3>Filas</h3>${q.rows.map((r, j) => `<div class="inline">${input(`Fila ${j + 1}`, `questions.${i}.rows.${j}.label`, r.label)}${button('Eliminar fila', 'remove-row', `${q.id}:${r.id}`, 'danger')}</div>`).join('')}${button('Agregar fila', 'add-row', q.id)}` : ''}${['number', 'scale', 'multiple'].includes(q.type) ? `<div class="grid">${input(q.type === 'multiple' ? 'Mínimo de selecciones' : 'Mínimo', `questions.${i}.min`, q.min, 'number')}${input(q.type === 'multiple' ? 'Máximo de selecciones' : 'Máximo', `questions.${i}.max`, q.max, 'number')}</div>` : ''}${['text', 'paragraph'].includes(q.type) ? input('Máximo de caracteres', `questions.${i}.maxLength`, q.maxLength, 'number', 'min="1" max="10000"') : ''}${q.type === 'date' ? `<div class="grid">${input('Fecha mínima', `questions.${i}.minDate`, q.minDate, 'date')}${input('Fecha máxima', `questions.${i}.maxDate`, q.maxDate, 'date')}</div>` : ''}${q.type === 'file' ? '<p class="muted">Hasta 3 imágenes o PDF por pregunta, máximo 4 MB cada archivo. El servidor valida las imágenes y exige antivirus para PDF.</p>' : ''}<div class="actions">${button('↑ Subir', 'question-up', q.id)}${button('↓ Bajar', 'question-down', q.id)}${button('Duplicar pregunta', 'duplicate-question', q.id)}${button('Eliminar pregunta', 'remove-question', q.id, 'danger')}</div></details>`).join('')}<div class="actions">${button('Agregar pregunta', 'add-question')}</div></fieldset><div class="toolbar"><small id="save-status">${dirty ? 'Cambios sin guardar · borrador local' : `Guardado · revisión ${form.revision || 0}`}</small><div class="actions">${!locked ? button('Guardar planilla', 'save', '', 'primary') : ''}${button('Vista previa', 'preview')}${!locked ? button('Publicar', 'publish') : ''}${button('Mis encuestas', 'home')}</div></div>`
    app.querySelector('.card')?.insertAdjacentHTML('beforeend', `<h3>Presentación</h3>${input('Minutos estimados', 'estimatedMinutes', s.estimatedMinutes || 5, 'number', 'min="1" max="120"')}${area('Mensaje al terminar', 'confirmation', s.confirmation || '')}${checkbox('Contiene temas sensibles', 'sensitive', s.sensitive)}${s.cover ? `<img class="question-image" data-asset="${e(s.cover)}" alt="${e(s.coverAlt || 'Portada de la encuesta')}">${button('Quitar portada', 'remove-cover')}` : ''}<label>Imagen de portada<input type="file" accept="image/*" data-upload-cover="1"></label>${input('Texto alternativo de portada', 'coverAlt', s.coverAlt || '')}`)
    app.querySelectorAll('details.card').forEach((details, i) => {
      if (i >= s.sections.length) return
      const section = s.sections[i]
      details.insertAdjacentHTML('beforeend', `${section.image ? `<img class="question-image" data-asset="${e(section.image)}" alt="${e(section.imageAlt || 'Imagen de sección')}">${button('Quitar imagen', 'remove-section-image', section.id)}` : ''}<label>Imagen de sección<input type="file" accept="image/*" data-upload-section="${e(section.id)}"></label>${input('Texto alternativo de imagen', `sections.${i}.imageAlt`, section.imageAlt || '')}`)
    })
    app.querySelectorAll('details.card.question').forEach((details, i) => {
      const q = s.questions[i]
      if (q.type === 'scale' || (q.type === 'number' && q.measure === 'ordinal')) details.insertAdjacentHTML('beforeend', checkbox('Tratar los puntos como intervalares para calcular media y Pearson (supuesto del investigador)', `questions.${i}.treatAsInterval`, q.treatAsInterval))
      details.insertAdjacentHTML('beforeend', `${input('Texto alternativo de imagen de pregunta', `questions.${i}.imageAlt`, q.imageAlt || '')}${q.type === 'number' ? input('Unidad de medida', `questions.${i}.unit`, q.unit || '') : ''}${['text', 'paragraph'].includes(q.type) ? input('Mínimo de caracteres', `questions.${i}.minLength`, q.minLength || 0, 'number', 'min="0" max="10000"') : ''}`)
    })
    if (!locked) app.querySelector('.toolbar .actions')?.insertAdjacentHTML('afterbegin', button('Importar preguntas', 'import-questions'))
    if (!locked) {
      app.querySelector('.toolbar .actions')?.insertAdjacentHTML('afterbegin', button('Deshacer', 'undo') + button('Rehacer', 'redo'))
      app.querySelector('[data-action="undo"]').disabled = !undoStack.length
      app.querySelector('[data-action="redo"]').disabled = !redoStack.length
      for (const action of ['preview', 'publish', 'save']) {
        const control = app.querySelector(`.toolbar [data-action="${action}"]`)
        control?.parentElement.prepend(control)
      }
    }
    applyTheme(s.theme); hydrateImages()
  }
  function applyTheme(theme) {
    const colors = matchMedia('(prefers-color-scheme:dark)').matches ? { violet: '#a38aed', blue: '#82b4ff', green: '#72cfac' } : { violet: '#6750c8', blue: '#245c9f', green: '#176742' }
    document.documentElement.style.setProperty('--accent', colors[theme] || colors.violet)
  }
  async function save() {
    form = await request(form.id ? `/forms/${form.id}` : '/forms', form.id ? 'PUT' : 'POST', { schema: form.schema, revision: form.revision })
    dirty = false; localStorage.removeItem(`novvin-survey-form:${account.id}`); renderEditor(); toast('Planilla guardada. Las encuestas publicadas siguen intactas.')
  }
  async function publish() {
    if (dirty || !form.id) await save()
    if (!await modal('Publicar encuesta', `<p>Se publicará una copia de la revisión ${form.revision}. Quien responda necesita una cuenta y deberá aceptar participar.</p><label>Audiencia<select id="publish-visibility"><option value="public">Pública</option><option value="followers">Seguidores</option><option value="private">Solo tú</option></select></label><label>Inicio programado (opcional, tu zona horaria)<input id="publish-open" type="datetime-local"></label><label>Fecha y hora de cierre (opcional, tu zona horaria)<input id="publish-close" type="datetime-local"></label><label>Texto para Social<textarea id="publish-caption" maxlength="2000"></textarea></label><label class="choice"><input id="publish-social" type="checkbox" checked>Publicar tarjeta en Social</label>`)) return
    const open = document.getElementById('publish-open').value
    const close = document.getElementById('publish-close').value
    const publicationStorageKey = `novvin-survey-publication:${account.id}:${form.id}:${form.revision}`
    let publicationKey = sessionStorage.getItem(publicationStorageKey)
    if (!publicationKey) { publicationKey = id().replace(/-/g, ''); sessionStorage.setItem(publicationStorageKey, publicationKey) }
    campaign = await request(`/forms/${form.id}/publish`, 'POST', { publicationKey, revision: form.revision, visibility: document.getElementById('publish-visibility').value, opensAt: open ? new Date(open).toISOString() : null, closesAt: close ? new Date(close).toISOString() : null, caption: document.getElementById('publish-caption').value, social: document.getElementById('publish-social').checked })
    await manage(campaign.id); sessionStorage.removeItem(publicationStorageKey); toast('Encuesta publicada.')
  }
  async function asset(id, cId) {
    if (!assets.has(id)) { const a = await request(`/assets/${id}${cId ? `?campaignId=${encodeURIComponent(cId)}` : ''}`); assets.set(id, a) }
    return assets.get(id)
  }
  function hydrateImages() { document.querySelectorAll('[data-asset]').forEach(async img => { try { const a = await asset(img.dataset.asset, survey?.id); if (a.mime.startsWith('image/')) img.src = `data:${a.mime};base64,${a.data}` } catch { img.alt = 'Imagen no disponible' } }) }
  async function upload(file, campaignId) {
    if (!file || file.size > 4194304) throw new Error('Elige un archivo de hasta 4 MB.')
    const data = await new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = reject; reader.readAsDataURL(file) })
    return request('/assets', 'POST', { name: file.name, data, campaignId })
  }
  async function respond(cId, key = '') {
    if (!await guard()) return
    view = 'respond'; preview = false; dirty = false
    survey = await request(`/campaigns/${cId}${key ? `?key=${encodeURIComponent(key)}` : ''}`)
    clockOffset = Date.parse(survey.server_time) - Date.now(); answers = survey.response?.answers || {}; consent = !!survey.response; editing = false; sectionIndex = 0; sectionStack = []
    const stored = sessionStorage.getItem(`novvin-survey-answer:${account.id}:${cId}`)
    if (!survey.response && stored) { try { answers = JSON.parse(stored) } catch { /* ignore interrupted drafts */ } }
    renderRespond()
  }
  function orderedOptions(q) {
    if (!q.randomize) return q.options
    // Stable per account and campaign. Navigation never reshuffles a checked option.
    const seed = `${account?.id}:${survey.id || 'preview'}:${q.id}`
    const hash = v => [...seed + v].reduce((h, c) => Math.imul(h ^ c.charCodeAt(0), 16777619), 2166136261) >>> 0
    return [...q.options].sort((a, b) => hash(a.id) - hash(b.id))
  }
  function questionHtml(q) {
    const v = answers[q.id], required = q.required ? 'required' : '', common = `data-answer="${e(q.id)}"${['text', 'paragraph', 'number', 'date', 'dropdown', 'file'].includes(q.type) ? ` aria-label="${e(q.title)}"` : ''}`, options = orderedOptions(q)
    let control = ''
    if (['text', 'paragraph'].includes(q.type)) control = q.type === 'paragraph' ? `<textarea ${common} maxlength="${q.maxLength || 5000}" ${required}>${e(v)}</textarea>` : `<input ${common} maxlength="${q.maxLength || 500}" value="${e(v)}" ${required}>`
    if (q.type === 'number') control = `<input ${common} type="number" step="${q.measure === 'discrete' ? '1' : 'any'}" value="${e(v)}" ${q.min != null ? `min="${q.min}"` : ''} ${q.max != null ? `max="${q.max}"` : ''} ${required}>`
    if (q.type === 'date') control = `<input ${common} type="date" value="${e(v)}" ${q.minDate ? `min="${e(q.minDate)}"` : ''} ${q.maxDate ? `max="${e(q.maxDate)}"` : ''} ${required}>`
    if (q.type === 'dropdown') control = `<select ${common} ${required}><option value="">Seleccionar</option>${options.map(o => `<option value="${e(o.id)}" ${v === o.id ? 'selected' : ''}>${e(o.label)}</option>`).join('')}</select>`
    if (['single', 'multiple'].includes(q.type)) control = options.map(o => `<label class="choice"><input ${common} type="${q.type === 'multiple' ? 'checkbox' : 'radio'}" name="${e(q.id)}" value="${e(o.id)}" ${q.type === 'multiple' ? v?.includes(o.id) ? 'checked' : '' : v === o.id ? 'checked' : ''} ${q.type === 'single' ? required : ''}>${e(o.label)}${o.image ? `<img data-asset="${e(o.image)}" alt="${e(o.label)}">` : ''}</label>`).join('')
    if (q.type === 'scale') control = Array.from({ length: (q.max ?? 5) - (q.min ?? 1) + 1 }, (_, i) => (q.min ?? 1) + i).map(n => `<label class="choice"><input ${common} type="radio" name="${e(q.id)}" value="${n}" ${v === n ? 'checked' : ''} ${required}>${n}</label>`).join('')
    if (q.type === 'matrix') control = q.rows.map(r => `<label>${e(r.label)}<select ${common} data-row="${e(r.id)}" ${required}><option value="">Seleccionar</option>${options.map(o => `<option value="${e(o.id)}" ${v?.[r.id] === o.id ? 'selected' : ''}>${e(o.label)}</option>`).join('')}</select></label>`).join('')
    if (q.type === 'file') control = `<p class="muted">Hasta 3 imágenes o PDF de 4 MB. Se guardan en privado junto a tu respuesta.</p><input type="file" ${common} accept="image/*,application/pdf" multiple><div class="actions">${(v || []).map(f => button('Ver archivo', 'asset', f) + button('Quitar archivo', 'remove-answer-file', `${q.id}:${f}`, 'danger')).join('')}</div>`
    return `<fieldset class="card question"><legend>${e(q.title)}${q.required ? ' *' : ''}</legend>${q.description ? `<p>${e(q.description)}</p>` : ''}${q.image ? `<img class="question-image" data-asset="${e(q.image)}" alt="${e(q.imageAlt || 'Imagen de la pregunta')}">` : ''}<p class="muted">${q.required ? 'Obligatoria' : 'Opcional'} · ${e(typeLabels[q.type])}</p>${control}</fieldset>`
  }
  function renderRespond() {
    view = 'respond'; const s = survey.schema; applyTheme(s.theme)
    const closed = !preview && survey.status !== 'open', responded = !!survey.response && !editing
    const editable = !closed && Date.parse(survey.edit_until) > Date.now() + clockOffset
    if (survey.response?.removed_at) {
      app.innerHTML = `<div class="card"><h1>Respuesta retirada</h1><p>Tu respuesta fue retirada o cumplió su plazo de conservación. Esta cuenta conserva su participación registrada y no puede enviar otra respuesta.</p>${button('Mis encuestas', 'home')}</div>`; return
    }
    if (responded) {
      app.innerHTML = `<div class="card"><h1>Respuesta enviada</h1><p>Completaste <strong>${e(s.title)}</strong>. Tu respuesta está guardada y solo el creador puede ver el conjunto de respuestas.</p>${s.confirmation ? `<p>${e(s.confirmation)}</p>` : ''}<p class="identity">El creador ve tu cuenta y tus respuestas. Solo se admite una respuesta por cuenta en esta encuesta.</p><p id="edit-countdown">${editable ? `Puedes editar hasta ${date(survey.edit_until)}. El cierre de la encuesta puede terminar ese plazo antes.` : 'El plazo para editar terminó o la encuesta está cerrada.'}</p><div class="actions">${editable ? button('Editar mi respuesta', 'edit-answer', '', 'primary') : ''}${button('Ver mi respuesta', 'my-answer')}${!survey.owner ? button('Reportar encuesta', 'report-survey', survey.id) : ''}${button('Mis encuestas', 'home')}</div></div>`; return
    }
    if (!consent) {
      app.innerHTML = `<div class="card">${s.cover ? `<img class="question-image" data-asset="${e(s.cover)}" alt="${e(s.coverAlt || 'Portada de la encuesta')}">` : ''}<h1>${e(s.title)}</h1><p>${e(s.description)}</p><span class="chip">${survey.response_count || 0} respuestas</span><h2>Antes de participar</h2><p><strong>Propósito:</strong> ${e(s.purpose)}</p><p><strong>Responsable / contacto:</strong> ${e(s.contact)}</p><p><strong>Duración estimada:</strong> ${e(s.estimatedMinutes || 5)} minutos</p><p><strong>Uso y conservación:</strong> ${e(s.retention)}</p><p><strong>Plazo automático:</strong> ${s.retentionDays ? e(s.retentionDays) + ' días desde el envío para respuestas y adjuntos.' : 'El responsable aún debe definir el plazo antes de aceptar respuestas.'} Se conserva un registro mínimo de participación para impedir un segundo envío. Las copias descargadas y los respaldos tienen una conservación separada.</p><p class="identity">Esta encuesta no es anónima. El creador verá tu cuenta, nombre y respuestas. Los demás participantes solo verán la cantidad de respuestas. Tu participación es voluntaria.</p><p>${e(s.consent)}</p><p class="muted">Una respuesta por cuenta. Se puede editar durante 30 minutos desde el primer envío, mientras la encuesta siga abierta. Cierre: ${date(survey.closes_at)}.</p>${closed ? `<p class="error">${survey.status === 'scheduled' ? `La encuesta abre el ${date(survey.opens_at)}.` : 'La encuesta está cerrada. No acepta respuestas.'}</p>` : `<label class="choice"><input id="consent" type="checkbox">Acepto participar y he leído la información anterior.</label>${button(preview ? 'Probar formulario' : 'Comenzar encuesta', 'start-answer', '', 'primary')}`}${!preview && !survey.owner ? button('Reportar encuesta', 'report-survey', survey.id) : ''}${preview ? button('Volver al editor', 'end-preview') : ''}</div>`; hydrateImages(); return
    }
    const section = s.sections[sectionIndex], qs = s.questions.filter(q => q.section === section.id)
    app.innerHTML = `<h1>${e(s.title)}${preview ? ' · Vista previa' : ''}</h1><p class="muted">${editing ? 'Editando la misma respuesta. El plazo original no se reinicia.' : 'Borrador sin enviar.'}</p><div class="progress" aria-label="Progreso por sección"><i style="width:${100 * (sectionIndex + 1) / s.sections.length}%"></i></div><h2>${e(section.title)}</h2><p>${e(section.description)}</p><form id="answer-form">${qs.map(questionHtml).join('')}<div class="actions">${sectionStack.length ? button('Anterior', 'answer-back') : ''}${button(sectionIndex === s.sections.length - 1 || qs.at(-1)?.options.find(o => o.id === answers[qs.at(-1).id])?.next === 'end' ? 'Enviar respuesta' : 'Siguiente', 'answer-next', '', 'primary')}${preview ? button('Volver al editor', 'end-preview') : ''}</div></form><p class="muted">Las preguntas marcadas con * son obligatorias. Al enviar aceptas el consentimiento mostrado al comienzo.</p>`
    if (section.image) document.getElementById('answer-form').insertAdjacentHTML('beforebegin', `<img class="question-image" data-asset="${e(section.image)}" alt="${e(section.imageAlt || 'Imagen de sección')}">`)
    hydrateImages()
  }
  function validateSection() {
    const f = document.getElementById('answer-form'); if (!f.reportValidity()) return false
    for (const q of survey.schema.questions.filter(q => q.section === survey.schema.sections[sectionIndex].id)) {
      const value = answers[q.id]
      if (q.required && (value == null || value === '' || (Array.isArray(value) && !value.length))) { toast(`Completa: ${q.title}`); return false }
      if (q.type === 'multiple' && ((q.min != null && (value || []).length < q.min) || (q.max != null && (value || []).length > q.max))) { toast(`Revisa la cantidad de opciones: ${q.title}`); return false }
    }
    return true
  }
  async function nextAnswer() {
    if (!validateSection()) return
    const s = survey.schema, qs = s.questions.filter(q => q.section === s.sections[sectionIndex].id), last = qs.at(-1), next = last?.options.find(o => o.id === answers[last.id])?.next
    const nextIndex = next && next !== 'end' ? s.sections.findIndex(a => a.id === next) : sectionIndex + 1
    if (next !== 'end' && nextIndex < s.sections.length) { sectionStack.push(sectionIndex); sectionIndex = nextIndex; renderRespond(); window.scrollTo(0, 0); return }
    if (preview) { toast('Vista previa completada. No se guardaron respuestas.'); consent = false; answers = {}; renderEditor(); return }
    if (!await modal(editing ? 'Guardar cambios en tu respuesta' : 'Enviar respuesta', '<p>Revisa que tus datos sean correctos. Se guardará una sola respuesta para esta cuenta.</p>')) return
    const result = await request(`/campaigns/${survey.id}/responses`, 'POST', { answers, consent: true, revision: survey.revision, ...(editing ? { editRevision: survey.response.edit_revision } : {}) })
    survey.response = result; survey.edit_until = result.edit_until; editing = false
    sessionStorage.removeItem(`novvin-survey-answer:${account.id}:${survey.id}`); renderRespond(); toast(result.duplicate ? 'Esta cuenta ya tenía una respuesta. Puedes consultarla o editarla dentro del plazo.' : 'Respuesta guardada.')
  }
  async function manage(cId) {
    if (!await guard()) return
    if (campaign?.id !== cId) resultFilters = { from: '', to: '', search: '' }
    campaign = (await request('/campaigns')).campaigns.find(c => c.id === cId)
    if (!campaign) throw new Error('Encuesta no disponible.')
    view = 'manage'; dirty = false; renderManage()
  }
  function renderManage() {
    const c = campaign, open = c.status === 'open' && (!c.closes_at || Date.parse(c.closes_at) > Date.now())
    app.innerHTML = `<h1>${e(c.schema.title)}</h1><p class="muted">Encuesta publicada · revisión ${c.revision} · ${c.response_count} respuestas · ${open ? 'Abierta' : 'Cerrada / archivada'}</p><p>${date(c.closes_at)}</p><div class="tabs">${button('Resumen y gráficos', 'results', c.id, 'primary')}${button('Respuestas individuales', 'individuals', c.id)}${button('Relaciones entre variables', 'relations', c.id)}${button('Descargar', 'exports', c.id)}${button('Compartir / QR', 'share', c.id)}${button('Historial', 'history', c.id)}${button('Ver formulario', 'respond', c.id)}</div><div class="card"><h2>Administrar participación</h2><p>Al reabrir se conservan las respuestas. Cada cuenta mantiene su única respuesta y el plazo de edición original.</p><div class="actions">${c.status === 'trashed' ? button('Restaurar cerrada', 'campaign-restore', c.id) + button('Eliminar encuesta y respuestas definitivamente', 'campaign-purge', c.id, 'danger') : open ? button('Cerrar ahora', 'campaign-close', c.id) + button('Programar / cambiar cierre', 'campaign-schedule', c.id) : button('Reabrir', 'campaign-reopen', c.id)}${c.status !== 'trashed' ? button('Archivar y retirar de Social', 'campaign-archive', c.id) + button('Enviar a papelera', 'campaign-trash', c.id) : ''}${c.post_exists ? button('Retirar publicación de Social', 'campaign-unpublish', c.id) : ''}</div><p class="muted">Retirar una publicación conserva la encuesta y sus respuestas en esta cuenta. La eliminación definitiva sí borra los datos y adjuntos de participación.</p></div><section id="result-panel"></section>`
    if (c.status !== 'trashed') app.querySelector('.card .actions')?.insertAdjacentHTML('beforeend', button('Cambiar audiencia', 'campaign-audience', c.id))
    if (!c.post_exists && !c.moderation_blocked && !['archived','trashed'].includes(c.status)) app.querySelector('.card .actions')?.insertAdjacentHTML('beforeend',button('Publicar de nuevo en Social','campaign-republish',c.id))
    if (c.moderation_blocked) app.querySelector('.card')?.insertAdjacentHTML('afterbegin', `<p class="error">Restringida por moderación: ${e(c.moderation_note)}</p>`)
    app.querySelector('h1 + p').textContent = `Encuesta publicada · revisión ${c.revision} · ${c.response_count} respuestas · ${campaignStatus(c)}`
    app.querySelector('.tabs')?.insertAdjacentHTML('beforeend', button('Diccionario de variables', 'dictionary', c.id))
    app.querySelector('#result-panel').insertAdjacentHTML('beforebegin', `<div class="card"><h2>Filtrar resultados</h2><p class="muted">Los filtros se aplican a gráficos, respuestas individuales, relaciones y descargas.</p><label>Desde<input id="filter-from" type="datetime-local" value="${e(resultFilters.from)}"></label><label>Hasta<input id="filter-to" type="datetime-local" value="${e(resultFilters.to)}"></label><label>Nombre o ID de participante<input id="filter-search" value="${e(resultFilters.search)}" maxlength="160"></label><div class="actions">${button('Aplicar filtros', 'apply-result-filters', '', 'primary')}${button('Limpiar filtros', 'clear-result-filters')}</div></div>`)
  }
  function resultQuery() {
    const query = new URLSearchParams()
    if (resultFilters.from) query.set('from', new Date(resultFilters.from).toISOString())
    if (resultFilters.to) query.set('to', new Date(resultFilters.to).toISOString())
    if (resultFilters.search) query.set('search', resultFilters.search)
    return query.toString()
  }
  function exportFilters() { return { from: resultFilters.from ? new Date(resultFilters.from).toISOString() : null, to: resultFilters.to ? new Date(resultFilters.to).toISOString() : null, search: resultFilters.search } }
  function dictionaryForm() {
    const current = campaign.dictionary || {}
    document.getElementById('result-panel').innerHTML = `<div class="card"><h2>Diccionario de variables</h2><p class="muted">Estos metadatos describen tu investigación y pueden cambiar sin modificar respuestas ni preguntas publicadas. Su revisión se incluye en las exportaciones.</p>${campaign.schema.questions.map(q => {
      const d = current[q.id] || {}
      return `<fieldset class="card" data-dictionary="${e(q.id)}"><legend>${e(q.title)}</legend>${select('Rol', 'role', d.role || q.role, roles)}${input('Unidad (si aplica)', 'unit', d.unit || q.unit || '', 'text', 'maxlength="60"')}${area('Hipótesis / nota metodológica', 'hypothesis', d.hypothesis || q.hypothesis || '', 'maxlength="1500"')}</fieldset>`
    }).join('')}${button('Guardar diccionario', 'save-dictionary', '', 'primary')}</div>`
  }
  const statCards = q => q.descriptive ? `<div class="stats">${[['n', 'n'], ['Media', 'mean'], ['Mediana', 'median'], ['DE muestral', 'sd'], ['Q1', 'q1'], ['Q3', 'q3'], ['RIC', 'iqr'], ['Mínimo', 'min'], ['Máximo', 'max']].map(([label, k]) => `<div class="stat"><small>${label}</small><strong>${num(q.descriptive[k])}</strong></div>`).join('')}<div class="stat"><small>Modas</small><strong>${q.descriptive.modes.map(num).join(', ')}</strong></div></div>` : ''
  function bars(entries, style) {
    const max = Math.max(1, ...entries.map(f => f.absolute))
    return entries.map(f => `<div class="bar"><span>${e(f.label)}</span><div class="track"><i style="width:${Math.max(0, f.absolute / max * 100)}%;${style === 'sticks' ? 'height:5px;border-radius:0' : ''}"></i></div><span>${num(f.absolute)}${f.relative != null ? ` · ${num(f.relative * 100)}%` : ''}</span></div>`).join('')
  }
  function boxplot(d) {
    if (!d) return '<p>No hay datos cuantitativos.</p>'
    const x = v => 35 + 620 * (v - d.min) / (d.max - d.min || 1)
    return `<svg class="chart" viewBox="0 0 700 180" role="img" aria-label="Diagrama de caja: Q1 ${d.q1}, mediana ${d.median}, Q3 ${d.q3}"><line x1="${x(d.whiskerMin)}" y1="75" x2="${x(d.whiskerMax)}" y2="75" stroke="currentColor"/><rect x="${x(d.q1)}" y="45" width="${Math.max(2, x(d.q3) - x(d.q1))}" height="60" fill="var(--accent)" opacity=".6"/><line x1="${x(d.median)}" y1="45" x2="${x(d.median)}" y2="105" stroke="currentColor" stroke-width="3"/>${[d.whiskerMin, d.whiskerMax].map(v => `<line x1="${x(v)}" y1="55" x2="${x(v)}" y2="95" stroke="currentColor"/>`).join('')}${d.outliers.map(v => `<circle cx="${x(v)}" cy="75" r="4" fill="currentColor"/>`).join('')}<text x="35" y="145" fill="currentColor">${e(num(d.min))}</text><text x="600" y="145" fill="currentColor">${e(num(d.max))}</text></svg><small>Cuartiles con interpolación lineal; bigotes hasta los últimos valores dentro de 1,5 × RIC. Los atípicos se muestran y no se eliminan.</small>`
  }
  function quantitativeChart(q,kind) {
    const source=kind==='histogram'?q.histogram:q.frequencies, entries=source.slice(0,40)
    if (!entries.length) return '<p>No hay valores numéricos.</p>'
    const low=kind==='histogram'?entries[0].low:Number(entries[0].value), high=kind==='histogram'?entries.at(-1).high:Number(entries.at(-1).value)
    const max=Math.max(1,...entries.map(v=>v.absolute)), x=v=>high===low?370:55+610*(v-low)/(high-low)
    const shapes=entries.map(v=>{const y=260-200*v.absolute/max;return kind==='histogram'?`<rect x="${x(v.low)}" y="${y}" width="${Math.max(1,x(v.high)-x(v.low))}" height="${260-y}" fill="var(--accent)" stroke="var(--bg)" stroke-width=".5"/>`:`<line x1="${x(Number(v.value))}" y1="260" x2="${x(Number(v.value))}" y2="${y}" stroke="var(--accent)" stroke-width="2"/><circle cx="${x(Number(v.value))}" cy="${y}" r="3" fill="var(--accent)"/>`}).join('')
    const labels=Array.from({length:5},(_,i)=>`<text x="${55+610*i/4}" y="285" text-anchor="middle" fill="currentColor">${e(num(low+(high-low)*i/4))}</text>`).join('')
    return `<svg class="chart" viewBox="0 0 700 315" role="img" aria-label="${kind==='histogram'?'Histograma con intervalos contiguos':'Diagrama de bastones'}; frecuencia absoluta, consulta la tabla"><path d="M55 50 V260 H665" fill="none" stroke="currentColor"/><text x="12" y="50" fill="currentColor">${max}</text><text x="20" y="260" fill="currentColor">0</text>${shapes}${labels}</svg><small>Frecuencia absoluta. ${kind==='histogram'?'El último intervalo incluye el extremo superior.':'Valores numéricos.'}${source.length>40?` Se muestran los primeros 40 de ${source.length} valores; la tabla y el archivo de datos contienen todos.`:''}</small>`
  }
  function chart(q, kind) {
    if (!q.answered) return '<p>No hay respuestas analizables para este gráfico con los filtros actuales.</p>'
    if (kind === 'box') return boxplot(q.descriptive)
    if (kind === 'histogram' || kind === 'sticks') return quantitativeChart(q,kind)
    if (kind === 'pie') {
      if (q.type === 'multiple' || q.type === 'matrix') return '<p>El gráfico de pastel requiere categorías mutuamente excluyentes.</p>'
      if (q.frequencies.filter(f => f.absolute > 0).length > 20) return '<p>Hay demasiadas categorías para un gráfico de pastel legible. Elige barras.</p>'
      let sum = 0; const colors = ['#8064d4', '#398fbb', '#2d9670', '#dc9950', '#c65d87', '#777ab8'], parts = q.frequencies.map((f, i) => { const start = sum; sum += f.relative * 100; return `${colors[i % colors.length]} ${start}% ${sum}%` })
      return `<div class="pie" role="img" aria-label="Distribución por categoría; consulta la tabla de frecuencias debajo" style="background:conic-gradient(${parts.length ? parts.join(',') : '#eee 0% 100%'})"></div>${bars(q.frequencies)}`
    }
    return bars(q.frequencies, kind)
  }
  async function results() {
    stats = await request(`/campaigns/${campaign.id}/results?page=${page}&${resultQuery()}`)
    const panel = document.getElementById('result-panel')
    panel.innerHTML = `<div class="card"><h2>Resumen privado</h2><div class="stat"><small>Respuestas recibidas</small><strong>${stats.total}</strong></div><p class="muted">${e(stats.caveat)}</p><h3>Participación por día (UTC)</h3>${bars(stats.timeline.map(t => ({ label: t.date, absolute: t.count })))}</div>${stats.questions.map(q => `<article class="card"><h2>${e(q.title)}</h2><p class="muted">${e(measures[q.measure])} · ${e(roles[q.role])} · Respondidas ${q.answered} · Sin respuesta ${q.missing} · No aplicables ${q.notApplicable}</p>${statCards(q)}${select('Gráfico', `chart:${q.id}`, q.descriptive && q.measure === 'continuous' ? 'histogram' : 'bars', { bars: 'Barras', ...(['single', 'dropdown', 'scale'].includes(q.type) ? { pie: 'Pastel' } : {}), ...(q.descriptive ? { sticks: 'Bastones', histogram: 'Histograma', box: 'Caja y bigotes' } : {}) })}<div id="chart-${e(q.id)}">${chart(q, q.descriptive && q.measure === 'continuous' ? 'histogram' : 'bars')}</div><details><summary>Tabla de frecuencias</summary><div class="scroll"><table><thead><tr><th>Valor</th><th>Absoluta</th><th>Relativa</th><th>%</th><th>Acumulada</th></tr></thead><tbody>${q.frequencies.map(f => `<tr><td>${e(f.label)}</td><td>${f.absolute}</td><td>${num(f.relative)}</td><td>${num(f.relative * 100)}</td><td>${num(f.cumulative)}</td></tr>`).join('')}</tbody></table></div></details>${q.type === 'multiple' ? '<small>Porcentajes sobre cuentas que respondieron; pueden sumar más de 100 %.</small>' : ''}${['text', 'paragraph', 'file'].includes(q.type) ? '<small>Consulta el contenido en Respuestas individuales. Los textos libres no se interpretan como variables numéricas.</small>' : ''}</article>`).join('')}`
    panel.querySelectorAll('article.card').forEach((card, i) => {
      const q = stats.questions[i]
      if (q.measure === 'ordinal') card.insertAdjacentHTML('beforeend', `<p class="muted">${q.treatAsInterval ? 'El investigador decidió tratar los puntos como intervalares. Media, desviación y Pearson dependen de ese supuesto.' : 'Escala ordinal: se muestran mediana, cuartiles y frecuencias. No se calcula media ni desviación estándar; las relaciones usan Spearman.'}</p>`)
      if (q.answered && (q.frequencies.length || q.histogram.length)) card.insertAdjacentHTML('beforeend', `<div class="actions">${button('Descargar gráfico PNG', 'download-chart-png', q.id)}${button('Descargar gráfico SVG', 'download-chart-svg', q.id)}</div>`)
    })
  }
  function display(q, v) {
    if (v == null) return 'Sin respuesta'
    if (q.type === 'file') return v.map(a => button('Descargar adjunto', 'asset', a)).join('')
    if (Array.isArray(v)) return v.map(a => e(q.options.find(o => o.id === a)?.label || a)).join(', ')
    if (typeof v === 'object') return q.rows.map(r => `${e(r.label)}: ${e(q.options.find(o => o.id === v[r.id])?.label || 'Sin respuesta')}`).join('<br>')
    return e(q.options.find(o => o.id === v)?.label || v)
  }
  async function individuals() {
    stats = await request(`/campaigns/${campaign.id}/results?page=${page}&${resultQuery()}`)
    document.getElementById('result-panel').innerHTML = `<h2>Respuestas individuales · privadas</h2><p class="identity">Estos datos identifican a los participantes. Utilízalos según el consentimiento y el propósito declarados.</p>${stats.responses.map(r => `<details class="card"><summary>${e(r.display_name || 'Cuenta')} · ${date(r.submitted_at)}</summary><small>Cuenta: ${e(r.account_id)} · Actualizada ${date(r.updated_at)} · Revisión ${r.edit_revision}</small>${campaign.schema.questions.map(q => `<h3>${e(q.title)}</h3><p>${Object.hasOwn(r.answers, q.id) ? display(q, r.answers[q.id]) : 'No aplicable por salto'}</p>`).join('')}</details>`).join('') || '<p>No hay respuestas.</p>'}<div class="actions">${page ? button('Anterior', 'responses-prev') : ''}<span>Página ${page + 1} de ${Math.max(1, stats.pages)}</span>${page + 1 < stats.pages ? button('Siguiente', 'responses-next') : ''}</div>`
    document.querySelectorAll('#result-panel details.card').forEach((details, i) => details.insertAdjacentHTML('beforeend', button('Eliminar esta respuesta y sus adjuntos', 'remove-response', stats.responses[i].id, 'danger')))
  }
  function relationsForm() {
    const eligible = campaign.schema.questions.filter(q => ['single', 'dropdown', 'number', 'scale', 'date'].includes(q.type))
    const options = eligible.map(q => `<option value="${e(q.id)}">${e(q.title)} · ${e(roles[q.role])}</option>`).join('')
    document.getElementById('result-panel').innerHTML = `<div class="card"><h2>Relaciones entre variables</h2><p>Los roles independiente y dependiente son etiquetas del diseño de la investigación. Una correlación no demuestra causalidad.</p><div class="grid"><label>Variable X<select id="relation-x">${options}</select></label><label>Variable Y<select id="relation-y">${options}</select></label></div>${button('Analizar pares completos', 'calculate-relation', '', 'primary')}<div id="relation-result"></div></div>`
  }
  async function calculateRelation() {
    const x = document.getElementById('relation-x').value, y = document.getElementById('relation-y').value, r = await request(`/campaigns/${campaign.id}/relations?x=${encodeURIComponent(x)}&y=${encodeURIComponent(y)}&${resultQuery()}`)
    let body
    if (r.kind === 'scatter') {
      const xs = r.pairs.map(p => p[0]), ys = r.pairs.map(p => p[1]), minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
      body = `<p>Pares completos: ${r.n} · Correlación de ${r.method === 'spearman' ? 'Spearman' : 'Pearson'}: ${num(r.coefficient)}</p><svg class="chart" viewBox="0 0 700 250" role="img" aria-label="Diagrama de dispersión"><path d="M40 15 V210 H680" fill="none" stroke="currentColor"/>${r.pairs.slice(0, 3000).map(p => `<circle cx="${45 + (p[0] - minX) / (maxX - minX || 1) * 620}" cy="${205 - (p[1] - minY) / (maxY - minY || 1) * 180}" r="3" fill="var(--accent)" opacity=".6"/>`).join('')}</svg><small>Se dibujan hasta 3.000 pares; el cálculo usa todos los pares completos.</small>`
    } else {
      const label = (qId, val) => e(campaign.schema.questions.find(q => q.id === qId)?.options.find(o => o.id === val)?.label || val)
      body = `<p>Pares completos: ${r.n} · Chi cuadrado descriptivo: ${num(r.chiSquared)} · gl: ${r.degreesOfFreedom}</p><p>${r.assumptionsMet ? 'Todas las frecuencias esperadas son ≥ 5. Debes revisar la independencia del muestreo.' : 'No se cumplen las frecuencias esperadas mínimas para un contraste estándar.'}</p><div class="scroll"><table><thead><tr><th>X / Y</th>${r.columns.map(c => `<th>${label(y, c)}</th>`).join('')}<th>Total</th></tr></thead><tbody>${r.rows.map((row, i) => `<tr><td>${label(x, row)}</td>${r.cells[i].map((n, j) => `<td>${n}<small> · Esperada ${num(r.expected[i][j])}</small></td>`).join('')}<td>${r.rowTotals[i]}</td></tr>`).join('')}</tbody></table></div>`
    }
    document.getElementById('relation-result').innerHTML = body + `<p class="muted">${e(r.note)}</p>`
  }
  function exportForm() {
    document.getElementById('result-panel').innerHTML = `<div class="card"><h2>Descargas privadas</h2><p>Excel incluye datos, diccionario, frecuencias, descriptivos, participación, gráficos y metadatos. Word y PDF incluyen un informe organizado o el instrumento en blanco.</p><label>Formato<select id="export-format"><option value="xlsx">Excel (.xlsx)</option><option value="csv">CSV</option><option value="docx">Word (.docx)</option><option value="pdf">PDF</option></select></label><label class="choice"><input id="export-instrument" type="checkbox">Instrumento en blanco (Word o PDF)</label><label class="choice"><input id="export-identity" type="checkbox">Incluir cuenta y nombre de los participantes</label><p class="muted">Sin esta opción se omiten cuenta y nombre de la descarga. Las respuestas pueden contener datos personales escritos por los participantes.</p>${button('Generar y descargar', 'download-export', '', 'primary')}</div>`
    const eligible = campaign.schema.questions.filter(q => ['single', 'dropdown', 'number', 'scale', 'date'].includes(q.type))
    const options = `<option value="">Sin relación adicional</option>${eligible.map(q => `<option value="${e(q.id)}">${e(q.title)}</option>`).join('')}`
    document.querySelector('#result-panel .card').insertAdjacentHTML('beforeend', `<details><summary>Incluir relación entre dos variables</summary><p class="muted">El informe describe asociaciones; no infiere causalidad.</p><label>Variable X<select id="export-x">${options}</select></label><label>Variable Y<select id="export-y">${options}</select></label></details>`)
  }
  async function share() {
    const { link } = await request(`/campaigns/${campaign.id}/qr`, 'POST', { format: 'link' })
    document.getElementById('result-panel').innerHTML = `<div class="card"><h2>Compartir encuesta</h2><p>El enlace y el QR abren la app Novvin en Android. La persona necesita tenerla instalada e iniciar sesión. Se respeta la audiencia: ${e(campaign.visibility)}.</p><label>Enlace<input id="share-link" readonly value="${e(link)}"></label><div class="actions">${button('Copiar enlace', 'copy-link')}${button('Compartir enlace', 'share-link')}<select id="qr-format" aria-label="Formato QR"><option value="png">QR en PNG</option><option value="svg">QR en SVG</option><option value="pdf">QR para imprimir en PDF</option><option value="docx">QR para imprimir en Word</option></select>${button('Descargar QR', 'download-qr')}${button('Regenerar enlace y QR', 'campaign-rotate-link', campaign.id)}</div><p class="muted">Al regenerar, los enlaces anteriores dejan de funcionar. La encuesta y sus respuestas se conservan.</p></div>`
  }
  async function stateAction(action, target) {
    if (action.startsWith('form-')) {
      const row = allForms.find(f => f.id === target), operation = action.slice(5)
      const content = operation === 'purge' ? `<p>Esto elimina esta planilla y su historial. Las encuestas publicadas conservan sus preguntas y respuestas.</p><label>Escribe el título para confirmar<input id="confirm-title" placeholder="${e(row.schema.title)}"></label>` : '<p>La acción cambia el estado privado de esta planilla. Las encuestas publicadas se conservan.</p>'
      if (!await modal('Cambiar estado de planilla', content)) return
      await request(`/forms/${target}/state`, 'POST', { action: operation, confirm: document.getElementById('confirm-title')?.value }); await home(); return
    }
    const operation = action.slice(9)
    let body = '<p>Se conservarán las respuestas recibidas y la restricción de una respuesta por cuenta.</p>'
    if (operation === 'audience') body = `<p>La nueva audiencia se aplicará al formulario y a su publicación en Social.</p>${select('Audiencia', 'unused', campaign.visibility, { public: 'Pública', followers: 'Seguidores', private: 'Solo tú' }).replace('data-field="unused"', 'id="new-visibility"')}`
    if (['reopen', 'schedule'].includes(operation)) body += '<label>Nueva fecha y hora de cierre (opcional)<input id="new-close" type="datetime-local"></label>'
    if (operation === 'purge') body = `<p>Se eliminarán definitivamente la encuesta, respuestas, adjuntos y publicación. Esta acción no se puede deshacer.</p><label>Escribe el título para confirmar<input id="confirm-title" placeholder="${e(campaign.schema.title)}"></label>`
    if (!await modal({ close: 'Cerrar encuesta', reopen: 'Reabrir encuesta', schedule: 'Programar cierre', archive: 'Archivar encuesta', trash: 'Enviar a papelera', purge: 'Eliminar definitivamente', 'rotate-link': 'Regenerar enlace', unpublish: 'Retirar de Social', restore: 'Restaurar encuesta', audience: 'Cambiar audiencia' }[operation], body)) return
    const close = document.getElementById('new-close')?.value
    await request(`/campaigns/${target}/state`, 'POST', { action: operation, visibility: document.getElementById('new-visibility')?.value, closesAt: close ? new Date(close).toISOString() : null, confirm: document.getElementById('confirm-title')?.value })
    if (operation === 'purge') await home(); else { await manage(target); if (operation === 'rotate-link') await share() }
  }
  async function action(a, target) {
    if (a === 'report-survey') return reportSurvey()
    if (a === 'review-reports') return reviewReports()
    if (a === 'review-instrument') return reviewInstrument(target)
    if (a.startsWith('moderation-')) return moderate(target, a.slice('moderation-'.length))
    if (a === 'retry') return boot()
    if (a === 'home') return home()
    if (a === 'new') { if (await guard()) newForm(); return }
    if (a === 'filter') { filter = target; renderHome(); return }
    if (a === 'draft') { form = JSON.parse(localStorage.getItem(`novvin-survey-form:${account.id}`)); rememberedSchema = structuredClone(form.schema); undoStack = []; redoStack = []; dirty = true; renderEditor(); return }
    if (a === 'edit' || a === 'duplicate') {
      if (!await guard()) return
      form = await request(`/forms/${target}`)
      rememberedSchema = structuredClone(form.schema); undoStack = []; redoStack = []
      if (a === 'duplicate') { form = { schema: structuredClone(form.schema) }; form.schema.title += ' (copia)'; dirty = true; markDirty() } else dirty = false
      renderEditor(); return
    }
    if (a === 'save') return save()
    if (a === 'undo' || a === 'redo') {
      const source = a === 'undo' ? undoStack : redoStack, destination = a === 'undo' ? redoStack : undoStack
      if (!source.length) return
      destination.push(structuredClone(form.schema)); form.schema = source.pop(); rememberedSchema = structuredClone(form.schema)
      dirty = true; localStorage.setItem(`novvin-survey-form:${account.id}`, JSON.stringify(form)); renderEditor(); return
    }
    if (a === 'import-questions') {
      const sources = (await request('/forms')).forms.filter(source => source.id !== form.id && !source.trashed_at)
      if (!sources.length) throw new Error('Guarda otra planilla para poder importar sus preguntas.')
      if (!await modal('Importar preguntas', `<p>Se copiarán todas las preguntas de la planilla elegida a la primera sección. Las respuestas, las publicaciones y los saltos condicionales no se copiarán.</p><label>Planilla<select id="import-source">${sources.map(source => `<option value="${e(source.id)}">${e(source.schema.title)} · revisión ${source.revision}</option>`).join('')}</select></label>`)) return
      const source = sources.find(item => item.id === document.getElementById('import-source').value)
      if (form.schema.questions.length + source.schema.questions.length > 100) throw new Error('Una planilla admite hasta 100 preguntas.')
      form.schema.questions.push(...source.schema.questions.map(question => ({ ...structuredClone(question), id: id(), section: form.schema.sections[0].id,
        options: question.options.map(option => ({ ...structuredClone(option), id: id(), next: null })), rows: question.rows.map(row => ({ ...structuredClone(row), id: id() })) })))
      markDirty(); renderEditor(); toast('Preguntas importadas a la planilla en edición.'); return
    }
    if (a === 'publish') return publish()
    if (a === 'add-question') { addQuestion(); renderEditor(); return }
    if (a === 'add-section') { form.schema.sections.push({ id: id(), title: `Sección ${form.schema.sections.length + 1}`, description: '' }); markDirty(); renderEditor(); return }
    if (a === 'remove-cover') { form.schema.cover = null; markDirty(); renderEditor(); return }
    if (a === 'remove-section-image') { form.schema.sections.find(s => s.id === target).image = null; markDirty(); renderEditor(); return }
    if (a === 'remove-section') {
      form.schema.questions.filter(q => q.section === target).forEach(q => q.section = form.schema.sections[0].id === target ? form.schema.sections[1].id : form.schema.sections[0].id)
      form.schema.questions.forEach(q => q.options.forEach(o => { if (o.next === target) o.next = null }))
      form.schema.sections = form.schema.sections.filter(s => s.id !== target); markDirty(); renderEditor(); return
    }
    if (['remove-question', 'duplicate-question', 'question-up', 'question-down', 'add-option', 'remove-option', 'add-row', 'remove-row', 'remove-image', 'remove-option-image'].includes(a)) {
      const [qid, oid] = target.split(':'), i = form.schema.questions.findIndex(q => q.id === qid), q = form.schema.questions[i]
      if (a === 'remove-question') { if (!await modal('Eliminar pregunta', '<p>Se quitará esta pregunta de la planilla en edición.</p>')) return; form.schema.questions.splice(i, 1) }
      if (a === 'duplicate-question') { const copy = structuredClone(q); copy.id = id(); copy.options = copy.options.map(o => ({ ...o, id: id() })); copy.rows = copy.rows.map(r => ({ ...r, id: id() })); form.schema.questions.splice(i + 1, 0, copy) }
      if (a === 'question-up' && i > 0) [form.schema.questions[i], form.schema.questions[i - 1]] = [form.schema.questions[i - 1], q]
      if (a === 'question-down' && i < form.schema.questions.length - 1) [form.schema.questions[i], form.schema.questions[i + 1]] = [form.schema.questions[i + 1], q]
      if (a === 'add-option') q.options.push({ id: id(), label: `Opción ${q.options.length + 1}` })
      if (a === 'remove-option') q.options = q.options.filter(o => o.id !== oid)
      if (a === 'add-row') q.rows.push({ id: id(), label: `Fila ${q.rows.length + 1}` })
      if (a === 'remove-row') q.rows = q.rows.filter(r => r.id !== oid)
      if (a === 'remove-image') q.image = null
      if (a === 'remove-option-image') q.options.find(o => o.id === oid).image = null
      markDirty(); renderEditor(); return
    }
      if (a === 'preview') { preview = true; survey = { schema: structuredClone(form.schema), status: 'open', response_count: 0 }; answers = {}; consent = false; sectionIndex = 0; sectionStack = []; renderRespond(); return }
    if (a === 'end-preview') { preview = false; renderEditor(); return }
    if (a === 'respond') return respond(target)
    if (a === 'start-answer') { if (!document.getElementById('consent').checked) { toast('Marca que aceptas participar.'); return } consent = true; renderRespond(); return }
    if (a === 'answer-back') { sectionIndex = sectionStack.pop(); renderRespond(); return }
    if (a === 'answer-next') return nextAnswer()
    if (a === 'edit-answer') { if (Date.parse(survey.edit_until) <= Date.now() + clockOffset) throw new Error('Terminó el plazo para editar.'); editing = true; sectionIndex = 0; sectionStack = []; renderRespond(); return }
    if (a === 'my-answer') { app.innerHTML = `<h1>Mi respuesta</h1>${survey.schema.questions.map(q => `<article class="card"><h2>${e(q.title)}</h2><div>${Object.hasOwn(survey.response.answers, q.id) ? display(q, survey.response.answers[q.id]) : 'No aplicable por salto'}</div></article>`).join('')}${button('Volver a la confirmación', 'answer-confirmation')}`; return }
    if (a === 'answer-confirmation') { renderRespond(); return }
    if (a === 'remove-answer-file') { const [qid, aid] = target.split(':'); answers[qid] = answers[qid].filter(v => v !== aid); renderRespond(); return }
    if (a === 'asset') { download(await asset(target, survey?.id)); return }
    if (a === 'manage') { page = 0; return manage(target) }
    if (a === 'apply-result-filters') { resultFilters = { from: document.getElementById('filter-from').value, to: document.getElementById('filter-to').value, search: document.getElementById('filter-search').value.trim() }; page = 0; return results() }
    if (a === 'clear-result-filters') { resultFilters = { from: '', to: '', search: '' }; page = 0; renderManage(); return results() }
    if (a === 'results') return results()
    if (a === 'individuals') { page = 0; return individuals() }
    if (a === 'responses-prev' || a === 'responses-next') { page += a === 'responses-next' ? 1 : -1; return individuals() }
    if (a === 'remove-response') {
      if (!await modal('Eliminar respuesta', '<p>Se borrarán la respuesta y sus archivos. La cuenta seguirá marcada como participante y no podrá enviar otra respuesta a esta encuesta.</p>')) return
      await request(`/campaigns/${campaign.id}/responses/${target}`, 'DELETE', { confirm: 'ELIMINAR' })
      await individuals(); toast('Respuesta eliminada.'); return
    }
    if (a === 'relations') return relationsForm()
    if (a === 'dictionary') return dictionaryForm()
    if (a === 'save-dictionary') {
      const dictionary = Object.fromEntries([...document.querySelectorAll('[data-dictionary]')].map(fieldset => [fieldset.dataset.dictionary, {
        role: fieldset.querySelector('[data-field="role"]').value,
        unit: fieldset.querySelector('[data-field="unit"]').value.trim(),
        hypothesis: fieldset.querySelector('[data-field="hypothesis"]').value.trim()
      }]))
      campaign = await request(`/campaigns/${campaign.id}/dictionary`, 'PUT', { revision: campaign.dictionary_revision, dictionary })
      dictionaryForm(); toast('Diccionario guardado en una nueva revisión.'); return
    }
    if (a === 'calculate-relation') return calculateRelation()
    if (a === 'exports') return exportForm()
    if (a === 'download-export') { const format = document.getElementById('export-format').value, instrument = document.getElementById('export-instrument').checked, x = document.getElementById('export-x').value, y = document.getElementById('export-y').value; if (instrument && !['pdf', 'docx'].includes(format)) throw new Error('El instrumento en blanco se descarga en Word o PDF.'); if (!!x !== !!y || (x && x === y)) throw new Error('Elige dos variables diferentes o deja ambas sin seleccionar.'); if (format === 'csv' && x) throw new Error('La relación entre variables se incluye en Excel, Word o PDF.'); download(await request(`/campaigns/${campaign.id}/export`, 'POST', { format, instrument, identity: document.getElementById('export-identity').checked, filters: exportFilters(), x, y })); return }
    if (a === 'download-chart-png' || a === 'download-chart-svg') { const kind = [...document.querySelectorAll('[data-field]')].find(el => el.dataset.field === `chart:${target}`)?.value; download(await request(`/campaigns/${campaign.id}/charts/${target}/export`, 'POST', { format: a.endsWith('png') ? 'png' : 'svg', kind, filters: exportFilters() })); return }
    if (a === 'share') return share()
    if (a === 'copy-link') { const i = document.getElementById('share-link'); if (window.NovvinSurveys) window.NovvinSurveys.copy(i.value); else { i.select(); document.execCommand('copy') }; return }
    if (a === 'share-link') { if (window.NovvinSurveys) window.NovvinSurveys.share(document.getElementById('share-link').value); return }
    if (a === 'download-qr') { download(await request(`/campaigns/${campaign.id}/qr`, 'POST', { format: document.getElementById('qr-format').value })); return }
    if (a === 'history') { const h = await request(`/campaigns/${campaign.id}/history`); document.getElementById('result-panel').innerHTML = `<div class="card"><h2>Historial</h2>${h.events.map(v => `<p>${date(v.created_at)} · ${e(v.action)}</p>`).join('')}</div>`; return }
    if (a === 'revisions') {
      const h = await request(`/forms/${target}/revisions`)
      if (!await modal('Revisiones guardadas', `<p>Elige la revisión que deseas recuperar como planilla nueva.</p><label>Revisión<select id="restore-revision">${h.revisions.map(r => `<option value="${r.revision}">Revisión ${r.revision} · ${date(r.created_at)}</option>`).join('')}</select></label>`)) return
      const revision = Number(document.getElementById('restore-revision').value)
      form = { schema: structuredClone(h.revisions.find(r => r.revision === revision).schema) }; form.schema.title += ' (recuperada)'
      rememberedSchema = null; undoStack = []; redoStack = []; dirty = true; markDirty(); renderEditor(); return
    }
    if (a.startsWith('form-') || a.startsWith('campaign-')) return stateAction(a, target)
  }
  document.addEventListener('click', async event => {
    const b = event.target.closest('[data-action]'); if (!b || busy) return
    try { setBusy(true); b.disabled = true; await action(b.dataset.action, b.dataset.id) }
    catch (error) { toast(error.message || 'No se pudo completar la operación.') }
    finally { setBusy(false); if (b.isConnected) b.disabled = false }
  })
  document.getElementById('home').addEventListener('click', async () => {
    if (busy) return
    try { setBusy(true); await home() } catch (error) { toast(error.message) } finally { setBusy(false) }
  })
  window.surveyBack = async () => {
    try {
      const dialog = document.getElementById('modal')
      if (dialog.open) { dialog.close('cancel'); return }
      if (busy) { toast('Espera a que termine la operación.'); return }
      if (preview) { preview = false; renderEditor(); return }
      if (!await guard()) return
      if (view === 'home') { if (window.NovvinSurveys) window.NovvinSurveys.close() }
      else { dirty = false; await home() }
    } catch (error) { toast(error.message || 'No se pudo volver.'); }
  }
  document.addEventListener('input', event => {
    if (event.target.id === 'library-search') { librarySearch = event.target.value; filterLibrary(); return }
    const field = event.target.dataset.field
    if (field?.startsWith('chart:')) { const q = stats.questions.find(q => q.id === field.slice(6)); document.getElementById(`chart-${q.id}`).innerHTML = chart(q, event.target.value); return }
    if (field && view === 'editor') {
      const path = field.split('.'); let obj = form.schema; path.slice(0, -1).forEach(k => obj = obj[k]); obj[path.at(-1)] = event.target.type === 'checkbox' ? event.target.checked : event.target.type === 'number' ? event.target.value === '' ? null : Number(event.target.value) : event.target.value; markDirty()
    }
    const qid = event.target.dataset.answer
    if (!qid || event.target.type === 'file') return
    const q = survey.schema.questions.find(q => q.id === qid), el = event.target
    if (q.type === 'multiple') answers[qid] = [...document.querySelectorAll(`[data-answer="${qid}"]:checked`)].map(v => v.value)
    else if (q.type === 'matrix') { answers[qid] ||= {}; answers[qid][el.dataset.row] = el.value || null }
    else answers[qid] = ['number', 'scale'].includes(q.type) ? el.value === '' ? null : Number(el.value) : el.value || null
    if (!preview) sessionStorage.setItem(`novvin-survey-answer:${account.id}:${survey.id}`, JSON.stringify(answers))
  })
  document.addEventListener('change', async event => {
    const el = event.target, field = el.dataset.field
    if (field?.endsWith('.type')) { const q = form.schema.questions[Number(field.split('.')[1])]; q.measure = q.type === 'scale' ? 'ordinal' : q.type === 'number' ? 'continuous' : 'nominal'; if (q.type === 'scale') { q.min = 1; q.max = 5 } else { q.min = null; q.max = null } markDirty(); renderEditor(); return }
    if (field?.endsWith('.section')) { renderEditor(); return }
    if (el.type !== 'file') return
    if (!el.files?.length) return
    if (busy) { toast('Espera a que termine la operación anterior.'); return }
    try {
      setBusy(true); el.disabled = true; toast('Validando archivo…')
      if (el.dataset.uploadCover || el.dataset.uploadSection) {
        const a = await upload(el.files[0])
        if (el.dataset.uploadCover) form.schema.cover = a.id
        else form.schema.sections.find(s => s.id === el.dataset.uploadSection).image = a.id
        markDirty(); renderEditor()
      } else if (el.dataset.uploadQuestion || el.dataset.uploadOption) {
        const [qid, oid] = (el.dataset.uploadQuestion || el.dataset.uploadOption).split(':'), a = await upload(el.files[0]), q = form.schema.questions.find(q => q.id === qid)
        if (oid) q.options.find(o => o.id === oid).image = a.id; else q.image = a.id
        markDirty(); renderEditor()
      } else if (el.dataset.answer) {
        if (preview) throw new Error('Los archivos se adjuntan al responder una encuesta publicada.')
        const files = [...el.files]; if ((answers[el.dataset.answer]?.length || 0) + files.length > 3) throw new Error('Máximo 3 archivos por pregunta.')
        const ids = []; for (const f of files) ids.push((await upload(f, survey.id)).id)
        answers[el.dataset.answer] = [...(answers[el.dataset.answer] || []), ...ids]
        sessionStorage.setItem(`novvin-survey-answer:${account.id}:${survey.id}`, JSON.stringify(answers)); renderRespond()
      }
      toast('Archivo guardado en privado.')
    } catch (error) { toast(error.message) } finally { setBusy(false); el.disabled = false }
  })
  window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = '' } })
  if (window.NovvinSurveys) window.NovvinSurveys.ready()
  async function boot() {
    account = await request('/me')
    if (params.get('id')) await respond(params.get('id'), params.get('key') || '')
    else if (params.get('mode') === 'create') newForm()
    else await home()
  }
  // Parent listeners/native bridge are installed before the bundled document loads.
  boot().catch(error => { app.innerHTML = `<div class="error"><h1>No se pudo abrir Encuestas</h1><p>${e(error.message)}</p>${button('Reintentar', 'retry')}</div>`; app.setAttribute('aria-busy', 'false') })
})();
