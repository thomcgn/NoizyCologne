import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost' });
for (const name of ['window', 'document', 'Event', 'HTMLElement']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const require = createRequire(import.meta.url);
const React = require('react');
const { act, createElement: h, StrictMode, useState } = React;
const { createRoot } = require('react-dom/client');
const originalFetch = globalThis.fetch;
const originalVisibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
const originalSetInterval = window.setInterval;
const originalClearInterval = window.clearInterval;

let root;
let intervalCallbacks = [];
let clearedIntervals = 0;
let tick;
function setVisibility(state) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}
function delay(ms = 25) { return new Promise(resolve => setTimeout(resolve, ms)); }
async function settle(work = () => {}) {
  await act(async () => { await work(); await delay(); });
}
async function waitFor(predicate, attempts = 20) {
  for (let index = 0; index < attempts && !predicate(); index++) await settle();
  assert.ok(predicate(), 'expected state was reached');
}
const modules = new Map();
function loadSource(url) {
  if (modules.has(url.href)) return modules.get(url.href);
  const loaded = { exports: {} };
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  function localRequire(specifier) {
    if (specifier === 'next/image') return { default: ({ src, alt }) => h('img', { src, alt }) };
    if (specifier === 'next/navigation') return { useSearchParams: () => new URLSearchParams('') };
    if (specifier === 'next/link') return { default: ({ href, children }) => h('a', { href }, children) };
    if (specifier === './useDayPreview') return { useDayPreview: () => ({ date: null, handlers: () => ({}), open() {}, close() {} }) };
    if (specifier === './EntryEditor') return { default: () => null };
    if (specifier === '../DashboardModal') return { default: ({ open, children }) => open ? h('div', null, children) : null };
    if (specifier === '../DataCollection') return { default: props => h('div', null, props.items.map(item => h('p', { key: props.getName(item) }, props.getName(item)))) };
    if (specifier === '../workspaceActions') return { useWorkspaceAction: () => {} };
    if (specifier.startsWith('.')) {
      for (const extension of ['.tsx', '.ts']) {
        const next = new URL(specifier + extension, url);
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
const catalogSource = new URL('../src/app/dashboard/CatalogProvider.tsx', import.meta.url);
const calendarSource = new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url);
const pollingSource = new URL('../src/app/dashboard/vorschlaege/useDiscoveryPolling.ts', import.meta.url);
const historySource = new URL('../src/app/dashboard/vorschlaege/DiscoveryHistory.tsx', import.meta.url);

afterEach(() => {
  if (root) act(() => root.unmount());
  root = null;
  document.body.replaceChildren();
  globalThis.fetch = originalFetch;
  window.setInterval = originalSetInterval;
  window.clearInterval = originalClearInterval;
  intervalCallbacks = [];
  clearedIntervals = 0;
  tick = undefined;
  if (originalVisibility) Object.defineProperty(document, 'visibilityState', originalVisibility);
});

function mount(element) {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  return settle(() => root.render(h(StrictMode, null, element)));
}
function catalogFetch(calls) {
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    return new Response(JSON.stringify([]));
  };
}
function captureInterval() {
  window.setInterval = callback => { intervalCallbacks.push(callback); tick = callback; return intervalCallbacks.length; };
  window.clearInterval = () => { clearedIntervals++; };
}

test('catalog context value stays identical across parent renders without data changes', async () => {
  const { CatalogProvider, useCatalog } = loadSource(catalogSource);
  const calls = [];
  catalogFetch(calls);
  const values = [];
  let bump;
  function Probe() { values.push(useCatalog()); return null; }
  function Wrapper() {
    const [, setCount] = useState(0);
    bump = () => setCount(current => current + 1);
    return h(CatalogProvider, null, h(Probe));
  }
  await mount(h(Wrapper));
  await waitFor(() => {
    const value = values.at(-1);
    return !!value && !value.entriesLoading && !value.partiesLoading && !value.venuesLoading && !value.actorsLoading && !value.draftsLoading;
  });
  assert.equal(values.length > 1, true, 'the probe rendered');
  const stable = values.at(-1);
  assert.ok(stable, 'catalog context is available');
  const rendersBefore = values.length;
  await settle(() => bump());
  assert.equal(values.length > rendersBefore, true, 'the parent re-render reached the consumer');
  assert.equal(values.at(-1), stable, 'unchanged catalog state must not create a new context value');
});

test('calendar mount does not repeat the provider-owned entries and parties fetches', async () => {
  const { CatalogProvider } = loadSource(catalogSource);
  const Page = loadSource(calendarSource).default;
  const calls = [];
  catalogFetch(calls);
  await mount(h(CatalogProvider, null, h(Page)));
  await waitFor(() => calls.includes('/api/v1/calendar-entries'));
  assert.equal(calls.filter(url => url === '/api/v1/calendar-entries').length, 1);
  assert.equal(calls.filter(url => url === '/api/v1/parties').length, 1);
  assert.equal(calls.filter(url => url === '/api/v1/actors').length, 1);
  assert.equal(calls.filter(url => url === '/api/v1/venues').length, 1);
  assert.equal(calls.filter(url => url === '/api/v1/editorial-drafts').length, 1);
});

test('all discovery subscribers use one timer and polling pauses while hidden', async () => {
  captureInterval();
  const { useDiscoveryPolling } = loadSource(pollingSource);
  const hits = [];
  function Probe({ id }) { useDiscoveryPolling(() => { hits.push(id); }); return null; }
  await mount(h('div', null, h(Probe, { id: 'overview' }), h(Probe, { id: 'changes' })));
  assert.equal(intervalCallbacks.length - clearedIntervals, 1, 'one active shared interval for all discovery panels');
  assert.ok(tick, 'the shared ticker was registered');

  setVisibility('visible');
  await settle(() => tick());
  assert.deepEqual(hits, ['overview', 'changes']);
  hits.length = 0;

  setVisibility('hidden');
  await settle(() => tick());
  assert.deepEqual(hits, [], 'hidden tabs do not poll');
  await settle(() => document.dispatchEvent(new dom.window.Event('visibilitychange')));
  assert.deepEqual(hits, [], 'hidden tabs do not refresh on visibility events');

  setVisibility('visible');
  await settle(() => document.dispatchEvent(new dom.window.Event('visibilitychange')));
  assert.deepEqual(hits, ['overview', 'changes'], 'returning to the tab refreshes once');
});

test('history ignores unchanged overview data and only reloads for visible, relevant changes', async () => {
  captureInterval();
  const DiscoveryHistory = loadSource(historySource).default;
  let historyCalls = 0;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/api/v1/discovery?filter=')) {
      historyCalls++;
      return new Response(JSON.stringify({ candidates: [] }));
    }
    return new Response(JSON.stringify([]));
  };
  const overview = { automaticallyImported: 0, runs: [{ id: 'run-1', status: 'DONE', report: 'ok' }], candidates: [], sources: [] };
  const props = { data: overview, onReview() {}, onReviewId() {} };
  await mount(h(DiscoveryHistory, props));
  assert.equal(historyCalls, 1, 'strict mode must not fetch the initial history twice');

  await settle(() => tick());
  assert.equal(historyCalls, 1, 'polling without relevant changes does not reload history');

  await settle(() => root.render(h(StrictMode, null, h(DiscoveryHistory, { ...props, data: { ...overview } }))));
  assert.equal(historyCalls, 1, 'a new data reference with unchanged values is ignored');

  await settle(() => root.render(h(StrictMode, null, h(DiscoveryHistory, { ...props, data: { ...overview, automaticallyImported: 1 } }))));
  assert.equal(historyCalls, 2, 'a changed overview value refreshes history');
});

test('HTTP 413 reports the shared 10 MB flyer limit', async () => {
  const { CatalogProvider, useCatalog } = loadSource(catalogSource);
  const calls = [];
  catalogFetch(calls);
  let catalog;
  function Probe() { catalog = useCatalog(); return null; }
  await mount(h(CatalogProvider, null, h(Probe)));
  await waitFor(() => !!catalog && !catalog.entriesLoading && !catalog.partiesLoading);

  globalThis.fetch = async () => new Response('', { status: 413 });
  await settle(async () => {
    await assert.rejects(catalog.reloadEntries({ throwOnError: true }), /höchstens 10 MB/);
    await assert.rejects(catalog.reloadParties({ throwOnError: true }), /höchstens 10 MB/);
  });
  assert.match(catalog.entriesError ?? '', /höchstens 10 MB/);
  assert.match(catalog.partiesError ?? '', /höchstens 10 MB/);
  assert.doesNotMatch(catalog.entriesError ?? '', /8 MB/);
});
