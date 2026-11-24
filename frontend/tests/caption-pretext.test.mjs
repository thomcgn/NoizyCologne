import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'https://tsa.noizycologne.de', pretendToBeVisual: true,
});
for (const name of ['window', 'document', 'HTMLElement', 'HTMLDetailsElement', 'Event', 'MouseEvent', 'KeyboardEvent', 'StorageEvent']) {
  globalThis[name] = dom.window[name];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
window.confirm = () => true;
window.innerWidth = 1440;
window.setInterval = globalThis.setInterval;
window.clearInterval = globalThis.clearInterval;

const require = createRequire(import.meta.url);
const React = require('react');
const { act, createElement: h, StrictMode } = React;
const { createRoot } = require('react-dom/client');
const originalFetch = globalThis.fetch;
const originalSetItem = dom.window.Storage.prototype.setItem;

const modules = new Map();
function loadSource(url) {
  if (modules.has(url.href)) return modules.get(url.href);
  const loaded = { exports: {} };
  modules.set(url.href, loaded.exports);
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  function localRequire(specifier) {
    if (specifier === 'next/link') return { default: props => h('a', props) };
    if (specifier === 'next/image') return { default: props => h('img', props) };
    if (specifier.endsWith('/CatalogProvider')) return { useCatalog: () => catalogue };
    if (specifier.startsWith('.')) {
      for (const extension of ['.tsx', '.ts']) {
        const next = new URL(new URL(specifier, url).href + extension);
        try { readFileSync(next); } catch { continue; }
        return loadSource(next);
      }
    }
    return require(specifier);
  }
  new Function('require', 'module', 'exports', compiled)(localRequire, loaded, loaded.exports);
  modules.set(url.href, loaded.exports);
  return loaded.exports;
}

const { CAPTION_PRETEXT_ENDPOINT, CAPTION_PRETEXT_NOTICE_KEY } = loadSource(
  new URL('../src/app/dashboard/useCaptionPretext.ts', import.meta.url));
const SettingsPage = loadSource(new URL('../src/app/dashboard/settings/genres/page.tsx', import.meta.url)).default;
const EditorialPage = loadSource(new URL('../src/app/dashboard/redaktion/page.tsx', import.meta.url)).default;

let backend;
let putFails;
let roots;
const genre = { id: 'genre', canonicalName: 'rock', displayName: 'Rock', active: true, aliases: [] };
const party = { id: 'party', name: 'Test Party', instagram: 'party', color: '#123456', residentDjIds: [], otherDjIds: [], organizerIds: [], standardGenres: [], djMentionDefault: 'manual', recurrence: null, defaultFlyer: null };
const venue = { id: 'venue', name: 'Test Venue', instagram: 'venue', websiteUrl: '', eventsUrl: '', discoveryEnabled: false };
const actor = { id: 'actor', name: 'Test DJ', instagram: 'dj', kind: 'person', roles: ['dj'], collectiveIds: [] };
const entry = { id: 'entry', date: '2027-01-02', type: 'event', locationId: 'venue', partyId: 'party', entryPrice: '', startTime: '22:00', notes: '', mentionDjIds: [], eventFlyer: null };
const catalogue = {
  entries: [entry], entriesLoading: false, entriesError: null, reloadEntries: async () => {},
  parties: [party], venues: [venue], actors: [actor],
  drafts: [], draftsLoading: false, draftsError: null, reloadDrafts: async () => {},
  createDraft: async value => value, updateDraft: async value => value,
  musicTracks: [], musicTracksLoading: false, musicTracksError: null, reloadMusicTracks: async () => {},
  createMusicTrack: async value => value, updateMusicTrack: async value => value,
  uploadEventFlyer: async () => {}, deleteEventFlyer: async () => {}, analyzeEventFlyerGenres: async () => [],
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
function fetchFixture(url, options = {}) {
  if (url === CAPTION_PRETEXT_ENDPOINT) {
    if (options.method === 'PUT') {
      if (putFails) return json({ message: 'Speichern fehlgeschlagen' }, 500);
      const body = JSON.parse(options.body);
      if (body.expectedRevision && body.expectedRevision !== backend.revision)
        return json({ message: 'Der Pre-Text wurde inzwischen geändert.' }, 409);
      if (body.pretext !== backend.pretext) {
        backend = { pretext: body.pretext, revision: backend.revision + 1 };
      }
      return json({ ...backend });
    }
    return json({ ...backend });
  }
  if (url === '/api/v1/genres') return json([genre]);
  return json([]);
}

async function settle() {
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
async function mount(component) {
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  roots.push(root);
  await act(async () => { root.render(h(StrictMode, null, component)); });
  await settle();
}
function button(text) {
  const found = [...document.querySelectorAll('button')].find(item => item.textContent.trim().includes(text));
  assert.ok(found, `Button ${text} vorhanden`);
  return found;
}
function captionInput() {
  const label = [...document.querySelectorAll('label')].find(item => item.textContent.includes('Pre-Text / Accountname'));
  assert.ok(label);
  const input = label.querySelector('input');
  assert.ok(input);
  return input;
}
function editorialCaption() {
  const field = document.querySelector('textarea[aria-label="Caption"]');
  assert.ok(field);
  return field;
}
function type(input, value) {
  input.focus();
  act(() => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function clickSave() {
  await act(async () => { button('Pre-Text speichern').click(); });
  await settle();
}
function storageHint() {
  return JSON.stringify({ ...backend });
}
async function syncFromApi() {
  await act(async () => { window.dispatchEvent(new Event('focus')); });
  await settle();
}
async function simulateOtherTab() {
  await act(async () => {
    window.dispatchEvent(new StorageEvent('storage', {
      key: CAPTION_PRETEXT_NOTICE_KEY, newValue: storageHint(), storageArea: window.localStorage,
    }));
  });
  await settle();
}

beforeEach(() => {
  backend = { pretext: 'noizycologne', revision: 1 };
  putFails = false;
  roots = [];
  window.localStorage.clear();
  globalThis.fetch = fetchFixture;
});
afterEach(async () => {
  for (const root of roots) await act(async () => root.unmount());
  roots = [];
  document.body.replaceChildren();
  globalThis.fetch = originalFetch;
  dom.window.Storage.prototype.setItem = originalSetItem;
  window.innerWidth = 1440;
});

test('Änderung in Tab B aktualisiert die offene Redaktion A und bestätigt genau den gespeicherten Stand', async () => {
  await mount(h(EditorialPage));
  await mount(h(SettingsPage));
  assert.match(editorialCaption().value, /^noizycologne /);

  type(captionInput(), 'Team Pretext');
  assert.doesNotMatch(button('Pre-Text speichern').textContent, /Gespeichert/);
  await clickSave();
  assert.match(button('Gespeichert').textContent, /Gespeichert/);
  assert.deepEqual(backend, { pretext: 'Team Pretext', revision: 2 });
  assert.equal(JSON.parse(window.localStorage.getItem(CAPTION_PRETEXT_NOTICE_KEY)).revision, 2);
  assert.match(editorialCaption().value, /^Team Pretext /);

  // Simulation des nativen storage-Events in einem anderen Tab: der Hinweis allein
  // muss die Redaktion aktualisieren, ohne dass dort gespeichert wurde.
  backend = { pretext: 'Anderer Tab', revision: 3 };
  await simulateOtherTab();
  assert.match(editorialCaption().value, /^Anderer Tab /);
  assert.doesNotMatch(button('Pre-Text speichern').textContent, /Gespeichert/,
    'Ein externer Stand darf in Tab B nicht als eigene Speicherung erscheinen');
});

test('Fehlerfall: gescheiterte Speicherung ist kein Erfolg, blockiertes localStorage verfälscht die Meldung nicht', async () => {
  await mount(h(SettingsPage));
  await syncFromApi();
  const original = backend.pretext;
  putFails = true;
  type(captionInput(), 'Nicht gespeichert');
  await act(async () => { button('Pre-Text speichern').click(); });
  await settle();
  assert.ok(document.querySelector('[role="alert"]').textContent.includes('Speichern fehlgeschlagen'));
  assert.doesNotMatch(button('Pre-Text speichern').textContent, /Gespeichert/);
  assert.equal(backend.pretext, original);

  putFails = false;
  dom.window.Storage.prototype.setItem = function (key) {
    if (key === CAPTION_PRETEXT_NOTICE_KEY) throw new Error('QuotaExceededError');
    return originalSetItem.call(this, key, arguments[1]);
  };
  type(captionInput(), 'Trotz Storage gespeichert');
  await clickSave();
  assert.match(button('Gespeichert').textContent, /Gespeichert/);
  assert.equal(backend.pretext, 'Trotz Storage gespeichert');
  assert.equal(window.localStorage.getItem(CAPTION_PRETEXT_NOTICE_KEY), null,
    'Der Erfolg darf nicht aus dem localStorage-Hinweis stammen');
});

test('390 px: Genre-Drawer und Pre-Text-Eingabe bleiben nach dem Schließen bedienbar', async () => {
  window.innerWidth = 390;
  await mount(h(SettingsPage));
  await syncFromApi();
  await act(async () => { window.dispatchEvent(new Event('resize')); });
  const opener = button('Genre anlegen');
  opener.focus();
  act(() => opener.click());
  const dialog = document.querySelector('[role="dialog"]');
  assert.ok(dialog);
  act(() => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.activeElement.dispatchEvent(event);
  });
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, opener);

  type(captionInput(), 'Mobil gespeichert');
  await clickSave();
  assert.match(button('Gespeichert').textContent, /Gespeichert/);
  assert.equal(backend.pretext, 'Mobil gespeichert');
});
