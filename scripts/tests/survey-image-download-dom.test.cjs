'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = process.env.SURVEY_TEST_REPO_ROOT || path.resolve(__dirname, '../..');
const assetRoot = process.env.SURVEY_ASSET_ROOT || path.join(repo, 'app/src/main/assets/surveys');
const { JSDOM } = require(path.join(repo, 'Conectate-web/node_modules/jsdom'));
const image = { name: 'pregunta.png', mime: 'image/png', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==' };
async function harness() {
  const dom = new JSDOM(fs.readFileSync(path.join(repo, 'app/src/main/assets/surveys/index.html'), 'utf8'), {
    url: 'https://surveys.novvin.invalid/index.html?id=synthetic-survey', runScripts: 'outside-only', pretendToBeVisual: true
  });
  const w = dom.window, calls = [], downloads = [];
  let failSave = true;
  w.ResizeObserver = class { observe() {} disconnect() {} };
  w.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  w.scrollTo = () => {};
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new w.Event('close')); };
  w.NovvinSurveys = { ready() {}, request(raw) {
    const input = JSON.parse(raw); calls.push(input);
    queueMicrotask(() => {
      let value;
      if (input.path === '/v1/surveys/me') value = { id: 'synthetic-account' };
      else if (input.path === '/v1/surveys/campaigns/synthetic-survey') value = {
        id: 'synthetic-survey', status: 'open', server_time: new Date().toISOString(),
        response_count: 0, closes_at: null, owner: false,
        schema: { title: 'Encuesta sintética', purpose: 'Auditoría', contact: 'QA', retention: 'Una prueba', consent: 'Prueba', retentionDays: 1,
          sections: [{ id: 's', title: 'Inicio' }], questions: [{ id: 'q', section: 's', title: 'Pregunta con imagen', type: 'text', image: 'synthetic-image', imageAlt: 'Imagen de prueba', options: [], rows: [] }] }
      };
      else if (input.path === '/v1/surveys/assets/synthetic-image?campaignId=synthetic-survey') value = image;
      else throw Error('Unexpected synthetic route ' + input.path);
      w.surveyReceive({ id: input.id, value });
    });
  }, download(raw) {
    const input = JSON.parse(raw); downloads.push(input);
    queueMicrotask(() => w.surveyReceive(failSave ? { id: input.requestId, error: 'No hay espacio para guardar la imagen.' } : {
      id: input.requestId, value: { name: image.name, location: 'Descargas/Novvin' }
    }));
  } };
  w.eval(fs.readFileSync(path.join(repo, 'app/src/main/assets/surveys/image-viewer.js'), 'utf8'));
  w.eval(fs.readFileSync(path.join(assetRoot, 'workbench.js'), 'utf8'));
  async function wait(fn) {
    for (let i = 0; i < 500; i++) { if (fn()) return; await new Promise(resolve => setTimeout(resolve, 2)); }
    throw Error('Synthetic UI did not settle: ' + w.document.body.textContent);
  }
  await wait(() => w.document.querySelector('[data-action=start-answer]'));
  w.document.getElementById('consent').checked = true;
  w.document.querySelector('[data-action=start-answer]').click();
  await wait(() => w.document.querySelector('.question-image')?.dataset.zoomReady === 'true');
  return { dom, w, calls, downloads, wait, succeed() { failSave = false; } };
}

test('Question-image viewer shows a rejected native save and permits successful retry', async () => {
  const h = await harness();
  try {
    h.w.document.querySelector('.question-image').click();
    const save = h.w.document.querySelector('[data-image-control=save]');
    assert.equal(save.hidden, false);
    save.click();
    await h.wait(() => /No hay espacio/.test(h.w.document.getElementById('notice').textContent));
    assert.equal(h.downloads.length, 1);
    assert.equal(h.w.document.getElementById('notice').style.display, 'block');
    assert.doesNotMatch(h.w.document.getElementById('notice').textContent, /Archivo guardado/);
    h.succeed(); save.click();
    await h.wait(() => /Archivo guardado: pregunta.png/.test(h.w.document.getElementById('notice').textContent));
    assert.equal(h.downloads.length, 2);
    assert.match(h.w.document.getElementById('notice').textContent, /Descargas\/Novvin/);
  } finally { h.dom.window.close(); }
});

test('Image hydration uses only the authorized bridge asset response for its native save', async () => {
  const h = await harness();
  try {
    h.succeed();
    h.w.document.querySelector('.question-image').click();
    h.w.document.querySelector('[data-image-control=save]').click();
    await h.wait(() => h.downloads.length === 1 && /Archivo guardado/.test(h.w.document.getElementById('notice').textContent));
    assert.equal(h.downloads[0].data, image.data);
    assert.equal(h.downloads[0].mime, 'image/png');
    assert.equal(h.calls.filter(c => c.path.includes('/assets/')).length, 1);
    assert.equal(h.calls.at(-1).path, '/v1/surveys/assets/synthetic-image?campaignId=synthetic-survey');
  } finally { h.dom.window.close(); }
});
