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
// Browser code uses window.setInterval and the global clearInterval interchangeably.
window.setInterval = globalThis.setInterval;
window.clearInterval = globalThis.clearInterval;
const require = createRequire(import.meta.url);
const React = require('react');
const { createRoot } = require('react-dom/client');
const { act, createElement: h, useState, StrictMode } = React;
const filename = new URL('../src/app/dashboard/DashboardModal.tsx', import.meta.url);
const compiled = ts.transpileModule(readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText;
const componentModule = { exports: {} };
new Function('require', 'module', 'exports', compiled)(require, componentModule, componentModule.exports);
const { default: DashboardModal, useModalFocus } = componentModule.exports;
let root;
afterEach(() => {
  if (root) act(() => root.unmount());
  root = null;
  document.body.replaceChildren();
});
function mount(component) {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(StrictMode, null, component)));
}
function click(element) { act(() => element.click()); }
function key(key, shiftKey = false) {
  const event = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });
  act(() => document.activeElement.dispatchEvent(event));
  return event;
}
function type(input, text) {
  input.focus();
  for (const letter of text) {
    act(() => {
      Object.getOwnPropertyDescriptor(input instanceof dom.window.HTMLTextAreaElement ? dom.window.HTMLTextAreaElement.prototype : dom.window.HTMLInputElement.prototype, 'value').set.call(input, input.value + letter);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    assert.ok(document.activeElement === input, "Typed field must retain focus after each character");
  }
}
function Form({ custom = false }) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState('');
  const [checked, setChecked] = useState(false);
  const [choice, setChoice] = useState('a');
  const [error, setError] = useState(false);
  const [closedValue, setClosedValue] = useState('');
  const close = () => { setClosedValue(value); setOpen(false); };
  const children = h('form', { onSubmit: e => { e.preventDefault(); if (value === 'valid') close(); else setError(true); } },
    h('input', { 'aria-label': 'First' }),
    h('input', { 'aria-label': 'Second', value, onChange: e => setValue(e.target.value) }),
    h('input', { type: 'checkbox', checked, onChange: e => setChecked(e.target.checked) }),
    h('select', { value: choice, onChange: e => setChoice(e.target.value) }, h('option', { value: 'a' }, 'A'), h('option', { value: 'b' }, 'B')),
    error && h('p', { role: 'alert' }, 'Please correct the input'),
    h('button', { type: 'button', onClick: close }, 'Cancel'),
    h('button', { type: 'submit' }, 'Save'));
  return h(React.Fragment, null,
    h('button', { id: 'trigger', onClick: () => setOpen(true) }, 'Open'),
    h('output', { id: 'closed-value' }, closedValue),
    custom ? h(CustomDialog, { open, close }, children) : h(DashboardModal, { title: 'Test', open, onClose: close }, children));
}
function CustomDialog({ open, close, children }) {
  const ref = React.useRef(null);
  useModalFocus(open, ref, close);
  return open && h('div', { ref, role: 'dialog', tabIndex: -1 }, children);
}
function openForm(custom = false) {
  mount(h(Form, { custom }));
  const trigger = document.querySelector('#trigger');
  trigger.focus();
  click(trigger);
  assert.equal(document.activeElement.getAttribute('aria-label'), 'First');
  return trigger;
}
for (const custom of [false, true]) {
  test(`${custom ? 'calendar layout' : 'DashboardModal'}: typing, field changes, checkbox/select and latest Escape callback`, () => {
    const trigger = openForm(custom);
    const second = document.querySelector('[aria-label="Second"]');
    type(second, 'abcdef');
    assert.equal(second.value, 'abcdef');
    const first = document.querySelector('[aria-label="First"]');
    type(first, 'first');
    const checkbox = document.querySelector('[type="checkbox"]');
    checkbox.focus();
    click(checkbox);
    assert.equal(checkbox.checked, true);
    assert.equal(document.activeElement, checkbox);
    const select = document.querySelector('select');
    select.focus();
    act(() => { select.value = 'b'; select.dispatchEvent(new Event('change', { bubbles: true })); });
    assert.equal(document.activeElement, select);
    key('Escape');
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.querySelector('#closed-value').textContent, 'abcdef');
    assert.equal(document.activeElement, trigger);
    click(trigger);
    assert.equal(document.activeElement, first.isConnected ? first : document.querySelector('[aria-label="First"]'));
  });
  test(`${custom ? 'calendar layout' : 'DashboardModal'}: Tab boundaries, cancel, validation error and successful save restore focus`, () => {
    const trigger = openForm(custom);
    const controls = document.querySelectorAll('[role="dialog"] input, [role="dialog"] select, [role="dialog"] button');
    const first = controls[0];
    const last = controls[controls.length - 1];
    last.focus();
    assert.equal(key('Tab').defaultPrevented, true);
    const boundaryFirst = document.activeElement;
    assert.equal(boundaryFirst, document.querySelector('[role="dialog"] button, [role="dialog"] input'));
    assert.equal(key('Tab', true).defaultPrevented, true);
    assert.equal(document.activeElement, last);
    click(last);
    assert.ok(document.querySelector('[role="alert"]'));
    assert.equal(document.activeElement, last);
    const second = document.querySelector('[aria-label="Second"]');
    type(second, 'valid');
    click(last);
    assert.equal(document.activeElement, trigger);
    click(trigger);
    assert.notEqual(document.activeElement, first);
    click([...document.querySelectorAll('button')].find(b => b.textContent === 'Cancel'));
    assert.equal(document.activeElement, trigger);
  });
}
test('closing by unmount restores a connected trigger; closed rerenders do not steal focus', () => {
  const trigger = document.createElement('button');
  document.body.append(trigger);
  trigger.focus();
  mount(h(DashboardModal, { open: true, title: 'Unmount', onClose() {} }, h('input')));
  assert.notEqual(document.activeElement, trigger);
  act(() => root.render(null));
  assert.equal(document.activeElement, trigger);
  const external = document.createElement('button');
  document.body.append(external);
  external.focus();
  for (let index = 0; index < 3; index++) {
    act(() => root.render(h(DashboardModal, { open: false, title: 'Closed', onClose() {} })));
    assert.equal(document.activeElement, external);
  }
});
test('disabled, hidden and collapsed controls are excluded; empty dialog traps Tab', () => {
  mount(h(DashboardModal, { open: true, title: 'Hidden', onClose() {} },
    h('input', { disabled: true }), h('input', { type: 'hidden' }),
    h('div', { hidden: true }, h('input')),
    h('details', null, h('summary', null, 'More'), h('input')),
    h('input', { id: 'visible' })));
  assert.equal(document.activeElement.id, 'visible');
  // The close button remains a valid boundary even without form controls.
  act(() => root.render(h(CustomDialog, { open: true, close() {} })));
  assert.equal(document.activeElement.getAttribute('role'), 'dialog');
  assert.equal(key('Tab').defaultPrevented, true);
  assert.equal(document.activeElement.getAttribute('role'), 'dialog');
});

// Load the actual forms with an in-memory catalogue. No application API is contacted.
const sourceCache = new Map();
let catalogue;
const reloadCalendarCatalog = async () => {};
function loadSource(url) {
  const path = url.pathname;
  if (sourceCache.has(path)) return sourceCache.get(path);
  const loadedModule = { exports: {} };
  sourceCache.set(path, loadedModule.exports);
  const code = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const localRequire = (specifier) => {
    if (specifier === 'next/link') return { __esModule: true, default: props => h('a', props) };
    if (specifier === 'next/navigation') return { useSearchParams: () => new URLSearchParams() };
    if (specifier.endsWith('/CatalogProvider')) return {
      ...loadSource(new URL('../src/app/dashboard/CatalogProvider.tsx', import.meta.url)),
      useCatalog: () => ({ reloadEntries: reloadCalendarCatalog, reloadParties: reloadCalendarCatalog, ...catalogue }),
    };
    if (specifier.endsWith('/DashboardModal')) return componentModule.exports;
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
  new Function('require', 'module', 'exports', code)(localRequire, loadedModule, loadedModule.exports);
  sourceCache.set(path, loadedModule.exports);
  return loadedModule.exports;
}
const party = { id: 'party', name: 'Test Party', instagram: 'party', color: '#123456', residentDjIds: [], otherDjIds: [], organizerIds: [], standardGenres: [], djMentionDefault: 'manual', recurrence: null, defaultFlyer: null };
const venue = { id: 'venue', name: 'Test Venue', instagram: 'venue', websiteUrl: '', eventsUrl: '', discoveryEnabled: false };
const actor = { id: 'actor', name: 'Test DJ', instagram: 'dj', kind: 'person', roles: ['dj'], collectiveIds: [] };
const entry = { id: 'entry', date: '2027-01-02', type: 'event', locationId: 'venue', partyId: 'party', entryPrice: '10', startTime: '22:00', notes: '', mentionDjIds: [], eventFlyer: null };
const currentCalendarEntry = () => ({ ...entry, date: new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) });
const genre = { id: 'genre', canonicalName: 'rock', displayName: 'Rock', active: true, aliases: [] };
// jsdom does not compile Tailwind. Supply the two utility baselines, then apply
// the real global stylesheet so theme overrides are tested on the actual forms.
const themeStyles = `.bg-white { background-color: #ffffff; } .text-black { color: #000000; }\n${readFileSync(new URL('../src/app/globals.css', import.meta.url), 'utf8').replace('@import "tailwindcss";', '')}`;
function contrastRatio(foreground, background) {
  function luminance(color) {
    const channels = color.match(/\d+(?:\.\d+)?/g).slice(0, 3).map(value => {
      const channel = Number(value) / 255;
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  }
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}
const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
function button(text) {
  const result = [...document.querySelectorAll('button')].find(element => element.textContent.trim().includes(text));
  assert.ok(result, `Button ${text} exists`);
  return result;
}
for (const [name, file, props, opener] of [
  ['Genres', 'settings/genres/page.tsx', {}, 'Genre anlegen'],
  ['Partyreihen', 'portfolio/page.tsx', {}, 'Neu anlegen'],
  ['Locations', 'portfolio/page.tsx', { initialCategory: 'locations' }, 'Neu anlegen'],
  ['DJs / Akteure', 'portfolio/akteure/page.tsx', {}, 'Neu anlegen'],
  ['Kalendereinträge', 'kalender/page.tsx', {}, 'Neuer Eintrag'],
]) {
  test(`${name}: actual create/edit forms preserve focus and button contrast across themes`, async () => {
    catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [entry] };
    globalThis.ResizeObserver = class { observe() {} disconnect() {} };
    globalThis.fetch = async () => new Response(JSON.stringify([genre]), { status: 200, headers: { 'Content-Type': 'application/json' } });
    const Page = loadSource(new URL(`../src/app/dashboard/${file}`, import.meta.url)).default;
    await act(async () => { mount(h(Page, props)); });
    const style = document.createElement('style');
    style.textContent = themeStyles;
    document.body.append(style);
    for (const mode of ['create', 'edit']) {
      const trigger = button(mode === 'create' ? opener : 'Bearbeiten');
      trigger.focus();
      click(trigger);
      const dialog = document.querySelector('[role="dialog"]');
      assert.ok(dialog);
      const inputs = [...dialog.querySelectorAll('input')].filter(input => ['text', 'url'].includes(input.type));
      assert.ok(inputs.length >= 1);
      type(inputs[Math.min(1, inputs.length - 1)], 'testing');
      for (const checkbox of dialog.querySelectorAll('input[type="checkbox"]:not(:disabled)')) {
        checkbox.focus();
        click(checkbox);
        assert.equal(document.activeElement, checkbox);
      }
      for (const select of dialog.querySelectorAll('select')) {
        if (select.options.length < 2) continue;
        select.focus();
        act(() => {
          select.selectedIndex = select.selectedIndex === 0 ? 1 : 0;
          select.dispatchEvent(new Event('change', { bubbles: true }));
        });
        assert.equal(document.activeElement, select);
      }
      const lastButton = [...dialog.querySelectorAll('button:not(:disabled)')].at(-1);
      lastButton.focus();
      key('Tab');
      assert.equal(document.activeElement, dialog.querySelector('button'));
      key('Tab', true);
      assert.equal(document.activeElement, lastButton);
      const focusedField = dialog.querySelector('input:not(:disabled):not([type="hidden"]), select, textarea');
      const primaryButtons = dialog.querySelectorAll('button.bg-white.text-black');
      assert.ok(primaryButtons.length > 0, `${name} ${mode}: primary actions are included in the contrast check`);
      for (const theme of ['dark', 'light']) {
        for (const width of [390, 1280]) {
          document.documentElement.dataset.theme = theme;
          window.innerWidth = width;
          focusedField.focus();
          act(() => window.dispatchEvent(new Event('resize')));
          assert.equal(document.activeElement, focusedField);
          for (const primary of primaryButtons) {
            const computed = getComputedStyle(primary);
            assert.ok(contrastRatio(computed.color, computed.backgroundColor) >= 4.5,
              `${name} ${mode}: ${primary.textContent} must remain legible in ${theme} mode at ${width}px`);
          }
        }
      }
      key('Escape');
      assert.equal(document.querySelector('[role="dialog"]'), null);
      assert.equal(document.activeElement, trigger);
      click(trigger);
      click(button('Abbrechen'));
      assert.equal(document.activeElement, trigger);
      assert.equal(document.querySelector('[role="dialog"]'), null);
    }
  });
}

test('suggestion review and source/profile dialogs preserve edits and focus', async () => {
  catalogue = { parties: [party], venues: [venue] };
  const overview = { automaticallyImported: 0, counts: [], runs: [],
    candidates: [{ id: 'candidate', title: 'Party', status: 'NEEDS_REVIEW', inquiry_status: 'PREPARED', dm_text: '', venue_name: 'Venue', evidence: '' }],
    sources: [{ id: 'source', special_source: true, name: 'QRUSH', venue_id: null, venue_name: '', url: 'https://example.org', approved: false, status: 'NOT_CHECKED', item_selector: '', title_selector: '', date_selector: '' }] };
  globalThis.fetch = async () => new Response(JSON.stringify(overview), { headers: { 'Content-Type': 'application/json' } });
  const Page = loadSource(new URL('../src/app/dashboard/vorschlaege/page.tsx', import.meta.url)).default;
  await act(async () => {
    mount(h(Page));
  });
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 20));
  });
  for (const [opener, selector] of [['Veranstaltung prüfen', 'textarea'], ['Sonderquelle editieren', 'input[type="url"]']]) {
    // The sources section is intentionally collapsible.
    for (const details of document.querySelectorAll('details')) details.open = true;
    const trigger = button(opener);
    trigger.focus();
    click(trigger);
    const input = document.querySelector(`[role="dialog"] ${selector}`);
    type(input, 'testing');
    key('Escape');
    assert.equal(document.activeElement, trigger);
    assert.equal(document.querySelector('[role="dialog"]'), null);
  }
});

test('calendar information dialog traps focus and restores its trigger', async () => {
  catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [currentCalendarEntry()] };
  const Page = loadSource(new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  const trigger = document.querySelector('button[title="Test Party"]');
  assert.ok(trigger);
  trigger.focus();
  click(trigger);
  const dialog = document.querySelector('[role="dialog"]');
  assert.ok(dialog);
  const buttons = dialog.querySelectorAll('button');
  buttons[buttons.length - 1].focus();
  key('Tab');
  assert.equal(document.activeElement, buttons[0]);
  key('Escape');
  assert.equal(document.activeElement, trigger);
});

// Switching between the calendar's two layouts must retain the page trigger.
test('calendar information → editor → Escape restores the original page trigger', async () => {
  catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [currentCalendarEntry()] };
  const Page = loadSource(new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  const trigger = document.querySelector('button[title="Test Party"]');
  trigger.focus();
  click(trigger);
  click(button('Bearbeiten'));
  assert.equal(document.querySelector('[role="dialog"] h2').textContent, 'Kalendereintrag bearbeiten');
  key('Escape');
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
});

test('discovery reset shows preview and requires a second explicit manual-rejection confirmation', async () => {
  const calls = [];
  const started = [];
  const preview = { token: 'a'.repeat(64), affectedCount: 3, preservedManual: 1, missingRaw: 2, enabledLocations: 1,
    items: [{ id: 'candidate', title: 'Unknown Party', venue: 'Venue', oldStatus: 'IGNORED', newStatus: 'NEEDS_REVIEW', classification: 'POSSIBLE_MATCH', reason: 'Genre-Match: Emo' }] };
  globalThis.fetch = async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body) });
    return new Response(JSON.stringify(url.endsWith('/preview') ? preview : { operationId: 'operation' }));
  };
  const Dialog = loadSource(new URL('../src/app/dashboard/vorschlaege/DiscoveryResetDialog.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Dialog, { initialMode: 'IGNORED', onClose() {}, onStarted: id => started.push(id) })); });
  assert.ok(document.querySelector('[aria-label="Reset-Vorschau"]').textContent.includes('3 Kandidaten'));
  const checkbox = document.querySelector('input[type="checkbox"]');
  assert.equal(checkbox.checked, false);
  checkbox.focus();
  await act(async () => { checkbox.click(); });
  assert.equal(document.activeElement, checkbox);
  assert.equal(button('Neubewertung bestätigen').disabled, true);
  click(document.querySelectorAll('input[type="checkbox"]')[1]);
  assert.equal(button('Neubewertung bestätigen').disabled, false);
  await act(async () => { button('Neubewertung bestätigen').click(); });
  const request = calls.find(call => call.url.endsWith('/reset'));
  assert.equal(request.body.includeManualRejected, true);
  assert.equal(request.body.confirmation, 'MANUELL ABGELEHNTE ERNEUT PRÜFEN');
  assert.equal(request.body.previewToken, preview.token);
  assert.equal(request.body.mode, 'IGNORED');
  assert.deepEqual(started, ['operation']);
});

test('discovery reset invalidates the preview when switching to a complete rescan', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    requests.push(body);
    return new Response(JSON.stringify({ token: body.mode, affectedCount: 0, preservedManual: 0, missingRaw: 0, enabledLocations: 2, items: [] }));
  };
  const Dialog = loadSource(new URL('../src/app/dashboard/vorschlaege/DiscoveryResetDialog.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Dialog, { initialMode: 'IGNORED', onClose() {}, onStarted() {} })); });
  await act(async () => { document.querySelector('input[value="RESCAN"]').click(); });
  assert.ok(requests.some(request => request.mode === 'RESCAN'));
  assert.ok(document.querySelector('[aria-label="Reset-Vorschau"]').textContent.includes('2 aktivierte Locations'));
  assert.equal(document.querySelector('input[type="checkbox"]').checked, false);
});

test('party standard genres submit display names, remain checked on edit and can be removed', async () => {
  const payloads = [];
  const save = async data => {
    payloads.push(data);
    const saved = { ...party, ...data, id: 'genre-party' };
    catalogue.parties = [saved];
    return saved;
  };
  catalogue = { parties: [], venues: [venue], actors: [], createParty: save, updateParty: save };
  const names = ['Emo', 'Metalcore', 'Hardcore'];
  globalThis.fetch = async url => new Response(JSON.stringify(url === '/api/v1/genres'
    ? names.map((name, index) => ({ id: `genre-id-${index}`, canonicalName: name.toLowerCase(), displayName: name, active: true })) : []));
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  click(button('Neu anlegen'));
  type(document.querySelector('[role="dialog"] input'), 'Genre Test');
  changeField(document.querySelector('[role="dialog"] input[type="time"]'), '23:00');
  function genreCheckbox(name) {
    const label = [...document.querySelectorAll('[role="dialog"] label')].find(label => label.textContent.trim() === name);
    assert.ok(label, `Genre label ${name}`);
    return label.querySelector('input');
  }
  for (const name of names) click(genreCheckbox(name));
  await act(async () => { button('Übernehmen').click(); });
  assert.deepEqual([...payloads[0].standardGenres].sort(), [...names].sort());
  await act(async () => { button('Bearbeiten').click(); });
  for (const name of names) assert.equal(genreCheckbox(name).checked, true);
  click(genreCheckbox('Hardcore'));
  await act(async () => { button('Übernehmen').click(); });
  assert.deepEqual([...payloads[1].standardGenres].sort(), ['Emo', 'Metalcore']);
  await act(async () => { button('Bearbeiten').click(); });
  assert.equal(genreCheckbox('Hardcore').checked, false);
});

test('historical unavailable standard genres stay visible and can be explicitly removed', async () => {
  catalogue = { parties: [{ ...party, standardGenres: ['Retired Genre'] }], venues: [venue], actors: [] };
  globalThis.fetch = async () => new Response('[]');
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  await act(async () => { button('Bearbeiten').click(); });
  const label = [...document.querySelectorAll('[role="dialog"] label')].find(label => label.textContent.includes('Retired Genre'));
  assert.ok(label);
  assert.equal(label.querySelector('input').checked, true);
  click(label.querySelector('input'));
  assert.equal(label.isConnected, false);
});

const monitoredBefore = { title: 'Teenage Dirtbag', starts: ['2026-11-07T23:00:00'], location: 'Blue Shell', state: '', evidence: 'Bisherige Quelle', url: 'https://club.example/old' };
const monitoredAfter = { ...monitoredBefore, title: 'Neon Paradise', url: 'https://club.example/new' };
const changeFixture = { id: 'change', revision: 0, kind: 'POSSIBLE_REPLACEMENT', state: 'OPEN', venue_name: 'Blue Shell', instagram: 'blue_shell', before_data: JSON.stringify(monitoredBefore), after_data: JSON.stringify(monitoredAfter), source_url: monitoredAfter.url, reason: 'Mehrere Veranstaltungen sind möglich', blocks_publication: true, calendar_entry_id: 'entry', calendar_party_id: 'party', calendar_venue_id: 'venue', calendar_date: '2026-11-07', calendar_start_time: '23:00', dm_text: '', answer: '', inquiry_status: 'NONE' };
function changeCatalogue() { catalogue = { parties: [party], venues: [venue], entries: [entry], reloadEntries: async () => {}, reloadDrafts: async () => {} }; }
async function mountChanges(fetcher) {
  changeCatalogue(); globalThis.fetch = fetcher;
  const Panel = loadSource(new URL('../src/app/dashboard/vorschlaege/EventChangesPanel.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Panel)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
function jsonResponse(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); }

test('event replacement comparison, editable DM and manual sent marker preserve focus and original entry', async () => {
  const calls = [];
  await mountChanges(async (url, options = {}) => {
    calls.push({ url, ...options });
    if (url.endsWith('/dm')) return jsonResponse({ text: 'Hallo Blue-Shell-Team! Teenage Dirtbag oder Neon Paradise am 07.11.?' });
    if (url.endsWith('/history')) return jsonResponse([]);
    if (options.method === 'PUT') return new Response(null, { status: 204 });
    return jsonResponse(url.endsWith('/cancellations') ? [] : [changeFixture]);
  });
  const trigger = button('Änderung vergleichen'); trigger.focus();
  await act(async () => { trigger.click(); });
  const table = document.querySelector('[role="dialog"] table');
  assert.match(table.textContent, /Teenage Dirtbag/); assert.match(table.textContent, /Neon Paradise/);
  for (const details of document.querySelectorAll('[role="dialog"] details')) details.open = true;
  const note = document.querySelector('[role="dialog"] textarea'); type(note, 'Location gefragt');
  await act(async () => { button('DM-Entwurf erstellen').click(); });
  const dm = [...document.querySelectorAll('label')].find(label => label.textContent.startsWith('DM-Entwurf')).querySelector('textarea');
  type(dm, ' Bitte bestätigen.');
  assert.equal(document.activeElement, dm);
  assert.ok(!calls.some(call => call.method === 'PUT'));
  await act(async () => { button('Rückfrage gesendet (manuell)').click(); });
  const decision = calls.find(call => call.method === 'PUT');
  assert.equal(JSON.parse(decision.body).action, 'SENT');
  assert.match(JSON.parse(decision.body).dmText, /Bitte bestätigen/);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
  assert.deepEqual(catalogue.entries, [entry]);
  assert.ok(calls.every(call => call.url.startsWith('/api/v1/')));
});

test('stale event-change decision displays conflict and retains unsaved input', async () => {
  await mountChanges(async (url, options = {}) => {
    if (options.method === 'PUT') return jsonResponse({ message: 'Änderung wurde inzwischen bearbeitet; neu laden' }, 409);
    return jsonResponse(url.endsWith('/history') || url.endsWith('/cancellations') ? [] : [changeFixture]);
  });
  await act(async () => { button('Änderung vergleichen').click(); });
  const input = document.querySelector('[role="dialog"] textarea'); type(input, 'Beide finden statt');
  await act(async () => { button('Als getrennte Veranstaltungen behandeln').click(); });
  assert.ok(document.querySelector('[role="dialog"]'));
  assert.equal(input.value, 'Beide finden statt');
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /inzwischen bearbeitet/);
  key('Escape');
});

test('manual cancellation uses central endpoint and the separate graphic draft never publishes', async () => {
  const calls = [];
  const cancellation = { id: 'cancellation', revision: 0, calendar_entry_id: 'entry', party_name: 'Test Party', venue_name: 'Test Venue', event_date: '2027-01-02', reason_code: 'PERSONALBEDINGTER_AUSFALL', announcement_caption: 'Die Veranstaltung wurde abgesagt.', announcement_status: 'DRAFT', media_asset_id: null };
  let confirmed = false;
  await mountChanges(async (url, options = {}) => {
    calls.push({ url, ...options });
    if (options.method === 'POST' && url.endsWith('/cancellations')) { confirmed = true; return jsonResponse({ id: cancellation.id }); }
    if (options.method === 'POST' && url.endsWith('/graphic')) return jsonResponse({ mediaAssetId: 'new-cancelled-image' });
    return jsonResponse(url.endsWith('/cancellations') ? (confirmed ? [cancellation] : []) : []);
  });
  click(button('Veranstaltung manuell absagen'));
  const selects = document.querySelectorAll('[role="dialog"] select');
  act(() => { selects[0].value = 'entry'; selects[0].dispatchEvent(new Event('change', { bubbles: true })); selects[1].value = 'PERSONALBEDINGTER_AUSFALL'; selects[1].dispatchEvent(new Event('change', { bubbles: true })); });
  await act(async () => { button('Gesamte Veranstaltung verbindlich absagen').click(); });
  const confirm = calls.find(call => call.method === 'POST' && call.url.endsWith('/cancellations'));
  assert.equal(JSON.parse(confirm.body).trigger, 'MANUAL');
  assert.equal(JSON.parse(confirm.body).reasonCode, 'PERSONALBEDINGTER_AUSFALL');
  for (const details of document.querySelectorAll('details')) details.open = true;
  click(button('Cancellation-Workflow öffnen'));
  await act(async () => { button('Absagegrafik vorbereiten').click(); });
  assert.match(document.querySelector('[role="dialog"]').textContent, /kein Remote-Abbruch bestätigt/);
  assert.ok(document.querySelector('a[href="/api/v1/cancellations/cancellation/graphic"]'));
  assert.ok(!calls.some(call => /publish|instagram/.test(call.url)));
  assert.deepEqual(catalogue.entries, [entry]);
  key('Escape');
});

test('editorial monitoring warnings preserve multiline captions and deliberately empty mentions across calendar refreshes', async () => {
  const saved = [];
  const persisted = { id: 'draft', calendarEntryId: 'entry', caption: 'Mein eigener Text\nBeginn: bleibt redaktionell', hashtags: '#original', mentionActorIds: [], genres: ['Emo'], status: 'DRAFT', musicTrackId: null, approvalNeedsReview: true, publicationBlockers: ['RESCHEDULE_DETECTED: Datum prüfen'] };
  catalogue = { parties: [{ ...party, residentDjIds: ['actor'], djMentionDefault: 'residents' }], venues: [venue], actors: [actor], entries: [{ ...entry, mentionDjIds: ['actor'] }], entriesLoading: false, entriesError: null, reloadEntries: async () => {}, drafts: [persisted], draftsLoading: false, draftsError: null, reloadDrafts: async () => {}, musicTracks: [], musicTracksLoading: false, musicTracksError: null, reloadMusicTracks: async () => {}, updateDraft: async value => { saved.push(value); return value; } };
  globalThis.fetch = async () => jsonResponse([genre]);
  const Page = loadSource(new URL('../src/app/dashboard/redaktion/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
  const caption = [...document.querySelectorAll('label')].find(label => label.querySelector('span')?.textContent === 'Caption').querySelector('textarea');
  assert.equal(caption.value, persisted.caption);
  const mention = [...document.querySelectorAll('label')].find(label => label.textContent.includes('Test DJ')).querySelector('input');
  assert.equal(mention.checked, false, 'Explicitly cleared mentions must not be repopulated from party defaults');
  assert.match(document.body.textContent, /Originalpost gesperrt/);
  catalogue = { ...catalogue, entries: [{ ...entry, date: '2027-01-03', mentionDjIds: ['actor'] }] };
  await act(async () => { root.render(h(StrictMode, null, h(Page))); });
  assert.equal(caption.value, persisted.caption, 'A calendar refresh must never regenerate a saved caption');
  type(caption, ' redaktionell ergänzt');
  assert.match(document.querySelector('#editorial-editor').textContent, /Ungespeicherte Änderungen/);
  click(button('Vorschau'));
  assert.equal(document.querySelector('.cc-editorial-layout').dataset.panel, 'preview');
  click(button('Bearbeiten'));
  assert.equal(caption.value, persisted.caption + ' redaktionell ergänzt');
  const unload = new Event('beforeunload', {cancelable: true}); window.dispatchEvent(unload);
  assert.equal(unload.defaultPrevented, true);
  let prompts = 0; window.confirm = () => {prompts++; return false;};
  try { click(document.querySelector('#editorial-editor a')); assert.equal(prompts, 1); }
  finally {window.confirm = () => true;}
  await act(async () => { button('Entwurf speichern').click(); });
  const afterSave = new Event('beforeunload', {cancelable: true}); window.dispatchEvent(afterSave);
  assert.equal(afterSave.defaultPrevented, false);
  assert.doesNotMatch(document.querySelector('#editorial-editor').textContent, /Ungespeicherte Änderungen/);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].caption, persisted.caption + ' redaktionell ergänzt');
  assert.deepEqual(saved[0].mentionActorIds, []);
});

function websiteFixture(overrides = {}) {
  return { previewToken:'fixture-preview', websiteUrl:'https://new-club.example/', suggestedName:'New Club', checkedAt:'2026-11-06T09:00:00Z',
    eventCount:12,partyCount:8,knownSeriesCount:2,incomplete:true,problems:[],
    sources:[{url:'https://new-club.example/events',fetchMethod:'BROWSER',diagnostics:[],extraction:{status:'EVENTS_FOUND',events:[{}],complete:false}}],...overrides };
}
async function mountLocationOnboarding(fetcher, save, update = save) {
  catalogue={parties:[],venues:[],actors:[],createVenue:save,updateVenue:update};
  globalThis.fetch=fetcher;
  const Page=loadSource(new URL('../src/app/dashboard/portfolio/page.tsx',import.meta.url)).default;
  await act(async()=>{mount(h(Page,{initialCategory:'locations'}));});
  click(button('Neu anlegen'));
  const dialog=document.querySelector('[role="dialog"]');dialog.querySelector('details').open=true;
  const website=dialog.querySelector('input[type="url"]');type(website,'https://new-club.example/');return website;
}
test('location onboarding needs only a website, shows browser diagnostics and saves previewed sources without importing',async()=>{
  const requests=[],saved=[];
  const website=await mountLocationOnboarding(async(url,options={})=>{
    requests.push({url,...options});return new Response(JSON.stringify(url==='/api/v1/discovery/onboard'?websiteFixture():[]));
  },async data=>{saved.push(data);return {...data,id:'new-venue'};});
  await act(async()=>{button('Website automatisch prüfen').click();await new Promise(resolve=>setTimeout(resolve,20));});
  const dialog=document.querySelector('[role="dialog"]');
  assert.equal(dialog.querySelector('input').value,'New Club');assert.equal(document.activeElement,website);
  assert.match(dialog.textContent,/12 Veranstaltungen gefunden, 8 mögliche Partys, 2 bekannte Partyreihen/);
  assert.match(dialog.textContent,/Browser: Veranstaltungen gefunden/);assert.match(dialog.textContent,/Vollständigkeit.*nicht bestätigt/);
  await act(async()=>{button('Übernehmen').click();});
  assert.equal(saved.length,1);assert.equal(saved[0].websiteUrl,'https://new-club.example/');
  const sourceSave=requests.find(request=>request.url==='/api/v1/discovery/onboard/new-venue/sources');assert.ok(sourceSave);
  assert.deepEqual(JSON.parse(sourceSave.body),{previewToken:'fixture-preview'});
  assert.ok(!requests.some(request=>request.url.includes('/import')||request.url.includes('/calendar')));
  assert.equal(document.querySelector('[role="dialog"]'),null);
});
test('late website previews cannot overwrite a changed website, name or focus',async()=>{
  let finish;
  const website=await mountLocationOnboarding(async url=>url==='/api/v1/discovery/onboard'?new Promise(resolve=>{finish=resolve;}):new Response('[]'),async data=>({...data,id:'new-venue'}));
  click(button('Website automatisch prüfen'));
  act(()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(website,'https://changed.example/');website.dispatchEvent(new Event('input',{bubbles:true}));});
  await act(async()=>{finish(new Response(JSON.stringify(websiteFixture({suggestedName:'Stale Club'}))));await new Promise(resolve=>setTimeout(resolve,20));});
  assert.equal(website.value,'https://changed.example/');assert.equal(document.activeElement,website);
  assert.equal(document.querySelector('[role="dialog"] input').value,'');assert.doesNotMatch(document.querySelector('[role="dialog"]').textContent,/Stale Club|12 Veranstaltungen/);
});
test('source registration failure keeps the saved location for retry instead of creating a duplicate',async()=>{
  let created=0,updated=0,registrations=0;
  await mountLocationOnboarding(async url=>{
    if(url==='/api/v1/discovery/onboard')return new Response(JSON.stringify(websiteFixture()));
    if(url.endsWith('/sources'))return new Response(null,{status:++registrations===1?500:204});
    return new Response('[]');
  },async data=>{created++;return {...data,id:'retained-venue'};},async data=>{updated++;assert.equal(data.id,'retained-venue');return data;});
  await act(async()=>{button('Website automatisch prüfen').click();await new Promise(resolve=>setTimeout(resolve,20));});
  await act(async()=>{button('Übernehmen').click();});
  assert.match(document.querySelector('[role="alert"]').textContent,/Location ist gespeichert/);
  await act(async()=>{button('Offene Schritte fortsetzen').click();});
  assert.equal(created,1);assert.equal(updated,0);assert.equal(document.querySelector('[role="dialog"]'),null);
});
test('unreadable website reports an unknown count rather than a confirmed zero events',async()=>{
  await mountLocationOnboarding(async url=>new Response(JSON.stringify(url==='/api/v1/discovery/onboard'?websiteFixture({eventCount:0,partyCount:0,knownSeriesCount:0,problems:['BROWSER_TIMEOUT'],sources:[]}):[])),async data=>({...data,id:'new-venue'}));
  await act(async()=>{button('Website automatisch prüfen').click();await new Promise(resolve=>setTimeout(resolve,20));});
  const text=document.querySelector('[role="dialog"]').textContent;
  assert.match(text,/Keine verlässliche Veranstaltungszahl ermittelbar/);assert.match(text,/Browser-Zeitlimit/);assert.doesNotMatch(text,/0 Veranstaltungen gefunden/);
});

function changeField(element, value) {
  element.focus();
  act(() => {
    if (element.tagName === 'SELECT') {
      element.value = value;
      element.dispatchEvent(new Event('change', {bubbles: true}));
    } else {
      const prototype = element.tagName === 'TEXTAREA' ? dom.window.HTMLTextAreaElement.prototype : dom.window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
      element.dispatchEvent(new Event('input', {bubbles: true}));
    }
  });
}

test('party defaults require a new start time and preserve unknown, free and slider values across edits', async () => {
  const saved=[];
  const save=async value=>{const result={...value,id:'defaults'};saved.push(value);catalogue.parties=[result];return result;};
  catalogue={parties:[],venues:[venue],actors:[],createParty:save,updateParty:save};
  globalThis.fetch=async url=>new Response(JSON.stringify(url.includes('aliases')?[]:[genre]));
  const Page=loadSource(new URL('../src/app/dashboard/portfolio/page.tsx',import.meta.url)).default;
  await act(async()=>mount(h(Page)));click(button('Neu anlegen'));
  type(document.querySelector('[role="dialog"] input'),'Default Night');
  const time=document.querySelector('input[type="time"]');assert.equal(time.required,true);
  const range=document.querySelector('input[type="range"]');assert.equal(range.disabled,true);
  assert.equal(range.min,'0');assert.equal(range.max,'30');assert.equal(range.step,'1');
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved.length,0);
  changeField(time,'22:45');
  const unknown=[...document.querySelectorAll('label')].find(label=>label.textContent==='Preis noch unbekannt').querySelector('input');
  click(unknown);assert.match(document.querySelector('output').textContent,/0 €.*Eintritt frei/);
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved[0].defaultStartTime,'22:45');assert.equal(saved[0].defaultEntryPrice,0);
  await act(async()=>button('Bearbeiten').click());
  assert.equal(document.querySelector('input[type="time"]').value,'22:45');
  changeField(document.querySelector('input[type="range"]'),'30');assert.equal(document.querySelector('output').textContent,'30 €');
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved[1].defaultEntryPrice,30);
  await act(async()=>button('Bearbeiten').click());
  click([...document.querySelectorAll('label')].find(label=>label.textContent==='Preis noch unbekannt').querySelector('input'));
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved[2].defaultEntryPrice,null);
});

test('new calendar forms suggest series defaults and retain manual values while relative starts stay unknown', async () => {
  const saved=[];
  catalogue={parties:[{...party,defaultStartTime:'22:45',defaultEntryPrice:0}],venues:[venue],actors:[],createEntry:async value=>saved.push(value)};
  const Editor=loadSource(new URL('../src/app/dashboard/kalender/EntryEditor.tsx',import.meta.url)).default;
  const props={date:new Date(2027,0,9),onSaved(){},onCancel(){}};
  mount(h(Editor,props));
  const select=()=>{const choices=document.querySelectorAll('select');changeField(choices[0],'venue');changeField(choices[1],'party');};
  select();assert.equal(document.querySelector('input[type="time"]').value,'22:45');
  const price=[...document.querySelectorAll('label')].find(label=>label.textContent.includes('Eintrittspreis')).querySelector('input');
  assert.equal(price.value,'Eintritt frei');changeField(price,'9 €');changeField(document.querySelector('input[type="time"]'),'00:30');
  changeField(document.querySelector('textarea'),'Manueller Termin');
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved[0].startTime,'00:30');assert.equal(saved[0].entryPrice,'9 €');
  act(()=>root.render(h(Editor,{...props,key:'relative'})));select();changeField(document.querySelector('textarea'),'nach dem Konzert');
  assert.equal(document.querySelector('input[type="time"]').value,'');
  await act(async()=>document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(saved[1].startTime,'');
});

test('portfolio tables paginate and search genres and related actors without modifying records', async () => {
  const rows = Array.from({length: 60}, (_, index) => ({...party, id: `party-${index}`, name: `Party ${index}`, standardGenres: index === 40 ? ['Emo'] : ['Indie'], residentDjIds: index === 40 ? ['actor'] : []}));
  catalogue = {parties: rows, venues: [venue], actors: [actor]};
  globalThis.fetch = async () => new Response(JSON.stringify([genre]));
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => mount(h(Page, {standalone: true})));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 25);
  click(button('Weiter'));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 25);
  changeField(document.querySelector('input[type="search"]'), 'Emo');
  assert.deepEqual([...document.querySelectorAll('[data-record-id]')].map(row => row.dataset.recordId), ['party-40']);
  changeField(document.querySelector('input[type="search"]'), 'Test DJ');
  assert.equal(document.querySelector('[data-record-id]').dataset.recordId, 'party-40');
  const trigger = document.querySelector('[data-record-open]');
  trigger.focus();
  await act(async () => trigger.click());
  assert.ok(document.querySelector('[role="dialog"].cc-drawer'));
  assert.equal(document.querySelector('[role="dialog"] input').value, 'Party 40');
  key('Escape');
  assert.equal(document.activeElement, trigger);
  assert.equal(rows[40].name, 'Party 40');
});

test('actor filters use real roles and retain collective membership in search and editor', async () => {
  const collective = {...actor, id: 'collective', name: 'Crew Beispiel', kind: 'collective', roles: ['organizer']};
  catalogue = {actors: [{...actor, collectiveIds: ['collective']}, collective, {...actor, id: 'guest', name: 'Gast', roles: []}]};
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/akteure/page.tsx', import.meta.url)).default;
  await act(async () => mount(h(Page)));
  const filters = document.querySelectorAll('.cc-collection-toolbar select');
  changeField(filters[1], 'dj');
  assert.deepEqual([...document.querySelectorAll('[data-record-id]')].map(row => row.dataset.recordId), ['actor']);
  changeField(document.querySelector('input[type="search"]'), 'Crew Beispiel');
  assert.equal(document.querySelectorAll('[data-record-id]').length, 1);
  changeField(filters[0], 'collective');
  assert.match(document.body.textContent, /Keine passenden DJs oder Kollektive/);
  changeField(filters[1], 'organizer');
  assert.equal(document.querySelector('[data-record-id]').dataset.recordId, 'collective');
  click(document.querySelector('[data-record-open]'));
  assert.equal(document.querySelector('[role="dialog"] input').value, 'Crew Beispiel');
});

test('calendar table keeps chronological order, cancellations and closure/location filters', async () => {
  catalogue = {parties: [party], venues: [venue, {...venue, id: 'other', name: 'Andere Location'}], actors: [actor], entries: [
    {...entry, id: 'late', date: '2027-01-05', eventStatus: 'CANCELLED', notes: 'Geprüfter Hinweis'},
    {...entry, id: 'early', date: '2027-01-02', mentionDjIds: ['actor']},
    {id: 'closure', date: '2027-01-03', type: 'closure', reason: 'Geschlossen', locationId: 'other', notes: ''},
  ]};
  const Page = loadSource(new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url)).default;
  await act(async () => mount(h(Page)));
  const ids = () => [...document.querySelectorAll('[data-record-id]')].map(row => row.dataset.recordId);
  assert.deepEqual(ids(), ['early', 'closure', 'late']);
  assert.match(document.querySelector('[data-record-id="late"]').textContent, /ABGESAGT/);
  changeField(document.querySelector('input[type="search"]'), 'Geprüfter Hinweis');
  assert.deepEqual(ids(), ['late']);
  changeField(document.querySelector('input[type="search"]'), 'Test DJ');
  assert.deepEqual(ids(), ['early']);
  changeField(document.querySelector('input[type="search"]'), '');
  const filters = document.querySelectorAll('.cc-collection-toolbar select');
  changeField(filters[0], 'closure');
  assert.deepEqual(ids(), ['closure']);
  changeField(filters[1], 'venue');
  assert.equal(ids().length, 0);
  assert.match(document.body.textContent, /Keine passenden Einträge/);
  changeField(filters[1], 'other');
  click(document.querySelector('[data-record-open]'));
  assert.match(document.querySelector('[role="dialog"]').textContent, /Location-Sperre/);
  click(button('Bearbeiten'));
  assert.equal(document.querySelector('[role="dialog"] select').value, 'other');
  assert.equal(document.querySelector('[role="dialog"] button[aria-pressed="true"]').textContent.trim(), 'Location-Sperre');
});

for (const [file, props] of [['portfolio/page.tsx', {standalone: true}], ['portfolio/akteure/page.tsx', {}], ['kalender/page.tsx', {}]]) {
  test(`${file}: drawer cancel protects changes and successful saving closes without a discard prompt`, async () => {
    let saved;
    const update = async value => {saved = value; return value;};
    catalogue = {parties: [party], venues: [venue], actors: [actor], entries: [entry], updateParty: update, updateActor: update, updateEntry: update};
    globalThis.fetch = async url => new Response(JSON.stringify(url.includes('aliases') ? [] : [genre]));
    const Page = loadSource(new URL(`../src/app/dashboard/${file}`, import.meta.url)).default;
    await act(async () => mount(h(Page, props)));
    const trigger = button('Bearbeiten');
    trigger.focus();
    await act(async () => trigger.click());
    const dialog = document.querySelector('[role="dialog"]');
    const input = dialog.querySelector(file.startsWith('kalender') ? 'textarea' : 'input');
    type(input, ' geändert');
    let prompts = 0;
    window.confirm = () => {prompts++; return false;};
    try {
      click(button('Abbrechen'));
      assert.equal(document.querySelector('[role="dialog"]'), dialog);
      assert.equal(prompts, 1);
      await act(async () => {dialog.querySelector('form').dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));});
      assert.ok(saved);
      if (file.startsWith('kalender')) {
        assert.equal(saved.notes, 'geändert');
        assert.equal(saved.date, entry.date);
        assert.equal(saved.locationId, entry.locationId);
        assert.equal(saved.partyId, entry.partyId);
        assert.deepEqual(saved.mentionDjIds, []);
      } else assert.match(saved.name, / geändert$/);
      assert.equal(prompts, 1);
      assert.equal(document.querySelector('[role="dialog"]'), null);
      assert.equal(document.activeElement, trigger);
    } finally {window.confirm = () => true;}
  });
}

test('global search filters real catalogue data, supports keyboard commands and protects active editors', async () => {
  catalogue = {parties: [party], venues: [venue], actors: [actor], entries: [entry]};
  const Palette = loadSource(new URL('../src/app/dashboard/CommandPalette.tsx', import.meta.url)).default;
  let runs = 0;
  mount(h(Palette, {action: {id: 'create', label: 'Neuer Eintrag', run: () => runs++}}));
  const trigger = document.querySelector('button'); trigger.focus();
  act(() => document.dispatchEvent(new KeyboardEvent('keydown', {key: 'k', ctrlKey: true, bubbles: true, cancelable: true})));
  const search = document.querySelector('input[type="search"]');
  assert.equal(document.activeElement, search);
  changeField(search, 'Test DJ');
  assert.equal(document.querySelectorAll('.cc-command-result').length, 1);
  const result = document.querySelector('.cc-command-result');
  assert.equal(result.getAttribute('href'), '/dashboard/djs#record=actor');
  key('ArrowDown'); assert.equal(document.activeElement, result);
  key('Escape'); assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
  click(trigger);
  changeField(document.querySelector('input[type="search"]'), 'Neuer Eintrag');
  click([...document.querySelectorAll('button')].find(button => button.textContent === 'Neuer Eintrag'));
  assert.equal(runs, 1); assert.equal(document.querySelector('[role="dialog"]'), null);
  const editor = document.createElement('div'); editor.setAttribute('role', 'dialog'); document.body.append(editor);
  act(() => document.dispatchEvent(new KeyboardEvent('keydown', {key: 'k', metaKey: true, bubbles: true})));
  assert.equal(document.querySelectorAll('[role="dialog"]').length, 1);
});

test('record links resolve after catalogue loading and reopen on same-page hash navigation', async () => {
  const {useRecordLink, recordLink} = loadSource(new URL('../src/app/dashboard/useRecordLink.ts', import.meta.url));
  const opened = [];
  function Links({items}) {useRecordLink(items, item => opened.push(item.id)); return null;}
  window.history.replaceState(null, '', recordLink('/dashboard/partys', 'party:40'));
  try {
    mount(h(Links, {items: []})); assert.deepEqual(opened, []);
    act(() => root.render(h(Links, {items: [{id: 'party:40'}, {id: 'other'}]})));
    assert.deepEqual(opened, ['party:40']);
    act(() => root.render(h(Links, {items: [{id: 'party:40'}, {id: 'other'}]})));
    assert.deepEqual(opened, ['party:40']);
    act(() => {window.history.replaceState(null, '', recordLink('/dashboard/partys', 'other')); window.dispatchEvent(new Event('hashchange'));});
    assert.deepEqual(opened, ['party:40', 'other']);
  } finally {window.history.replaceState(null, '', '/');}
});

test('portfolio fixed recurrence preserves confirmed planning context across edits', async () => {
  const saved=[];
  const save=async data=>{const result={...party,...data,id:'recurrence-party'};catalogue.parties=[result];saved.push(data);return result;};
  catalogue={parties:[],venues:[venue],actors:[],createParty:save,updateParty:save};
  globalThis.fetch=async url=>jsonResponse(url.includes('aliases')?[]:[genre]);
  const Page=loadSource(new URL('../src/app/dashboard/portfolio/page.tsx',import.meta.url)).default;
  await act(async()=>mount(h(Page)));click(button('Neu anlegen'));
  type(document.querySelector('[role="dialog"] input'),'Plan Night');
  changeField(document.querySelector('input[type="time"]'),'22:00');
  const field=name=>[...document.querySelectorAll('[role="dialog"] label')].find(label=>label.textContent.trim().startsWith(name)).querySelector('input,select');
  changeField(field('Wiederholung'),'weekly');
  assert.equal(field('Bestätigte Turnus-Location').value,'');assert.equal(field('Planung ab Datum').value,'');
  changeField(field('Wochentag'),'6');changeField(field('Bestätigte Turnus-Location'),venue.id);changeField(field('Planung ab Datum'),'2026-11-07');
  await act(async()=>button('Übernehmen').click());
  assert.deepEqual(saved[0].recurrence,{kind:'weekly',weekday:6,locationId:venue.id,startDate:'2026-11-07'});
  await act(async()=>button('Bearbeiten').click());
  assert.equal(field('Bestätigte Turnus-Location').value,venue.id);assert.equal(field('Planung ab Datum').value,'2026-11-07');
  changeField(field('Wiederholung'),'monthly_weekday');changeField(field('Woche im Monat'),'-1');
  await act(async()=>button('Übernehmen').click());
  assert.deepEqual(saved[1].recurrence,{kind:'monthly_weekday',weekday:6,occurrence:-1,locationId:venue.id,startDate:'2026-11-07'});
});

test('editorial planned occurrences show a confirmation warning and disable approval', async () => {
  const persisted={id:'draft',calendarEntryId:'entry',caption:'Planentwurf',hashtags:'',mentionActorIds:[],genres:[],status:'DRAFT',musicTrackId:null,publicationBlockers:[]};
  catalogue={parties:[party],venues:[venue],actors:[actor],entries:[{...entry,eventStatus:'PLANNED'}],entriesLoading:false,entriesError:null,reloadEntries:async()=>{},drafts:[persisted],draftsLoading:false,draftsError:null,reloadDrafts:async()=>{},musicTracks:[],musicTracksLoading:false,musicTracksError:null,reloadMusicTracks:async()=>{}};
  globalThis.fetch=async()=>jsonResponse([genre]);
  const Page=loadSource(new URL('../src/app/dashboard/redaktion/page.tsx',import.meta.url)).default;
  await act(async()=>mount(h(Page)));
  assert.match(document.body.textContent,/Geplant – nicht bestätigt\. Keine Veröffentlichungsfreigabe möglich/);
  assert.equal(document.querySelector('option[value="APPROVED"]').disabled,true);
});

// UX-01–09: exercise the actual genre page and real shared modal/list components.
async function mountGenreEditor(fetcher) {
  globalThis.fetch = fetcher;
  const Page = loadSource(new URL('../src/app/dashboard/settings/genres/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); await new Promise(resolve => setTimeout(resolve, 20)); });
  // Initial loading is scheduled by the real page, not fabricated by this fixture.
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
function genreEditorField(name) {
  const label = [...document.querySelectorAll('[role="dialog"] label')].find(item => item.textContent.trim() === name);
  assert.ok(label, `Genre field ${name} exists`);
  return label.querySelector('input');
}
async function submitGenreForm(form = document.querySelector('[role="dialog"] form')) {
  assert.ok(form);
  await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
}
function genreRow(id) {
  const row = [...document.querySelectorAll('[data-record-id]')].find(element => element.dataset.recordId === id);
  assert.ok(row, `Genre row ${id} exists`);
  return row;
}

test('genres UX: create and update close after persisted success and restore focus without a discard prompt', async () => {
  const calls = [];
  await mountGenreEditor(async (url, options = {}) => {
    calls.push({ url, method: options.method ?? 'GET', body: options.body && JSON.parse(options.body) });
    if (!options.method) return jsonResponse([genre]);
    return jsonResponse({ ...genre, id: options.method === 'POST' ? 'new-genre' : genre.id, ...JSON.parse(options.body) });
  });
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    const create = button('Genre anlegen'); create.focus(); click(create);
    changeField(genreEditorField('Kanonischer Name'), 'ebm');
    changeField(genreEditorField('Anzeigename'), 'EBM');
    await submitGenreForm();
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.activeElement, create);
    assert.match(document.body.textContent, /Genre „EBM“ gespeichert/);
    assert.equal(calls.filter(call => call.method === 'POST').length, 1);
    const edit = [...genreRow('genre').querySelectorAll('button')].find(element => element.textContent === 'Bearbeiten');
    edit.focus(); click(edit);
    changeField(genreEditorField('Anzeigename'), 'Rockmusik');
    await submitGenreForm();
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.activeElement, edit);
    assert.equal(calls.find(call => call.method === 'PUT').url, '/api/v1/genres/genre');
    assert.match(genreRow('genre').textContent, /Rockmusik/);
    assert.equal(prompts, 0);
  } finally { window.confirm = () => true; }
});

test('genres UX: server validation stays inside the open editor with values and focused error', async () => {
  await mountGenreEditor(async (_url, options = {}) => options.method ? jsonResponse({ message: 'Dieser Genre-Alias ist bereits vergeben.' }, 409) : jsonResponse([genre]));
  click(button('Genre anlegen'));
  changeField(genreEditorField('Kanonischer Name'), 'rock');
  changeField(genreEditorField('Anzeigename'), 'Rock');
  await submitGenreForm();
  const alert = document.querySelector('[role="dialog"] [role="alert"]');
  assert.ok(alert);
  assert.match(alert.textContent, /bereits vergeben/);
  assert.equal(document.activeElement, alert);
  assert.equal(genreEditorField('Anzeigename').value, 'Rock');
  assert.equal(document.querySelectorAll('[role="alert"]').length, 1);
});

test('genres UX: clean and reverted drafts close freely; changed drafts protect Cancel, X and Escape', async () => {
  await mountGenreEditor(async () => jsonResponse([genre]));
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    click(button('Genre anlegen')); key('Escape');
    assert.equal(document.querySelector('[role="dialog"]'), null);
    click(button('Genre anlegen'));
    changeField(genreEditorField('Kanonischer Name'), 'draft');
    changeField(genreEditorField('Kanonischer Name'), '');
    click(button('Abbrechen'));
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(prompts, 0);
    click(button('Genre anlegen'));
    changeField(genreEditorField('Kanonischer Name'), 'changed');
    for (const close of [() => click(button('Abbrechen')), () => click(document.querySelector('[data-modal-close]')), () => key('Escape')]) {
      close(); assert.ok(document.querySelector('[role="dialog"]'));
    }
    assert.equal(prompts, 3);
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload); assert.equal(unload.defaultPrevented, true);
    window.confirm = () => true; key('Escape');
    assert.equal(document.querySelector('[role="dialog"]'), null);
  } finally { window.confirm = () => true; }
});

test('genres UX: pending save ignores dismissal until the result is known', async () => {
  let finish;
  await mountGenreEditor(async (_url, options = {}) => options.method ? new Promise(resolve => { finish = resolve; }) : jsonResponse([genre]));
  click(button('Genre anlegen'));
  changeField(genreEditorField('Kanonischer Name'), 'ebm');
  changeField(genreEditorField('Anzeigename'), 'EBM');
  await submitGenreForm();
  key('Escape'); click(document.querySelector('[data-modal-close]'));
  assert.ok(document.querySelector('[role="dialog"]'));
  assert.equal(document.querySelector('[role="dialog"] form').getAttribute('aria-busy'), 'true');
  await act(async () => { finish(jsonResponse({ ...genre, id: 'new', canonicalName: 'ebm', displayName: 'EBM' })); });
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('genres UX: alias drafts are isolated by genre and cross-row submit cannot move an alias', async () => {
  const calls = [];
  const other = { ...genre, id: 'other', displayName: 'EBM', canonicalName: 'ebm' };
  await mountGenreEditor(async (url, options = {}) => {
    if (!options.method) return jsonResponse([genre, other]);
    calls.push({ url, body: JSON.parse(options.body) });
    return jsonResponse({ id: `alias-${calls.length}`, value: JSON.parse(options.body).value });
  });
  changeField(genreRow('genre').querySelector('input'), 'Rock-Alias');
  await submitGenreForm(genreRow('other').querySelector('form'));
  assert.equal(calls.length, 0);
  assert.match(genreRow('other').querySelector('[role="alert"]').textContent, /Alias eingeben/);
  assert.equal(genreRow('genre').querySelector('input').value, 'Rock-Alias');
  changeField(genreRow('other').querySelector('input'), 'Electronic Body Music');
  await submitGenreForm(genreRow('other').querySelector('form'));
  assert.deepEqual(calls, [{ url: '/api/v1/genres/other/aliases', body: { value: 'Electronic Body Music' } }]);
  assert.equal(genreRow('genre').querySelector('input').value, 'Rock-Alias');
  assert.ok(genreRow('genre').querySelector('[aria-label="Alias für Rock hinzufügen"]'));
});

test('genres UX: alias inline edit and confirmed delete use the correct event-independent IDs without native prompts', async () => {
  const calls = [];
  const item = { ...genre, aliases: [{ id: 'alias-a', value: 'Alternative' }, { id: 'alias-b', value: 'Rock and Roll' }] };
  await mountGenreEditor(async (url, options = {}) => {
    if (!options.method) return jsonResponse([item]);
    calls.push({ url, method: options.method, body: options.body && JSON.parse(options.body) });
    return options.method === 'DELETE' ? new Response(null, { status: 204 }) : jsonResponse({ id: 'alias-a', value: JSON.parse(options.body).value });
  });
  const originalPrompt = window.prompt;
  window.prompt = () => { throw new Error('Alias edit must use the application form'); };
  window.confirm = () => { throw new Error('Alias deletion must show its consequence in the application'); };
  try {
    click(document.querySelector('[aria-label="Alias Alternative für Rock bearbeiten"]'));
    const editForm = genreRow('genre').querySelector('li form');
    changeField(editForm.querySelector('input'), 'Alternative Rock');
    await submitGenreForm(editForm);
    assert.deepEqual(calls[0], { url: '/api/v1/genres/genre/aliases/alias-a', method: 'PUT', body: { value: 'Alternative Rock' } });
    click(document.querySelector('[aria-label="Alias Rock and Roll für Rock entfernen"]'));
    assert.match(genreRow('genre').textContent, /Das Genre bleibt erhalten/);
    assert.equal(calls.length, 1);
    click(genreRow('genre').querySelector('[role="group"] button:last-child'));
    assert.equal(calls.length, 1);
    click(document.querySelector('[aria-label="Alias Rock and Roll für Rock entfernen"]'));
    await act(async () => button('Alias endgültig entfernen').click());
    assert.equal(calls[1].url, '/api/v1/genres/genre/aliases/alias-b');
    assert.equal(calls[1].method, 'DELETE');
    assert.equal(document.querySelector('[aria-label="Alias Rock and Roll für Rock bearbeiten"]'), null);
    assert.ok(document.querySelector('[aria-label="Alias Alternative Rock für Rock bearbeiten"]'));
  } finally { window.prompt = originalPrompt; window.confirm = () => true; }
});

test('genres UX: shared list bounds visible rows, searches aliases, and explains Discovery usage', async () => {
  const rows = Array.from({ length: 60 }, (_, index) => ({ ...genre, id: `g-${index}`, canonicalName: `genre-${index}`, displayName: `Genre ${String(index).padStart(2, '0')}`, aliases: index === 59 ? [{ id: 'last-alias', value: 'Hidden synonym' }] : [] }));
  await mountGenreEditor(async () => jsonResponse(rows));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 25);
  click(button('Weiter'));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 25);
  changeField(document.querySelector('[aria-label="Genres und Aliase durchsuchen"]'), 'Hidden synonym');
  assert.equal(document.querySelectorAll('[data-record-id]').length, 1);
  assert.equal(genreRow('g-59').dataset.recordId, 'g-59');
  assert.match(document.body.textContent, /Aktiv für Genreerkennung und Discovery/);
});

async function mountPartySaveFlow({ aliases, reload, upload, save, update } = {}) {
  const calls = { created: 0, updated: 0, aliasWrites: [], aliasReads: 0, reloaded: 0, uploaded: 0 };
  catalogue = {
    parties: [], venues: [venue], actors: [],
    createParty: async data => {
      calls.created++;
      if (save) await save(data);
      const persisted = { ...data, id: 'persisted-party' };
      catalogue.parties = [persisted];
      return persisted;
    },
    updateParty: async data => { calls.updated++; await update?.(calls.updated, data); catalogue.parties = [data]; return data; },
    reloadEntries: async options => { calls.reloaded++; await reload?.(calls.reloaded, options); },
    uploadPartyFlyer: async (id, file) => { calls.uploaded++; return await upload?.(calls.uploaded, id, file); },
  };
  globalThis.fetch = async (url, options = {}) => {
    if (url.includes('/aliases')) {
      if (options.method === 'PUT') {
        const value = JSON.parse(options.body);
        calls.aliasWrites.push(value);
        return await aliases?.(calls.aliasWrites.length, value) ?? new Response(null, { status: 204 });
      }
      calls.aliasReads++; return jsonResponse(['Stale server alias']);
    }
    return jsonResponse([genre]);
  };
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => mount(h(Page)));
  click(button('Neu anlegen'));
  changeField(document.querySelector('[role="dialog"] input'), 'Persisted Party');
  changeField(document.querySelector('[role="dialog"] input[type="time"]'), '23:00');
  return calls;
}
async function submitPortfolioSave() {
  await act(async () => document.querySelector('[role="dialog"] form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
}

test('portfolio UX: failed primary persistence reports failure and does not run supplementary operations', async () => {
  const calls = await mountPartySaveFlow({ save: async () => { throw new Error('Stammdaten nicht erreichbar'); } });
  await submitPortfolioSave();
  assert.equal(calls.created, 1);
  assert.equal(calls.aliasWrites.length, 0);
  assert.equal(calls.reloaded, 0);
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /Speichern der Partyreihe wurde nicht bestätigt/);
  assert.doesNotMatch(document.querySelector('[role="dialog"]').textContent, /ist gespeichert/);
});

test('portfolio UX: alias failure retains confirmed party ID and draft, retries aliases only, and never auto-imports', async () => {
  const calls = await mountPartySaveFlow({ aliases: async count => new Response(null, { status: count === 1 ? 503 : 204 }) });
  const aliases = document.querySelector('[role="dialog"] textarea');
  changeField(aliases, 'My verified alias');
  await submitPortfolioSave();
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /Partyreihe ist gespeichert; Discovery-Aliase speichern ist noch offen/);
  assert.equal(aliases.value, 'My verified alias');
  assert.equal(calls.aliasReads, 0, 'Retaining a newly saved ID must not overwrite draft aliases through a new GET');
  assert.equal(calls.reloaded, 0);
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 0);
  assert.equal(calls.aliasWrites.length, 2);
  assert.deepEqual(calls.aliasWrites[1], { aliases: ['My verified alias'] });
  assert.equal(calls.reloaded, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.match(document.body.textContent, /Partyreihe „Persisted Party“ gespeichert/);
});

test('portfolio UX: edits after partial success update retained ID while already saved aliases are not repeated', async () => {
  const calls = await mountPartySaveFlow({ reload: async count => { if (count === 1) throw new Error('Kalenderansicht offline'); } });
  changeField(document.querySelector('[role="dialog"] textarea'), 'Stable alias');
  await submitPortfolioSave();
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /Nur die Kalenderansicht/);
  changeField(document.querySelector('[role="dialog"] input'), 'Changed persisted party');
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 1);
  assert.equal(catalogue.parties[0].id, 'persisted-party');
  assert.equal(catalogue.parties[0].name, 'Changed persisted party');
  assert.equal(calls.aliasWrites.length, 1);
  assert.equal(calls.reloaded, 2);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('portfolio UX: flyer failure retries the outstanding upload without rewriting successful party or aliases', async () => {
  const calls = await mountPartySaveFlow({ upload: async count => { if (count === 1) throw new Error('Flyer nicht erreichbar'); return { id: 'flyer' }; } });
  const input = document.querySelector('[role="dialog"] input[type="file"]');
  const file = new dom.window.File(['synthetic'], 'fixture.png', { type: 'image/png' });
  Object.defineProperty(input, 'files', { configurable: true, value: [file] });
  act(() => input.dispatchEvent(new Event('change', { bubbles: true })));
  await submitPortfolioSave();
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /Partyreihe ist gespeichert; Standardflyer hochladen ist noch offen/);
  assert.equal(calls.reloaded, 0);
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 0);
  assert.equal(calls.aliasWrites.length, 1); assert.equal(calls.uploaded, 2);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('portfolio UX: refresh-only retry never repeats already persisted CRUD, aliases or flyer', async () => {
  const calls = await mountPartySaveFlow({ reload: async count => { if (count === 1) throw new Error('Refresh offline'); } });
  await submitPortfolioSave();
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /Partyreihe und ihre Zusatzdaten sind gespeichert/);
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 0);
  assert.equal(calls.aliasWrites.length, 1); assert.equal(calls.reloaded, 2);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('portfolio UX: location partial save preserves ID, persists changed fields, then retries source registration', async () => {
  let creates = 0, updates = 0, registrations = 0;
  let updated;
  await mountLocationOnboarding(async url => {
    if (url === '/api/v1/discovery/onboard') return jsonResponse(websiteFixture());
    if (url.endsWith('/sources')) return new Response(null, { status: ++registrations === 1 ? 503 : 204 });
    return jsonResponse([]);
  }, async value => { creates++; return { ...value, id: 'stable-venue' }; }, async value => { updates++; updated = value; return value; });
  await act(async () => { button('Website automatisch prüfen').click(); await new Promise(resolve => setTimeout(resolve, 20)); });
  await submitPortfolioSave();
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /^Location ist gespeichert; Quellen übernehmen/);
  changeField(document.querySelector('[role="dialog"] input'), 'Updated club name');
  await submitPortfolioSave();
  assert.equal(creates, 1); assert.equal(updates, 1); assert.equal(registrations, 2);
  assert.equal(updated.id, 'stable-venue'); assert.equal(updated.name, 'Updated club name');
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('catalog UX: 413 upload messages honor party10MB versus event8MB and explicit refresh errors propagate', async () => {
  const actual = loadSource(new URL('../src/app/dashboard/CatalogProvider.tsx', import.meta.url));
  let context;
  function Probe() { context = actual.useCatalog(); return null; }
  const failing = new Set();
  globalThis.fetch = async (url, options = {}) => options.method === 'POST'
    ? new Response('Request body too large', { status: 413 })
    : failing.has(url)
      ? new Response('Unavailable', { status: 503 })
      : jsonResponse([]);
  await act(async () => { mount(h(actual.CatalogProvider, null, h(Probe))); await new Promise(resolve => setTimeout(resolve, 20)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
  const file = new File(['synthetic'], 'fixture.png', { type: 'image/png' });
  await act(async () => {
    await assert.rejects(() => context.uploadPartyFlyer('party', file), /höchstens 10 MB/);
    await assert.rejects(() => context.uploadEventFlyer('event', file), /höchstens 8 MB/);
  });
  for (const [path, reload, error, message] of [
    ['/api/v1/calendar-entries', 'reloadEntries', 'entriesError', /Kalendereinträge konnten nicht geladen werden/],
    ['/api/v1/parties', 'reloadParties', 'partiesError', /Partyreihen konnten nicht geladen werden/],
    ['/api/v1/editorial-drafts', 'reloadDrafts', 'draftsError', /Redaktionsentwürfe konnten nicht geladen werden/],
  ]) {
    failing.add(path);
    await act(async () => { await assert.rejects(() => context[reload]({throwOnError:true}), message); });
    assert.match(context[error], message);
    // Background callers retain the established state-based contract.
    await act(async () => { await context[reload](); });
  }
});

test('DashboardModal busy prevents Escape, X and dismiss capture before any discard prompt and retains dirty protection', () => {
  function BusyForm() {
    const [open, setOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    const [value, setValue] = useState('');
    return h(React.Fragment, null,
      h('button', { id: 'busy-modal-trigger', onClick: () => setOpen(true) }, 'Open protected form'),
      h(DashboardModal, { open, title: 'Pending save', protectChanges: true, busy, onClose: () => setOpen(false) },
        h('input', { 'aria-label': 'Protected draft', value, onChange: event => setValue(event.target.value) }),
        h('button', { id: 'busy-start', type: 'button', onClick: () => setBusy(true) }, 'Start save'),
        h('button', { id: 'busy-finish', type: 'button', onClick: () => setBusy(false) }, 'Report failure'),
        h('button', { id: 'busy-cancel', type: 'button', 'data-modal-dismiss': true, onClick: () => setOpen(false) }, 'Cancel')));
  }
  mount(h(BusyForm));
  const trigger = document.querySelector('#busy-modal-trigger');
  trigger.focus(); click(trigger);
  const input = document.querySelector('[aria-label="Protected draft"]');
  changeField(input, 'Keep this unsaved draft');
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    click(document.querySelector('#busy-start'));
    assert.equal(document.querySelector('[data-modal-close]').disabled, true);
    key('Escape'); click(document.querySelector('[data-modal-close]')); click(document.querySelector('#busy-cancel'));
    assert.equal(prompts, 0);
    assert.ok(document.querySelector('[role="dialog"]'));
    assert.equal(input.value, 'Keep this unsaved draft');
    click(document.querySelector('#busy-finish'));
    assert.equal(document.querySelector('[data-modal-close]').disabled, false);
    key('Escape');
    assert.equal(prompts, 1, 'Ignoring a pending dismissal must not clear the existing dirty state');
    assert.ok(document.querySelector('[role="dialog"]'));
    assert.equal(input.value, 'Keep this unsaved draft');
    window.confirm = () => true; key('Escape');
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.activeElement, trigger);
  } finally { window.confirm = () => true; }
});

test('portfolio UX: pending supplementary request ignores all dismissal paths without a prompt; failure retains values and retry', async () => {
  let finish;
  const calls = await mountPartySaveFlow({ aliases: count => count === 1 ? new Promise(resolve => { finish = resolve; }) : new Response(null, { status: 204 }) });
  const dialog = document.querySelector('[role="dialog"]');
  changeField(dialog.querySelector('textarea'), 'Verified alias draft');
  await submitPortfolioSave();
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    assert.equal(dialog.querySelector('form').getAttribute('aria-busy'), 'true');
    assert.equal(dialog.querySelector('[data-modal-close]').disabled, true);
    assert.equal(dialog.querySelector('[data-modal-dismiss]').matches(':disabled'), true);
    key('Escape'); click(dialog.querySelector('[data-modal-close]')); click(dialog.querySelector('[data-modal-dismiss]'));
    assert.equal(prompts, 0);
    assert.equal(document.querySelector('[role="dialog"]'), dialog);
    await act(async () => { finish(new Response(null, { status: 503 })); });
    assert.match(dialog.querySelector('[role="alert"]').textContent, /Partyreihe ist gespeichert; Discovery-Aliase/);
    assert.equal(dialog.querySelector('input').value, 'Persisted Party');
    assert.equal(dialog.querySelector('textarea').value, 'Verified alias draft');
    key('Escape');
    assert.equal(prompts, 1);
    assert.equal(document.querySelector('[role="dialog"]'), dialog);
    await submitPortfolioSave();
    assert.equal(calls.created, 1); assert.equal(calls.updated, 0);
    assert.deepEqual(calls.aliasWrites, [{ aliases: ['Verified alias draft'] }, { aliases: ['Verified alias draft'] }]);
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(prompts, 1, 'Successful retry must close without a discard prompt');
  } finally { window.confirm = () => true; }
});

test('portfolio UX: failed PUT after partial create identifies the previously confirmed party and leaves the changed draft open', async () => {
  const calls = await mountPartySaveFlow({
    aliases: count => new Response(null, { status: count === 1 ? 503 : 204 }),
    update: async count => { if (count === 1) throw new Error('Changed values were not confirmed'); },
  });
  await submitPortfolioSave();
  changeField(document.querySelector('[role="dialog"] input'), 'Unconfirmed new party name');
  await submitPortfolioSave();
  const dialog = document.querySelector('[role="dialog"]');
  const status = dialog.querySelector('[role="status"]').textContent;
  assert.match(status, /Zuletzt bestätigter Stand: Partyreihe „Persisted Party“/);
  assert.match(status, /geänderten Stammdaten sind noch nicht bestätigt/);
  assert.doesNotMatch(status, /Unconfirmed new party name/);
  assert.match(dialog.querySelector('[role="alert"]').textContent, /geänderten Stammdaten wurde nicht bestätigt/);
  assert.equal(dialog.querySelector('input').value, 'Unconfirmed new party name');
  assert.equal(catalogue.parties[0].name, 'Persisted Party');
  assert.equal(calls.created, 1); assert.equal(calls.updated, 1); assert.equal(calls.aliasWrites.length, 1);
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 2); assert.equal(calls.aliasWrites.length, 2);
  assert.equal(catalogue.parties[0].id, 'persisted-party');
  assert.equal(catalogue.parties[0].name, 'Unconfirmed new party name');
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.match(document.body.textContent, /Partyreihe „Unconfirmed new party name“ gespeichert/);
});

test('portfolio UX: failed location PUT never labels changed values as saved and retries against the retained ID', async () => {
  let creates = 0, updates = 0, registrations = 0;
  let persisted;
  await mountLocationOnboarding(async url => {
    if (url === '/api/v1/discovery/onboard') return jsonResponse(websiteFixture());
    if (url.endsWith('/sources')) return new Response(null, { status: ++registrations === 1 ? 503 : 204 });
    return jsonResponse([]);
  }, async value => { creates++; persisted = { ...value, id: 'retained-location' }; return persisted; }, async value => {
    updates++;
    if (updates === 1) throw new Error('Location changes were not confirmed');
    persisted = value; return value;
  });
  await act(async () => { button('Website automatisch prüfen').click(); await new Promise(resolve => setTimeout(resolve, 20)); });
  await submitPortfolioSave();
  changeField(document.querySelector('[role="dialog"] input'), 'Unconfirmed new location name');
  await submitPortfolioSave();
  const dialog = document.querySelector('[role="dialog"]');
  const savedStatus = [...dialog.querySelectorAll('[role="status"]')].find(element => element.textContent.startsWith('Zuletzt bestätigter Stand:'));
  assert.ok(savedStatus);
  const status = savedStatus.textContent;
  assert.match(status, /Zuletzt bestätigter Stand: Location „New Club“/);
  assert.match(status, /geänderten Stammdaten sind noch nicht bestätigt/);
  assert.doesNotMatch(status, /Unconfirmed new location name/);
  assert.match(dialog.querySelector('[role="alert"]').textContent, /geänderten Stammdaten wurde nicht bestätigt/);
  assert.equal(dialog.querySelector('input').value, 'Unconfirmed new location name');
  assert.equal(persisted.name, 'New Club');
  assert.equal(creates, 1); assert.equal(updates, 1); assert.equal(registrations, 1);
  await submitPortfolioSave();
  assert.equal(creates, 1); assert.equal(updates, 2); assert.equal(registrations, 2);
  assert.equal(persisted.id, 'retained-location');
  assert.equal(persisted.name, 'Unconfirmed new location name');
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('portfolio UX: category transitions clear partial save progress instead of reusing a party ID for a location', async () => {
  const calls = await mountPartySaveFlow({ aliases: count => new Response(null, { status: count === 1 ? 503 : 204 }) });
  await submitPortfolioSave();
  let createdLocation;
  catalogue.createVenue = async value => { createdLocation = { ...value, id: 'independent-location' }; catalogue.venues = [createdLocation]; return createdLocation; };
  click(button('Locations'));
  assert.equal(document.querySelector('[role="dialog"]'), null);
  click(button('Neu anlegen'));
  changeField(document.querySelector('[role="dialog"] input'), 'Independent location');
  await submitPortfolioSave();
  assert.equal(createdLocation.id, 'independent-location');
  assert.equal(createdLocation.name, 'Independent location');
  assert.equal(calls.created, 1); assert.equal(calls.updated, 0);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  click(button('Partyreihen'));
  await act(async () => button('Bearbeiten').click());
  assert.equal(calls.aliasReads, 1, 'Returning to an existing record must load its server aliases afresh');
  await submitPortfolioSave();
  assert.equal(calls.created, 1); assert.equal(calls.updated, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('portfolio UX: cancelling a partial flow resets its persisted ID before creating an independent party', async () => {
  const calls = await mountPartySaveFlow({ aliases: count => new Response(null, { status: count === 1 ? 503 : 204 }) });
  await submitPortfolioSave();
  click(button('Abbrechen'));
  assert.equal(document.querySelector('[role="dialog"]'), null);
  click(button('Neu anlegen'));
  changeField(document.querySelector('[role="dialog"] input'), 'Another independent party');
  changeField(document.querySelector('[role="dialog"] input[type="time"]'), '22:30');
  await submitPortfolioSave();
  assert.equal(calls.created, 2); assert.equal(calls.updated, 0);
  assert.equal(calls.aliasWrites.length, 2);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});
