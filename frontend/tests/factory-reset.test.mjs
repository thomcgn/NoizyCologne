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
  '../DashboardModal': { default: modal },
}).default;

let root;
let requests = [];
let backendStatus = 200;
let confirms = 0;
let preview = { recordCount: 52, mediaFileCount: 4, tables: [], challenge: 'fresh-one-use-challenge' };
let result = { deletedRecords: 52, deletedFiles: 4, filesNotDeleted: 0, partial: false };
const originalFetch = globalThis.fetch;
const originalConfirm = window.confirm;

globalThis.fetch = async (url, options = {}) => {
  requests.push({ url, options });
  if (options.method === 'POST') {
    if (backendStatus !== 200) return new Response(JSON.stringify({ error: 'Die Datenbank ist beschäftigt.' }), { status: backendStatus });
    return new Response(JSON.stringify(result), { status: 200 });
  }
  return new Response(JSON.stringify(preview), { status: 200 });
};
window.confirm = () => { confirms++; return true; };

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
    window.confirm = originalConfirm;
  }
});

process.on('exit', () => { globalThis.fetch = originalFetch; window.confirm = originalConfirm; });
