import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost' });
for (const name of ['window', 'document', 'HTMLElement', 'HTMLDetailsElement', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[name] = dom.window[name];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
window.confirm = () => true;
window.setInterval = globalThis.setInterval;
window.clearInterval = globalThis.clearInterval;

const require = createRequire(import.meta.url);
const React = require('react');
const { createRoot } = require('react-dom/client');
const { act, createElement: h, useState, Fragment } = React;
const originalFetch = globalThis.fetch;
let root;

afterEach(() => {
  if (root) act(() => root.unmount());
  root = null;
  document.body.replaceChildren();
  globalThis.fetch = originalFetch;
  window.confirm = () => true;
});

const sourceCache = new Map();
function loadSource(file) {
  const url = new URL(file, import.meta.url);
  const path = url.pathname;
  if (sourceCache.has(path)) return sourceCache.get(path);
  const loaded = { exports: {} };
  sourceCache.set(path, loaded.exports);
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
  }).outputText;
  const localRequire = (specifier) => {
    if (specifier === 'next/link') return { __esModule: true, default: props => h('a', props) };
    if (specifier === 'next/navigation') return navigation;
    if (specifier.endsWith('/CatalogProvider')) return {
      ...loadSource('../src/app/dashboard/CatalogProvider.tsx'),
      useCatalog: () => ({ ...catalogue }),
    };
    if (specifier.endsWith('/DashboardModal')) return loadSource('../src/app/dashboard/DashboardModal.tsx');
    if (specifier.endsWith('/ActionFeedback')) return loadSource('../src/app/dashboard/ActionFeedback.tsx');
    if (specifier.startsWith('.')) {
      const base = new URL(specifier, url);
      for (const extension of ['.tsx', '.ts']) {
        const candidate = new URL(base.href + extension);
        try { readFileSync(candidate); } catch { continue; }
        return loadSource(candidate);
      }
    }
    return require(specifier);
  };
  new Function('require', 'module', 'exports', compiled)(localRequire, loaded, loaded.exports);
  sourceCache.set(path, loaded.exports);
  return loaded.exports;
}

let navigation = { useRouter: () => ({ replace() {}, refresh() {} }) };
let catalogue = {};

function mount(component) {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(Fragment, null, component)));
}
function button(text, within = document) {
  const found = [...within.querySelectorAll('button')].find(element => element.textContent.trim().startsWith(text));
  assert.ok(found, `Button ${text} exists`);
  return found;
}
function click(element) { act(() => element.click()); }
function changeField(element, value) {
  element.focus();
  const prototype = element.tagName === 'SELECT' ? dom.window.HTMLSelectElement.prototype
    : element.tagName === 'TEXTAREA' ? dom.window.HTMLTextAreaElement.prototype
      : dom.window.HTMLInputElement.prototype;
  act(() => {
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
  });
}
function key(name) {
  const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
  act(() => document.activeElement.dispatchEvent(event));
  return event;
}
function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

test('expliziter Dirty-Guard fragt nur bei echter, nicht zurückgenommener Änderung nach', () => {
  const DashboardModal = loadSource('../src/app/dashboard/DashboardModal.tsx').default;
  const { useDirtyGuard } = loadSource('../src/app/dashboard/useDirtyGuard.ts');
  function Form() {
    const [open, setOpen] = useState(false);
    const [value, setValue] = useState('');
    const guard = useDirtyGuard(JSON.stringify({ value }), 'form-contract-guard');
    return h(Fragment, null,
      h('button', { id: 'open-guard', onClick: () => setOpen(true) }, 'Formular öffnen'),
      h(DashboardModal, { open, title: 'Vertrag', protectChanges: true, dirtyGuard: guard, onClose: () => setOpen(false) },
        h('input', { 'aria-label': 'Wert', value, onChange: event => setValue(event.target.value) }),
        h('button', { type: 'button', onClick: () => { guard.setClean(); setOpen(false); } }, 'Speichern und schließen')));
  }
  mount(h(Form));
  const trigger = document.querySelector('#open-guard');
  trigger.focus(); click(trigger);
  const input = document.querySelector('[aria-label="Wert"]');
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    changeField(input, 'Erst geändert');
    key('Escape');
    assert.equal(prompts, 1);
    assert.ok(document.querySelector('[role="dialog"]'), 'Verweigerte Verwerfung muss den Dialog offen lassen');
    changeField(input, '');
    key('Escape');
    assert.equal(prompts, 1, 'Zurückgenommene Eingabe ist kein ungespeicherter Stand');
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.activeElement, trigger);
    click(trigger);
    changeField(document.querySelector('[aria-label="Wert"]'), 'Neu');
    click(button('Speichern und schließen'));
    assert.equal(prompts, 1, 'setClean() nach Erfolg verhindert die Verwerfungsfrage');
    assert.equal(document.querySelector('[role="dialog"]'), null);
  } finally { window.confirm = () => true; }
});

test('ActionFeedback unterscheidet Erfolg, Teilerfolg und Fehler zugänglich', () => {
  const { default: ActionFeedback } = loadSource('../src/app/dashboard/ActionFeedback.tsx');
  mount(h(Fragment, null,
    h(ActionFeedback, { kind: 'success' }, 'Akteur gespeichert.'),
    h(ActionFeedback, { kind: 'partial' }, 'Termin gespeichert; Ansicht noch offen.'),
    h(ActionFeedback, { kind: 'error' }, 'Speichern fehlgeschlagen.')));
  const statuses = [...document.querySelectorAll('[role="status"]')].map(element => element.textContent);
  assert.deepEqual(statuses, ['Erfolgreich: Akteur gespeichert.', 'Teilweise erfolgreich: Termin gespeichert; Ansicht noch offen.']);
  const alert = document.querySelector('[role="alert"]');
  assert.match(alert.textContent, /Fehlgeschlagen: Speichern fehlgeschlagen/);
});

test('Logout bei HTTP 500 bleibt angemeldet und meldet den Fehler sichtbar', async () => {
  const replaced = [];
  let refreshes = 0;
  navigation = { useRouter: () => ({ replace: path => replaced.push(path), refresh: () => { refreshes++; } }) };
  const LogoutButton = loadSource('../src/app/dashboard/LogoutButton.tsx').default;
  let status = 500;
  globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Revocation failed' }), { status });
  mount(h(LogoutButton));
  await act(async () => { button('Abmelden').click(); });
  assert.deepEqual(replaced, []);
  assert.equal(refreshes, 0);
  assert.match(document.querySelector('#logout-error').textContent, /Abmelden fehlgeschlagen/);
  assert.ok(document.querySelector('[role="alert"]'), 'Fehler muss als Alert erkennbar sein');
  status = 200;
  await act(async () => { button('Abmelden').click(); });
  assert.deepEqual(replaced, ['/login']);
  assert.equal(refreshes, 1);
  assert.equal(document.querySelector('[role="alert"]'), null);
});

const party = { id: 'party', name: 'Test Party', instagram: 'party', color: '#123456', residentDjIds: [], otherDjIds: [], organizerIds: [], standardGenres: [], djMentionDefault: 'manual', recurrence: null, defaultFlyer: null };
const venue = { id: 'venue', name: 'Test Venue', instagram: 'venue', websiteUrl: '', eventsUrl: '', discoveryEnabled: false };
const actor = { id: 'actor', name: 'Test DJ', instagram: 'dj', kind: 'person', roles: ['dj'], collectiveIds: [] };
const entry = { id: 'entry', date: '2027-01-02', type: 'event', locationId: 'venue', partyId: 'party', entryPrice: '10', startTime: '22:00', notes: '', mentionDjIds: [], eventFlyer: null };

test('390 px: Fokus, Touch-Auslösung und Erfolgsmeldung im Akteur-Editor', async () => {
  window.innerWidth = 390;
  const created = [];
  catalogue = {
    actors: [actor], parties: [party], venues: [venue], entries: [entry],
    createActor: async data => { created.push(data); return { ...data, id: 'created' }; },
    updateActor: async data => data,
    deleteActor: async () => {},
    reloadActors: async () => {},
  };
  globalThis.fetch = async () => jsonResponse([]);
  const Page = loadSource('../src/app/dashboard/portfolio/akteure/page.tsx').default;
  await act(async () => { mount(h(Page)); });
  const trigger = button('+ Neu anlegen');
  trigger.focus();
  click(trigger);
  const dialog = document.querySelector('[role="dialog"]');
  assert.ok(dialog);
  const nameInput = dialog.querySelector('input');
  changeField(nameInput, 'Neuer DJ');
  assert.equal(document.activeElement, nameInput, 'Touch-Eingabe darf den Fokus nicht verlieren');
  for (const checkbox of dialog.querySelectorAll('input[type="checkbox"]:not(:disabled)')) {
    checkbox.focus();
    click(checkbox);
    assert.equal(document.activeElement, checkbox);
  }
  const save = button('Speichern und schließen', dialog);
  assert.equal(save.textContent.includes('Änderungen'), false, 'Neuanlage heißt nicht Änderungen speichern');
  await act(async () => { save.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); });
  assert.equal(created.length, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.match(document.querySelector('[role="status"]').textContent, /Neuer DJ.*gespeichert/);
  assert.equal(document.activeElement, trigger, 'Fokus kehrt zum auslösenden Datensatz zurück');
  window.innerWidth = 1024;
});

test('Kalender speichert mit Wirkungslabel und meldet Erfolg am auslösenden Datensatz', async () => {
  const saved = [];
  catalogue = {
    parties: [party], venues: [venue], actors: [actor], entries: [entry],
    createEntry: async data => { saved.push(data); return data; },
    updateEntry: async data => { saved.push(data); return data; },
  };
  const Editor = loadSource('../src/app/dashboard/kalender/EntryEditor.tsx').default;
  const messages = [];
  mount(h(Editor, { date: new Date(2027, 0, 9), onSaved: message => messages.push(message), onCancel() {} }));
  assert.match(button('Speichern und schließen').textContent, /Speichern und schließen/);
  changeField(document.querySelectorAll('select')[0], 'venue');
  changeField(document.querySelectorAll('select')[1], 'party');
  await act(async () => { document.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  assert.equal(saved.length, 1);
  assert.match(messages[0], /Kalendereintrag für 2027-01-09 gespeichert/);
  act(() => root.render(h(Editor, { date: new Date(2027, 0, 9), entry, onSaved: message => messages.push(message), onCancel() {} })));
  assert.match(button('Änderungen speichern').textContent, /Änderungen speichern/);
});
