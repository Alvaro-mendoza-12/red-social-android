'use strict';
(() => {
  let dialog, image, stage, output, previousFocus, saveImage, scale = 1, x = 0, y = 0;
  const pointers = new Map();
  const accepted = source => /^data:image\/(?:png|jpeg|webp|gif);base64,[a-z0-9+/=\s]+$/i.test(source);
  function fit() {
    if (!image.naturalWidth || !stage.clientWidth) return { width: 0, height: 0 };
    const ratio = Math.min(stage.clientWidth / image.naturalWidth, stage.clientHeight / image.naturalHeight);
    return { width: image.naturalWidth * ratio, height: image.naturalHeight * ratio };
  }
  function render() {
    // Android WebView may report a supported dvh unit with zero layout height.
    // Use the actual visible viewport so the full-screen flex stage can expand.
    const viewport = window.visualViewport;
    dialog.style.width = `${Math.max(1, viewport?.width || window.innerWidth)}px`;
    dialog.style.height = `${Math.max(1, viewport?.height || window.innerHeight)}px`;
    const size = fit();
    const limitX = Math.max(0, (size.width * scale - stage.clientWidth) / 2);
    const limitY = Math.max(0, (size.height * scale - stage.clientHeight) / 2);
    x = Math.max(-limitX, Math.min(limitX, x)); y = Math.max(-limitY, Math.min(limitY, y));
    image.style.width = `${size.width}px`; image.style.height = `${size.height}px`;
    image.style.transform = `translate(${x}px,${y}px) scale(${scale})`;
    output.textContent = `${Math.round(scale * 100)}%`;
    stage.setAttribute('aria-label', `Imagen ampliada al ${output.textContent}`);
    dialog.querySelector('[data-image-control="in"]').disabled = scale >= 4;
    dialog.querySelector('[data-image-control="out"]').disabled = scale <= 1;
  }
  function zoom(value) { scale = Math.max(1, Math.min(4, value)); if (scale === 1) x = y = 0; render(); }
  function close() {
    if (!dialog?.open) return false;
    dialog.close(); return true;
  }
  function distance() { const p = [...pointers.values()]; return p.length === 2 ? Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y) : 0; }
  function initialize() {
    if (dialog) return;
    dialog = document.createElement('dialog'); dialog.id = 'survey-image-viewer';
    dialog.setAttribute('aria-label', 'Vista de imagen');
    dialog.innerHTML = '<div class="image-viewer-controls"><button type="button" data-image-control="close" aria-label="Cerrar imagen">Cerrar</button><button type="button" data-image-control="out" aria-label="Reducir imagen">−</button><output aria-live="polite">100%</output><button type="button" data-image-control="in" aria-label="Ampliar imagen">+</button><button type="button" data-image-control="reset">Restablecer</button><button type="button" data-image-control="save" hidden>Guardar imagen</button></div><div class="image-viewer-stage" tabindex="0" role="group"><img alt=""></div>';
    document.body.append(dialog); image = dialog.querySelector('img'); stage = dialog.querySelector('.image-viewer-stage'); output = dialog.querySelector('output');
    image.onload = render;
    dialog.addEventListener('close', () => { pointers.clear(); image.removeAttribute('src'); saveImage = undefined; previousFocus?.focus(); });
    dialog.addEventListener('click', event => {
      const action = event.target.closest('[data-image-control]')?.dataset.imageControl;
      if (action === 'close') close();
      else if (action === 'in') zoom(scale + .5);
      else if (action === 'out') zoom(scale - .5);
      else if (action === 'reset') zoom(1);
      else if (action === 'save') saveImage?.();
    });
    stage.addEventListener('dblclick', () => zoom(scale > 1 ? 1 : 2));
    stage.addEventListener('keydown', event => {
      if (['+', '=', '-','0'].includes(event.key)) { event.preventDefault(); zoom(event.key === '0' ? 1 : scale + (event.key === '-' ? -.5 : .5)); }
    });
    stage.addEventListener('pointerdown', event => {
      if (pointers.size >= 2) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY }); stage.setPointerCapture(event.pointerId);
    });
    stage.addEventListener('pointermove', event => {
      const before = pointers.get(event.pointerId); if (!before) return;
      const previousDistance = distance(); pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (pointers.size === 2 && previousDistance > 0) zoom(scale * distance() / previousDistance);
      else if (scale > 1) { x += event.clientX - before.x; y += event.clientY - before.y; render(); }
    });
    for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) stage.addEventListener(name, event => pointers.delete(event.pointerId));
    const resized = () => { if (dialog.open) render(); };
    window.addEventListener('resize', resized);
    window.visualViewport?.addEventListener('resize', resized);
  }
  function open(source, alt = '', onSave) {
    if (!accepted(source)) return false;
    initialize(); previousFocus = document.activeElement; scale = 1; x = y = 0; pointers.clear();
    image.alt = alt; image.src = source; saveImage = typeof onSave === 'function' ? onSave : undefined;
    dialog.querySelector('[data-image-control="save"]').hidden = !saveImage;
    if (!dialog.open) dialog.showModal();
    render(); stage.focus(); return true;
  }
  function enable(element, onSave) {
    if (!accepted(element.src) || element.dataset.zoomReady) return;
    element.dataset.zoomReady = 'true'; element.tabIndex = 0; element.setAttribute('role', 'button');
    element.setAttribute('aria-label', `Ampliar imagen: ${element.alt || 'Imagen de la encuesta'}`);
    element.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); open(element.src, element.alt, onSave); });
    element.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(element.src, element.alt, onSave); } });
  }
  window.SurveyImageViewer = Object.freeze({ open, close, enable });
})();
