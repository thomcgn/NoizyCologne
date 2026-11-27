import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://tsa.noizycologne.de' });
for (const key of ['window', 'document', 'HTMLElement', 'HTMLDetailsElement', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
window.confirm = () => true;
const require = createRequire(import.meta.url);
const React = require('react');
const { act, createElement: h } = React;
const { createRoot } = require('react-dom/client');

function compile(path, imported = {}) {
  const output = ts.transpileModule(readFileSync(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const compiledModule = { exports: {} };
  new Function('require', 'module', 'exports', output)(
    name => imported[name] ?? require(name), compiledModule, compiledModule.exports,
  );
  return compiledModule.exports;
}

const modal = compile('../src/app/dashboard/DashboardModal.tsx').default;
const Panel = compile('../src/app/dashboard/settings/FactoryResetPanel.tsx', {
  '../DashboardModal': { __esModule: true, default: modal },
}).default;

let root;
let requests = [];
let backendStatus = 200;
let confirms = 0;
let preview = { recordCount: 52, mediaFileCount: 4, tables: [], challenge: 'fresh-one-use-challenge' };
let result = { deletedRecords: 52, deletedFiles: 4, filesNotDeleted: 0, partial: false };
const originalFetch = globalThis.fetch;
const originalConfirm = window.confirm;

async function defaultPost() {
  if (backendStatus !== 200) return new Response(JSON.stringify({ error: 'Die Datenbank ist beschäftigt.' }), { status: backendStatus });
  return new Response(JSON.stringify(result), { status: 200 });
}
const defaultGet = async () => new Response(JSON.stringify(preview), { status: 200 });
let postResponse = defaultPost;
let getResponse = defaultGet;
const fixtureFetch = async (url, options = {}) => {
  requests.push({ url, options });
  return options.method === 'POST' ? postResponse(url, options) : getResponse(url, options);
};
const defaultConfirm = () => { confirms++; return true; };
globalThis.fetch = fixtureFetch;
window.confirm = defaultConfirm;

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = null;
  document.body.replaceChildren();
  window.localStorage.clear();
  requests = [];
  backendStatus = 200;
  confirms = 0;
  preview = { recordCount: 52, mediaFileCount: 4, tables: [], challenge: 'fresh-one-use-challenge' };
  result = { deletedRecords: 52, deletedFiles: 4, filesNotDeleted: 0, partial: false };
  postResponse = defaultPost; getResponse = defaultGet; globalThis.fetch = fixtureFetch; window.confirm = defaultConfirm;
});

async function mount() {
  const container = document.createElement('main');
  document.body.append(container);
  root = createRoot(container);
  await act(async () => root.render(h(Panel)));
}
async function click(button) {
  await act(async () => button.click());
}
function button(label) {
  const found = [...document.querySelectorAll('button')].find(node => node.textContent.includes(label));
  assert.ok(found, `Expected button ${label}`);
  return found;
}
async function change(input, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

test('Werkseinstellungen begin with a read-only preview and require all confirmation steps', async () => {
  await mount();
  assert.equal(document.querySelector('[role="dialog"]'), null);
  await click(button('Werkseinstellungen öffnen'));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].options.method, undefined);
  assert.match(document.querySelector('[role="dialog"]').textContent, /52.*Datenbankzeilen und.*4.*Dateien/);
  const submit = button('Kompletten Reset ausführen');
  assert.equal(submit.disabled, true);
  const phrase = document.querySelector('#factory-reset-phrase');
  const password = document.querySelector('#factory-reset-password');
  await change(phrase, 'kompletter reset');
  await change(password, 'AktuellesPasswort');
  await click(document.querySelector('input[type="checkbox"]'));
  assert.equal(submit.disabled, true);
  await change(phrase, 'Kompletter Reset');
  assert.equal(submit.disabled, false);
  await act(async () => submit.closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(confirms, 1);
  assert.equal(requests.length, 2);
  assert.equal(requests[1].options.method, 'POST');
  assert.deepEqual(JSON.parse(requests[1].options.body), {
    phrase: 'Kompletter Reset', password: 'AktuellesPasswort', challenge: 'fresh-one-use-challenge',
  });
  assert.match(document.body.textContent, /Werkseinstellungen wiederhergestellt/);
});

test('Abbrechen makes no destructive request and backend failures remain visible', async () => {
  backendStatus = 409;
  await mount();
  await click(button('Werkseinstellungen öffnen'));
  await change(document.querySelector('#factory-reset-phrase'), 'Kompletter Reset');
  await change(document.querySelector('#factory-reset-password'), 'Passwort');
  await click(document.querySelector('input[type="checkbox"]'));
  await act(async () => button('Kompletten Reset ausführen').closest('form')
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  assert.match(document.body.textContent, /Die Datenbank ist beschäftigt/);
  assert.equal(requests.filter(item => item.options.method === 'POST').length, 1);
  await click(button('Abbrechen'));
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('A confirmation cancellation never sends a reset', async () => {
  window.confirm = () => { confirms++; return false; };
  try {
    await mount();
    await click(button('Werkseinstellungen öffnen'));
    await change(document.querySelector('#factory-reset-phrase'), 'Kompletter Reset');
    await change(document.querySelector('#factory-reset-password'), 'Passwort');
    await click(document.querySelector('input[type="checkbox"]'));
    await act(async () => button('Kompletten Reset ausführen').closest('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    assert.equal(confirms, 1);
    assert.equal(requests.filter(item => item.options.method === 'POST').length, 0);
  } finally {
    window.confirm = defaultConfirm;
  }
});

process.on('exit', () => { globalThis.fetch = originalFetch; window.confirm = originalConfirm; });

async function prepareReset() {
  await mount();
  await click(button('Werkseinstellungen öffnen'));
  await fillConfirmation();
}
async function fillConfirmation(password = 'AktuellesPasswort') {
  await change(document.querySelector('#factory-reset-phrase'), 'Kompletter Reset');
  await change(document.querySelector('#factory-reset-password'), password);
  const check = document.querySelector('input[type="checkbox"]');
  if (!check.checked) await click(check);
}
async function submit() {
  const form = document.querySelector('[role="dialog"] form');
  assert.ok(form);
  await act(async () => form.dispatchEvent(new Event('submit', {bubbles:true,cancelable:true})));
}
function assertNewConfirmationRequired() {
  assert.equal(document.querySelector('#factory-reset-phrase').value, '');
  assert.equal(document.querySelector('#factory-reset-password').value, '');
  assert.equal(document.querySelector('input[type="checkbox"]').checked, false);
  assert.equal(button('Kompletten Reset ausführen').disabled, true);
}
function assertUnconfirmed() {
  assert.match(document.querySelector('[role="dialog"]').textContent, /Reset-Ausgang unbestätigt.*Administrator.*keinen weiteren Reset/);
  assert.equal(document.querySelector('[role="dialog"] form'), null);
  assert.ok(![...document.querySelectorAll('button')].some(item => item.textContent.includes('Vorschau erneut laden')));
  assert.equal(button('Ansicht neu laden').disabled, false);
  assert.equal(requests.filter(item => item.options.method === 'POST').length, 1);
  assert.equal(requests.filter(item => item.options.method !== 'POST').length, 1);
}

test('synchronous submit lock and busy modal prevent duplicate POSTs and every pending dismissal path', async () => {
  let finish;
  const gate = new Promise(resolve => { finish = resolve; });
  postResponse = async () => { await gate; return defaultPost(); };
  try {
    await prepareReset();
    const modal = document.querySelector('[role="dialog"]');
    const form = modal.querySelector('form');
    await act(async () => {
      form.dispatchEvent(new Event('submit', {bubbles:true,cancelable:true}));
      form.dispatchEvent(new Event('submit', {bubbles:true,cancelable:true}));
    });
    assert.equal(confirms, 1);
    assert.equal(requests.filter(item => item.options.method === 'POST').length, 1);
    assert.ok([...modal.querySelectorAll('input')].every(input => input.matches(':disabled')));
    assert.equal(modal.querySelector('[data-modal-close]').disabled, true);
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true,cancelable:true}));
      modal.querySelector('[data-modal-close]').click();
      button('Abbrechen').click();
    });
    assert.equal(document.querySelector('[role="dialog"]'), modal);
    assert.equal(confirms, 1);
    await act(async () => finish());
    assert.match(modal.textContent, /Werkseinstellungen wiederhergestellt/);
  } finally { finish(); }
});

test('stale 409 refreshes the preview and requires a new phrase, password, acknowledgement and native confirmation', async () => {
  let attempts = 0;
  postResponse = async () => {
    attempts++;
    if (attempts === 1) {
      preview = {...preview, recordCount: 77, challenge:'new-revision-challenge'};
      return new Response(JSON.stringify({error:'Die Vorschau ist veraltet.'}), {status:409});
    }
    return defaultPost();
  };
  await prepareReset(); await submit();
  assert.match(document.body.textContent, /Die Vorschau ist veraltet/);
  assert.match(document.body.textContent, /77.*Datenbankzeilen/);
  assertNewConfirmationRequired();
  assert.equal(requests.length, 3);
  await submit(); assert.equal(attempts, 1);
  await fillConfirmation('ErneutBestätigtesPasswort'); await submit();
  assert.equal(confirms, 2);
  const writes = requests.filter(item => item.options.method === 'POST');
  assert.deepEqual(JSON.parse(writes[1].options.body), {phrase:'Kompletter Reset',password:'ErneutBestätigtesPasswort',challenge:'new-revision-challenge'});
});

for (const status of [400,401]) test(`confirmed pre-execution ${status} requires all confirmation fields again`, async () => {
  backendStatus = status;
  await prepareReset(); await submit();
  assertNewConfirmationRequired();
  assert.equal(confirms, 1);
  assert.equal(requests.filter(item => item.options.method !== 'POST').length, 2);
  assert.doesNotMatch(document.body.textContent, /Reset-Ausgang unbestätigt/);
});

for (const status of [503,409]) test(`explicit unknown outcome on HTTP ${status} never loads another reset preview, including after reopen`, async () => {
  postResponse = async () => new Response(JSON.stringify({error:'Technischer Ausgang nicht nachweisbar.',outcomeUnknown:true}), {status});
  await prepareReset(); await submit(); assertUnconfirmed();
  await click(document.querySelector('[data-modal-close]'));
  assert.equal(document.querySelector('[role="dialog"]'), null);
  await click(button('Werkseinstellungen öffnen')); assertUnconfirmed();
  assert.match(document.querySelector('[role="dialog"]').textContent, /Technischer Ausgang nicht nachweisbar/);
});

for (const [label, respond] of [
  ['transport interruption', async () => {throw new TypeError('Synthetic connection lost');}],
  ['broken success JSON', async () => new Response('{', {status:200})],
  ['broken rejection JSON', async () => new Response('{', {status:409})],
  ['missing result fields', async () => new Response(JSON.stringify({partial:false}), {status:200})],
  ['unclassified HTTP failure', async () => new Response(JSON.stringify({error:'Serverfehler'}), {status:503})],
]) test(`${label} keeps the reset outcome unconfirmed without automatic reads or mutation retry`, async () => {
  postResponse = respond;
  await prepareReset(); await submit(); assertUnconfirmed();
  assert.doesNotMatch(document.querySelector('[role="dialog"]').textContent, /Werkseinstellungen wiederhergestellt/);
});

test('partial confirmed result displays backend message even when zero media files failed', async () => {
  result = {...result, partial:true, message:'Fachdaten gelöscht; der technische Abschluss ist noch offen.'};
  window.localStorage.setItem('keep-preferences', 'yes');
  await prepareReset(); await submit();
  assert.equal(document.querySelector('[role="dialog"] [role="status"]').textContent, result.message);
  assert.doesNotMatch(document.body.textContent, /0 Mediendatei\(en\) konnten nicht/);
  assert.doesNotMatch(document.body.textContent, /Reset-Ausgang unbestätigt/);
  assert.equal(window.localStorage.getItem('keep-preferences'), 'yes');
  assert.equal(requests.length, 2);
  assert.equal(document.querySelector('[role="dialog"] form'), null);
  assert.equal(button('Ansicht neu laden').disabled, false);
});

test('partial fallback distinguishes outstanding technical completion from actual file failures', async () => {
  result = {...result, partial:true};
  await prepareReset(); await submit();
  assert.match(document.querySelector('[role="status"]').textContent, /Datenbankreset ist bestätigt; der technische Abschluss/);
  assert.doesNotMatch(document.querySelector('[role="status"]').textContent, /Mediendatei\(en\) konnten nicht/);
});

test('late preview responses cannot reopen or overwrite a newly opened preview after close', async () => {
  let finish;
  const gate = new Promise(resolve => { finish = resolve; });
  const old = {...preview, recordCount:12, challenge:'old-closed-challenge'};
  let reads = 0;
  getResponse = async () => {
    reads++;
    if (reads === 1) {await gate; return new Response(JSON.stringify(old));}
    return new Response(JSON.stringify({...preview, recordCount:99, challenge:'current-open-challenge'}));
  };
  try {
    await mount(); await click(button('Werkseinstellungen öffnen'));
    assert.match(document.body.textContent, /Reset-Vorschau wird geladen/);
    const firstSignal = requests[0].options.signal;
    await click(document.querySelector('[data-modal-close]'));
    assert.equal(firstSignal.aborted, true);
    await click(button('Werkseinstellungen öffnen'));
    assert.match(document.querySelector('[role="dialog"]').textContent, /99.*Datenbankzeilen/);
    await act(async () => finish());
    assert.match(document.querySelector('[role="dialog"]').textContent, /99.*Datenbankzeilen/);
    assert.doesNotMatch(document.querySelector('[role="dialog"]').textContent, /12 Datenbankzeilen/);
    assertNewConfirmationRequired();
    await fillConfirmation(); await submit();
    const write = requests.find(item => item.options.method === 'POST');
    assert.equal(JSON.parse(write.options.body).challenge, 'current-open-challenge');
  } finally { finish(); }
});

test('failed read-only preview can be retried without requesting any reset', async () => {
  let reads = 0;
  getResponse = async () => ++reads === 1 ? new Response(JSON.stringify({error:'Vorschau nicht erreichbar'}), {status:503}) : defaultGet();
  await mount(); await click(button('Werkseinstellungen öffnen'));
  assert.match(document.body.textContent, /Vorschau nicht erreichbar/);
  await click(button('Vorschau erneut laden'));
  assertNewConfirmationRequired();
  assert.equal(requests.filter(item => item.options.method === 'POST').length, 0);
  assert.equal(confirms, 0);
});
